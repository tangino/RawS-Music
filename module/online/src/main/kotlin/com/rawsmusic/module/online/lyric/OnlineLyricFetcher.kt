package com.rawsmusic.module.online.lyric

import android.util.Log
import android.util.LruCache
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 在线歌词抓取与解析器
 * 优先按平台与歌曲 ID 精确拉取，兜底通过网易云 / 酷我按歌曲名与歌手进行全网搜索与匹配
 */
object OnlineLyricFetcher {

    private const val TAG = "OnlineLyricFetcher"

    // 播放页歌词必须尽快出现：抓不到就尽快失败，不能让上一首的歌词/占位词幕在屏幕上挂太久。
    // 因此超时明显短于默认值，并且给整次抓取再套一个总预算（见 TOTAL_BUDGET_MS）。
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 4_500
    private const val TOTAL_BUDGET_MS = 12_000L
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // 内存缓存：50 首歌曲的 LRC 字符串
    private val lyricCache = LruCache<String, String>(50)

    // 抓不到歌词的歌曲（纯音乐 / 冷门曲）也要记住，避免每次播放都重新等一遍网络。
    // 只缓存「失败」一小段时间，网络恢复后仍有机会重新命中。
    private const val MISS_TTL_MS = 5 * 60 * 1000L
    private val lyricMissCache = LruCache<String, Long>(50)

    /**
     * 获取在线歌曲的 LRC 歌词文本（包含翻译，格式为标准 LRC）
     */
    suspend fun fetchLyric(
        title: String,
        artist: String,
        platform: String? = null,
        songId: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty() && cleanArtist.isEmpty()) return@withContext null

        val cacheKey = buildCacheKey(cleanTitle, cleanArtist, platform, songId)
        lyricCache.get(cacheKey)?.let { return@withContext it }
        val missedAt = lyricMissCache.get(cacheKey)
        if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_TTL_MS) {
            return@withContext null
        }

        // 尝试从当前正在播放的 MusicSourcePlaybackController 上下文提取平台和 ID
        var targetPlatform = platform
        var targetSongId = songId
        if (targetPlatform.isNullOrBlank() || targetSongId.isNullOrBlank()) {
            val currentItem = MusicSourcePlaybackController.snapshot.value.currentItem
            if (currentItem != null && (currentItem.title.contains(cleanTitle) || cleanTitle.contains(currentItem.title))) {
                targetPlatform = currentItem.sourceId
                targetSongId = currentItem.remoteId
            }
        }

        val fetched = withTimeoutOrNull(TOTAL_BUDGET_MS) {
            // 1. 尝试按平台 + ID 精确获取
            if (!targetPlatform.isNullOrBlank() && !targetSongId.isNullOrBlank()) {
                val directLrc = fetchDirectByPlatform(targetPlatform, targetSongId)
                if (!directLrc.isNullOrBlank()) return@withTimeoutOrNull directLrc
            }

            // 2. 兜底方案：按「歌名 + 歌手」检索网易云 / 酷我。
            //    两个搜索并行发起，谁先给出可用歌词就用谁；串行执行会把最坏等待时间翻倍。
            coroutineScope {
                val netease = async { searchNeteaseLyric(cleanTitle, cleanArtist) }
                val kuwo = async { searchKuwoLyric(cleanTitle, cleanArtist) }
                netease.await()?.takeIf { it.isNotBlank() } ?: kuwo.await()
            }
        }

        if (fetched.isNullOrBlank()) {
            lyricMissCache.put(cacheKey, System.currentTimeMillis())
            null
        } else {
            lyricCache.put(cacheKey, fetched)
            lyricMissCache.remove(cacheKey)
            fetched
        }
    }

    private fun buildCacheKey(title: String, artist: String, platform: String?, songId: String?): String {
        return "${platform.orEmpty()}|${songId.orEmpty()}|$title|$artist"
    }

    private fun fetchDirectByPlatform(platform: String, songId: String): String? {
        val p = platform.lowercase(Locale.ROOT)
        return when {
            p == "netease" || p == "wy" -> fetchNeteaseLyric(songId)
            p == "kuwo" || p == "kw" -> fetchKuwoLyric(songId)
            p == "qq" || p == "tx" -> fetchQQLyric(songId)
            else -> null
        }
    }

    /**
     * 从网易云 API 获取歌词（原文 + 翻译）
     */
    fun fetchNeteaseLyric(songId: String): String? {
        val id = songId.filter { it.isDigit() }
        if (id.isEmpty()) return null
        return try {
            val url = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"
            val jsonStr = httpGet(url, referer = "https://music.163.com/") ?: return null
            val root = JSONObject(jsonStr)
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
            val tlyric = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()

            if (lrc.isBlank()) return null
            buildString {
                append(lrc.trim())
                if (tlyric.isNotBlank()) {
                    if (isNotEmpty() && !endsWith("\n")) append("\n")
                    append(tlyric.trim())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchNeteaseLyric failed for id=$id", e)
            null
        }
    }

    /**
     * 在网易云通过搜索歌名歌手获取歌词
     */
    private fun searchNeteaseLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=$encoded&type=1&offset=0&total=true&limit=3"
            val jsonStr = httpGet(url, referer = "https://music.163.com/") ?: return null
            val root = JSONObject(jsonStr)
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val songId = songs.optJSONObject(0)?.optLong("id", 0L) ?: 0L
            if (songId <= 0L) return null
            fetchNeteaseLyric(songId.toString())
        } catch (e: Exception) {
            Log.w(TAG, "searchNeteaseLyric failed for query=$query", e)
            null
        }
    }

    /**
     * 从酷我 API 获取歌词
     */
    fun fetchKuwoLyric(songId: String): String? {
        val normalized = songId.removePrefix("MUSIC_").trim()
        if (normalized.isEmpty()) return null
        return try {
            val url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$normalized&httpsStatus=1"
            val jsonStr = httpGet(url, referer = "http://m.kuwo.cn/") ?: return null
            val root = JSONObject(jsonStr)
            val dataObj = root.optJSONObject("data") ?: root
            val lrcArr = dataObj.optJSONArray("lrclist") ?: return null

            val lines = mutableListOf<String>()
            for (i in 0 until lrcArr.length()) {
                val obj = lrcArr.optJSONObject(i) ?: continue
                val text = obj.optString("lineLyric").ifEmpty { obj.optString("line") }.trim()
                val seconds = obj.optDouble("time", -1.0)
                if (text.isNotEmpty() && seconds >= 0.0) {
                    val totalMs = (seconds * 1000.0).toLong()
                    val min = totalMs / 60000
                    val sec = (totalMs % 60000) / 1000
                    val ms = (totalMs % 1000) / 10
                    lines.add(String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", min, sec, ms, text))
                }
            }
            if (lines.isEmpty()) null else lines.joinToString("\n")
        } catch (e: Exception) {
            Log.w(TAG, "fetchKuwoLyric failed for id=$normalized", e)
            null
        }
    }

    /**
     * 在酷我通过搜索歌名歌手获取歌词
     */
    private fun searchKuwoLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?client=kt&all=$encoded&type=music&itemset=web_2013&format=json"
            val jsonStr = httpGet(url, referer = "http://m.kuwo.cn/") ?: return null
            val root = JSONObject(jsonStr)
            val abslist = root.optJSONArray("abslist") ?: return null
            if (abslist.length() == 0) return null

            val musicId = abslist.optJSONObject(0)?.optString("MUSICRID") ?: return null
            fetchKuwoLyric(musicId)
        } catch (e: Exception) {
            Log.w(TAG, "searchKuwoLyric failed for query=$query", e)
            null
        }
    }

    /**
     * 从 QQ 音乐获取歌词
     */
    fun fetchQQLyric(songMid: String): String? {
        if (songMid.isBlank()) return null
        return try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1"
            val jsonStr = httpGet(url, referer = "https://y.qq.com/") ?: return null
            val root = JSONObject(jsonStr)
            val lyric = unescapeLyric(root.optString("lyric"))
            val trans = unescapeLyric(root.optString("trans"))

            if (lyric.isBlank()) return null
            buildString {
                append(lyric.trim())
                if (trans.isNotBlank()) {
                    if (isNotEmpty() && !endsWith("\n")) append("\n")
                    append(trans.trim())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchQQLyric failed for songMid=$songMid", e)
            null
        }
    }

    private fun unescapeLyric(str: String): String {
        if (str.isEmpty()) return ""
        return str.replace("&#58;", ":")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace("&#32;", " ")
            .replace("&#46;", ".")
            .replace("&#40;", "(")
            .replace("&#41;", ")")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
    }

    private fun httpGet(urlString: String, referer: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val uri = URI(urlString)
            conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "*/*")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
