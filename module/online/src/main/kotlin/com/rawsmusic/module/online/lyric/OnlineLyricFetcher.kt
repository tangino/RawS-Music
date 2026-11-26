package com.rawsmusic.module.online.lyric

import android.util.Base64
import android.util.LruCache
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.online.playback.OnlinePlaybackMatcher
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * 在线歌词抓取与解析器
 * 1. 若已知平台与歌曲 ID，优先精确拉取；
 * 2. 兜底检索严格遵循：先独立从 QQ 音乐平台抓取（快速返回，绝不被慢平台拖死）；
 *    QQ 无结果时再通过 OkHttp 并行从 网易云 / 酷我 / 酷狗 多平台兜底（支持取消立即断流）。
 */
object OnlineLyricFetcher {

    private const val TAG = "OnlineLyricFetcher"

    private const val CONNECT_TIMEOUT_SEC = 4L
    private const val READ_TIMEOUT_SEC = 5L
    private const val QQ_PHASE_BUDGET_MS = 5_000L
    private const val FALLBACK_PHASE_BUDGET_MS = 6_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // 内存缓存：80 首歌曲的 LRC 字符串
    private val lyricCache = LruCache<String, String>(80)

    // 抓不到歌词的歌曲（纯音乐 / 冷门曲）短暂缓存失败，避免反复等待网络
    private const val MISS_TTL_MS = 3 * 60 * 1000L
    private val lyricMissCache = LruCache<String, Long>(80)

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

        // 尝试从当前正在播放的 MusicSourcePlaybackController 上下文提取平台和 ID（排除 webdav）
        var targetPlatform = platform
        var targetSongId = songId
        if (targetPlatform.isNullOrBlank() || targetSongId.isNullOrBlank()) {
            val currentItem = MusicSourcePlaybackController.snapshot.value.currentItem
            if (currentItem != null &&
                !currentItem.sourceId.equals("webdav", ignoreCase = true) &&
                (currentItem.title.contains(cleanTitle) || cleanTitle.contains(currentItem.title))
            ) {
                targetPlatform = currentItem.sourceId
                targetSongId = currentItem.remoteId
            }
        }

        // 1. 尝试按已知平台 + ID 精确获取
        if (!targetPlatform.isNullOrBlank() && !targetSongId.isNullOrBlank()) {
            val directLrc = withTimeoutOrNull(QQ_PHASE_BUDGET_MS) {
                fetchDirectByPlatform(targetPlatform, targetSongId)
            }
            if (!directLrc.isNullOrBlank()) {
                AppLogger.i(TAG, "Lyric hit direct: platform=$targetPlatform id=$targetSongId len=${directLrc.length}")
                lyricCache.put(cacheKey, directLrc)
                lyricMissCache.remove(cacheKey)
                return@withContext directLrc
            }
        }

        // 2. 第一优先级：独立从 QQ 音乐平台抓取（命中即刻返回，彻底避免被其他慢平台拖死超时）
        val qqLrc = withTimeoutOrNull(QQ_PHASE_BUDGET_MS) {
            searchQQLyric(cleanTitle, cleanArtist)
        }?.takeIf { it.isNotBlank() }

        if (qqLrc != null) {
            AppLogger.i(TAG, "Lyric hit QQ: title=$cleanTitle artist=$cleanArtist len=${qqLrc.length}")
            lyricCache.put(cacheKey, qqLrc)
            lyricMissCache.remove(cacheKey)
            return@withContext qqLrc
        }

        // 3. 第二优先级：QQ 无歌词时，再并发从 网易云 / 酷我 / 酷狗 多平台兜底（OkHttp 可立即取消）
        val fallbackLrc = withTimeoutOrNull(FALLBACK_PHASE_BUDGET_MS) {
            coroutineScope {
                val netease = async { searchNeteaseLyric(cleanTitle, cleanArtist) }
                val kuwo = async { searchKuwoLyric(cleanTitle, cleanArtist) }
                val kugou = async { searchKugouLyric(cleanTitle, cleanArtist) }

                val neResult = netease.await()?.takeIf { it.isNotBlank() }
                if (neResult != null) {
                    kuwo.cancel()
                    kugou.cancel()
                    return@coroutineScope "netease" to neResult
                }
                val kwResult = kuwo.await()?.takeIf { it.isNotBlank() }
                if (kwResult != null) {
                    kugou.cancel()
                    return@coroutineScope "kuwo" to kwResult
                }
                val kgResult = kugou.await()?.takeIf { it.isNotBlank() }
                if (kgResult != null) {
                    return@coroutineScope "kugou" to kgResult
                }
                null
            }
        }

        val fetched = fallbackLrc?.second
        if (fetched.isNullOrBlank()) {
            AppLogger.w(TAG, "Lyric miss on all platforms: title=$cleanTitle artist=$cleanArtist")
            lyricMissCache.put(cacheKey, System.currentTimeMillis())
            null
        } else {
            AppLogger.i(TAG, "Lyric hit ${fallbackLrc.first}: title=$cleanTitle artist=$cleanArtist len=${fetched.length}")
            lyricCache.put(cacheKey, fetched)
            lyricMissCache.remove(cacheKey)
            fetched
        }
    }

    private fun buildCacheKey(title: String, artist: String, platform: String?, songId: String?): String {
        return "${platform.orEmpty()}|${songId.orEmpty()}|$title|$artist"
    }

    private suspend fun fetchDirectByPlatform(platform: String, songId: String): String? {
        val p = platform.lowercase(Locale.ROOT)
        return when {
            p == "qq" || p == "tx" -> fetchQQLyric(songId)
            p == "netease" || p == "wy" -> fetchNeteaseLyric(songId)
            p == "kuwo" || p == "kw" -> fetchKuwoLyric(songId)
            else -> null
        }
    }

    /**
     * 在 QQ 音乐通过搜索歌名歌手获取歌词（第一优先级），优先选取歌名与歌手精确匹配的曲目。
     */
    private suspend fun searchQQLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=$encoded&format=json"
            val raw = httpGet(searchUrl, referer = "https://y.qq.com/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
            val listArr = root.optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: return null
            if (listArr.length() == 0) return null

            var matchedMid: String? = null
            var fallbackMid: String? = null
            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val mid = item.optString("songmid").ifBlank { item.optString("mid") }.trim()
                if (mid.isEmpty()) continue
                if (fallbackMid == null) fallbackMid = mid

                val candTitle = item.optString("songname").ifBlank { item.optString("name") }
                val singersArr = item.optJSONArray("singer")
                val candArtist = buildString {
                    if (singersArr != null) {
                        for (j in 0 until singersArr.length()) {
                            val n = singersArr.optJSONObject(j)?.optString("name").orEmpty().trim()
                            if (n.isNotEmpty()) {
                                if (isNotEmpty()) append(" / ")
                                append(n)
                            }
                        }
                    }
                }
                if (OnlinePlaybackMatcher.isTitleMatched(title, candTitle) &&
                    OnlinePlaybackMatcher.isArtistMatched(artist, candArtist)
                ) {
                    matchedMid = mid
                    break
                }
            }
            val targetMid = matchedMid ?: fallbackMid ?: return null
            fetchQQLyric(targetMid)
        } catch (e: Exception) {
            AppLogger.w(TAG, "searchQQLyric failed for query=$query: ${e.message}")
            null
        }
    }

    /**
     * 从 QQ 音乐获取歌词（原文 + 翻译）
     */
    suspend fun fetchQQLyric(songMid: String): String? {
        if (songMid.isBlank()) return null
        return try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&nobase64=1"
            val raw = httpGet(url, referer = "https://y.qq.com/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
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
            AppLogger.w(TAG, "fetchQQLyric failed for songMid=$songMid: ${e.message}")
            null
        }
    }

    /**
     * 从网易云 API 获取歌词（原文 + 翻译）
     */
    suspend fun fetchNeteaseLyric(songId: String): String? {
        val id = songId.filter { it.isDigit() }
        if (id.isEmpty()) return null
        return try {
            val url = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"
            val raw = httpGet(url, referer = "https://music.163.com/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
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
            AppLogger.w(TAG, "fetchNeteaseLyric failed for id=$id: ${e.message}")
            null
        }
    }

    /**
     * 在网易云通过搜索歌名歌手获取歌词
     */
    private suspend fun searchNeteaseLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=$encoded&type=1&offset=0&total=true&limit=5"
            val raw = httpGet(url, referer = "https://music.163.com/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            var matchedId = 0L
            var fallbackId = 0L
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optLong("id", 0L)
                if (id <= 0L) continue
                if (fallbackId == 0L) fallbackId = id
                val candTitle = s.optString("name")
                val artistsArr = s.optJSONArray("artists")
                val candArtist = buildString {
                    if (artistsArr != null) {
                        for (j in 0 until artistsArr.length()) {
                            val n = artistsArr.optJSONObject(j)?.optString("name").orEmpty().trim()
                            if (n.isNotEmpty()) {
                                if (isNotEmpty()) append(" / ")
                                append(n)
                            }
                        }
                    }
                }
                if (OnlinePlaybackMatcher.isTitleMatched(title, candTitle) &&
                    OnlinePlaybackMatcher.isArtistMatched(artist, candArtist)
                ) {
                    matchedId = id
                    break
                }
            }
            val targetId = if (matchedId > 0L) matchedId else fallbackId
            if (targetId <= 0L) return null
            fetchNeteaseLyric(targetId.toString())
        } catch (e: Exception) {
            AppLogger.w(TAG, "searchNeteaseLyric failed for query=$query: ${e.message}")
            null
        }
    }

    /**
     * 从酷我 API 获取歌词
     */
    suspend fun fetchKuwoLyric(songId: String): String? {
        val normalized = songId.removePrefix("MUSIC_").trim()
        if (normalized.isEmpty()) return null
        return try {
            val url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$normalized&httpsStatus=1"
            val raw = httpGet(url, referer = "http://m.kuwo.cn/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
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
            AppLogger.w(TAG, "fetchKuwoLyric failed for id=$normalized: ${e.message}")
            null
        }
    }

    /**
     * 在酷我通过搜索歌名歌手获取歌词
     */
    private suspend fun searchKuwoLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?client=kt&all=$encoded&ft=music&encoding=utf8&rformat=json&pcjson=1&itemset=web_2013&pn=0&rn=5"
            val raw = httpGet(url, referer = "http://m.kuwo.cn/") ?: return null
            val root = parseJsonObjectSafe(raw) ?: return null
            val abslist = root.optJSONArray("abslist") ?: return null
            if (abslist.length() == 0) return null

            var matchedId: String? = null
            var fallbackId: String? = null
            for (i in 0 until abslist.length()) {
                val item = abslist.optJSONObject(i) ?: continue
                val musicId = item.optString("MUSICRID").trim()
                if (musicId.isEmpty()) continue
                if (fallbackId == null) fallbackId = musicId
                val candTitle = item.optString("SONGNAME").ifBlank { item.optString("NAME") }
                val candArtist = item.optString("ARTIST")
                if (OnlinePlaybackMatcher.isTitleMatched(title, candTitle) &&
                    OnlinePlaybackMatcher.isArtistMatched(artist, candArtist)
                ) {
                    matchedId = musicId
                    break
                }
            }
            val targetId = matchedId ?: fallbackId ?: return null
            fetchKuwoLyric(targetId)
        } catch (e: Exception) {
            AppLogger.w(TAG, "searchKuwoLyric failed for query=$query: ${e.message}")
            null
        }
    }

    /**
     * 在酷狗音乐通过搜索歌名歌手获取歌词（多平台兜底之一）
     */
    private suspend fun searchKugouLyric(title: String, artist: String): String? {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$encoded&duration=&hash="
            val searchRaw = httpGet(searchUrl, referer = "https://www.kugou.com/") ?: return null
            val candidates = parseJsonObjectSafe(searchRaw)?.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val first = candidates.optJSONObject(0) ?: return null
            val id = first.optString("id").trim()
            val accessKey = first.optString("accesskey").trim()
            if (id.isEmpty() || accessKey.isEmpty()) return null

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val dlRaw = httpGet(dlUrl, referer = "https://www.kugou.com/") ?: return null
            val encodedContent = parseJsonObjectSafe(dlRaw)?.optString("content").orEmpty().trim()
            if (encodedContent.isEmpty()) return null
            val decoded = String(Base64.decode(encodedContent, Base64.DEFAULT), Charsets.UTF_8).trim()
            decoded.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "searchKugouLyric failed for query=$query: ${e.message}")
            null
        }
    }

    private fun parseJsonObjectSafe(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonSlice = trimmed.substring(start, end + 1)
        return runCatching { JSONObject(jsonSlice) }
            .getOrElse { runCatching { JSONObject(jsonSlice.replace('\'', '"')) }.getOrNull() }
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

    private suspend fun httpGet(urlString: String, referer: String): String? =
        suspendCancellableCoroutine { cont ->
            val request = try {
                Request.Builder()
                    .url(urlString)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build()
            } catch (_: Throwable) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val call = httpClient.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val text = try {
                        response.use { resp ->
                            if (resp.isSuccessful) resp.body?.string() else null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (cont.isActive) cont.resume(text)
                }
            })
        }
}
