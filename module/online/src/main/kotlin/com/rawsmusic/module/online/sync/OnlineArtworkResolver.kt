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

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        PlaylistStore.getInstance(context).artworkFallbackDelegate = this
        AppLogger.i(TAG, "OnlineArtworkResolver successfully installed into PlaylistStore")
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

    override fun peekCachedArtworkUrlInMemory(title: String, artist: String): String? {
        val key = cacheKey(title, artist)
        memoryCache.get(key)?.takeIf { isValidRemoteCoverUrl(it) }?.let { return it }
        return metadataCache.get(key)?.coverUrl?.takeIf { isValidRemoteCoverUrl(it) }
    }

    override fun peekCachedMetadataInMemory(title: String, artist: String): ResolvedOnlineTrackMetadata? {
        val key = cacheKey(title, artist)
        return metadataCache.get(key)?.let { cached ->
            if (cached.coverUrl.isNotBlank() && isPlaceholderUrl(cached.coverUrl)) {
                cached.copy(coverUrl = "")
            } else {
                cached
            }
        }
    }

    override fun getCachedArtworkUrl(title: String, artist: String): String? {
        val key = cacheKey(title, artist)
        memoryCache.get(key)?.takeIf { isValidRemoteCoverUrl(it) }?.let { return it }
        return getCachedMetadata(title, artist)?.coverUrl?.takeIf { isValidRemoteCoverUrl(it) }
    }

    override fun getCachedMetadata(title: String, artist: String): ResolvedOnlineTrackMetadata? {
        val key = cacheKey(title, artist)
        metadataCache.get(key)?.let { cached ->
            return if (cached.coverUrl.isNotBlank() && isPlaceholderUrl(cached.coverUrl)) {
                cached.copy(coverUrl = "").also { metadataCache.put(key, it) }
            } else {
                cached
            }
        }
        val rawDisk = readDiskMetadata(key) ?: return null
        val disk = if (rawDisk.coverUrl.isNotBlank() && isPlaceholderUrl(rawDisk.coverUrl)) {
            rawDisk.copy(coverUrl = "")
        } else {
            rawDisk
        }
        metadataCache.put(key, disk)
        if (isValidRemoteCoverUrl(disk.coverUrl)) {
            memoryCache.put(key, disk.coverUrl)
        }
        return disk
    }

    override fun requestArtworkResolution(title: String, artist: String, songKey: String) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) return

        val key = cacheKey(cleanTitle, cleanArtist)
        val cached = getCachedMetadata(cleanTitle, cleanArtist)
        if (cached != null && isValidRemoteCoverUrl(cached.coverUrl)) return
        val lastFail = failedTimestamps[key]
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAILED_COOLDOWN_MS) return
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                fetchSemaphore.withPermit {
                    val meta = fetchMetadataFromOnline(cleanTitle, cleanArtist)
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
        appContext?.let { ctx ->
            PlaylistStore.getInstance(ctx).updateSongsMetadataBatch(batch)
        }
    }

    /**
     * 为歌单列表批量预热解析封面（云同步还原后调用）。
     * 发现无有效封面或使用默认占位封面时，优先从 QQ 音乐平台获取正确封面。
     */
    fun prefetchArtworksForSongs(songs: List<UserPlaylistSong>) {
        songs.forEach { song ->
            val hasValidHttpCover = isValidRemoteCoverUrl(song.albumArtPath)
            val isUsingDefaultCover = song.albumArtPath.isNotBlank() && isPlaceholderUrl(song.albumArtPath)
            val hasPhysicalFile = song.path.startsWith("/") && File(song.path).exists()
            // 针对当前无物理文件且缺少有效网络封面，或明确使用了默认占位封面的条目触发 QQ 音乐优先寻源
            if (song.title.isNotBlank() && (isUsingDefaultCover || (!hasValidHttpCover && !hasPhysicalFile))) {
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
     * 智能多平台级联搜索获取歌曲完整标签与高清封面（优先 QQ 音乐 → 次选网易云 → 兜底酷我音乐）。
     * 若 QQ 音乐命中了歌曲元数据但封面缺失或为默认占位图，继续尝试从同平台候选或后续平台补齐真实封面。
     */
    private suspend fun fetchMetadataFromOnline(title: String, artist: String): ResolvedOnlineTrackMetadata? {
        val keyword = if (artist.isNotBlank() && !artist.equals("未知", true) && !artist.equals("<unknown>", true)) {
            "$title $artist"
        } else {
            title
        }

        val repository = OnlineMusicRepository.getInstance()
        val platforms = listOf(
            OnlinePlatform.QQ,
            OnlinePlatform.NETEASE,
            OnlinePlatform.KUWO,
        )

        var fallbackMetaWithoutCover: ResolvedOnlineTrackMetadata? = null

        for (platform in platforms) {
            try {
                val items = repository.searchSongs(keyword, page = 1, pageSize = 5, platform = platform)
                    .getOrNull()
                    .orEmpty()
                if (items.isEmpty()) continue
                val best = pickBestMetadataMatch(items, title, artist) ?: continue
                val validCover = best.coverUrl?.takeIf { isValidRemoteCoverUrl(it) }.orEmpty()
                    .ifBlank {
                        // 同平台（尤其是 QQ 音乐）若首选条目缺封面，从同批同名同歌手候选中补取有效封面
                        items.firstOrNull { item ->
                            item.title.equals(title, ignoreCase = true) &&
                                (artist.isBlank() || item.artist.contains(artist, ignoreCase = true) || artist.contains(item.artist, ignoreCase = true)) &&
                                isValidRemoteCoverUrl(item.coverUrl)
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
                    // 若前面优先平台（QQ 音乐）已有元数据但缺封面，保留其元数据并仅补齐真实封面
                    return fallbackMetaWithoutCover?.copy(coverUrl = validCover) ?: candidate
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
    ): OnlineSongItem? {
        val exactWithCover = items.firstOrNull { item ->
            item.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || item.artist.contains(artist, ignoreCase = true) || artist.contains(item.artist, ignoreCase = true)) &&
                isValidRemoteCoverUrl(item.coverUrl)
        }
        if (exactWithCover != null) return exactWithCover
        val exact = items.firstOrNull { item ->
            item.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || item.artist.contains(artist, ignoreCase = true) || artist.contains(item.artist, ignoreCase = true))
        }
        if (exact != null) return exact
        return items.firstOrNull { item -> isValidRemoteCoverUrl(item.coverUrl) } ?: items.firstOrNull()
    }
}
