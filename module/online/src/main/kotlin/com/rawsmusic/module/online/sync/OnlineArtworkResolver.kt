package com.rawsmusic.module.online.sync

import android.content.Context
import android.util.LruCache
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.PlaylistArtworkFallbackDelegate
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.prefs.ResolvedOnlineTrackMetadata
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 混合歌单缺失封面的在线自动寻源与持久化自愈服务。
 *
 * 核心机制：
 * 1. 内存 LRU 极速命中，0 延时为虚拟降级歌曲提供已知的网络封面；
 * 2. 具备 In-Flight 请求去重与最大并发限制（Semaphore），避免上千首歌曲同时触发网络风暴；
 * 3. 跨网易云、QQ 音乐、酷我三大平台进行智能多源级联搜寻单曲封面；
 * 4. 寻源成功后同时更新内存缓存、Room 数据库（user_playlist_songs 表）与 PlaylistStore 内存歌单，
 *    实现界面平滑淡入，并在下次打开歌单时永久本地秒开。
 */
object OnlineArtworkResolver : PlaylistArtworkFallbackDelegate {

    private const val TAG = "OnlineArtworkResolver"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = LruCache<String, String>(1000)
    private val metadataCache = LruCache<String, ResolvedOnlineTrackMetadata>(1000)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchSemaphore = Semaphore(3)
    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        PlaylistStore.getInstance(context).artworkFallbackDelegate = this
        AppLogger.i(TAG, "OnlineArtworkResolver successfully installed into PlaylistStore")
    }

    private fun cacheKey(title: String, artist: String): String =
        "${title.trim().lowercase()}__${artist.trim().lowercase()}"

    override fun getCachedArtworkUrl(title: String, artist: String): String? {
        val key = cacheKey(title, artist)
        return memoryCache.get(key)?.takeIf { it.isNotBlank() }
            ?: metadataCache.get(key)?.coverUrl?.takeIf { it.isNotBlank() }
    }

    override fun getCachedMetadata(title: String, artist: String): ResolvedOnlineTrackMetadata? {
        val key = cacheKey(title, artist)
        return metadataCache.get(key)
    }

    override fun requestArtworkResolution(title: String, artist: String, songKey: String) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) return

        val key = cacheKey(cleanTitle, cleanArtist)
        if (metadataCache.get(key) != null) return
        if (!inFlight.add(key)) return

        scope.launch {
            try {
                fetchSemaphore.withPermit {
                    val meta = fetchMetadataFromOnline(cleanTitle, cleanArtist)
                    if (meta != null) {
                        metadataCache.put(key, meta)
                        if (meta.coverUrl.isNotBlank()) {
                            memoryCache.put(key, meta.coverUrl)
                        }
                        AppLogger.i(
                            TAG,
                            "Resolved online metadata (${meta.platformId}) for '$cleanTitle - $cleanArtist': " +
                                "album=${meta.album}, cover=${meta.coverUrl.take(64)}"
                        )
                        appContext?.let { ctx ->
                            PlaylistStore.getInstance(ctx).updateSongMetadataInMemoryAndDb(cleanTitle, cleanArtist, meta)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Failed to resolve online metadata for '$cleanTitle - $cleanArtist': ${e.message}")
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * 为歌单列表批量预热解析封面（云同步还原后调用）。
     */
    fun prefetchArtworksForSongs(songs: List<UserPlaylistSong>) {
        songs.forEach { song ->
            val hasValidHttpCover = song.albumArtPath.startsWith("http://", true) ||
                song.albumArtPath.startsWith("https://", true)
            val hasPhysicalFile = song.path.startsWith("/") && File(song.path).exists()
            // 针对当前无物理文件且缺少网络封面的条目触发预热
            if (!hasValidHttpCover && !hasPhysicalFile && song.title.isNotBlank()) {
                requestArtworkResolution(song.title, song.artist, song.key)
            }
        }
    }

    private fun isPlaceholderUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val u = url.lowercase()
        return u.contains("default_avatar") ||
            u.contains("5639395138885805") ||
            u.contains("6y-uleoritedbvrolvq8a==") ||
            u.contains("109951163250233892") ||
            u.contains("default_album_art")
    }

    /**
     * 智能多平台级联搜索获取歌曲完整标签与高清封面（优先 QQ 音乐 → 次选网易云 → 兜底酷我音乐）。
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

        for (platform in platforms) {
            try {
                val items = repository.searchSongs(keyword, page = 1, pageSize = 5, platform = platform)
                    .getOrNull()
                    .orEmpty()
                if (items.isEmpty()) continue
                val best = pickBestMetadataMatch(items, title, artist) ?: continue
                val validCover = best.coverUrl?.takeIf {
                    (it.startsWith("http://", true) || it.startsWith("https://", true)) &&
                        !isPlaceholderUrl(it)
                }.orEmpty()
                return ResolvedOnlineTrackMetadata(
                    platformId = platform.id,
                    songId = best.id,
                    title = best.title.ifBlank { title },
                    artist = best.artist.ifBlank { artist },
                    album = best.album,
                    durationMs = best.durationMs,
                    coverUrl = validCover,
                )
            } catch (t: Throwable) {
                AppLogger.d(TAG, "${platform.displayName} metadata search '$keyword' skipped: ${t.message}")
            }
        }

        return null
    }

    private fun pickBestMetadataMatch(
        items: List<OnlineSongItem>,
        title: String,
        artist: String,
    ): OnlineSongItem? {
        val exact = items.firstOrNull { item ->
            item.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || item.artist.contains(artist, ignoreCase = true) || artist.contains(item.artist, ignoreCase = true))
        }
        if (exact != null) return exact
        return items.firstOrNull { item ->
            val hasValidCover = item.coverUrl?.let {
                (it.startsWith("http://", true) || it.startsWith("https://", true)) && !isPlaceholderUrl(it)
            } == true
            hasValidCover
        } ?: items.firstOrNull()
    }
}
