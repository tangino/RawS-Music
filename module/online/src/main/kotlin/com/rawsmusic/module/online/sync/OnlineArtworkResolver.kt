package com.rawsmusic.module.online.sync

import android.content.Context
import android.util.LruCache
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistArtworkFallbackDelegate
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.prefs.ResolvedMetadataUpdate
import com.rawsmusic.module.data.prefs.ResolvedOnlineTrackMetadata
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 混合歌单缺失封面的在线自动寻源与持久化自愈服务。
 *
 * 核心机制：
 * 1. 二级缓存（内存 LRU + MMKV 持久化磁盘缓存），进程重启后仍 0 网络请求秒开已识别的网络歌曲标签与封面；
 * 2. 具备 In-Flight 请求去重、失败负缓存（10 分钟内不重复搜索冷门无结果曲目）与并发控制（Semaphore(5)）；
 * 3. 跨 QQ 音乐、网易云、酷我三大平台进行智能多源级联搜寻单曲元数据与封面；
 * 4. 批量防抖合并回填（250ms 窗口或满 8 首立即提交）至 PlaylistStore 内存缓存与 Room 数据库，
 *    彻底消除单曲逐条刷新引发的全歌单重复解析风暴。
 */
object OnlineArtworkResolver : PlaylistArtworkFallbackDelegate {

    private const val TAG = "OnlineArtworkResolver"
    private const val FAILED_COOLDOWN_MS = 10 * 60 * 1000L
    private const val BATCH_FLUSH_DELAY_MS = 250L
    private const val BATCH_FLUSH_THRESHOLD = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = LruCache<String, String>(2000)
    private val metadataCache = LruCache<String, ResolvedOnlineTrackMetadata>(2000)
    private val localPathCoverCache = LruCache<String, String>(3000)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedTimestamps = ConcurrentHashMap<String, Long>()
    private val fetchSemaphore = Semaphore(5)

    private class LocalPathMetaIndex(
        val sourceSongs: List<AudioFile>,
        val byPath: Map<String, Pair<String, String>>,
    )
    @Volatile
    private var localPathMetaIndex: LocalPathMetaIndex? = null

    private fun resolveTitleAndArtistForLocalPath(audioPath: String): Pair<String, String>? {
        val cleanPath = audioPath.trim()
        if (cleanPath.isBlank()) return null
        val songs = MusicRepository.songs.value
        if (songs.isNotEmpty()) {
            val cached = localPathMetaIndex
            val map = if (cached != null && cached.sourceSongs === songs) {
                cached.byPath
            } else {
                buildMap<String, Pair<String, String>>(songs.size) {
                    songs.forEach { song ->
                        if (song.path.isNotBlank() && song.title.isNotBlank()) {
                            putIfAbsent(song.path, song.title to song.artist)
                        }
                    }
                }.also { localPathMetaIndex = LocalPathMetaIndex(songs, it) }
            }
            map[cleanPath]?.let { return it }
        }
        // 兜底：从文件名解析（支持 "歌手 - 歌名" 或纯歌名）
        val stem = cleanPath.substringAfterLast('/').substringBeforeLast('.').trim()
        if (stem.isBlank()) return null
        val dashIdx = stem.indexOf(" - ")
        return if (dashIdx > 0 && dashIdx + 3 < stem.length) {
            stem.substring(dashIdx + 3).trim() to stem.substring(0, dashIdx).trim()
        } else {
            stem to ""
        }
    }

    /**
     * 供 BitmapProvider 同步查询本地无内嵌封面音频的已缓存网络封面（内存 LRU + MMKV 持久化）。
     */
    fun lookupCachedOnlineCoverForLocalPath(audioPath: String): String? {
        val cleanPath = audioPath.trim()
        if (cleanPath.isBlank()) return null
        localPathCoverCache.get(cleanPath)?.takeIf { isValidRemoteCoverUrl(it) }?.let { return it }
        val (title, artist) = resolveTitleAndArtistForLocalPath(cleanPath) ?: return null
        val cachedUrl = getCachedArtworkUrl(title, artist)?.takeIf { isValidRemoteCoverUrl(it) } ?: return null
        localPathCoverCache.put(cleanPath, cachedUrl)
        return cachedUrl
    }

    /**
     * 供 BitmapProvider 在本地音乐库歌曲确认无内嵌/同目录封面时，异步从网络平台（优先 QQ 音乐）拉取真实封面。
     */
    fun requestOnlineCoverForLocalPath(
        audioPath: String,
        onResolved: (String) -> Unit,
    ) {
        val cleanPath = audioPath.trim()
        if (cleanPath.isBlank()) return
        lookupCachedOnlineCoverForLocalPath(cleanPath)?.let { cachedUrl ->
            onResolved(cachedUrl)
            return
        }
        val (rawTitle, rawArtist) = resolveTitleAndArtistForLocalPath(cleanPath) ?: return
        val cleanTitle = rawTitle.trim()
        val cleanArtist = rawArtist.trim()
        if (cleanTitle.isBlank()) return

        val key = cacheKey(cleanTitle, cleanArtist)
        val lastFail = failedTimestamps[key]
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAILED_COOLDOWN_MS) return
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                fetchSemaphore.withPermit {
                    val meta = fetchMetadataFromOnline(cleanTitle, cleanArtist)
                    if (meta != null && isValidRemoteCoverUrl(meta.coverUrl)) {
                        failedTimestamps.remove(key)
                        metadataCache.put(key, meta)
                        memoryCache.put(key, meta.coverUrl)
                        localPathCoverCache.put(cleanPath, meta.coverUrl)
                        writeDiskMetadata(key, meta)
                        AppLogger.i(
                            TAG,
                            "Resolved local library cover (${meta.platformId}) for '$cleanTitle - $cleanArtist': ${meta.coverUrl.take(64)}"
                        )
                        onResolved(meta.coverUrl)
                        enqueueBatchUpdate(ResolvedMetadataUpdate(cleanTitle, cleanArtist, meta))
                    } else {
                        failedTimestamps[key] = System.currentTimeMillis()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                failedTimestamps[key] = System.currentTimeMillis()
                AppLogger.w(TAG, "Failed to resolve local library cover for '$cleanTitle - $cleanArtist': ${e.message}")
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private val pendingUpdates = ConcurrentLinkedQueue<ResolvedMetadataUpdate>()
    private val flushLock = Any()
    @Volatile
    private var scheduledFlushJob: Job? = null

    private val _resolvedArtworkEvents = MutableSharedFlow<ResolvedMetadataUpdate>(extraBufferCapacity = 64)
    val resolvedArtworkEvents: SharedFlow<ResolvedMetadataUpdate> = _resolvedArtworkEvents.asSharedFlow()

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        // 同步清理上次播放曲目与持久化队列快照中残留的跨平台错位封面（早于 PlayerService 启动）
        AppPreferences.sanitizeCrossPlatformPlayerPersistence()
        PlaylistStore.getInstance(appCtx).artworkFallbackDelegate = this
        scope.launch {
            repairCrossPlatformPersistence(appCtx)
        }
        AppLogger.i(TAG, "OnlineArtworkResolver successfully installed into PlaylistStore")
    }

    /**
     * 全自动存量自愈：
     * 1. 扫描 MMKV `online_track_metadata_v1` 中所有 `p == "qq"` 却残留网易云 `126.net`/`163.com` 封面的脏记录，
     *    擦除错位封面写回磁盘，并携带精确 `online://qq/<songId>` 重新从 QQ 音乐拉取官方正版封面；
     * 2. 联动 [PlaylistStore.repairCrossPlatformArtworksWithDelegate] 清洗所有歌单与 Room 数据库中的跨平台错位封面并排队补齐。
     */
    private suspend fun repairCrossPlatformPersistence(context: Context) {
        val toReResolve = ArrayList<Triple<String, String, String>>()
        runCatching {
            val allKeys = AppPreferences.allOnlineTrackMetadataKeys()
            for (key in allKeys) {
                val rawMeta = readDiskMetadata(key) ?: continue
                val expectedPlatform = OnlinePlatform.fromId(rawMeta.platformId)
                if (rawMeta.coverUrl.isNotBlank() && isForeignCoverForPlatform(expectedPlatform, rawMeta.coverUrl)) {
                    val cleaned = rawMeta.copy(coverUrl = "")
                    metadataCache.put(key, cleaned)
                    memoryCache.remove(key)
                    writeDiskMetadata(key, cleaned)
                    if (cleaned.title.isNotBlank()) {
                        val syntheticKey = if (cleaned.platformId.isNotBlank() && cleaned.songId.isNotBlank()) {
                            "online://${cleaned.platformId}/${cleaned.songId}"
                        } else {
                            ""
                        }
                        toReResolve.add(Triple(cleaned.title, cleaned.artist, syntheticKey))
                    }
                }
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "MMKV cross-platform cover repair scan error: ${e.message}")
        }

        // 触发 PlaylistStore 清洗 Room 数据库与内存歌单中的跨平台错位封面并请求同源重拉
        runCatching {
            PlaylistStore.getInstance(context).repairCrossPlatformArtworks()
        }.onFailure { e ->
            AppLogger.w(TAG, "PlaylistStore cross-platform cover repair error: ${e.message}")
        }

        if (toReResolve.isNotEmpty()) {
            AppLogger.i(TAG, "Healing ${toReResolve.size} cached tracks with cross-platform mismatched covers")
            for ((t, a, sk) in toReResolve) {
                requestArtworkResolution(t, a, sk)
            }
        }
    }

    private fun cacheKey(title: String, artist: String): String =
        "${title.trim().lowercase()}__${artist.trim().lowercase()}"

    private fun readDiskMetadata(key: String): ResolvedOnlineTrackMetadata? {
        val raw = AppPreferences.getOnlineTrackMetadataJson(key)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            ResolvedOnlineTrackMetadata(
                platformId = obj.optString("p"),
                songId = obj.optString("id"),
                title = obj.optString("t"),
                artist = obj.optString("a"),
                album = obj.optString("al"),
                durationMs = obj.optLong("d", 0L),
                coverUrl = obj.optString("c"),
            )
        }.getOrNull()?.takeIf { it.platformId.isNotBlank() && it.songId.isNotBlank() }
    }

    private fun writeDiskMetadata(key: String, meta: ResolvedOnlineTrackMetadata) {
        runCatching {
            val json = JSONObject().apply {
                put("p", meta.platformId)
                put("id", meta.songId)
                put("t", meta.title)
                put("a", meta.artist)
                put("al", meta.album)
                put("d", meta.durationMs)
                put("c", meta.coverUrl)
            }.toString()
            AppPreferences.putOnlineTrackMetadataJson(key, json)
        }
    }

    /**
     * 校验封面 URL 是否与歌曲所属平台发生跨平台错位（例如 QQ 平台歌曲却挂着网易云 `126.net`/`163.com` 封面）。
     */
    private fun isForeignCoverForPlatform(expectedPlatform: OnlinePlatform?, url: String?): Boolean {
        if (expectedPlatform == null || url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        return when (expectedPlatform) {
            OnlinePlatform.QQ -> lower.contains("126.net") || lower.contains("163.com")
            OnlinePlatform.NETEASE -> lower.contains("gtimg.cn") || lower.contains("y.qq.com")
            else -> false
        }
    }

    private fun sanitizeMetadataCover(
        meta: ResolvedOnlineTrackMetadata,
        songKey: String = "",
    ): ResolvedOnlineTrackMetadata {
        val expectedPlatform = parseOriginFromSongKey(songKey)?.first
            ?: OnlinePlatform.fromId(meta.platformId)
        val invalidOrForeign = meta.coverUrl.isNotBlank() && (
            isPlaceholderUrl(meta.coverUrl) ||
                isForeignCoverForPlatform(expectedPlatform, meta.coverUrl)
            )
        return if (invalidOrForeign) meta.copy(coverUrl = "") else meta
    }

    override fun peekCachedArtworkUrlInMemory(title: String, artist: String, songKey: String): String? {
        val key = cacheKey(title, artist)
        val expectedPlatform = parseOriginFromSongKey(songKey)?.first
        memoryCache.get(key)
            ?.takeIf { isValidRemoteCoverUrl(it) && !isForeignCoverForPlatform(expectedPlatform, it) }
            ?.let { return it }
        return peekCachedMetadataInMemory(title, artist, songKey)?.coverUrl?.takeIf { isValidRemoteCoverUrl(it) }
    }

    override fun peekCachedMetadataInMemory(title: String, artist: String, songKey: String): ResolvedOnlineTrackMetadata? {
        val key = cacheKey(title, artist)
        return metadataCache.get(key)?.let { cached -> sanitizeMetadataCover(cached, songKey) }
    }

    override fun getCachedArtworkUrl(title: String, artist: String, songKey: String): String? {
        val key = cacheKey(title, artist)
        val expectedPlatform = parseOriginFromSongKey(songKey)?.first
        memoryCache.get(key)
            ?.takeIf { isValidRemoteCoverUrl(it) && !isForeignCoverForPlatform(expectedPlatform, it) }
            ?.let { return it }
        return getCachedMetadata(title, artist, songKey)?.coverUrl?.takeIf { isValidRemoteCoverUrl(it) }
    }

    override fun getCachedMetadata(title: String, artist: String, songKey: String): ResolvedOnlineTrackMetadata? {
        val key = cacheKey(title, artist)
        metadataCache.get(key)?.let { cached ->
            val sanitized = sanitizeMetadataCover(cached, songKey)
            if (sanitized !== cached) {
                metadataCache.put(key, sanitized)
                memoryCache.remove(key)
            }
            return sanitized
        }
        val rawDisk = readDiskMetadata(key) ?: return null
        val disk = sanitizeMetadataCover(rawDisk, songKey)
        metadataCache.put(key, disk)
        if (isValidRemoteCoverUrl(disk.coverUrl)) {
            memoryCache.put(key, disk.coverUrl)
        } else {
            memoryCache.remove(key)
        }
        return disk
    }

    override fun requestArtworkResolution(title: String, artist: String, songKey: String) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) return

        val key = cacheKey(cleanTitle, cleanArtist)
        val cached = getCachedMetadata(cleanTitle, cleanArtist, songKey)
        val originPlatform = parseOriginFromSongKey(songKey)?.first
        val platformMatches = originPlatform == null || cached?.platformId.equals(originPlatform.id, ignoreCase = true)
        if (cached != null && platformMatches && isValidRemoteCoverUrl(cached.coverUrl)) return
        val lastFail = failedTimestamps[key]
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAILED_COOLDOWN_MS) return
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                fetchSemaphore.withPermit {
                    val meta = fetchMetadataFromOnline(cleanTitle, cleanArtist, songKey)
                    if (meta != null) {
                        failedTimestamps.remove(key)
                        metadataCache.put(key, meta)
                        if (meta.coverUrl.isNotBlank()) {
                            memoryCache.put(key, meta.coverUrl)
                        }
                        writeDiskMetadata(key, meta)
                        AppLogger.i(
                            TAG,
                            "Resolved online metadata (${meta.platformId}) for '$cleanTitle - $cleanArtist': " +
                                "album=${meta.album}, cover=${meta.coverUrl.take(64)}"
                        )
                        enqueueBatchUpdate(ResolvedMetadataUpdate(cleanTitle, cleanArtist, meta))
                    } else {
                        failedTimestamps[key] = System.currentTimeMillis()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                failedTimestamps[key] = System.currentTimeMillis()
                AppLogger.w(TAG, "Failed to resolve online metadata for '$cleanTitle - $cleanArtist': ${e.message}")
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun enqueueBatchUpdate(update: ResolvedMetadataUpdate) {
        pendingUpdates.add(update)
        if (pendingUpdates.size >= BATCH_FLUSH_THRESHOLD) {
            flushPendingUpdates()
            return
        }
        synchronized(flushLock) {
            if (scheduledFlushJob?.isActive != true) {
                scheduledFlushJob = scope.launch {
                    delay(BATCH_FLUSH_DELAY_MS)
                    flushPendingUpdates()
                }
            }
        }
    }

    private fun flushPendingUpdates() {
        val batch = ArrayList<ResolvedMetadataUpdate>()
        while (true) {
            val item = pendingUpdates.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return
        for (item in batch) {
            val m = item.metadata
            if (m.coverUrl.isNotBlank()) {
                com.rawsmusic.core.common.prefs.PlayHistoryManager.refreshOnlineTrackArtwork(
                    title = item.queryTitle,
                    artist = item.queryArtist,
                    platformId = m.platformId,
                    coverUrl = m.coverUrl,
                )
                _resolvedArtworkEvents.tryEmit(item)
            }
        }
        appContext?.let { ctx ->
            PlaylistStore.getInstance(ctx).updateSongsMetadataBatch(batch)
        }
    }

    /**
     * 为歌单列表批量预热解析封面（云同步还原后调用）。
     * 发现无有效封面、使用默认占位封面或跨平台错位封面时，自动从对应原平台（或 QQ 音乐）获取正确封面。
     */
    fun prefetchArtworksForSongs(songs: List<UserPlaylistSong>) {
        songs.forEach { song ->
            val originPlatform = parseOriginFromSongKey(song.key.ifBlank { song.path })?.first
            val isForeignCover = isForeignCoverForPlatform(originPlatform, song.albumArtPath)
            val hasValidHttpCover = isValidRemoteCoverUrl(song.albumArtPath) && !isForeignCover
            val isUsingDefaultCover = song.albumArtPath.isNotBlank() && isPlaceholderUrl(song.albumArtPath)
            val hasPhysicalFile = song.path.startsWith("/") && File(song.path).exists()
            // 针对无有效同源封面、默认占位封面或跨平台错位封面的条目触发同源优先寻源
            if (song.title.isNotBlank() && (isForeignCover || isUsingDefaultCover || (!hasValidHttpCover && !hasPhysicalFile))) {
                requestArtworkResolution(song.title, song.artist, song.key)
            }
        }
    }

    private fun isValidRemoteCoverUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim()
        return (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) &&
            !isPlaceholderUrl(u)
    }

    private fun isPlaceholderUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val u = url.trim().lowercase()
        return u.contains("default_avatar") ||
            u.contains("5639395138885805") ||
            u.contains("6y-uleoritedbvrolvq8a==") ||
            u.contains("109951163250233892") ||
            u.contains("default_album_art") ||
            u.contains("ic_default_album_art") ||
            u.contains("default_album") ||
            u.contains("default_cover") ||
            u.contains("default_pic") ||
            u.contains("no_cover") ||
            u.contains("nopic") ||
            u.contains("m00000000000000") ||
            u.endsWith("m000.jpg") ||
            u.endsWith("m0000.jpg")
    }

    /**
     * 从本地歌单条目的 songKey（如 `online://netease/12345`）解析添加时的来源平台与原始 songId。
     * 若条目来自本地音乐库路径（如 `/storage/...`）或其他无平台标识格式，则返回 null。
     */
    private fun parseOriginFromSongKey(songKey: String): Pair<OnlinePlatform, String>? {
        val trimmed = songKey.trim()
        if (!trimmed.startsWith("online://", ignoreCase = true)) return null
        val rest = trimmed.substringAfter("://")
        val platformRaw = rest.substringBefore('/', "")
        val songId = rest.substringAfter('/', "").trim()
        val platform = OnlinePlatform.fromId(platformRaw) ?: return null
        return platform to songId
    }

    /**
     * 智能多平台级联获取本地歌单歌曲的完整标签与高清封面：
     * 1. 若能从 songKey 识别出添加时的来源平台（如网易云/酷狗/酷我/咪咕/QQ），直接优先从该原平台获取（支持按原始 songId 精确命中）；
     * 2. 若找不到添加时的来源平台（例如从本地音乐库添加，但本地文件已缺失），直接优先从 QQ 音乐平台获取；
     * 3. 首选平台未命中或缺失有效封面时，按 QQ 音乐 → 网易云 → 酷我音乐 级联补齐。
     */
    private suspend fun fetchMetadataFromOnline(
        title: String,
        artist: String,
        songKey: String = "",
    ): ResolvedOnlineTrackMetadata? {
        val keyword = if (artist.isNotBlank() && !artist.equals("未知", true) && !artist.equals("<unknown>", true)) {
            "$title $artist"
        } else {
            title
        }

        val origin = parseOriginFromSongKey(songKey)
        val originPlatform = origin?.first
        val originSongId = origin?.second.orEmpty()

        val repository = OnlineMusicRepository.getInstance()

        // 0. 若明确来自 QQ 音乐且携带原始 songMid，优先直接按 songMid 精确查询 QQ 官方详情与封面（0 搜索误差、绝不串图）
        if (originPlatform == OnlinePlatform.QQ && originSongId.isNotBlank()) {
            val exactQQ = runCatching {
                repository.getQQMusicSource().getSongDetailByMid(originSongId)
            }.getOrNull()
            if (exactQQ != null && isValidRemoteCoverUrl(exactQQ.coverUrl)) {
                return ResolvedOnlineTrackMetadata(
                    platformId = OnlinePlatform.QQ.id,
                    songId = exactQQ.id.ifBlank { originSongId },
                    title = exactQQ.title.ifBlank { title },
                    artist = exactQQ.artist.ifBlank { artist },
                    album = exactQQ.album,
                    durationMs = exactQQ.durationMs,
                    coverUrl = exactQQ.coverUrl.orEmpty(),
                )
            }
        }

        val defaultFallback = listOf(
            OnlinePlatform.QQ,
            OnlinePlatform.NETEASE,
            OnlinePlatform.KUWO,
        )
        // 若歌曲已明确归属某在线平台（如 QQ 平台标签），严格锁定从该原平台获取标签与封面，禁止跨平台去网易云缝合异源封面
        val platforms = if (originPlatform != null) {
            listOf(originPlatform)
        } else {
            defaultFallback
        }

        var fallbackMetaWithoutCover: ResolvedOnlineTrackMetadata? = null

        for (platform in platforms) {
            try {
                val items = repository.searchSongs(keyword, page = 1, pageSize = 10, platform = platform)
                    .getOrNull()
                    .orEmpty()
                if (items.isEmpty()) continue
                val expectedSongId = if (platform == originPlatform) originSongId else ""
                val best = pickBestMetadataMatch(items, title, artist, expectedSongId) ?: continue
                val validCover = best.coverUrl
                    ?.takeIf { isValidRemoteCoverUrl(it) && !isForeignCoverForPlatform(platform, it) }
                    .orEmpty()
                    .ifBlank {
                        // 同平台若首选条目缺封面，仅允许从同平台通过歌名+歌手+版本/语种校验的候选中补取有效封面
                        items.firstOrNull { item ->
                            com.rawsmusic.module.online.playback.OnlinePlaybackMatcher.isTitleMatched(title, item.title) &&
                                com.rawsmusic.module.online.playback.OnlinePlaybackMatcher.isArtistMatched(artist, item.artist) &&
                                isValidRemoteCoverUrl(item.coverUrl) &&
                                !isForeignCoverForPlatform(platform, item.coverUrl)
                        }?.coverUrl.orEmpty()
                    }
                val candidate = ResolvedOnlineTrackMetadata(
                    platformId = platform.id,
                    songId = best.id,
                    title = best.title.ifBlank { title },
                    artist = best.artist.ifBlank { artist },
                    album = best.album,
                    durationMs = best.durationMs,
                    coverUrl = validCover,
                )
                if (validCover.isNotBlank()) {
                    // 标签与封面保持严格同源一致，禁止把网易封面嫁接到 QQ 平台的标签记录上
                    return candidate
                }
                if (fallbackMetaWithoutCover == null) {
                    fallbackMetaWithoutCover = candidate
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "${platform.displayName} metadata search '$keyword' skipped: ${t.message}")
            }
        }

        return fallbackMetaWithoutCover
    }

    private fun pickBestMetadataMatch(
        items: List<OnlineSongItem>,
        title: String,
        artist: String,
        expectedSongId: String = "",
    ): OnlineSongItem? {
        // 1. 若在添加时的原平台检索且命中原始 songId，直接首选该精确条目
        if (expectedSongId.isNotBlank()) {
            items.firstOrNull { it.id.equals(expectedSongId, ignoreCase = true) }?.let { return it }
        }
        // 2. 严格按歌名 + 歌手硬门控过滤，杜绝同名不同歌手的标签误回填
        val matched = items.filter { item ->
            com.rawsmusic.module.online.playback.OnlinePlaybackMatcher.isTitleMatched(title, item.title) &&
                com.rawsmusic.module.online.playback.OnlinePlaybackMatcher.isArtistMatched(artist, item.artist)
        }
        if (matched.isEmpty()) return null
        return matched.maxByOrNull { item ->
            val baseScore = com.rawsmusic.module.online.playback.OnlinePlaybackMatcher.calculateMatchScore(
                targetTitle = title,
                targetArtist = artist,
                candidateTitle = item.title,
                candidateArtist = item.artist,
                targetDurationMs = 0L,
                candidateDurationMs = item.durationMs,
            )
            baseScore + if (isValidRemoteCoverUrl(item.coverUrl)) 5 else 0
        }
    }
}
