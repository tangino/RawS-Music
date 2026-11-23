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
        limit: Int = 3,
    ): List<KuwoSongMatch> = withContext(Dispatchers.IO) {
        val cleanT = extractCoreTitle(title)
        val cleanA = cleanKeyword(artist)
        val normA = normalizeArtist(cleanA)

        // 第一阶段：精准关键词搜索（纯净主歌名 + 规范化歌手）
        val keyword1 = if (normA.isNotBlank() && !cleanT.contains(normA, ignoreCase = true)) {
            "$cleanT $normA"
        } else {
            cleanT
        }

        var candidateItems = searchApi(keyword1)

        // 检查第一阶段候选池中是否已有与目标主歌名相符的高品质结果；
        // 避免无关曲目带母带标记导致跳过纯歌名补全搜索。
        val cleanTLower = cleanT.lowercase()
        val hasMatchedHighQuality = candidateItems.any { item ->
            val candTitle = item.optString("SONGNAME")
                .ifEmpty { item.optString("NAME") }
                .ifEmpty { item.optString("FSONGNAME").trim('"') }
                .ifEmpty { item.optString("name") }
            val candCore = extractCoreTitle(candTitle).lowercase()
            val titleHit = cleanTLower.isNotBlank() &&
                (candCore == cleanTLower || candCore.contains(cleanTLower) || cleanTLower.contains(candCore))
            if (!titleHit) return@any false
            val info = item.optString("N_MINFO").ifEmpty { item.optString("MINFO") }
            info.contains("bitrate:20900") || info.contains("bitrate:2000") || info.contains("bitrate:4000")
        }

        if (!hasMatchedHighQuality && cleanT.isNotBlank() && keyword1 != cleanT) {
            val fallbackItems = searchApi(cleanT)
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
                candTitle,
                candArtist,
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
                "&rn=20"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "kwplayer_ar_9.2.2.1")
                .build()

            val rawResp = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string()
            } ?: return emptyList()

            val fixedJson = rawResp.replace('\'', '"')
            val absList = JSONObject(fixedJson).optJSONArray("abslist") ?: return emptyList()
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
        val seenBitrates = mutableSetOf<Int>()

        while (matcher.find()) {
            val level = matcher.group(1) ?: ""
            val bitrate = matcher.group(2)?.toIntOrNull() ?: continue
            val format = matcher.group(3) ?: ""
            val sizeStr = matcher.group(4) ?: ""
            val sizeBytes = parseSizeBytes(sizeStr)
            if (sizeBytes <= 0L) continue

            if (seenBitrates.add(bitrate)) {
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
                addOption(128, "h", "mp3")
                addOption(100, "h", "ogg")
                addOption(48, "s", "aac")
                addOption(2000, "ff", "flac")
            }
            RawSourceQuality.Standard -> {
                addOption(128, "h", "mp3")
                addOption(320, "p", "mp3")
                addOption(100, "h", "ogg")
                addOption(192, "p", "ogg")
                addOption(300, "p", "ogg")
                addOption(48, "s", "aac")
                addOption(2000, "ff", "flac")
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
     * 在 Top N 匹配曲目 × 全明文音质降级链中寻找首个通过真实流校验的完整正片资源。
     */
    suspend fun resolveValidatedSongResource(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L,
        requestedQuality: RawSourceQuality = qualityFromSourcePreference(),
        allowEncrypted: Boolean = false,
        maxRidCandidates: Int = 3,
        urlValidator: suspend (KuwoSongMatch, KuwoDownloadResource) -> Boolean,
    ): Pair<KuwoSongMatch, KuwoDownloadResource>? = withContext(Dispatchers.IO) {
        val matches = matchTopSongs(title, artist, expectedDurationMs, limit = maxRidCandidates)
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

    private val pairedBracketsRegex = Regex("""\s*[\(\[\{（【].*?[\]\)\}】]""")
    private val unclosedTrailingBracketRegex = Regex("""\s*[\(\[\{（【].*$""")
    private val dashSubtitleRegex = Regex(
        """(?i)\s*[-–—~|]\s*(《.*|.*?(电视剧|电影|网剧|动画|动漫|番剧|游戏|综艺|纪录片|主题曲|片头曲|片尾曲|插曲|推广曲|宣传曲|概念曲|印象曲|人物曲|原声|ost|feat\.?|ft\.?|remaster|重制版|完整版).*)$"""
    )
    private val trailingBookSubtitleRegex = Regex(
        """(?i)\s*《[^》]*》?\s*(电视剧|电影|网剧|动画|动漫|游戏|综艺|主题曲|片头曲|片尾曲|插曲|推广曲|宣传曲|原声|ost)?.*$"""
    )
    private val versionLiveRegex = Regex("""(?i)(\blive\b|现场版?|演唱会|音乐节|unplugged|演唱会现场)""")
    private val versionInstrumentalRegex = Regex("""(?i)(伴奏|纯音乐|\binstrumental\b|\boff\s*vocal\b|\binst\.?\b|消音版|无人声)""")
    private val versionDjRemixRegex = Regex("""(?i)(\bdj\b|\bremix\b|串烧|蹦迪|慢摇|电音版|舞曲版|mashup)""")
    private val versionCoverRegex = Regex("""(?i)(翻唱|\bcover\b)""")
    private val versionSpeedRegex = Regex("""(?i)(加速版?|降调版?|变速版?|0\.\d+x|1\.\d+x|\bnightcore\b|\bslowed\b|\bsped\s*up\b)""")
    private val versionAcousticRegex = Regex("""(?i)(吉他版|钢琴版|清唱|阿卡贝拉|\bacoustic\b|口琴版|古筝版|二胡版|笛子版)""")

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

    /**
     * 歌手常见艺名、同音字及别字归一化（如 钟欣桐 -> 钟欣潼）。
     */
    private fun normalizeArtist(artist: String): String {
        return artist.replace("桐", "潼")
            .replace(" ", "")
            .lowercase()
    }

    private fun calculateMatchScore(
        srcTitle: String,
        srcArtist: String,
        candTitle: String,
        candArtist: String,
        expectedDurMs: Long,
        candDurMs: Long,
        qualities: List<KuwoQualityOption>
    ): Int {
        var score = 0
        val sT = extractCoreTitle(srcTitle).lowercase()
        val cT = extractCoreTitle(candTitle).lowercase()
        val sA = normalizeArtist(cleanKeyword(srcArtist))
        val cA = normalizeArtist(cleanKeyword(candArtist))

        // 1. 纯净主歌名匹配（已剥离 -《剧名》/括号等无害附加信息）
        var titleMatched = false
        if (sT.isNotBlank() && cT.isNotBlank()) {
            if (sT == cT) {
                score += 55
                titleMatched = true
            } else if (cT.contains(sT) || sT.contains(cT)) {
                score += 32
                titleMatched = true
            }
        }

        // 2. 版本特征对称校验：防止 Live / 伴奏 / DJ / 翻唱 / 加速版靠高音质挤掉原版
        val srcTags = extractVersionTags(srcTitle)
        val candTags = extractVersionTags(candTitle)
        var hasVersionConflict = false
        for (tag in candTags) {
            if (tag !in srcTags) {
                hasVersionConflict = true
                score -= when (tag) {
                    VersionTag.INSTRUMENTAL, VersionTag.DJ_REMIX, VersionTag.COVER, VersionTag.SPEED -> 60
                    VersionTag.LIVE, VersionTag.ACOUSTIC -> 45
                }
            }
        }
        for (tag in srcTags) {
            if (tag in candTags) {
                score += 20
            } else {
                hasVersionConflict = true
                score -= 25
            }
        }

        // 3. 歌手匹配（兼容多歌手拆分）
        if (sA.isNotBlank() && cA.isNotBlank()) {
            if (sA == cA) {
                score += 35
            } else if (cA.contains(sA) || sA.contains(cA)) {
                score += 35
            } else {
                val sParts = sA.split('&', '/', '、', ' ').filter { it.isNotBlank() }
                val cParts = cA.split('&', '/', '、', ' ').filter { it.isNotBlank() }
                if (sParts.any { cA.contains(it) } || cParts.any { sA.contains(it) }) {
                    score += 30
                }
            }
        }

        // 4. 时长精准对齐校验
        var durationSeverelyOff = false
        if (expectedDurMs > 0L && candDurMs > 0L) {
            val diff = kotlin.math.abs(expectedDurMs - candDurMs)
            if (diff <= 3500L) {
                score += 35
            } else if (diff <= 8000L) {
                score += 15
            } else if (diff > 25000L) {
                score -= 35
                durationSeverelyOff = true
            }
        }

        // 5. 品质加权：仅在主歌名命中、版本无冲突且时长未严重偏离时才加分，避免低相关母带逆袭
        val hasMaster = qualities.any { it.bitrate == 20900 }
        val hasFlac = qualities.any { it.bitrate == 2000 || it.bitrate == 4000 }
        if (titleMatched && !hasVersionConflict && !durationSeverelyOff) {
            if (hasMaster) {
                score += 40
            } else if (hasFlac) {
                score += 25
            }
        }
        if (qualities.isNotEmpty() && qualities.all { it.bitrate <= 128 }) {
            score -= 20
        }

        return score
    }
}
