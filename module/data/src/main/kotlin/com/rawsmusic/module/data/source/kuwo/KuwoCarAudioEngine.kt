package com.rawsmusic.module.data.source.kuwo

import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 酷我车载母带引擎（Kuwo Car Audio Engine）。
 *
 * 核心特性：
 * 1. 采用官方车载客户端（kwplayer_ar）最新移动端接口，直通高规格音频资源池；
 * 2. 优先提取 192kHz/24bit 臻品母带（20900kmflac）或 Hi-Res/CD 无损 FLAC（4000k/2000k）；
 * 3. 针对加密格式（.mflac/.mgg），内置 kwDES + QMCv2 流式解密还原；
 * 4. 多阶段搜索匹配（艺名别字容错、多歌手拆分、时长精准对齐与品质权重优先）。
 */
object KuwoCarAudioEngine {

    private const val TAG = "KuwoCarAudioEngine"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val secureRandom = SecureRandom()

    data class KuwoQualityOption(
        val level: String,
        val bitrate: Int,
        val format: String,
        val sizeStr: String,
        val sizeBytes: Long = 0L,
    )

    data class KuwoSongMatch(
        val rid: String,
        val songName: String,
        val artist: String,
        val album: String,
        val durationSec: Long,
        val qualities: List<KuwoQualityOption>
    )

    data class KuwoDownloadResource(
        val downloadUrl: String,
        val targetExtension: String,
        val isEncrypted: Boolean,
        val qmcCipher: QmcCipher?,
        val bitrate: Int,
        val qualityTag: String
    )

    /**
     * 搜索并按歌名、歌手与时长对齐最佳候选曲目（兼容旧调用方）。
     */
    suspend fun matchBestSong(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L
    ): KuwoSongMatch? = matchTopSongs(title, artist, expectedDurationMs, limit = 1).firstOrNull()

    /**
     * 搜索并按匹配得分降序返回 Top N 同曲候选（支持主 RID 返回版权提示音时无缝切换同曲其他专辑 RID）。
     */
    suspend fun matchTopSongs(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L,
        expectedAlbum: String = "",
        limit: Int = 3,
    ): List<KuwoSongMatch> = withContext(Dispatchers.IO) {
        val cleanT = extractCoreTitle(title)
        val cleanA = cleanKeyword(artist)
        val normA = normalizeArtist(cleanA)
        val srcVersionTags = extractVersionTags(title)
        val srcIsLive = (VersionTag.LIVE in srcVersionTags) || isLiveAlbum(expectedAlbum)
        val srcLanguageTags = extractLanguageTags(title)
        val langSearchHint = when {
            LanguageTag.CANTONESE in srcLanguageTags -> "粤"
            LanguageTag.MANDARIN in srcLanguageTags -> "国语"
            LanguageTag.ENGLISH in srcLanguageTags -> "英文"
            LanguageTag.JAPANESE in srcLanguageTags -> "日语"
            LanguageTag.KOREAN in srcLanguageTags -> "韩语"
            LanguageTag.MINNAN in srcLanguageTags -> "闽南语"
            else -> ""
        }

        // 第一阶段：若原曲本身明确为语种/Live 版本，才携带限定词搜索；否则搜纯净「主歌名 + 歌手」
        val primaryTitleQuery = buildString {
            append(cleanT)
            if (langSearchHint.isNotEmpty()) append(' ').append(langSearchHint)
            if (srcIsLive) append(" live")
        }.trim()
        val keyword1 = if (normA.isNotBlank() && !primaryTitleQuery.contains(normA, ignoreCase = true)) {
            "$primaryTitleQuery $normA"
        } else {
            primaryTitleQuery
        }

        var candidateItems = searchApi(keyword1)

        // 检查第一阶段候选池中是否已有通过严格门控（歌名+歌手+版本+语种+5秒时长）的高品质结果
        val hasValidStrictMatch = candidateItems.any { item ->
            val candTitle = item.optString("SONGNAME")
                .ifEmpty { item.optString("NAME") }
                .ifEmpty { item.optString("FSONGNAME").trim('"') }
                .ifEmpty { item.optString("name") }
            val candArtist = item.optString("ARTIST")
                .ifEmpty { item.optString("AARTIST") }
                .ifEmpty { item.optString("artist") }
            val candAlbum = item.optString("ALBUM").ifEmpty { item.optString("album") }
            val candDurMs = item.optLong("DURATION", 0L) * 1000L
            val qualities = parseMinfo(item.optString("N_MINFO").ifEmpty { item.optString("MINFO") })
            val score = calculateMatchScore(
                title, artist, expectedAlbum,
                candTitle, candArtist, candAlbum,
                expectedDurationMs, candDurMs, qualities,
            )
            score >= 60
        }

        if (!hasValidStrictMatch && cleanT.isNotBlank()) {
            val fallbackKeyword = if (primaryTitleQuery != cleanT && normA.isNotBlank()) {
                "$cleanT $normA"
            } else {
                cleanT
            }
            if (fallbackKeyword != keyword1) {
                val fallbackItems = searchApi(fallbackKeyword)
                if (fallbackItems.isNotEmpty()) {
                    val existingRids = candidateItems.map { it.optString("MUSICRID").ifBlank { it.optString("id") } }.toSet()
                    val merged = candidateItems.toMutableList()
                    for (it in fallbackItems) {
                        val r = it.optString("MUSICRID").ifBlank { it.optString("id") }
                        if (r !in existingRids) {
                            merged.add(it)
                        }
                    }
                    candidateItems = merged
                }
            }
        }

        val scored = ArrayList<Pair<Int, KuwoSongMatch>>()
        val seenRids = HashSet<String>()

        for (item in candidateItems) {
            val rawRid = item.optString("MUSICRID").ifBlank { item.optString("id") }
            val rid = rawRid.replace("MUSIC_", "").trim()
            if (rid.isBlank() || !seenRids.add(rid)) continue

            val candTitle = item.optString("SONGNAME")
                .ifEmpty { item.optString("NAME") }
                .ifEmpty { item.optString("FSONGNAME").trim('"') }
                .ifEmpty { item.optString("name") }
            val candArtist = item.optString("ARTIST")
                .ifEmpty { item.optString("AARTIST") }
                .ifEmpty { item.optString("artist") }
            val candAlbum = item.optString("ALBUM").ifEmpty { item.optString("album") }
            val candDurSec = item.optLong("DURATION", 0L)

            val nMinfo = item.optString("N_MINFO").ifEmpty { item.optString("MINFO") }
            val qualities = parseMinfo(nMinfo)

            val score = calculateMatchScore(
                title,
                artist,
                expectedAlbum,
                candTitle,
                candArtist,
                candAlbum,
                expectedDurationMs,
                candDurSec * 1000L,
                qualities
            )

            if (score >= 40) {
                scored.add(
                    score to KuwoSongMatch(
                        rid = rid,
                        songName = candTitle,
                        artist = candArtist,
                        album = candAlbum,
                        durationSec = candDurSec,
                        qualities = qualities
                    )
                )
            }
        }

        val topMatches = scored
            .sortedByDescending { it.first }
            .take(limit.coerceAtLeast(1))
            .map { it.second }

        if (topMatches.isNotEmpty()) {
            val lead = topMatches.first()
            AppLogger.i(TAG, "酷我引擎匹配成功: ${lead.songName} - ${lead.artist} (主RID: ${lead.rid}, 候选数: ${topMatches.size}, 音质项: ${lead.qualities.size}个)")
        } else {
            AppLogger.w(TAG, "酷我搜索未命中合适曲目: '$title' - '$artist'")
        }
        topMatches
    }

    /**
     * 调用酷我移动端搜索接口（对齐 HotDownloader，携带 newver=1&vipver=1&mobi=1 激活母带资源库）。
     */
    private fun searchApi(keyword: String): List<JSONObject> {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?" +
                "client=kt" +
                "&all=$encoded" +
                "&ft=music" +
                "&encoding=utf8" +
                "&rformat=json" +
                "&pcjson=1" +
                "&itemset=web_2013" +
                "&vipver=1" +
                "&newver=1" +
                "&mobi=1" +
                "&pn=0" +
                "&rn=30"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "kwplayer_ar_9.2.2.1")
                .build()

            val rawResp = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string()
            } ?: return emptyList()

            // 注意：携带 `pcjson=1` 时酷我返回标准双引号 JSON（对齐 HotDownloader `search.rs`）。
            // 严禁无条件 `replace('\'', '"')`，否则歌名/专辑中的英文单引号（如 `Don't`、`Eason's`）会直接破坏 JSON 结构！
            val rootJson = runCatching { JSONObject(rawResp) }
                .getOrElse { JSONObject(rawResp.replace('\'', '"')) }
            val absList = rootJson.optJSONArray("abslist") ?: return emptyList()
            val result = mutableListOf<JSONObject>()
            for (i in 0 until absList.length()) {
                absList.optJSONObject(i)?.let { result.add(it) }
            }
            return result
        } catch (e: Throwable) {
            AppLogger.w(TAG, "酷我接口搜索异常: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 解析酷我 N_MINFO / MINFO 字符串（对齐 HotDownloader parser.rs：解析 size 字节数并过滤无效占位条目）。
     */
    private fun parseMinfo(infoStr: String): List<KuwoQualityOption> {
        if (infoStr.isBlank()) return emptyList()
        val list = mutableListOf<KuwoQualityOption>()
        val pattern = Pattern.compile("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")
        val matcher = pattern.matcher(infoStr)
        val seenKeys = mutableSetOf<String>()

        while (matcher.find()) {
            val level = matcher.group(1) ?: ""
            val bitrate = matcher.group(2)?.toIntOrNull() ?: continue
            val format = matcher.group(3) ?: ""
            val sizeStr = matcher.group(4) ?: ""
            val sizeBytes = parseSizeBytes(sizeStr)
            if (sizeBytes <= 0L) continue

            if (seenKeys.add("$bitrate:${format.lowercase()}")) {
                list.add(KuwoQualityOption(level, bitrate, format, sizeStr, sizeBytes))
            }
        }
        return list
    }

    private fun parseSizeBytes(sizeStr: String): Long {
        val m = Pattern.compile("(?i)^([\\d.]+)([kmg]?b)$").matcher(sizeStr.trim())
        if (!m.matches()) return 0L
        val num = m.group(1)?.toDoubleOrNull() ?: return 0L
        val multiplier = when (m.group(2)?.lowercase()) {
            "b" -> 1.0
            "kb" -> 1024.0
            "mb" -> 1024.0 * 1024.0
            "gb" -> 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (num * multiplier).toLong()
    }

    /**
     * 将 Configure Sources（音源配置页）的偏好音质字符串统一映射为 RawSourceQuality。
     */
    fun qualityFromSourcePreference(pref: String = AppPreferences.Source.preferredQuality): RawSourceQuality =
        when (pref.trim().lowercase()) {
            "flac24bit", "hires", "hr" -> RawSourceQuality.HiRes
            "flac", "sq", "lossless" -> RawSourceQuality.Lossless
            "320k", "hq", "high", "super" -> RawSourceQuality.High
            else -> RawSourceQuality.Standard
        }

    /**
     * 依据目标音质策略，向官方车机接口请求真实下载链接与解密密钥（对齐 HotDownloader playback.rs 全明文音质降级链）。
     * - 若传入 [urlValidator]，每拿到直链会立即校验（拦截版权提示音/试听截断），不通过则自动顺延尝试下一档音质。
     */
    suspend fun resolveDownloadResource(
        songMatch: KuwoSongMatch,
        requestedQuality: RawSourceQuality = qualityFromSourcePreference(),
        allowEncrypted: Boolean = true,
        urlValidator: (suspend (KuwoDownloadResource) -> Boolean)? = null,
    ): KuwoDownloadResource? = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<KuwoQualityOption>()
        val hasQualityManifest = songMatch.qualities.isNotEmpty()
        fun addOption(bitrate: Int, fallbackLevel: String, fallbackFormat: String) {
            val found = songMatch.qualities.find { it.bitrate == bitrate }
                ?: if (!hasQualityManifest) KuwoQualityOption(fallbackLevel, bitrate, fallbackFormat, "") else return
            if (candidates.none { it.bitrate == found.bitrate && it.format.equals(found.format, ignoreCase = true) }) {
                candidates.add(found)
            }
        }

        when (requestedQuality) {
            RawSourceQuality.HiRes -> {
                if (allowEncrypted) addOption(20900, "zply", "mflac")
                addOption(4000, "hr", "flac")
                addOption(2000, "ff", "flac")
                addOption(320, "p", "mp3")
                addOption(300, "p", "ogg")
                addOption(192, "p", "ogg")
                addOption(128, "h", "mp3")
                addOption(100, "h", "ogg")
                addOption(48, "s", "aac")
            }
            RawSourceQuality.Lossless -> {
                addOption(2000, "ff", "flac")
                addOption(4000, "hr", "flac")
                if (allowEncrypted) addOption(20900, "zply", "mflac")
                addOption(320, "p", "mp3")
                addOption(300, "p", "ogg")
                addOption(192, "p", "ogg")
                addOption(128, "h", "mp3")
                addOption(100, "h", "ogg")
                addOption(48, "s", "aac")
            }
            RawSourceQuality.Super, RawSourceQuality.High -> {
                addOption(320, "p", "mp3")
                addOption(300, "p", "ogg")
                addOption(192, "p", "ogg")
                addOption(2000, "ff", "flac")
                addOption(4000, "hr", "flac")
                addOption(128, "h", "mp3")
                addOption(100, "h", "ogg")
                addOption(48, "s", "aac")
            }
            RawSourceQuality.Standard -> {
                addOption(128, "h", "mp3")
                addOption(320, "p", "mp3")
                addOption(100, "h", "ogg")
                addOption(192, "p", "ogg")
                addOption(300, "p", "ogg")
                addOption(48, "s", "aac")
                addOption(2000, "ff", "flac")
                addOption(4000, "hr", "flac")
            }
        }

        val filtered = if (allowEncrypted) {
            candidates
        } else {
            candidates.filterNot {
                it.format.equals("mflac", ignoreCase = true) ||
                    it.format.equals("mgg", ignoreCase = true) ||
                    it.format.equals("mmp4", ignoreCase = true)
            }
        }

        for (opt in filtered) {
            val res = requestCarApi(songMatch.rid, opt.bitrate, opt.format) ?: continue
            if (urlValidator == null || urlValidator(res)) {
                return@withContext res
            }
            AppLogger.w(TAG, "酷我音质档位校验未通过（疑似提示音/试听），尝试下一档位: rid=${songMatch.rid}, br=${opt.bitrate}${opt.format}")
        }
        null
    }

    /**
     * 轻量快速校验酷我车机签名直链（对齐 HotDownloader `kuwo/download.rs` Header 与流完整性语义）：
     * - 使用车机同款 `User-Agent: okhttp/4.10.0` 与 `Referer: http://www.kuwo.cn/` 发起首块 Range 探活；
     * - 校验 HTTP 200/206、排除网页/JSON 错误体及试听 URL；
     * - 通过响应 `Content-Range` 总字节数（或 `N_MINFO` 声明体积）验证完整正片大小，彻底避免
     *   `MediaMetadataRetriever` 在 30MB+ 无损 FLAC HTTP 流上超时导致的误杀，同时精准拦截 5s 版权提示音与 30s/60s 试听片段。
     */
    suspend fun validateCarStreamUrl(
        match: KuwoSongMatch,
        resource: KuwoDownloadResource,
        expectedDurationMs: Long = 0L,
    ): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val rawUrl = resource.downloadUrl.trim()
        if (rawUrl.isBlank() || !rawUrl.startsWith("http", ignoreCase = true)) return@withContext null
        val lowerUrl = rawUrl.lowercase()
        if (lowerUrl.contains("audition") || lowerUrl.contains("preview") || lowerUrl.contains("trial") ||
            lowerUrl.contains("listen_part") || lowerUrl.contains("/short/") || lowerUrl.contains("试听")
        ) {
            return@withContext null
        }

        val effectiveDurMs = when {
            expectedDurationMs > 5000L -> expectedDurationMs
            match.durationSec > 5L -> match.durationSec * 1000L
            else -> 0L
        }
        // 当外部传入了明确预期时长且候选记录也有有效时长时，二次确认时长误差 ≤ 5 秒
        if (expectedDurationMs > 5000L && match.durationSec > 5L) {
            if (kotlin.math.abs(expectedDurationMs - match.durationSec * 1000L) > 5000L) {
                return@withContext null
            }
        }

        try {
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", "okhttp/4.10.0")
                .header("Referer", "http://www.kuwo.cn/")
                .header("Range", "bytes=0-4095")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val finalUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type", "")?.lowercase().orEmpty()
                if (code >= 400 || contentType.contains("text/html") || contentType.contains("application/json")) {
                    AppLogger.w(TAG, "酷我直链探活拒绝: status=$code, contentType=$contentType")
                    return@withContext null
                }
                if (!resp.isSuccessful && code != 206) {
                    return@withContext null
                }

                val finalLower = finalUrl.lowercase()
                if (finalLower.contains("audition") || finalLower.contains("preview") || finalLower.contains("trial") ||
                    finalLower.contains("listen_part") || finalLower.contains("/short/")
                ) {
                    return@withContext null
                }

                var totalBytes = -1L
                val contentRange = resp.header("Content-Range")
                if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
                    totalBytes = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                }
                if (totalBytes <= 0L && code == 200) {
                    totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                if (totalBytes <= 0L) {
                    totalBytes = match.qualities
                        .firstOrNull { it.bitrate == resource.bitrate }
                        ?.sizeBytes ?: -1L
                }

                // 防版权提示音（通常 < 500KB）与防 30s~60s 短试听体积下限校验
                val minBytesPerSec = when {
                    resource.bitrate >= 1000 -> 35_000L
                    resource.bitrate >= 300 -> 24_000L
                    resource.bitrate >= 120 -> 10_000L
                    else -> 4_500L
                }
                if (effectiveDurMs >= 65_000L && totalBytes > 0L) {
                    val minExpectedBytes = (effectiveDurMs / 1000L) * minBytesPerSec
                    if (totalBytes < minExpectedBytes) {
                        AppLogger.w(
                            TAG,
                            "酷我直链体积疑似试听/提示音: actual=$totalBytes < min=$minExpectedBytes (dur=${effectiveDurMs}ms, br=${resource.bitrate}k)"
                        )
                        return@withContext null
                    }
                } else if (totalBytes in 1..600_000L && effectiveDurMs >= 45_000L) {
                    return@withContext null
                }

                // 读取前几个字节确认不是伪装成 200 的空流或错误文本
                val headBytes = resp.body?.source()?.apply { request(16) }?.buffer?.clone()?.readByteArray()
                if (headBytes != null && headBytes.isEmpty()) {
                    return@withContext null
                }

                return@withContext finalUrl to effectiveDurMs
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "酷我车机直链探活异常: ${e.message}")
            return@withContext null
        }
    }

    /**
     * 在 Top N 匹配曲目 × 全明文音质降级链中寻找首个通过真实流校验的完整正片资源。
     */
    suspend fun resolveValidatedSongResource(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L,
        expectedAlbum: String = "",
        requestedQuality: RawSourceQuality = qualityFromSourcePreference(),
        allowEncrypted: Boolean = false,
        maxRidCandidates: Int = 3,
        urlValidator: suspend (KuwoSongMatch, KuwoDownloadResource) -> Boolean,
    ): Pair<KuwoSongMatch, KuwoDownloadResource>? = withContext(Dispatchers.IO) {
        val matches = matchTopSongs(title, artist, expectedDurationMs, expectedAlbum, limit = maxRidCandidates)
        for (match in matches) {
            val res = resolveDownloadResource(
                songMatch = match,
                requestedQuality = requestedQuality,
                allowEncrypted = allowEncrypted,
                urlValidator = { candidate -> urlValidator(match, candidate) },
            )
            if (res != null) {
                return@withContext match to res
            }
        }
        null
    }

    /**
     * 调用酷我官方 mobi.s 车载端下载直链转换接口。
     */
    private fun requestCarApi(rid: String, bitrate: Int, format: String): KuwoDownloadResource? {
        try {
            val user = (secureRandom.nextLong() and 0xFFFFFFFFL).toString()
            val androidIdBytes = ByteArray(8)
            secureRandom.nextBytes(androidIdBytes)
            val androidId = androidIdBytes.joinToString("") { "%02x".format(it) }

            val br = "${bitrate}k$format"
            val url = "http://mobi.kuwo.cn/mobi.s?" +
                "f=web" +
                "&user=$user" +
                "&android_id=$androidId" +
                "&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk" +
                "&type=convert_url_with_sign" +
                "&from=PC" +
                "&rid=$rid" +
                "&br=$br" +
                "&format=$format"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.10.0")
                .header("Referer", "http://www.kuwo.cn/")
                .build()

            val raw = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            } ?: return null

            val json = JSONObject(raw)
            if (json.optInt("code", -1) != 200) {
                return null
            }

            val dataObj = json.optJSONObject("data") ?: return null
            val downloadUrl = dataObj.optString("url")
            if (downloadUrl.isBlank() || !downloadUrl.startsWith("http")) return null

            val respBitrate = dataObj.optInt("bitrate", 0)
            val respFormat = dataObj.optString("format")

            // 严格校验码率与格式一致性，避免静默降级为低品质假音频
            if (respBitrate != bitrate || !respFormat.equals(format, ignoreCase = true)) {
                AppLogger.d(TAG, "车机响应码率/格式不匹配: 请求=$bitrate$format, 响应=$respBitrate$respFormat")
                return null
            }

            val isEncrypted = format.equals("mflac", ignoreCase = true) || format.equals("mgg", ignoreCase = true)
            var qmcCipher: QmcCipher? = null

            if (isEncrypted) {
                val rawEkey = dataObj.optString("ekey")
                if (rawEkey.isNotBlank()) {
                    val decryptedKey = KwDes.decryptEkey(rawEkey, user)
                    if (!decryptedKey.isNullOrBlank()) {
                        qmcCipher = QmcCipher.createFromEkey(decryptedKey)
                    }
                }
                if (qmcCipher == null) {
                    AppLogger.w(TAG, "酷我母带 ekey 解密失败，跳过该格式: rid=$rid")
                    return null
                }
            }

            val targetExt = when (format.lowercase()) {
                "mflac" -> "flac"
                "mgg" -> "ogg"
                else -> format.lowercase()
            }

            val qualityTag = when {
                bitrate >= 10000 -> "臻品母带 (192kHz/24bit)"
                bitrate >= 3000 -> "Hi-Res 无损"
                bitrate >= 1000 -> "FLAC 无损"
                bitrate >= 320 -> "320K MP3"
                else -> "${bitrate}K 音频"
            }

            AppLogger.i(TAG, "酷我车载母带直链解析成功! RID=$rid, 音质=$qualityTag, 加密=$isEncrypted, 真实扩展名=$targetExt")

            return KuwoDownloadResource(
                downloadUrl = downloadUrl,
                targetExtension = targetExt,
                isEncrypted = isEncrypted,
                qmcCipher = qmcCipher,
                bitrate = bitrate,
                qualityTag = qualityTag
            )
        } catch (e: Throwable) {
            AppLogger.w(TAG, "请求酷我车机接口异常: ${e.message}")
            return null
        }
    }

    private enum class VersionTag {
        LIVE,
        INSTRUMENTAL,
        DJ_REMIX,
        COVER,
        SPEED,
        ACOUSTIC,
    }

    private enum class LanguageTag {
        CANTONESE,
        MANDARIN,
        ENGLISH,
        JAPANESE,
        KOREAN,
        MINNAN,
    }

    private val bracketContentRegex = Regex("""[\(\[（【［\{]([^)\]）】］\}]*)[\)\]）】］\}]""")
    private val explicitLangPatterns: List<Pair<LanguageTag, Regex>> = listOf(
        LanguageTag.CANTONESE to Regex("""(?i)(粤语版?|广东话版?|(?<![a-zA-Z])cantonese(?![a-zA-Z]))"""),
        LanguageTag.MANDARIN to Regex("""(?i)(国语版?|普通话版?|(?<![a-zA-Z])mandarin(?![a-zA-Z]))"""),
        LanguageTag.ENGLISH to Regex("""(?i)(英语版|英文版|(?<![a-zA-Z])english\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        LanguageTag.JAPANESE to Regex("""(?i)(日语版|日文版|(?<![a-zA-Z])japanese\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        LanguageTag.KOREAN to Regex("""(?i)(韩语版|韩文版|(?<![a-zA-Z])korean\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        LanguageTag.MINNAN to Regex("""(?i)(闽南语版?|台语版?)"""),
    )
    private val bracketShortLangPatterns: List<Pair<LanguageTag, Regex>> = listOf(
        LanguageTag.CANTONESE to Regex("""(?i)^[\s\-·•]*(粤|粤语|粤语版|广东话|cantonese)[\s\-·•]*$"""),
        LanguageTag.MANDARIN to Regex("""(?i)^[\s\-·•]*(国|国语|国语版|普通话|mandarin)[\s\-·•]*$"""),
        LanguageTag.ENGLISH to Regex("""(?i)^[\s\-·•]*(英|英文|英语|英文版|英语版|english|eng\s*ver\.?)[\s\-·•]*$"""),
        LanguageTag.JAPANESE to Regex("""(?i)^[\s\-·•]*(日|日文|日语|日文版|日语版|japanese|jp\s*ver\.?)[\s\-·•]*$"""),
        LanguageTag.KOREAN to Regex("""(?i)^[\s\-·•]*(韩|韩文|韩语|韩文版|韩语版|korean|kr\s*ver\.?)[\s\-·•]*$"""),
        LanguageTag.MINNAN to Regex("""(?i)^[\s\-·•]*(闽|闽南|闽南语|台|台语)[\s\-·•]*$"""),
    )

    private val pairedBracketsRegex = Regex("""\s*[\(\[\{（【].*?[\]\)\}】]""")
    private val unclosedTrailingBracketRegex = Regex("""\s*[\(\[\{（【].*$""")
    private val dashSubtitleRegex = Regex(
        """(?i)\s*[-–—~|]\s*(《.*|.*?(电视剧|电影|网剧|动画|动漫|番剧|游戏|综艺|纪录片|主题曲|片头曲|片尾曲|插曲|推广曲|宣传曲|概念曲|印象曲|人物曲|原声|ost|feat\.?|ft\.?|remaster|重制版|完整版).*)$"""
    )
    private val trailingBookSubtitleRegex = Regex(
        """(?i)\s*《[^》]*》?\s*(电视剧|电影|网剧|动画|动漫|游戏|综艺|主题曲|片头曲|片尾曲|插曲|推广曲|宣传曲|原声|ost)?.*$"""
    )
    // Android ICU 正则中汉字与数字均属 `\w`，必须用 ASCII 字母边界以确保 `(live版)`、`(2023live)`、`歌曲名live` 100% 识别
    private val versionLiveRegex = Regex("""(?i)((?<![a-zA-Z])(live|unplugged)(?![a-zA-Z])|现场版?|演唱会|音乐节|演唱会现场|音乐会现场|巡演现场)""")
    private val albumLiveRegex = Regex("""(?i)((?<![a-zA-Z])(live|concert|unplugged|livehouse)(?![a-zA-Z])|演唱会|音乐节|现场版|现场实况|音乐会现场|巡演|巡回演出)""")
    private val versionInstrumentalRegex = Regex("""(?i)(伴奏|纯音乐|(?<![a-zA-Z])(instrumental|off\s*vocal|inst\.?|karaoke)(?![a-zA-Z])|消音版|无人声|卡拉ok)""")
    private val versionDjRemixRegex = Regex("""(?i)((?<![a-zA-Z])(dj|remix|mashup|bootleg)(?![a-zA-Z])|串烧|蹦迪|慢摇|电音版|舞曲版)""")
    private val versionCoverRegex = Regex("""(?i)(翻唱|(?<![a-zA-Z])cover(?![a-zA-Z]))""")
    private val versionSpeedRegex = Regex("""(?i)(加速版?|降调版?|变速版?|0\.\d+x|1\.\d+x|(?<![a-zA-Z])(nightcore|slowed|sped\s*up)(?![a-zA-Z]))""")
    private val versionAcousticRegex = Regex("""(?i)(吉他版|钢琴版|清唱|阿卡贝拉|(?<![a-zA-Z])acoustic(?![a-zA-Z])|口琴版|古筝版|二胡版|笛子版)""")

    private fun isLiveAlbum(rawAlbum: String): Boolean {
        if (rawAlbum.isBlank()) return false
        return albumLiveRegex.containsMatchIn(rawAlbum)
    }

    private fun hasModifierLiveTag(rawTitle: String): Boolean {
        if (rawTitle.isBlank()) return false
        if (bracketContentRegex.findAll(rawTitle).any { versionLiveRegex.containsMatchIn(it.groupValues.getOrNull(1).orEmpty()) }) {
            return true
        }
        val afterDash = rawTitle.substringAfter(" - ", "").ifEmpty { rawTitle.substringAfter(" — ", "") }
        return afterDash.isNotEmpty() && versionLiveRegex.containsMatchIn(afterDash)
    }

    /**
     * 提取纯净主歌名：剥离括号、连字符后的影视/综艺/OST 副标题以及尾部书名号说明，
     * 解决如「遗失的美好-《海豚湾恋人》电视剧片尾曲」与「遗失的美好」无法精确对齐的问题。
     */
    private fun extractCoreTitle(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ""
        var core = trimmed
            .replace(pairedBracketsRegex, "")
            .replace(unclosedTrailingBracketRegex, "")
            .replace(dashSubtitleRegex, "")
            .replace(trailingBookSubtitleRegex, "")
            .replace(Regex("""[-–—_/\\|&]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // 若整首歌名本身就是书名号或被全部剥空，回退到基础清洗结果
        if (core.isBlank()) {
            core = cleanKeyword(trimmed)
        }
        return core
    }

    /**
     * 从清洗前的完整歌名中提取会改变音频内容的版本特征标签，用于对称校验，防止伴奏/Live/DJ 误匹配原版。
     */
    private fun extractVersionTags(rawTitle: String): Set<VersionTag> {
        if (rawTitle.isBlank()) return emptySet()
        val tags = HashSet<VersionTag>(2)
        if (versionLiveRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.LIVE)
        if (versionInstrumentalRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.INSTRUMENTAL)
        if (versionDjRemixRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.DJ_REMIX)
        if (versionCoverRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.COVER)
        if (versionSpeedRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.SPEED)
        if (versionAcousticRegex.containsMatchIn(rawTitle)) tags.add(VersionTag.ACOUSTIC)
        return tags
    }

    private fun cleanKeyword(input: String): String {
        return input.replace(pairedBracketsRegex, "")
            .replace(Regex("""[-_/\\|&]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private val unknownArtists = setOf("未知歌手", "群星", "variousartists", "unknown", "va")

    /**
     * 歌手常见艺名、同音字及别字归一化（如 钟欣桐 -> 钟欣潼）。
     */
    private fun normalizeArtist(artist: String): String {
        return artist.replace("桐", "潼")
            .replace("·", "")
            .replace("•", "")
            .replace("'", "")
            .replace("\"", "")
            .replace(" ", "")
            .lowercase()
    }

    /**
     * 在去空格前按多歌手分隔符拆分，并同时保留括号内的中/英文别名（如 "陈奕迅(Eason Chan)"）。
     */
    private fun extractArtistTokens(rawArtist: String): List<String> {
        val trimmed = rawArtist.trim()
        if (trimmed.isBlank()) return emptyList()
        val tokens = LinkedHashSet<String>()
        // 1. 提取括号内的别名（常为英文名/中文名）
        pairedBracketsRegex.findAll(trimmed).forEach { match ->
            val inner = match.value.trim().trim('(', ')', '[', ']', '{', '}', '（', '）', '【', '】')
            inner.split('/', '\\', ',', '，', '、', '&', '|', '+')
                .map(::normalizeArtist)
                .filter { it.isNotBlank() }
                .forEach(tokens::add)
        }
        // 2. 提取主歌手列表
        val withoutBrackets = trimmed.replace(pairedBracketsRegex, " ")
        withoutBrackets.split('/', '\\', ',', '，', '、', '&', '|', '+')
            .map(::normalizeArtist)
            .filter { it.isNotBlank() }
            .forEach(tokens::add)
        return tokens.toList()
    }

    /**
     * 歌手匹配硬门控：当双方均提供了有效歌手名时，必须存在共同歌手才允许匹配，
     * 彻底杜绝同名不同歌手的曲目误命中酷我车载。
     */
    private fun isArtistMatched(srcArtist: String, candArtist: String): Boolean {
        val srcTokens = extractArtistTokens(srcArtist)
        val candTokens = extractArtistTokens(candArtist)
        if (srcTokens.isEmpty() || candTokens.isEmpty()) return true
        if (srcTokens.any { it in unknownArtists } || candTokens.any { it in unknownArtists }) return true

        val srcWhole = normalizeArtist(cleanKeyword(srcArtist))
        val candWhole = normalizeArtist(cleanKeyword(candArtist))
        if (srcWhole.isNotBlank() && srcWhole == candWhole) return true

        return srcTokens.any { s ->
            candTokens.any { c ->
                s == c || (minOf(s.length, c.length) >= 2 && (s.contains(c) || c.contains(s)))
            }
        }
    }

    private val unknownAlbums = setOf("未知专辑", "unknownalbum", "unknown", "单曲", "single")

    private fun normalizeAlbum(album: String): String {
        val core = extractCoreTitle(album)
        return core.replace("·", "")
            .replace("•", "")
            .replace("'", "")
            .replace("\"", "")
            .replace("《", "")
            .replace("》", "")
            .replace(" ", "")
            .lowercase()
    }

    /**
     * 从清洗前的完整歌名中提取语种版本标记（如 `歌曲名(粤)` / `(粤语)` / `(国)` / `粤语版`），
     * 用于对称硬门控，彻底杜绝国粤双语同名曲串版。
     */
    private fun extractLanguageTags(rawTitle: String): Set<LanguageTag> {
        if (rawTitle.isBlank()) return emptySet()
        val tags = HashSet<LanguageTag>(2)
        for ((lang, regex) in explicitLangPatterns) {
            if (regex.containsMatchIn(rawTitle)) {
                tags.add(lang)
            }
        }
        bracketContentRegex.findAll(rawTitle).forEach { match ->
            val inner = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.isNotEmpty()) {
                for ((lang, regex) in bracketShortLangPatterns) {
                    if (regex.matches(inner)) {
                        tags.add(lang)
                    }
                }
            }
        }
        return tags
    }

    private fun calculateMatchScore(
        srcTitle: String,
        srcArtist: String,
        srcAlbum: String,
        candTitle: String,
        candArtist: String,
        candAlbum: String,
        expectedDurMs: Long,
        candDurMs: Long,
        qualities: List<KuwoQualityOption>
    ): Int {
        var score = 0
        val sT = extractCoreTitle(srcTitle).lowercase()
        val cT = extractCoreTitle(candTitle).lowercase()
        val sA = normalizeArtist(cleanKeyword(srcArtist))
        val cA = normalizeArtist(cleanKeyword(candArtist))

        // 1. 纯净主歌名硬门控
        if (sT.isBlank() || cT.isBlank()) return -1000
        when {
            sT == cT -> score += 55
            minOf(sT.length, cT.length) >= 2 && (cT.contains(sT) || sT.contains(cT)) -> score += 32
            else -> return -1000
        }

        // 2. 版本特征对称硬门控：除非源歌曲本身就是 Live（歌名或专辑明确为 Live），否则严禁匹配任何标题或专辑带 Live/现场特征的候选！
        val srcTags = extractVersionTags(srcTitle)
        val candTags = extractVersionTags(candTitle)
        val srcIsLive = (VersionTag.LIVE in srcTags) || isLiveAlbum(srcAlbum)
        val candIsLive = (VersionTag.LIVE in candTags) || isLiveAlbum(candAlbum)
        if (!srcIsLive && candIsLive) {
            return -1000
        }
        // 其余非 Live 版本标签（伴奏/DJ/翻唱/加速/器乐）严格对称比对；若源不是专辑级 Live，则标题级 LIVE 标签也必须对称
        val srcCompareTags = if (srcIsLive) srcTags + VersionTag.LIVE else srcTags
        val candCompareTags = if (candIsLive) candTags + VersionTag.LIVE else candTags
        if (srcCompareTags != candCompareTags) {
            return -1000
        }
        // 若核心歌名本身包含单词 `live`（如《Live Forever》），校验括号/副标题修饰区的 Live 标签必须一致
        if (VersionTag.LIVE in srcTags && versionLiveRegex.containsMatchIn(sT)) {
            if (hasModifierLiveTag(srcTitle) != hasModifierLiveTag(candTitle)) {
                return -1000
            }
        }

        // 3. 语种版本对称硬门控：杜绝 `歌曲名(粤)` 误匹配无括号国语版 `歌曲名`（或反之）
        val srcLangs = extractLanguageTags(srcTitle)
        val candLangs = extractLanguageTags(candTitle)
        if (srcLangs != candLangs) {
            return -1000
        }

        // 4. 歌手硬门控 + 加权
        val sTokens = extractArtistTokens(srcArtist)
        val cTokens = extractArtistTokens(candArtist)
        val hasValidBothArtists = sTokens.isNotEmpty() && cTokens.isNotEmpty() &&
            sTokens.none { it in unknownArtists } && cTokens.none { it in unknownArtists }
        if (hasValidBothArtists) {
            if (!isArtistMatched(srcArtist, candArtist)) {
                return -1000
            }
            if (sA.isNotBlank() && sA == cA) {
                score += 35
            } else if (sTokens.any { it in cTokens }) {
                score += 35
            } else {
                score += 30
            }
        }

        // 5. 时长 5 秒硬门控（超过 5 秒一律淘汰，交由原声平台提供精确音频）
        if (expectedDurMs > 5000L && candDurMs > 5000L) {
            val diff = kotlin.math.abs(expectedDurMs - candDurMs)
            if (diff > 5000L) {
                return -1000
            }
            score += when {
                diff <= 1500L -> 35
                diff <= 3500L -> 25
                else -> 12
            }
        }

        // 6. 专辑匹配（次级加权因子：优先命中原专辑而非精选集/合辑）
        val sAlb = normalizeAlbum(srcAlbum)
        val cAlb = normalizeAlbum(candAlbum)
        if (sAlb.isNotBlank() && cAlb.isNotBlank() &&
            sAlb !in unknownAlbums && cAlb !in unknownAlbums
        ) {
            if (sAlb == cAlb) {
                score += 18
            } else if (minOf(sAlb.length, cAlb.length) >= 2 && (sAlb.contains(cAlb) || cAlb.contains(sAlb))) {
                score += 10
            }
        }

        // 7. 品质加权：所有硬门控均通过的前提下，优先选择更高规格母带/无损版本
        val hasMaster = qualities.any { it.bitrate == 20900 }
        val hasFlac = qualities.any { it.bitrate == 2000 || it.bitrate == 4000 }
        if (hasMaster) {
            score += 40
        } else if (hasFlac) {
            score += 25
        }
        if (qualities.isNotEmpty() && qualities.all { it.bitrate <= 128 }) {
            score -= 20
        }

        return score
    }
}
