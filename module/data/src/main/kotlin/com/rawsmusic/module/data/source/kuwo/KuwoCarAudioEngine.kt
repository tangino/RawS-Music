package com.rawsmusic.module.data.source.kuwo

import com.rawsmusic.core.common.utils.AppLogger
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
        val sizeStr: String
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
     * 搜索并按歌名、歌手与时长对齐最佳候选曲目。
     */
    suspend fun matchBestSong(
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L
    ): KuwoSongMatch? = withContext(Dispatchers.IO) {
        val cleanT = cleanKeyword(title)
        val cleanA = cleanKeyword(artist)
        val normA = normalizeArtist(cleanA)

        // 第一阶段：精准关键词搜索（歌名 + 规范化歌手）
        val keyword1 = if (normA.isNotBlank() && !cleanT.contains(normA, ignoreCase = true)) {
            "$cleanT $normA"
        } else {
            cleanT
        }

        var candidateItems = searchApi(keyword1)

        // 检查第一阶段候选池是否已包含母带或无损；若未包含且原曲有歌手名，进行纯歌名降级搜索补全
        val hasHighQuality = candidateItems.any { item ->
            val info = item.optString("N_MINFO").ifEmpty { item.optString("MINFO") }
            info.contains("bitrate:20900") || info.contains("bitrate:2000") || info.contains("bitrate:4000")
        }

        if (!hasHighQuality && cleanT.isNotBlank()) {
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

        var bestMatch: KuwoSongMatch? = null
        var highestScore = -1

        for (item in candidateItems) {
            val rawRid = item.optString("MUSICRID").ifBlank { item.optString("id") }
            val rid = rawRid.replace("MUSIC_", "").trim()
            if (rid.isBlank()) continue

            val candTitle = item.optString("SONGNAME").ifEmpty { item.optString("name") }
            val candArtist = item.optString("ARTIST").ifEmpty { item.optString("artist") }
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

            if (score > highestScore && score >= 40) {
                highestScore = score
                bestMatch = KuwoSongMatch(
                    rid = rid,
                    songName = candTitle,
                    artist = candArtist,
                    album = candAlbum,
                    durationSec = candDurSec,
                    qualities = qualities
                )
            }
        }

        if (bestMatch != null) {
            AppLogger.i(TAG, "酷我母带引擎精准匹配成功: ${bestMatch.songName} - ${bestMatch.artist} (RID: ${bestMatch.rid}, 音质项: ${bestMatch.qualities.size}个, 得分: $highestScore)")
        } else {
            AppLogger.w(TAG, "酷我搜索未命中合适曲目: '$title' - '$artist'")
        }
        bestMatch
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
     * 解析酷我 N_MINFO / MINFO 字符串。
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

            if (seenBitrates.add(bitrate)) {
                list.add(KuwoQualityOption(level, bitrate, format, sizeStr))
            }
        }
        return list
    }

    /**
     * 依据优先无损/母带策略，向官方车机接口请求真实下载链接与解密密钥。
     */
    suspend fun resolveDownloadResource(
        songMatch: KuwoSongMatch,
        preferLossless: Boolean = true
    ): KuwoDownloadResource? = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<KuwoQualityOption>()

        if (preferLossless) {
            // 1. 优先尝试 192kHz/24bit 臻品母带（20900kmflac）
            songMatch.qualities.find { it.bitrate == 20900 }?.let { candidates.add(it) }
                ?: candidates.add(KuwoQualityOption("zply", 20900, "mflac", ""))

            // 2. 尝试 Hi-Res / CD 无损 FLAC（4000k / 2000k）
            songMatch.qualities.find { it.bitrate == 4000 }?.let { candidates.add(it) }
                ?: candidates.add(KuwoQualityOption("hr", 4000, "flac", ""))
            songMatch.qualities.find { it.bitrate == 2000 }?.let { candidates.add(it) }
                ?: candidates.add(KuwoQualityOption("ff", 2000, "flac", ""))

            // 3. 降级尝试高品质 320k mp3 / 128k
            songMatch.qualities.find { it.bitrate == 320 }?.let { candidates.add(it) }
                ?: candidates.add(KuwoQualityOption("p", 320, "mp3", ""))
            songMatch.qualities.find { it.bitrate == 128 }?.let { candidates.add(it) }
        } else {
            songMatch.qualities.find { it.bitrate == 320 }?.let { candidates.add(it) }
                ?: candidates.add(KuwoQualityOption("p", 320, "mp3", ""))
            songMatch.qualities.find { it.bitrate == 2000 }?.let { candidates.add(it) }
            songMatch.qualities.find { it.bitrate == 128 }?.let { candidates.add(it) }
        }

        for (opt in candidates) {
            val res = requestCarApi(songMatch.rid, opt.bitrate, opt.format)
            if (res != null) {
                return@withContext res
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

    private fun cleanKeyword(input: String): String {
        return input.replace(Regex("""\s*[\(\[\{（【].*?[\]\)\}】]"""), "")
            .replace(Regex("""[-_/\\|&]"""), " ")
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
        val sT = cleanKeyword(srcTitle).lowercase()
        val cT = cleanKeyword(candTitle).lowercase()
        val sA = normalizeArtist(cleanKeyword(srcArtist))
        val cA = normalizeArtist(cleanKeyword(candArtist))

        // 歌名匹配
        if (sT == cT) {
            score += 50
        } else if (cT.contains(sT) || sT.contains(cT)) {
            score += 30
        }

        // 歌手匹配（兼容多歌手拆分）
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

        // 时长精准对齐校验
        if (expectedDurMs > 0L && candDurMs > 0L) {
            val diff = kotlin.math.abs(expectedDurMs - candDurMs)
            if (diff <= 3500L) {
                score += 35
            } else if (diff <= 8000L) {
                score += 15
            } else if (diff > 25000L) {
                score -= 35
            }
        }

        // 品质加权
        val hasMaster = qualities.any { it.bitrate == 20900 }
        val hasFlac = qualities.any { it.bitrate == 2000 || it.bitrate == 4000 }
        if (hasMaster) {
            score += 40
        } else if (hasFlac) {
            score += 25
        } else if (qualities.isNotEmpty() && qualities.all { it.bitrate <= 128 }) {
            score -= 20
        }

        return score
    }
}
