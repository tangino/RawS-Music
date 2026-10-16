package com.rawsmusic.module.online.playback

import android.content.Context
import android.util.LruCache
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.ResolveStrategy
import com.rawsmusic.module.data.source.playback.MusicSourceAudioResolver
import com.rawsmusic.module.data.source.runtime.MusicSourceRuntimeClient
import com.rawsmusic.module.data.source.runtime.MusicSourceSearchCoordinator
import com.rawsmusic.module.online.engine.LxSourceEngine
import com.rawsmusic.module.online.model.OnlinePlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 在线音频直链解析调度器与音源管理器（完整照搬自 OrBitPlayer 的 `OnlineAudioSourceManager` 寻源与换源算法）。
 *
 * 核心特性：
 * 1. 内存 LRU 缓存（200 条容量，15 分钟时效，0ms 秒开）
 * 2. 阶段 1：主音源原平台优先通道（无搜索延迟，不发生串歌，毫秒级直达）
 * 3. 阶段 2：主音源跨平台并发搜救寻源（各平台官方 API 检索 + `SongMatcher` 严格过滤打分 + 协程并发解析探活）
 * 4. 阶段 3：备用多音源并发竞速寻源（主音源失效时唤醒全部备用源并发搜救）
 * 5. 官方直链最终兜底（网易云 outer 兜底直链 + 咪咕 MIGUM2.0 官方直链）
 * 6. 阶梯音质探测（flac24bit -> flac -> 320k -> 128k）
 * 7. 严苛防试听与流完整性检验（`OnlinePlaybackProbe`）
 * 8. QuickJS 原生加速沙箱引擎（原地微秒级调用，零 IPC 开销）
 */
object OnlineAudioSourceResolver {

    private const val TAG = "OnlineAudioSourceMgr"

    data class MatchedPlatformSong(
        val platform: OnlinePlatform,
        val songId: String,
        val hash: String? = null,
        val matchTitle: String? = null,
        val matchArtist: String? = null,
        val durationMs: Long = 0L,
        val matchScore: Int = 0,
    )

    data class ResolvedAudioSource(
        val url: String,
        val platform: OnlinePlatform,
        val sourceName: String,
        val quality: String? = null,
        val durationMs: Long = 0L,
        val isFallback: Boolean = false,
        val fallbackReason: String? = null,
    )

    /**
     * 经典多源轮询候选平台轮询顺序：网易云 -> 酷狗 -> 酷我 -> QQ -> 咪咕。
     */
    val CLASSIC_POLLING_PLATFORMS = listOf(
        OnlinePlatform.NETEASE,
        OnlinePlatform.KUGOU,
        OnlinePlatform.KUWO,
        OnlinePlatform.QQ,
        OnlinePlatform.MIGU,
    )

    // 内存 LRU 缓存：Key = "$platform:$songId", Value = CachedUrl(url, expireAtMs)
    private data class CachedUrl(val url: String, val expireAtMs: Long, val quality: String? = null)
    private val memoryUrlCache = LruCache<String, CachedUrl>(200)

    // 原生 QuickJS 引擎池：按音源 ID 常驻在当前主进程 HandlerThread 内存中，微秒级执行 JS
    private val quickJsEngines = ConcurrentHashMap<String, LxSourceEngine>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        // 挂载到底层解析器，当底层遇到未注册音源 ID 的在线曲目时自动走在线寻源
        MusicSourceAudioResolver.onlineFallbackResolver = MusicSourceAudioResolver.OnlineAudioFallbackResolver { context, item, requestedQuality ->
            resolveFallbackForMediaItem(context, item, requestedQuality)
        }
        // 挂载直链探活校验器，确保第三方插件返回的直链经过防试听与流完整性校验
        MusicSourceAudioResolver.audioUrlValidator = MusicSourceAudioResolver.AudioUrlValidator { url, expectedDurationMs, _ ->
            OnlinePlaybackProbe.probeAndValidateAudioUrl(url, expectedDurationMs, null)
        }
        // 挂载 QuickJS 原生加速解析器，让已导入的洛雪插件在播放和预加载时享受毫秒级原地解析
        MusicSourceAudioResolver.quickJsLxAudioResolver = MusicSourceAudioResolver.QuickJsLxAudioResolver { context, source, item, quality ->
            resolveViaQuickJs(context, source, item, quality)
        }
    }

    @Synchronized
    fun getOrCreateQuickJsEngine(context: Context, source: InstalledLxSource): LxSourceEngine? {
        val existing = quickJsEngines[source.id]
        if (existing != null) return existing
        return try {
            val scriptFile = java.io.File(source.scriptPath)
            if (!scriptFile.exists()) return null
            val scriptContent = scriptFile.readText(Charsets.UTF_8)
            val engine = LxSourceEngine(context.applicationContext, scriptContent, source.id, source.name)
            quickJsEngines[source.id] = engine
            AppLogger.i(TAG, "Initialized QuickJS engine for LX source: ${source.name}")
            engine
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to initialize QuickJS engine for ${source.name}: ${e.message}")
            null
        }
    }

    fun evictQuickJsEngine(sourceId: String) {
        quickJsEngines.remove(sourceId)?.destroy()
    }

    private suspend fun resolveViaQuickJs(
        context: Context,
        source: InstalledLxSource,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource? = withContext(Dispatchers.IO) {
        val engine = getOrCreateQuickJsEngine(context, source) ?: return@withContext null
        val payloadObj = runCatching { JSONObject(item.sourcePayload) }.getOrNull() ?: JSONObject()
        val sourceKey = payloadObj.optString("source").ifBlank { payloadObj.optString("platform") }
            .ifBlank { item.remoteId.substringBefore(":", "wy") }
        val songId = payloadObj.optString("songmid").ifBlank { payloadObj.optString("id") }
            .ifBlank { item.remoteId.substringAfter(":", item.remoteId) }
        val qualityStr = when (quality) {
            RawSourceQuality.Lossless -> "flac"
            RawSourceQuality.HiRes -> "flac24bit"
            RawSourceQuality.High -> "320k"
            else -> "128k"
        }
        val musicInfo = JSONObject().apply {
            put("name", item.title)
            put("singer", item.artists.joinToString("、"))
            put("albumName", item.album)
            put("songmid", songId)
            put("id", songId)
            put("copyrightId", songId)
            put("source", sourceKey)
            put("types", org.json.JSONArray().apply {
                put(JSONObject().put("type", "128k"))
                put(JSONObject().put("type", "320k"))
                put(JSONObject().put("type", "flac"))
                put(JSONObject().put("type", "flac24bit"))
            })
        }
        val url = engine.resolveMusicUrl(sourceKey, musicInfo, qualityStr, timeoutMs = 2500L)
        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
            AppLogger.i(TAG, "QuickJS successfully resolved ${item.title} ($qualityStr) via ${source.name}: $url")
            RawResolvedAudioSource(
                url = url,
                quality = quality,
            )
        } else {
            null
        }
    }

    /** 主动驱逐指定单曲的内存 URL 缓存（当播放异常/403/过期时调用）。 */
    fun evictUrlCache(platform: OnlinePlatform, songId: String) {
        val baseKey = "${platform.id}:$songId"
        memoryUrlCache.remove(baseKey)
        listOf("128k", "320k", "flac", "flac24bit").forEach { q ->
            memoryUrlCache.remove("$baseKey:$q")
        }
        AppLogger.d(TAG, "Evicted URL cache for ${platform.displayName}:$songId")
    }

    /**
     * 跨平台在目标平台上搜索并筛选所有满足匹配条件的候选单曲（按匹配得分降序排列）。
     * 逐字照搬自 OrBitPlayer 的 `searchMatchedSongsOnPlatform`。
     */
    suspend fun searchMatchedSongsOnPlatform(
        targetPlatform: OnlinePlatform,
        title: String,
        artist: String,
        expectedDurationMs: Long = 0L,
    ): List<MatchedPlatformSong> = withContext(Dispatchers.IO) {
        val keyword = "$title $artist".trim()
        val matchedList = mutableListOf<MatchedPlatformSong>()
        try {
            when (targetPlatform) {
                OnlinePlatform.NETEASE -> {
                    val postData = FormBody.Builder()
                        .add("s", keyword)
                        .add("type", "1")
                        .add("offset", "0")
                        .add("limit", "10")
                        .add("total", "true")
                        .build()
                    val req = Request.Builder()
                        .url("https://music.163.com/api/search/get/web?csrf_token=")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Referer", "https://music.163.com/")
                        .post(postData)
                        .build()
                    val res = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    if (!res.isNullOrBlank()) {
                        val songsArr = JSONObject(res).optJSONObject("result")?.optJSONArray("songs")
                        if (songsArr != null) {
                            for (i in 0 until songsArr.length()) {
                                val obj = songsArr.optJSONObject(i) ?: continue
                                val id = obj.optLong("id").toString()
                                if (id.isBlank() || id == "0") continue
                                val candTitle = OnlinePlaybackMatcher.cleanText(obj.optString("name"))
                                val artistsArr = obj.optJSONArray("artists")
                                val candArtist = (0 until (artistsArr?.length() ?: 0))
                                    .mapNotNull { artistsArr?.optJSONObject(it)?.optString("name") }
                                    .joinToString(" / ")
                                val candDur = obj.optLong("duration", 0L)

                                if (OnlinePlaybackMatcher.isMatched(title, artist, candTitle, candArtist, expectedDurationMs, candDur)) {
                                    val score = OnlinePlaybackMatcher.calculateMatchScore(title, artist, candTitle, candArtist, expectedDurationMs, candDur)
                                    matchedList.add(MatchedPlatformSong(targetPlatform, id, matchTitle = candTitle, matchArtist = candArtist, durationMs = candDur, matchScore = score))
                                }
                            }
                        }
                    }
                }
                OnlinePlatform.QQ -> {
                    val payload = JSONObject().apply {
                        put("comm", JSONObject().apply {
                            put("ct", "19")
                            put("cv", "1873")
                            put("uin", "0")
                        })
                        put("req", JSONObject().apply {
                            put("module", "music.search.SearchCgiService")
                            put("method", "DoSearchForQQMusicDesktop")
                            put("param", JSONObject().apply {
                                put("query", keyword)
                                put("search_type", 0)
                                put("num_per_page", 10)
                                put("page_num", 1)
                            })
                        })
                    }
                    val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                    val req = Request.Builder()
                        .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Referer", "https://y.qq.com/")
                        .post(body)
                        .build()
                    val res = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    if (!res.isNullOrBlank()) {
                        val list = JSONObject(res).optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val first = list.optJSONObject(i) ?: continue
                                val mid = first.optString("mid").ifEmpty { first.optString("songmid") }
                                if (mid.isBlank()) continue
                                val candTitle = OnlinePlaybackMatcher.cleanText(first.optString("name").ifEmpty { first.optString("title") })
                                val singerArr = first.optJSONArray("singer")
                                val candArtist = (0 until (singerArr?.length() ?: 0))
                                    .mapNotNull { singerArr?.optJSONObject(it)?.optString("name") }
                                    .joinToString(" / ")
                                val candDur = first.optLong("interval", 0L) * 1000L

                                if (OnlinePlaybackMatcher.isMatched(title, artist, candTitle, candArtist, expectedDurationMs, candDur)) {
                                    val score = OnlinePlaybackMatcher.calculateMatchScore(title, artist, candTitle, candArtist, expectedDurationMs, candDur)
                                    matchedList.add(MatchedPlatformSong(targetPlatform, mid, matchTitle = candTitle, matchArtist = candArtist, durationMs = candDur, matchScore = score))
                                }
                            }
                        }
                    }
                }
                OnlinePlatform.KUGOU -> {
                    val encoded = URLEncoder.encode(keyword, "UTF-8")
                    val url = "http://songsearch.kugou.com/song_search_v2?keyword=$encoded&page=1&pagesize=10&platform=WebFilter"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    val res = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    if (!res.isNullOrBlank()) {
                        val list = JSONObject(res).optJSONObject("data")?.optJSONArray("lists")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val first = list.optJSONObject(i) ?: continue
                                val hash = first.optString("FileHash").ifEmpty { first.optString("HQFileHash").ifEmpty { first.optString("SQFileHash") } }
                                val id = first.optString("Audioid").ifEmpty { first.optString("Scid") }
                                if (hash.isBlank() && id.isBlank()) continue
                                val candTitle = OnlinePlaybackMatcher.cleanText(first.optString("SongName"))
                                val candArtist = OnlinePlaybackMatcher.cleanText(first.optString("SingerName"))
                                val candDur = first.optLong("Duration", 0L) * 1000L

                                if (OnlinePlaybackMatcher.isMatched(title, artist, candTitle, candArtist, expectedDurationMs, candDur)) {
                                    val score = OnlinePlaybackMatcher.calculateMatchScore(title, artist, candTitle, candArtist, expectedDurationMs, candDur)
                                    matchedList.add(MatchedPlatformSong(targetPlatform, if (id.isNotEmpty()) id else hash, hash = hash, matchTitle = candTitle, matchArtist = candArtist, durationMs = candDur, matchScore = score))
                                }
                            }
                        }
                    }
                }
                OnlinePlatform.KUWO -> {
                    val encoded = URLEncoder.encode(keyword, "UTF-8")
                    val url = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=10&rformat=json&encoding=utf8"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    val raw = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    if (!raw.isNullOrBlank()) {
                        val fixed = raw.replace('\'', '"')
                        val list = JSONObject(fixed).optJSONArray("abslist")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val first = list.optJSONObject(i) ?: continue
                                val id = first.optString("MUSICRID").replace("MUSIC_", "").ifEmpty { first.optString("id") }
                                if (id.isBlank()) continue
                                val candTitle = OnlinePlaybackMatcher.cleanText(first.optString("SONGNAME").ifEmpty { first.optString("name") })
                                val candArtist = OnlinePlaybackMatcher.cleanText(first.optString("ARTIST").ifEmpty { first.optString("artist") })
                                val candDur = first.optLong("DURATION", 0L) * 1000L

                                if (OnlinePlaybackMatcher.isMatched(title, artist, candTitle, candArtist, expectedDurationMs, candDur)) {
                                    val score = OnlinePlaybackMatcher.calculateMatchScore(title, artist, candTitle, candArtist, expectedDurationMs, candDur)
                                    matchedList.add(MatchedPlatformSong(targetPlatform, id, matchTitle = candTitle, matchArtist = candArtist, durationMs = candDur, matchScore = score))
                                }
                            }
                        }
                    }
                }
                OnlinePlatform.MIGU -> {
                    val encoded = URLEncoder.encode(keyword, "UTF-8")
                    val switchJson = URLEncoder.encode("{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":0,\"mvSong\":0,\"songlist\":0,\"bestShow\":0}", "UTF-8")
                    val url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=$encoded&pageNo=1&pageSize=10&searchSwitch=$switchJson"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Referer", "https://m.music.migu.cn")
                        .build()
                    val res = okHttpClient.newCall(req).execute().use { it.body?.string() }
                    if (!res.isNullOrBlank()) {
                        val list = JSONObject(res).optJSONObject("songResultData")?.optJSONArray("result")
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                val first = list.optJSONObject(i) ?: continue
                                val id = first.optString("copyrightId").ifEmpty { first.optString("id") }
                                if (id.isBlank()) continue
                                val candTitle = OnlinePlaybackMatcher.cleanText(first.optString("name"))
                                val singersArr = first.optJSONArray("singers")
                                val candArtist = if (singersArr != null) {
                                    (0 until singersArr.length()).mapNotNull { singersArr.optJSONObject(it)?.optString("name") }.joinToString(" / ")
                                } else {
                                    OnlinePlaybackMatcher.cleanText(first.optString("singer"))
                                }
                                val lengthStr = first.optString("length", "")
                                val candDur = if (lengthStr.contains(":")) {
                                    val parts = lengthStr.split(":")
                                    val m = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                                    val s = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                                    (m * 60L + s) * 1000L
                                } else {
                                    lengthStr.toLongOrNull()?.let { if (it < 1000) it * 1000L else it } ?: 0L
                                }

                                if (OnlinePlaybackMatcher.isMatched(title, artist, candTitle, candArtist, expectedDurationMs, candDur)) {
                                    val score = OnlinePlaybackMatcher.calculateMatchScore(title, artist, candTitle, candArtist, expectedDurationMs, candDur)
                                    matchedList.add(MatchedPlatformSong(targetPlatform, id, matchTitle = candTitle, matchArtist = candArtist, durationMs = candDur, matchScore = score))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Search match error on ${targetPlatform.displayName} for $keyword: ${e.message}")
        }
        matchedList.sortedByDescending { it.matchScore }
    }

    /** 平台对应的音源脚本 sourceKey。 */
    fun platformToSourceKey(platform: OnlinePlatform): String = when (platform) {
        OnlinePlatform.NETEASE -> "wy"
        OnlinePlatform.QQ -> "tx"
        OnlinePlatform.KUGOU -> "kg"
        OnlinePlatform.KUWO -> "kw"
        OnlinePlatform.MIGU -> "mg"
    }

    /** 音质字符串转 RawSourceQuality。 */
    fun stringToRawSourceQuality(quality: String?): RawSourceQuality = when (quality) {
        "flac24bit" -> RawSourceQuality.HiRes
        "flac" -> RawSourceQuality.Lossless
        "320k" -> RawSourceQuality.Super
        "128k" -> RawSourceQuality.Standard
        else -> RawSourceQuality.High
    }

    /**
     * 针对单个平台执行音频解析（音源脚本解析 + 官方直链兜底）。
     * 逐字照搬自 OrBitPlayer 的 `resolveSinglePlatformSource`。
     */
    suspend fun resolveSinglePlatformSource(
        context: Context,
        targetPlatform: OnlinePlatform,
        songId: String,
        title: String,
        artist: String,
        album: String,
        expectedDurationMs: Long,
        isOriginal: Boolean,
        lxSource: InstalledLxSource?,
        qualityTryList: List<String>,
    ): ResolvedAudioSource? = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<MatchedPlatformSong>()

        if (isOriginal && songId.isNotBlank()) {
            candidates.add(MatchedPlatformSong(targetPlatform, songId, hash = songId, matchTitle = title, matchArtist = artist, durationMs = expectedDurationMs))
        }

        // 跨平台寻源或原平台初步失败时，搜索匹配符合严格规则的候选集
        if (!isOriginal || candidates.isEmpty()) {
            val searched = searchMatchedSongsOnPlatform(targetPlatform, title, artist, expectedDurationMs)
            candidates.addAll(searched)
        }

        if (candidates.isEmpty()) {
            return@withContext null
        }

        val sourceKey = platformToSourceKey(targetPlatform)

        // 遍历所有满足匹配规则的候选曲目进行解析
        for (cand in candidates) {
            val effectiveId = cand.songId
            val effectiveHash = cand.hash ?: cand.songId

            // 1. 尝试通过音源脚本引擎解析 (优先通过 QuickJS 原生引擎，失败或未就绪时平滑降级至 WebView)
            if (lxSource != null) {
                val qjsEngine = getOrCreateQuickJsEngine(context, lxSource)
                val musicInfo = JSONObject().apply {
                    put("source", sourceKey)
                    put("songmid", effectiveId)
                    put("id", effectiveId)
                    put("copyrightId", effectiveId)
                    put("platform", sourceKey)
                    put("hash", effectiveHash)
                    put("name", cand.matchTitle ?: title)
                    put("singer", cand.matchArtist ?: artist)
                    put("albumName", album)
                    put("types", org.json.JSONArray().apply {
                        put(JSONObject().put("type", "128k"))
                        put(JSONObject().put("type", "320k"))
                        put(JSONObject().put("type", "flac"))
                        put(JSONObject().put("type", "flac24bit"))
                    })
                }

                for (q in qualityTryList) {
                    val rawQuality = stringToRawSourceQuality(q)
                    try {
                        val timeout = if (q == qualityTryList.first()) 2500L else 2000L
                        // 1.1 优先走主进程内单线程 QuickJS 原生引擎极速解析
                        var url = qjsEngine?.resolveMusicUrl(sourceKey, musicInfo, q, timeoutMs = timeout)
                        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) {
                            // 1.2 降级走原有通道
                            val mediaItem = RawSourceMediaItem(
                                sourceId = lxSource.id,
                                remoteId = "$sourceKey:$effectiveId",
                                mediaType = RawSourceMediaType.Music,
                                title = cand.matchTitle ?: title,
                                artists = listOf(cand.matchArtist ?: artist),
                                album = album,
                                durationMs = if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                artworkUrl = "",
                                sourcePayload = musicInfo.toString(),
                            )
                            val resolved = MusicSourceRuntimeClient.resolveLxAudio(context, lxSource, mediaItem, rawQuality, timeoutMs = timeout)
                            url = resolved.url
                        }
                        if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                            val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(url, expectedDurationMs, targetPlatform)
                            if (probePair != null) {
                                val (validUrl, detectedDur) = probePair
                                val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                                val res = ResolvedAudioSource(
                                    url = validUrl,
                                    platform = targetPlatform,
                                    sourceName = lxSource.name,
                                    quality = q,
                                    durationMs = finalDur,
                                    isFallback = !isOriginal,
                                    fallbackReason = if (!isOriginal) "原平台音频缺失，已精准匹配切换至 ${targetPlatform.displayName}" else null,
                                )
                                AppLogger.i(TAG, "Successfully resolved & verified $title ($q) via ${targetPlatform.displayName} (${if (isOriginal) "当前音源" else "跨平台备用源"}): $validUrl (duration: ${finalDur}ms)")
                                return@withContext res
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        AppLogger.d(TAG, "LX resolve error on $sourceKey for $effectiveId ($q): ${e.message}")
                    }
                }
            }

            // 2. 针对网易云官方 outer 兜底直链
            if (targetPlatform == OnlinePlatform.NETEASE) {
                if (effectiveId.isNotEmpty() && effectiveId.matches(Regex("^\\d+$"))) {
                    val outerUrl = "https://music.163.com/song/media/outer/url?id=$effectiveId.mp3"
                    val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(outerUrl, expectedDurationMs, targetPlatform)
                    if (probePair != null) {
                        val (validUrl, detectedDur) = probePair
                        val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                        val res = ResolvedAudioSource(
                            url = validUrl,
                            platform = targetPlatform,
                            sourceName = "网易云官方",
                            quality = "128k",
                            durationMs = finalDur,
                            isFallback = !isOriginal,
                            fallbackReason = if (!isOriginal) "原音源未匹配，自动降级至网易云官方音频" else null,
                        )
                        AppLogger.i(TAG, "Fallback to verified Netease outer URL for $title via ${targetPlatform.displayName}: $validUrl")
                        return@withContext res
                    }
                }
            }

            // 3. 针对咪咕音乐官方免费直链兜底
            if (targetPlatform == OnlinePlatform.MIGU) {
                if (effectiveId.isNotEmpty()) {
                    val miguUrl = OnlinePlaybackProbe.resolveMiguDirectPlayUrl(effectiveId)
                    if (!miguUrl.isNullOrBlank()) {
                        val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(miguUrl, expectedDurationMs, targetPlatform)
                        if (probePair != null) {
                            val (validUrl, detectedDur) = probePair
                            val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                            val res = ResolvedAudioSource(
                                url = validUrl,
                                platform = targetPlatform,
                                sourceName = "咪咕官方",
                                quality = "HQ",
                                durationMs = finalDur,
                                isFallback = !isOriginal,
                                fallbackReason = if (!isOriginal) "原音源未匹配，自动降级至咪咕官方直链" else null,
                            )
                            AppLogger.i(TAG, "Fallback to verified Migu official direct URL for $title: $validUrl")
                            return@withContext res
                        }
                    }
                }
            }
        }

        null
    }

    /**
     * 模式 1：经典多源轮询 (1:1 还原 OrbitPlayer 的 resolvePlayableSourceClassicPolling)。
     *
     * 特性：
     * 1. 候选平台按序逐一轮询（当前平台优先，其余平台按序），绝无乱序
     * 2. 可用音源按序逐一轮询（主音源优先，备用音源其后）
     * 3. 音质逐个尝试，执行严格的 3500ms/2500ms 紧凑超时，杜绝卡死挂起
     * 4. 拿到直链后经 OnlinePlaybackProbe 严格探活与流长度完整性防试听校验
     * 5. 网易云 outer 官方兜底与咪咕官方免费直链兜底
     * 6. 稳定性极佳，抗试听与偶发网络波动
     */
    suspend fun resolvePlayableSourceClassicPolling(
        context: Context,
        platform: OnlinePlatform,
        songId: String,
        title: String,
        artist: String,
        album: String,
        expectedDurationMs: Long = 0L,
        explicitQuality: String? = null,
    ): ResolvedAudioSource? = withContext(Dispatchers.IO) {
        val cacheKey = if (explicitQuality != null) "${platform.id}:$songId:$explicitQuality" else "${platform.id}:$songId"
        val now = System.currentTimeMillis()

        // 1. 查内存缓存 (15 分钟有效期)
        val cached = memoryUrlCache.get(cacheKey)
        if (cached != null && cached.expireAtMs > now) {
            AppLogger.d(TAG, "[ClassicPolling] Hit memory cache for $title: ${cached.url}")
            return@withContext ResolvedAudioSource(
                url = cached.url,
                platform = platform,
                sourceName = platform.displayName,
                durationMs = expectedDurationMs,
                quality = explicitQuality ?: cached.quality,
            )
        }

        val qualityTryList = OnlineQualityLadder.qualityTryList(explicitQuality)

        // 2. 候选平台列表：当前平台优先，其余平台按经典顺序轮询（网易云 -> 酷狗 -> 酷我 -> QQ -> 咪咕）
        val candidatePlatforms = (listOf(platform) + CLASSIC_POLLING_PLATFORMS).distinct()

        // 3. 所有可用音源列表：主音源优先，备用音源依次轮询
        val primarySource = LxSourcePluginStore.primarySource()
        val backupSources = LxSourcePluginStore.backupSources()
        val orderedEngines = if (primarySource != null) {
            listOf(primarySource) + backupSources
        } else {
            backupSources
        }

        var resolvedResult: ResolvedAudioSource? = null

        for (targetPlatform in candidatePlatforms) {
            val isOriginal = (targetPlatform == platform)
            val candidates = mutableListOf<MatchedPlatformSong>()

            if (isOriginal && songId.isNotBlank()) {
                candidates.add(
                    MatchedPlatformSong(
                        platform = targetPlatform,
                        songId = songId,
                        hash = songId,
                        matchTitle = title,
                        matchArtist = artist,
                        durationMs = expectedDurationMs,
                    ),
                )
            }

            // 跨平台寻源时获取该平台严格匹配的候选单曲（按评分降序取前 3 首）
            if (!isOriginal) {
                val searched = searchMatchedSongsOnPlatform(targetPlatform, title, artist, expectedDurationMs)
                candidates.addAll(searched.take(3))
            }

            if (candidates.isEmpty()) {
                continue
            }

            val sourceKey = platformToSourceKey(targetPlatform)

            // 4. 遍历当前平台的匹配候选单曲进行解析与探活
            for (cand in candidates) {
                val effectiveId = cand.songId
                val effectiveHash = cand.hash ?: cand.songId

                // 4.1 在当前候选曲目上依次轮询各个启用的音源脚本 (优先走 QuickJS 原生引擎)
                for (holder in orderedEngines) {
                    val qjsEngine = getOrCreateQuickJsEngine(context, holder)
                    val musicInfo = JSONObject().apply {
                        put("source", sourceKey)
                        put("songmid", effectiveId)
                        put("id", effectiveId)
                        put("copyrightId", effectiveId)
                        put("platform", sourceKey)
                        put("hash", effectiveHash)
                        put("name", cand.matchTitle ?: title)
                        put("singer", cand.matchArtist ?: artist)
                        put("albumName", album)
                        put("types", org.json.JSONArray().apply {
                            put(JSONObject().put("type", "128k"))
                            put(JSONObject().put("type", "320k"))
                            put(JSONObject().put("type", "flac"))
                            put(JSONObject().put("type", "flac24bit"))
                        })
                    }

                    for (q in qualityTryList) {
                        val rawQuality = stringToRawSourceQuality(q)
                        try {
                            val timeout = if (q == qualityTryList.first()) 2500L else 2000L
                            var url = qjsEngine?.resolveMusicUrl(sourceKey, musicInfo, q, timeoutMs = timeout)
                            if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) {
                                val mediaItem = RawSourceMediaItem(
                                    sourceId = holder.id,
                                    remoteId = "$sourceKey:$effectiveId",
                                    mediaType = RawSourceMediaType.Music,
                                    title = cand.matchTitle ?: title,
                                    artists = listOf(cand.matchArtist ?: artist),
                                    album = album,
                                    durationMs = if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                    artworkUrl = "",
                                    sourcePayload = musicInfo.toString(),
                                )
                                val resolved = MusicSourceRuntimeClient.resolveLxAudio(context, holder, mediaItem, rawQuality, timeoutMs = timeout)
                                url = resolved.url
                            }
                            if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                                val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(url, expectedDurationMs, targetPlatform)
                                if (probePair != null) {
                                    val (validUrl, detectedDur) = probePair
                                    val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                                    resolvedResult = ResolvedAudioSource(
                                        url = validUrl,
                                        platform = targetPlatform,
                                        sourceName = "${targetPlatform.displayName} (${holder.name})",
                                        quality = q,
                                        durationMs = finalDur,
                                        isFallback = !isOriginal,
                                        fallbackReason = if (!isOriginal) "原平台音频缺失，由「${holder.name}」切换至 ${targetPlatform.displayName}" else null,
                                    )
                                    AppLogger.i(TAG, "[ClassicPolling] Successfully resolved $title ($q) via ${holder.name} on ${targetPlatform.displayName}: $validUrl (duration: ${finalDur}ms)")
                                    break
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Throwable) {
                            AppLogger.d(TAG, "[ClassicPolling] LX resolve error on $sourceKey via ${holder.name} for $effectiveId ($q): ${e.message}")
                        }
                    }
                    if (resolvedResult != null) break
                }
                if (resolvedResult != null) break

                // 4.2 针对网易云官方 outer 兜底直链
                if (targetPlatform == OnlinePlatform.NETEASE) {
                    if (effectiveId.isNotEmpty() && effectiveId.matches(Regex("^\\d+$"))) {
                        val outerUrl = "https://music.163.com/song/media/outer/url?id=$effectiveId.mp3"
                        val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(outerUrl, expectedDurationMs, targetPlatform)
                        if (probePair != null) {
                            val (validUrl, detectedDur) = probePair
                            val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                            resolvedResult = ResolvedAudioSource(
                                url = validUrl,
                                platform = targetPlatform,
                                sourceName = "网易云官方",
                                quality = "128k",
                                durationMs = finalDur,
                                isFallback = !isOriginal,
                                fallbackReason = if (!isOriginal) "原音源未匹配，自动降级至网易云官方音频" else null,
                            )
                            AppLogger.i(TAG, "[ClassicPolling] Fallback to verified Netease outer URL for $title: $validUrl")
                            break
                        }
                    }
                }

                // 4.3 针对咪咕音乐官方免费直链兜底
                if (targetPlatform == OnlinePlatform.MIGU) {
                    if (effectiveId.isNotEmpty()) {
                        val miguUrl = OnlinePlaybackProbe.resolveMiguDirectPlayUrl(effectiveId)
                        if (!miguUrl.isNullOrBlank()) {
                            val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(miguUrl, expectedDurationMs, targetPlatform)
                            if (probePair != null) {
                                val (validUrl, detectedDur) = probePair
                                val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                                resolvedResult = ResolvedAudioSource(
                                    url = validUrl,
                                    platform = targetPlatform,
                                    sourceName = "咪咕官方",
                                    quality = "HQ",
                                    durationMs = finalDur,
                                    isFallback = !isOriginal,
                                    fallbackReason = if (!isOriginal) "原音源未匹配，自动降级至咪咕官方直链" else null,
                                )
                                AppLogger.i(TAG, "[ClassicPolling] Fallback to verified Migu official direct URL for $title: $validUrl")
                                break
                            }
                        }
                    }
                }
            }

            // 4.4 若原平台原始 ID 解析失败，但在原平台上尚未搜索其他版本，进行原平台二度搜救
            if (resolvedResult == null && isOriginal) {
                val searchedOriginal = searchMatchedSongsOnPlatform(targetPlatform, title, artist, expectedDurationMs)
                    .filter { it.songId != songId }
                    .take(2)
                for (cand in searchedOriginal) {
                    val effectiveId = cand.songId
                    val effectiveHash = cand.hash ?: cand.songId
                    for (holder in orderedEngines) {
                        val qjsEngine = getOrCreateQuickJsEngine(context, holder)
                    val musicInfo = JSONObject().apply {
                        put("source", sourceKey)
                        put("songmid", effectiveId)
                        put("id", effectiveId)
                        put("copyrightId", effectiveId)
                        put("platform", sourceKey)
                        put("hash", effectiveHash)
                        put("name", cand.matchTitle ?: title)
                        put("singer", cand.matchArtist ?: artist)
                        put("albumName", album)
                        put("types", org.json.JSONArray().apply {
                            put(JSONObject().put("type", "128k"))
                            put(JSONObject().put("type", "320k"))
                            put(JSONObject().put("type", "flac"))
                            put(JSONObject().put("type", "flac24bit"))
                        })
                    }

                    for (q in qualityTryList) {
                        val rawQuality = stringToRawSourceQuality(q)
                        try {
                            val timeout = if (q == qualityTryList.first()) 2500L else 2000L
                            var url = qjsEngine?.resolveMusicUrl(sourceKey, musicInfo, q, timeoutMs = timeout)
                            if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) {
                                val mediaItem = RawSourceMediaItem(
                                    sourceId = holder.id,
                                    remoteId = "$sourceKey:$effectiveId",
                                    mediaType = RawSourceMediaType.Music,
                                    title = cand.matchTitle ?: title,
                                    artists = listOf(cand.matchArtist ?: artist),
                                    album = album,
                                    durationMs = if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs,
                                    artworkUrl = "",
                                    sourcePayload = musicInfo.toString(),
                                )
                                val resolved = MusicSourceRuntimeClient.resolveLxAudio(context, holder, mediaItem, rawQuality, timeoutMs = timeout)
                                url = resolved.url
                            }
                            if (!url.isNullOrBlank() && url.startsWith("http", ignoreCase = true)) {
                                val probePair = OnlinePlaybackProbe.probeAndValidateAudioUrl(url, expectedDurationMs, targetPlatform)
                                if (probePair != null) {
                                    val (validUrl, detectedDur) = probePair
                                    val finalDur = if (detectedDur > 0L) detectedDur else if (cand.durationMs > 0L) cand.durationMs else expectedDurationMs
                                    resolvedResult = ResolvedAudioSource(
                                        url = validUrl,
                                        platform = targetPlatform,
                                        sourceName = "${targetPlatform.displayName} (${holder.name})",
                                        quality = q,
                                        durationMs = finalDur,
                                        isFallback = false,
                                        fallbackReason = null,
                                    )
                                    AppLogger.i(TAG, "[ClassicPolling] Successfully re-resolved $title via original platform re-search on ${targetPlatform.displayName}: $validUrl")
                                    break
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Throwable) {
                            AppLogger.d(TAG, "[ClassicPolling] Original re-search resolve error for $effectiveId: ${e.message}")
                        }
                    }
                    if (resolvedResult != null) break
                }
                if (resolvedResult != null) break
            }
        }

            // 一旦成功获取并通过流长度与时长检验的有效直链，立即结束平台轮询
            if (resolvedResult != null) {
                break
            }
        }

        // 7. 若仍未命中，尝试 MusicFree 插件补充兜底
        if (resolvedResult == null) {
            val enabledMusicFree = MusicSourcePluginStore.sources.value.filter { it.enabled }
            if (enabledMusicFree.isNotEmpty()) {
                try {
                    val query = "$title $artist".trim()
                    val aggregated = MusicSourceSearchCoordinator.search(
                        context = context,
                        sources = enabledMusicFree,
                        lxSources = emptyList(),
                        query = query,
                    )
                    val best = OnlinePlaybackBridge.selectBestCandidate(
                        target = com.rawsmusic.module.online.model.OnlineSongItem(
                            id = songId,
                            platform = platform,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = expectedDurationMs,
                        ),
                        candidates = aggregated.items,
                    )
                    if (best != null) {
                        val rawResolved = MusicSourceAudioResolver.resolve(context, best, RawSourceQuality.Standard)
                        val validated = OnlinePlaybackProbe.probeAndValidateAudioUrl(rawResolved.url, expectedDurationMs, platform)
                        if (validated != null) {
                            val (validUrl, dur) = validated
                            resolvedResult = ResolvedAudioSource(
                                url = validUrl,
                                platform = platform,
                                sourceName = "MusicFree 插件",
                                quality = "128k",
                                durationMs = if (dur > 0L) dur else expectedDurationMs,
                                isFallback = true,
                                fallbackReason = "全网多源搜救降级至 MusicFree 插件解析",
                            )
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "[ClassicPolling] MusicFree fallback error for $title: ${e.message}")
                }
            }
        }

        if (resolvedResult != null) {
            val expireAt = now + 15 * 60 * 1000L
            memoryUrlCache.put(cacheKey, CachedUrl(resolvedResult.url, expireAt, resolvedResult.quality))
        }

        resolvedResult
    }

    /**
     * 模式 2：并发智能寻源 (1:1 还原 OrbitPlayer 的 resolvePlayableSource 并发分支)。
     *
     * 特性：
     * 1. 阶段 1：主音源原平台优先通道（0ms 搜索延迟，不串歌，秒开）
     * 2. 阶段 2：主音源跨平台并发搜救（协程多平台并发检索探活，Channel 抢先返回）
     * 3. 阶段 3：备用多音源并发竞速寻源（唤醒全部备用源多协程全平台并发搜救）
     * 4. 阶段 4：MusicFree 插件兜底
     * 5. 寻源响应极其迅速
     */
    suspend fun resolvePlayableSourceConcurrent(
        context: Context,
        platform: OnlinePlatform,
        songId: String,
        title: String,
        artist: String,
        album: String,
        expectedDurationMs: Long = 0L,
        explicitQuality: String? = null,
    ): ResolvedAudioSource? = withContext(Dispatchers.IO) {
        val cacheKey = if (explicitQuality != null) "${platform.id}:$songId:$explicitQuality" else "${platform.id}:$songId"
        val now = System.currentTimeMillis()

        // 1. 查内存缓存 (0ms 秒开)
        val cached = memoryUrlCache.get(cacheKey)
        if (cached != null && cached.expireAtMs > now) {
            AppLogger.d(TAG, "[Concurrent] Hit memory cache for $title: ${cached.url}")
            return@withContext ResolvedAudioSource(
                url = cached.url,
                platform = platform,
                sourceName = platform.displayName,
                durationMs = expectedDurationMs,
                quality = explicitQuality ?: cached.quality,
            )
        }

        // 2. 确定可用音源池 (主音源优先，备用音源其后)
        val primarySource = LxSourcePluginStore.primarySource()
        val backupSources = LxSourcePluginStore.backupSources()

        val qualityTryList = OnlineQualityLadder.qualityTryList(explicitQuality)

        // 3. 第一阶段：主音源原平台优先通道
        var resolvedResult = resolveSinglePlatformSource(
            context = context,
            targetPlatform = platform,
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            expectedDurationMs = expectedDurationMs,
            isOriginal = true,
            lxSource = primarySource,
            qualityTryList = qualityTryList,
        )

        // 4. 第二阶段：主音源跨平台并发搜救寻源 (原平台无源时，对其余平台发起精准匹配与并发解析)
        if (resolvedResult == null) {
            val fallbackPlatforms = CLASSIC_POLLING_PLATFORMS.filter { it != platform }
            val channel = Channel<ResolvedAudioSource?>(fallbackPlatforms.size)

            coroutineScope {
                val jobs = fallbackPlatforms.map { targetPlatform ->
                    launch(Dispatchers.IO) {
                        val result = resolveSinglePlatformSource(
                            context = context,
                            targetPlatform = targetPlatform,
                            songId = songId,
                            title = title,
                            artist = artist,
                            album = album,
                            expectedDurationMs = expectedDurationMs,
                            isOriginal = false,
                            lxSource = primarySource,
                            qualityTryList = qualityTryList,
                        )
                        channel.send(result)
                    }
                }

                var finishedCount = 0
                while (finishedCount < fallbackPlatforms.size) {
                    val candidate = channel.receive()
                    finishedCount++
                    if (candidate != null) {
                        resolvedResult = candidate
                        jobs.forEach { job -> job.cancel() }
                        break
                    }
                }
            }
        }

        // 5. 第三阶段：备用多音源并发竞速寻源 (若主音源全平台均未命中，唤醒全部备用音源池并发搜救)
        if (resolvedResult == null && backupSources.isNotEmpty()) {
            val allPlatforms = CLASSIC_POLLING_PLATFORMS
            val totalRequests = backupSources.size * allPlatforms.size
            val backupChannel = Channel<ResolvedAudioSource?>(totalRequests)

            coroutineScope {
                val backupJobs = backupSources.flatMap { holder ->
                    allPlatforms.map { targetPlatform ->
                        launch(Dispatchers.IO) {
                            val isOrig = (targetPlatform == platform)
                            val res = resolveSinglePlatformSource(
                                context = context,
                                targetPlatform = targetPlatform,
                                songId = songId,
                                title = title,
                                artist = artist,
                                album = album,
                                expectedDurationMs = expectedDurationMs,
                                isOriginal = isOrig,
                                lxSource = holder,
                                qualityTryList = qualityTryList,
                            )?.let { successSource ->
                                successSource.copy(
                                    sourceName = "${successSource.platform.displayName} (${holder.name})",
                                    isFallback = true,
                                    fallbackReason = "主音源全平台无有效音源，由备用源「${holder.name}」成功解析",
                                )
                            }
                            backupChannel.send(res)
                        }
                    }
                }

                var finishedCount = 0
                while (finishedCount < totalRequests) {
                    val candidate = backupChannel.receive()
                    finishedCount++
                    if (candidate != null) {
                        resolvedResult = candidate
                        backupJobs.forEach { job -> job.cancel() }
                        break
                    }
                }
            }
        }

        // 6. 第四阶段：若启用了 MusicFree 源，且上述均未命中，进行 MusicFree 补充兜底
        if (resolvedResult == null) {
            val enabledMusicFree = MusicSourcePluginStore.sources.value.filter { it.enabled }
            if (enabledMusicFree.isNotEmpty()) {
                try {
                    val query = "$title $artist".trim()
                    val aggregated = MusicSourceSearchCoordinator.search(
                        context = context,
                        sources = enabledMusicFree,
                        lxSources = emptyList(),
                        query = query,
                    )
                    val best = OnlinePlaybackBridge.selectBestCandidate(
                        target = com.rawsmusic.module.online.model.OnlineSongItem(
                            id = songId,
                            platform = platform,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = expectedDurationMs,
                        ),
                        candidates = aggregated.items,
                    )
                    if (best != null) {
                        val rawResolved = MusicSourceAudioResolver.resolve(context, best, RawSourceQuality.Standard)
                        val validated = OnlinePlaybackProbe.probeAndValidateAudioUrl(rawResolved.url, expectedDurationMs, platform)
                        if (validated != null) {
                            val (validUrl, dur) = validated
                            resolvedResult = ResolvedAudioSource(
                                url = validUrl,
                                platform = platform,
                                sourceName = "MusicFree 插件",
                                quality = "128k",
                                durationMs = if (dur > 0L) dur else expectedDurationMs,
                                isFallback = true,
                                fallbackReason = "全网多源搜救降级至 MusicFree 插件解析",
                            )
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "[Concurrent] MusicFree fallback error for $title: ${e.message}")
                }
            }
        }

        // 成功获取后，写入 15 分钟 LRU 缓存
        if (resolvedResult != null) {
            val expireAt = now + 15 * 60 * 1000L
            memoryUrlCache.put(cacheKey, CachedUrl(resolvedResult.url, expireAt, resolvedResult.quality))
        }

        resolvedResult
    }

    /**
     * 统一入口：根据用户配置的寻源策略动态调度（经典多源轮询 vs 并发智能寻源）。
     */
    suspend fun resolvePlayableSource(
        context: Context,
        platform: OnlinePlatform,
        songId: String,
        title: String,
        artist: String,
        album: String,
        expectedDurationMs: Long = 0L,
        explicitQuality: String? = null,
    ): ResolvedAudioSource? = withContext(Dispatchers.IO) {
        val targetQuality = explicitQuality ?: AppPreferences.Source.preferredQuality
        val strategy = AppPreferences.Source.resolveStrategy
        if (strategy == ResolveStrategy.CLASSIC_POLLING) {
            resolvePlayableSourceClassicPolling(
                context = context,
                platform = platform,
                songId = songId,
                title = title,
                artist = artist,
                album = album,
                expectedDurationMs = expectedDurationMs,
                explicitQuality = targetQuality,
            )
        } else {
            resolvePlayableSourceConcurrent(
                context = context,
                platform = platform,
                songId = songId,
                title = title,
                artist = artist,
                album = album,
                expectedDurationMs = expectedDurationMs,
                explicitQuality = targetQuality,
            )
        }
    }

    /**
     * 为 RawSourceMediaItem 提供在线寻源兜底实现。
     */
    private suspend fun resolveFallbackForMediaItem(
        context: Context,
        item: RawSourceMediaItem,
        requestedQuality: RawSourceQuality,
    ): RawResolvedAudioSource? {
        val platform = OnlinePlatform.fromId(item.sourceId) ?: run {
            // 从 sourcePayload 解析 platform
            try {
                val json = JSONObject(item.sourcePayload)
                val pStr = json.optString("platform")
                OnlinePlatform.fromId(pStr)
            } catch (_: Exception) {
                null
            }
        } ?: OnlinePlatform.NETEASE

        val songId = item.remoteId.substringAfter(':', item.remoteId)
        val qualityStr = when (requestedQuality) {
            RawSourceQuality.HiRes -> "flac24bit"
            RawSourceQuality.Lossless -> "flac"
            RawSourceQuality.Super, RawSourceQuality.High -> "320k"
            else -> "128k"
        }

        val resolved = resolvePlayableSource(
            context = context,
            platform = platform,
            songId = songId,
            title = item.title,
            artist = item.artists.joinToString("/"),
            album = item.album,
            expectedDurationMs = item.durationMs,
            explicitQuality = qualityStr,
        ) ?: return null

        return RawResolvedAudioSource(
            url = resolved.url,
            quality = requestedQuality,
            headers = emptyMap(),
        )
    }
}
