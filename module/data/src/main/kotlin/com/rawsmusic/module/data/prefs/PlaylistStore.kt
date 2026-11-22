package com.rawsmusic.module.data.prefs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.DISLIKED_PLAYLIST_ID
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.model.queueIdentity
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.core.common.utils.SongMatcher
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PlaylistImportResult(
    val playlist: UserPlaylist?,
    val importedCount: Int,
    val missingCount: Int
)

/**
 * 歌单的合并视图（P2-05b）。
 *
 * 本地曲与在线曲**播放路径不同**，所以刻意不合并成一个列表：本地曲可以直接进播放队列，
 * 在线曲必须先经在线解析层（`OnlinePlaybackLauncher`）找出可播放地址。
 * 混在一起会让调用方以为它们同质，从而把在线曲当成文件路径去用。
 */
data class PlaylistEntries(
    val local: List<AudioFile>,
    val online: List<UserPlaylistSong>,
) {
    val isEmpty: Boolean get() = local.isEmpty() && online.isEmpty()
    val totalCount: Int get() = local.size + online.size
}

/**
 * 歌单实时音源数量统计（基于当前本地曲库、磁盘物理文件与离线下载状态实时解析）。
 */
data class PlaylistSourceCounts(
    val totalCount: Int,
    val localCount: Int,
    val onlineCount: Int,
) {
    val isHybrid: Boolean get() = localCount > 0 && onlineCount > 0
    val isPureOnline: Boolean get() = totalCount > 0 && onlineCount == totalCount
}

/** 导入时没有可用名字的兜底歌单名。与 `importTextPlaylist` 里既有的兜底保持一致。 */
private const val DEFAULT_IMPORT_PLAYLIST_NAME = "Imported playlist"

/**
 * 从在线平台（优先 QQ 音乐）获取的歌曲标签信息，用于本地歌单缺失物理文件时的元数据补齐。
 */
data class ResolvedOnlineTrackMetadata(
    val platformId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
)

/**
 * 批量元数据回填条目。
 */
data class ResolvedMetadataUpdate(
    val queryTitle: String,
    val queryArtist: String,
    val metadata: ResolvedOnlineTrackMetadata,
)

/**
 * 混合歌单中非本地或缺失封面/标签条目的在线寻源与缓存委托。
 */
interface PlaylistArtworkFallbackDelegate {
    fun getCachedArtworkUrl(title: String, artist: String): String?
    fun getCachedMetadata(title: String, artist: String): ResolvedOnlineTrackMetadata? = null
    fun requestArtworkResolution(title: String, artist: String, songKey: String = "")
}

class PlaylistStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: PlaylistStore? = null

        fun getInstance(context: Context): PlaylistStore =
            instance ?: synchronized(this) {
                instance ?: PlaylistStore(context.applicationContext).also { instance = it }
            }
    }

    @Volatile
    var artworkFallbackDelegate: PlaylistArtworkFallbackDelegate? = null

    private val storeFile = context.filesDir.resolve("rawsmusic_playlists.json")
    private val backupFile = File(storeFile.parentFile, "${storeFile.name}.bak")
    private val legacyPlaylists = loadLegacyPlaylists()
    private val dao = MusicDatabase.getInstance(context).userPlaylistDao()
    private val databaseDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "RawS-PlaylistsRoom").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
    private val databaseScope = CoroutineScope(SupervisorJob() + databaseDispatcher)
    private val mutationMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()

    // 内存 LRU 映射缓存：将歌曲 Key（包含在线条目及原本地缺失文件降级后的虚拟在线条目）映射到已解析结果，避免重复遍历曲库与磁盘 I/O
    private val onlineToLocalMatchCache = android.util.LruCache<String, AudioFile>(4000)

    // 整单解析结果快照缓存：相同歌单快照与曲库版本下 O(1) 命中，避免下钻转场重复计算
    private val resolvedPlaylistSnapshotCache = android.util.LruCache<String, List<AudioFile>>(24)

    // 最近一次有效整单快照（按 playlist.id 索引）：用于后台元数据刷新间隙首帧无缝复用，彻底避免返回再进入时属性还原闪变
    private data class CachedPlaylistSnapshot(
        val updatedAt: Long,
        val songCount: Int,
        val libraryFingerprint: String,
        val completedDownloads: Int,
        val songs: List<AudioFile>,
    )
    private val lastResolvedByPlaylistId = ConcurrentHashMap<String, CachedPlaylistSnapshot>()

    // 已确认不存在的本地文件路径负缓存：避免无本地曲库时每次重算对数百首歌反复触发 File.exists() 磁盘 I/O
    private val missingLocalPathCache = ConcurrentHashMap.newKeySet<String>()

    // 本地文件头校验结果缓存：避免每次打开歌单反复打开数百个文件读磁盘前 12 字节
    private val localAudioHeaderValidCache = android.util.LruCache<String, Boolean>(4000)

    @Volatile
    private var lastLibraryFingerprint: String? = null

    private class LibraryLookupCache(
        val sourceSongs: List<AudioFile>,
        val byIdentity: Map<String, AudioFile>,
        val byPath: Map<String, AudioFile>,
    )

    @Volatile
    private var libraryLookupCache: LibraryLookupCache? = null

    private fun libraryFingerprint(librarySongs: List<AudioFile>): String {
        if (librarySongs.isEmpty()) return "empty"
        return "${librarySongs.size}:${System.identityHashCode(librarySongs)}"
    }

    /**
     * 当本地曲库从空变为非空或发生增删变化时，自动清理旧的匹配与路径负缓存，确保新扫描曲目能即时切回本地音源；
     * 若本地曲库未变化（例如始终无本地音乐库），保持全部缓存热态，实现 0 I/O 秒开。
     */
    private fun ensureLibraryCacheFresh(librarySongs: List<AudioFile>) {
        val currentFp = libraryFingerprint(librarySongs)
        val prevFp = lastLibraryFingerprint
        if (prevFp != null && prevFp != currentFp) {
            onlineToLocalMatchCache.evictAll()
            resolvedPlaylistSnapshotCache.evictAll()
            lastResolvedByPlaylistId.clear()
            missingLocalPathCache.clear()
        }
        if (prevFp != currentFp) {
            lastLibraryFingerprint = currentFp
        }
    }

    private fun getOrBuildLibraryLookup(librarySongs: List<AudioFile>): LibraryLookupCache {
        val cached = libraryLookupCache
        if (cached != null && cached.sourceSongs === librarySongs) return cached
        if (librarySongs.isEmpty()) {
            return LibraryLookupCache(librarySongs, emptyMap(), emptyMap()).also { libraryLookupCache = it }
        }
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val byPath = buildMap<String, AudioFile>(librarySongs.size * 2) {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }
        return LibraryLookupCache(librarySongs, byIdentity, byPath).also { libraryLookupCache = it }
    }

    private fun completedDownloadCount(): Int =
        MusicSourceDownloadController.tasks.value.count {
            it.status == MusicSourceDownloadStatus.Completed
        }

    private fun playlistSnapshotKey(playlist: UserPlaylist, librarySongs: List<AudioFile>): String {
        val completedDownloads = completedDownloadCount()
        val libFp = libraryFingerprint(librarySongs)
        return "${playlist.id}|${playlist.updatedAt}|${playlist.songs.size}|$libFp|${_matchCacheVersion.value}|$completedDownloads"
    }

    /**
     * 判断封面路径是否为默认占位图或空图（包含内置默认图及各大平台占位 URL）。
     */
    private fun isDefaultOrPlaceholderArtwork(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val u = url.trim().lowercase(Locale.ROOT)
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
     * 判断是否为有效的外部网络封面 URL（必须是 http(s) 且非默认占位图）。
     */
    private fun isValidExternalArtworkUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) &&
            !isDefaultOrPlaceholderArtwork(trimmed)
    }

    /**
     * 异步回填单曲封面至内存歌单与数据库中（混合歌单封面自愈）。
     */
    fun updateSongArtworkInMemoryAndDb(title: String, artist: String, artworkUrl: String) {
        if (title.isBlank() || !isValidExternalArtworkUrl(artworkUrl)) return
        val currentPlaylists = _playlists.value
        var anyUpdated = false
        val touchedPlaylistIds = HashSet<String>()
        val newPlaylists = currentPlaylists.map { playlist ->
            var playlistChanged = false
            val newSongs = playlist.songs.map { song ->
                if (song.title.equals(title, ignoreCase = true) &&
                    (artist.isBlank() || song.artist.equals(artist, ignoreCase = true)) &&
                    isDefaultOrPlaceholderArtwork(song.albumArtPath)
                ) {
                    playlistChanged = true
                    anyUpdated = true
                    // 原地更新已缓存条目的封面，保留其已识别的网络/本地身份，绝不粗暴驱逐导致重新识别
                    onlineToLocalMatchCache.get(song.key)?.let { cached ->
                        if (isDefaultOrPlaceholderArtwork(cached.albumArtPath)) {
                            onlineToLocalMatchCache.put(song.key, cached.copy(albumArtPath = artworkUrl))
                        }
                    }
                    song.copy(albumArtPath = artworkUrl)
                } else {
                    song
                }
            }
            if (playlistChanged) {
                touchedPlaylistIds.add(playlist.id)
                playlist.copy(songs = newSongs)
            } else {
                playlist
            }
        }
        if (anyUpdated) {
            resolvedPlaylistSnapshotCache.evictAll()
            updateLastResolvedSnapshotsArtwork(touchedPlaylistIds, title, artist, artworkUrl)
            _playlists.value = newPlaylists
            databaseScope.launch {
                dao.updateArtworkPathByTitleAndArtist(title, artist, artworkUrl)
            }
        }
    }

    private fun updateLastResolvedSnapshotsArtwork(
        playlistIds: Set<String>,
        title: String,
        artist: String,
        artworkUrl: String,
    ) {
        for (pid in playlistIds) {
            val snap = lastResolvedByPlaylistId[pid] ?: continue
            var changed = false
            val updatedSongs = snap.songs.map { audio ->
                if (audio.title.equals(title, ignoreCase = true) &&
                    (artist.isBlank() || audio.artist.equals(artist, ignoreCase = true)) &&
                    isDefaultOrPlaceholderArtwork(audio.albumArtPath)
                ) {
                    changed = true
                    audio.copy(albumArtPath = artworkUrl)
                } else {
                    audio
                }
            }
            if (changed) {
                lastResolvedByPlaylistId[pid] = snap.copy(songs = updatedSongs)
            }
        }
    }

    /**
     * 异步回填在线平台（优先 QQ 音乐）获取的完整歌曲标签与封面至内存歌单与数据库。
     */
    fun updateSongMetadataInMemoryAndDb(
        queryTitle: String,
        queryArtist: String,
        metadata: ResolvedOnlineTrackMetadata,
    ) {
        updateSongsMetadataBatch(listOf(ResolvedMetadataUpdate(queryTitle, queryArtist, metadata)))
    }

    /**
     * 批量合并回填在线平台获取的歌曲标签与封面至内存缓存、歌单状态与 Room 数据库。
     * 相比单首逐条触发：
     * 1. 原地更新 `onlineToLocalMatchCache` 与 `lastResolvedByPlaylistId`，保留已识别的网络属性绝不还原；
     * 2. 合并多次 UI 重组与整单刷新为单次版本号递增，大幅降低 CPU 与主线程开销；
     * 3. 完整持久化封面、专辑、歌手与时长到 Room 数据库。
     */
    fun updateSongsMetadataBatch(updates: List<ResolvedMetadataUpdate>) {
        val validUpdates = updates.filter { it.queryTitle.isNotBlank() }
        if (validUpdates.isEmpty()) return

        // 按归一化 (title, artist) 建立快速查找索引
        val exactIndex = HashMap<String, ResolvedMetadataUpdate>(validUpdates.size * 2)
        val titleOnlyIndex = HashMap<String, ResolvedMetadataUpdate>(validUpdates.size)
        for (u in validUpdates) {
            val tKey = u.queryTitle.trim().lowercase(Locale.ROOT)
            val aKey = u.queryArtist.trim().lowercase(Locale.ROOT)
            exactIndex["$tKey\u0000$aKey"] = u
            if (aKey.isEmpty()) {
                titleOnlyIndex[tKey] = u
            }
        }

        fun findUpdate(title: String, artist: String): ResolvedMetadataUpdate? {
            val tKey = title.trim().lowercase(Locale.ROOT)
            val aKey = artist.trim().lowercase(Locale.ROOT)
            return exactIndex["$tKey\u0000$aKey"] ?: titleOnlyIndex[tKey]
        }

        fun mergeOnlineAudioWithMetadata(
            audio: AudioFile,
            metadata: ResolvedOnlineTrackMetadata,
        ): AudioFile {
            val nextAlbum = audio.album.ifBlank { metadata.album }
            val nextArtist = audio.artist.ifBlank { metadata.artist }
            val nextDuration = if (audio.duration > 0L) audio.duration else metadata.durationMs
            val nextCover = if (isValidExternalArtworkUrl(audio.albumArtPath)) {
                audio.albumArtPath
            } else if (isValidExternalArtworkUrl(metadata.coverUrl)) {
                metadata.coverUrl
            } else if (isDefaultOrPlaceholderArtwork(audio.albumArtPath)) {
                ""
            } else {
                audio.albumArtPath
            }
            val nextPath = if (audio.path.startsWith("online://") &&
                metadata.platformId.isNotBlank() &&
                metadata.songId.isNotBlank()
            ) {
                "online://${metadata.platformId}/${metadata.songId}"
            } else {
                audio.path
            }
            return if (nextPath != audio.path || nextAlbum != audio.album ||
                nextArtist != audio.artist || nextDuration != audio.duration ||
                nextCover != audio.albumArtPath
            ) {
                audio.copy(
                    path = nextPath,
                    artist = nextArtist,
                    album = nextAlbum,
                    duration = nextDuration,
                    albumArtPath = nextCover,
                )
            } else {
                audio
            }
        }

        val currentPlaylists = _playlists.value
        var anyPlaylistUpdated = false
        var anyCacheUpdated = false
        val touchedPlaylistIds = HashSet<String>()

        val newPlaylists = currentPlaylists.map { playlist ->
            var playlistChanged = false
            val newSongs = playlist.songs.map { song ->
                val matched = findUpdate(song.title, song.artist) ?: return@map song
                val metadata = matched.metadata

                // 优先原地更新 onlineToLocalMatchCache 中的已识别结果，绝不 remove 造成属性还原
                onlineToLocalMatchCache.get(song.key)?.let { cached ->
                    val merged = mergeOnlineAudioWithMetadata(cached, metadata)
                    if (merged !== cached) {
                        onlineToLocalMatchCache.put(song.key, merged)
                        anyCacheUpdated = true
                        touchedPlaylistIds.add(playlist.id)
                    }
                }

                val nextAlbum = song.album.ifBlank { metadata.album }
                val nextArtist = song.artist.ifBlank { metadata.artist }
                val nextDuration = if (song.duration > 0L) song.duration else metadata.durationMs
                val nextCover = if (isValidExternalArtworkUrl(song.albumArtPath)) {
                    song.albumArtPath
                } else if (isValidExternalArtworkUrl(metadata.coverUrl)) {
                    metadata.coverUrl
                } else if (isDefaultOrPlaceholderArtwork(song.albumArtPath)) {
                    ""
                } else {
                    song.albumArtPath
                }
                if (nextAlbum != song.album || nextArtist != song.artist ||
                    nextDuration != song.duration || nextCover != song.albumArtPath
                ) {
                    playlistChanged = true
                    anyPlaylistUpdated = true
                    touchedPlaylistIds.add(playlist.id)
                    song.copy(
                        artist = nextArtist,
                        album = nextAlbum,
                        duration = nextDuration,
                        albumArtPath = nextCover,
                    )
                } else {
                    song
                }
            }
            if (playlistChanged) playlist.copy(songs = newSongs) else playlist
        }

        // 原地更新受影响歌单的最近一次快照，确保返回再进入首帧直接展示最新网络属性与封面
        for (pid in touchedPlaylistIds) {
            val snap = lastResolvedByPlaylistId[pid] ?: continue
            var snapChanged = false
            val updatedSongs = snap.songs.map { audio ->
                val matched = findUpdate(audio.title, audio.artist) ?: return@map audio
                val merged = mergeOnlineAudioWithMetadata(audio, matched.metadata)
                if (merged !== audio) snapChanged = true
                merged
            }
            if (snapChanged) {
                lastResolvedByPlaylistId[pid] = snap.copy(songs = updatedSongs)
            }
        }

        if (anyPlaylistUpdated || anyCacheUpdated) {
            resolvedPlaylistSnapshotCache.evictAll()
            if (anyPlaylistUpdated) {
                _playlists.value = newPlaylists
            }
            _matchCacheVersion.value++
        }

        // 批量将补齐的封面与标签持久化到 Room 数据库
        databaseScope.launch {
            for (u in validUpdates) {
                val m = u.metadata
                if (m.coverUrl.isNotBlank() || m.album.isNotBlank() || m.durationMs > 0L || m.artist.isNotBlank()) {
                    runCatching {
                        dao.updateMetadataByTitleAndArtist(
                            queryTitle = u.queryTitle,
                            queryArtist = u.queryArtist,
                            resolvedArtist = m.artist,
                            resolvedAlbum = m.album,
                            resolvedDuration = m.durationMs,
                            resolvedArtwork = m.coverUrl,
                        )
                    }
                }
            }
        }
    }

    private val _matchCacheVersion = MutableStateFlow(0L)
    val matchCacheVersion: StateFlow<Long> = _matchCacheVersion.asStateFlow()

    fun invalidateMatchCache() {
        onlineToLocalMatchCache.evictAll()
        resolvedPlaylistSnapshotCache.evictAll()
        lastResolvedByPlaylistId.clear()
        missingLocalPathCache.clear()
        localAudioHeaderValidCache.evictAll()
        _matchCacheVersion.value++
    }

    private val _playlists = MutableStateFlow(
        legacyPlaylists ?: listOf(createFavoritesPlaylist())
    )
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    init {
        databaseScope.launch {
            try {
                val legacy = legacyPlaylists
                when {
                    legacy != null -> {
                        replaceRoom(ensureFavorites(legacy))
                        deleteLegacyFiles()
                    }
                    dao.countPlaylists() == 0 -> replaceRoom(listOf(createFavoritesPlaylist()))
                }
                _playlists.value = loadRoomSnapshot()
                ready.complete(Unit)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                throw error
            }
        }
    }

    fun favoriteSongKeys(): Set<String> =
        _playlists.value.firstOrNull { it.isFavorites }?.songs?.mapTo(mutableSetOf()) { it.key }
            ?: emptySet()

    fun isFavorite(song: AudioFile): Boolean {
        val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return false
        return favoriteKeysMatching(favorite, song).isNotEmpty()
    }

    suspend fun toggleFavorite(song: AudioFile): Boolean = withDatabase {
        mutationMutex.withLock {
            val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return@withLock false
            // 收藏行可能来自旧曲库路径/旧标签：用户明明在红心歌单里看到它，重新扫描后
            // playlistIdentityKey() 却已经变了。只按当前 key 删除会命中不到任何行，
            // 表现就是「点取消喜欢完全没反应」。这里先按 key/路径找，找不到再按元数据兜底。
            val matchedKeys = favoriteKeysMatching(favorite, song)
            val removing = matchedKeys.isNotEmpty()
            val now = System.currentTimeMillis()
            // 同一首歌可能存在“旧低音质文件”和“新高音质文件”两行：加入新高音质版本时
            // 先清掉同歌名/同歌手的旧版本，避免红心歌单里永远残留一条旧文件。
            val duplicateKeys = if (removing) {
                emptySet()
            } else {
                favorite.songs.filter { sameLooseSongIdentity(it, song) }
                    .mapTo(mutableSetOf()) { it.key }
            }
            val songs = if (removing) favorite.songs.filterNot { it.key in matchedKeys }
                else favorite.songs.filterNot { it.key in duplicateKeys } + song.toUserPlaylistSong()
            MusicDatabase.getInstance(context).withTransaction {
                if (removing) {
                    dao.deleteSongs(favorite.id, matchedKeys.toList())
                } else {
                    if (duplicateKeys.isNotEmpty()) dao.deleteSongs(favorite.id, duplicateKeys.toList())
                    dao.insertSongs(listOf(songs.last().toEntity(favorite.id, favorite.songs.size)))
                }
                dao.touchPlaylist(favorite.id, now)
            }
            _playlists.value = _playlists.value.map {
                if (it.id == favorite.id) it.copy(songs = songs, updatedAt = now) else it
            }
            !removing
        }
    }

    /**
     * 从红心歌单按“当前歌曲”删除，内部用 path/id/标题+歌手+时长兜底。
     * 返回实际删掉的存储 key，供在线 attitude 同步清理。
     */
    suspend fun removeFavoriteSongs(songs: List<AudioFile>): Set<String> = withDatabase {
        mutationMutex.withLock {
            val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return@withLock emptySet()
            val matchedKeys = songs
                .flatMap { song ->
                    favoriteKeysMatching(favorite, song) + favorite.songs
                        .filter { sameLooseSongIdentity(it, song) }
                        .map { it.key }
                }
                .toSet()
            if (matchedKeys.isEmpty()) return@withLock emptySet()

            val now = System.currentTimeMillis()
            val remaining = favorite.songs.filterNot { it.key in matchedKeys }
            MusicDatabase.getInstance(context).withTransaction {
                dao.deleteSongs(favorite.id, matchedKeys.toList())
                dao.touchPlaylist(favorite.id, now)
            }
            _playlists.value = _playlists.value.map {
                if (it.id == favorite.id) it.copy(songs = remaining, updatedAt = now) else it
            }
            matchedKeys
        }
    }

    private fun favoriteKeysMatching(favorite: UserPlaylist, song: AudioFile): Set<String> {
        val exact = favorite.songs
            .filter {
                it.key == song.playlistIdentityKey() ||
                    sameFavoritePath(it.path, song.path) ||
                    (it.id != 0L && it.id == song.id)
            }
            .mapTo(mutableSetOf()) { it.key }
        if (exact.isNotEmpty()) return exact

        val meta = favorite.songs
            .filter { stored ->
                if (!stored.title.equals(song.title, ignoreCase = true) ||
                    !stored.artist.equals(song.artist, ignoreCase = true)
                ) {
                    return@filter false
                }
                // 同名同歌手的多个版本靠时长区分；允许 2 秒误差覆盖不同源的取整差异。
                if (stored.duration <= 0L || song.duration <= 0L) return@filter true
                kotlin.math.abs(stored.duration - song.duration) <= 2_000L
            }
            .mapTo(mutableSetOf()) { it.key }
        if (meta.isNotEmpty()) return meta

        // 最后兜底：云端恢复/旧版本写入的孤儿行可能标题带 (Live)、版本后缀或时长完全
        // 不同。删除红心收藏是用户明确动作，这时按“核心歌名 + 歌手包含关系”清理，
        // 否则会出现“其他歌都能删，只有这一首怎么点都没反应”。
        return favorite.songs
            .filter { sameLooseSongIdentity(it, song) }
            .mapTo(mutableSetOf()) { it.key }
    }

    private fun sameLooseSongIdentity(stored: UserPlaylistSong, song: AudioFile): Boolean {
        val storedTitle = SongMatcher.normalizeForCompare(
            SongMatcher.extractCoreTitle(stored.title, stored.artist)
        )
        val songTitle = SongMatcher.normalizeForCompare(
            SongMatcher.extractCoreTitle(song.title, song.artist)
        )
        if (storedTitle.isBlank() || songTitle.isBlank() || storedTitle != songTitle) return false

        val storedArtist = SongMatcher.normalizeForCompare(stored.artist)
        val songArtist = SongMatcher.normalizeForCompare(song.artist)
        return storedArtist.isBlank() || songArtist.isBlank() ||
            storedArtist == songArtist ||
            storedArtist.contains(songArtist) ||
            songArtist.contains(storedArtist)
    }

    private fun sameFavoritePath(storedPath: String, songPath: String): Boolean {
        if (storedPath.isBlank() || songPath.isBlank()) return false
        return storedPath.replace('\\', '/')
            .equals(songPath.replace('\\', '/'), ignoreCase = true)
    }

    /**
     * 判定两条歌单持久化记录是否为“完全相同的歌曲”：
     * 1. 唯一 key 或归一化物理路径相同；
     * 2. 或四要素（歌曲名称、演唱者、专辑、时长）完全一致（专辑不一致不视为相同；时长容许 1 秒内毫秒取整误差）。
     */
    private fun isIdenticalPlaylistSong(a: UserPlaylistSong, b: UserPlaylistSong): Boolean {
        if (a.key == b.key) return true
        if (a.path.isNotBlank() && b.path.isNotBlank() && a.path.normalizedPath() == b.path.normalizedPath()) {
            return true
        }
        val titleA = a.title.trim()
        val titleB = b.title.trim()
        if (titleA.isBlank() || !titleA.equals(titleB, ignoreCase = true)) return false
        if (!a.artist.trim().equals(b.artist.trim(), ignoreCase = true)) return false
        if (!a.album.trim().equals(b.album.trim(), ignoreCase = true)) return false
        return a.duration == b.duration ||
            (a.duration > 0L && b.duration > 0L && kotlin.math.abs(a.duration - b.duration) <= 1_000L)
    }

    /**
     * 判定两条解析后的 AudioFile 是否在同一个歌单列表中冲突或完全相同：
     * 1. 同一物理路径 / 同一队列标识 / 同一非零 ID（会导致虚拟列表节点复用变为空白占位、多选连带选中）；
     * 2. 或四要素（歌曲名称、演唱者、专辑、时长）完全一致（专辑不一致不视为相同）。
     */
    private fun isIdenticalResolvedAudio(a: AudioFile, b: AudioFile): Boolean {
        if (a.playlistIdentityKey() == b.playlistIdentityKey()) return true
        if (a.queueIdentity() == b.queueIdentity()) return true
        if (a.id != 0L && a.id == b.id) return true
        if (a.path.isNotBlank() && b.path.isNotBlank() && a.path.normalizedPath() == b.path.normalizedPath()) {
            return true
        }
        val titleA = a.title.trim()
        val titleB = b.title.trim()
        if (titleA.isBlank() || !titleA.equals(titleB, ignoreCase = true)) return false
        if (!a.artist.trim().equals(b.artist.trim(), ignoreCase = true)) return false
        if (!a.album.trim().equals(b.album.trim(), ignoreCase = true)) return false
        return a.duration == b.duration ||
            (a.duration > 0L && b.duration > 0L && kotlin.math.abs(a.duration - b.duration) <= 1_000L)
    }

    /**
     * 从待添加候选列表中过滤掉与既有曲目或同批次前面曲目完全相同的歌曲。
     */
    private fun filterUniqueAdditions(
        existing: List<UserPlaylistSong>,
        candidates: Iterable<UserPlaylistSong>,
    ): List<UserPlaylistSong> {
        val accepted = ArrayList<UserPlaylistSong>(existing.size + 16)
        accepted.addAll(existing)
        val additions = ArrayList<UserPlaylistSong>()
        for (candidate in candidates) {
            if (accepted.none { isIdenticalPlaylistSong(it, candidate) }) {
                accepted.add(candidate)
                additions.add(candidate)
            }
        }
        return additions
    }

    /**
     * 对单个列表内部按“完全相同歌曲”标准保序去重。
     */
    private fun deduplicateStoredPlaylistSongs(songs: Iterable<UserPlaylistSong>): List<UserPlaylistSong> =
        filterUniqueAdditions(emptyList(), songs)

    /**
     * 对解析后的 AudioFile 列表保序去重，确保同一歌单中绝不出现重复 identity 或四要素完全相同的条目。
     * 采用 O(N) 哈希索引，避免大歌单（1000+ 首）在主线程首帧渲染时触发 O(N²) 卡顿。
     */
    private fun deduplicateResolvedAudioList(songs: List<AudioFile>): List<AudioFile> {
        if (songs.size <= 1) return songs
        val capacity = (songs.size * 4 / 3) + 16
        val seenIdentityKeys = HashSet<String>(capacity)
        val seenQueueIds = HashSet<String>(capacity)
        val seenIds = HashSet<Long>(capacity)
        val seenNormalizedPaths = HashSet<String>(capacity)
        val seenMetaDurations = HashMap<String, MutableList<Long>>(capacity)
        val result = ArrayList<AudioFile>(songs.size)

        for (song in songs) {
            val identityKey = song.playlistIdentityKey()
            if (identityKey in seenIdentityKeys) continue

            val queueId = song.queueIdentity()
            if (queueId in seenQueueIds) continue

            if (song.id != 0L && song.id in seenIds) continue

            val normalizedPath = if (song.path.isNotBlank()) song.path.normalizedPath() else ""
            if (normalizedPath.isNotEmpty() && normalizedPath in seenNormalizedPaths) continue

            val title = song.title.trim()
            val metaKey = if (title.isNotEmpty()) {
                "${title.lowercase(Locale.ROOT)}\u0000${song.artist.trim().lowercase(Locale.ROOT)}\u0000${song.album.trim().lowercase(Locale.ROOT)}"
            } else {
                ""
            }
            if (metaKey.isNotEmpty()) {
                val durations = seenMetaDurations[metaKey]
                if (durations != null && durations.any { prevDuration ->
                        prevDuration == song.duration ||
                            (prevDuration > 0L && song.duration > 0L && kotlin.math.abs(prevDuration - song.duration) <= 1_000L)
                    }
                ) {
                    continue
                }
            }

            seenIdentityKeys.add(identityKey)
            seenQueueIds.add(queueId)
            if (song.id != 0L) seenIds.add(song.id)
            if (normalizedPath.isNotEmpty()) seenNormalizedPaths.add(normalizedPath)
            if (metaKey.isNotEmpty()) {
                seenMetaDurations.getOrPut(metaKey) { ArrayList(2) }.add(song.duration)
            }
            result.add(song)
        }
        return result
    }

    suspend fun createPlaylist(name: String): UserPlaylist? = withDatabase {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return@withDatabase null
        mutationMutex.withLock {
            if (_playlists.value.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return@withLock null
            }
            val now = System.currentTimeMillis()
            val playlist = UserPlaylist("playlist-${UUID.randomUUID()}", cleanName, emptyList(), now, now)
            dao.upsertPlaylist(playlist.toEntity(_playlists.value.size))
            _playlists.value = _playlists.value + playlist
            playlist
        }
    }

    suspend fun deletePlaylist(id: String): Boolean = withDatabase {
        if (id == FAVORITES_PLAYLIST_ID || id == DISLIKED_PLAYLIST_ID) return@withDatabase false
        mutationMutex.withLock {
            if (_playlists.value.none { it.id == id }) return@withLock false
            dao.deletePlaylist(id)
            _playlists.value = _playlists.value.filterNot { it.id == id }
            true
        }
    }

    suspend fun renamePlaylist(id: String, newName: String): Boolean = withDatabase {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withDatabase false
        mutationMutex.withLock {
            if (_playlists.value.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) {
                return@withLock false
            }
            val current = _playlists.value.firstOrNull { it.id == id && !it.isSystemPlaylist }
                ?: return@withLock false
            val now = System.currentTimeMillis()
            dao.renamePlaylist(id, cleanName, now)
            _playlists.value = _playlists.value.map { playlist ->
                if (playlist.id == current.id) playlist.copy(name = cleanName, updatedAt = now)
                else playlist
            }
            true
        }
    }

    suspend fun addSongToPlaylist(playlistId: String, song: AudioFile) =
        addSongsToPlaylist(playlistId, listOf(song))

    suspend fun addSongsToPlaylist(playlistId: String, songs: Collection<AudioFile>) {
        addSongsToPlaylistAndReportAddedKeys(playlistId, songs)
    }

    suspend fun addSongsToPlaylistAndReportAddedKeys(playlistId: String, songs: Collection<AudioFile>): Set<String> =
        addOnlineSongsToPlaylistAndReportAddedKeys(playlistId, songs.map { it.toUserPlaylistSong() })

    suspend fun addOnlineSongToPlaylist(playlistId: String, song: UserPlaylistSong): Boolean =
        addOnlineSongsToPlaylistAndReportAddedKeys(playlistId, listOf(song)).isNotEmpty()

    suspend fun addOnlineSongsToPlaylistAndReportAddedKeys(playlistId: String, songs: Collection<UserPlaylistSong>): Set<String> = withDatabase {
        mutationMutex.withLock {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withLock emptySet<String>()
            val additions = filterUniqueAdditions(playlist.songs, songs)
            if (additions.isEmpty()) return@withLock emptySet<String>()

            val now = System.currentTimeMillis()
            dao.insertSongs(additions.mapIndexed { index, song ->
                song.toEntity(playlistId, playlist.songs.size + index)
            })
            dao.touchPlaylist(playlistId, now)
            _playlists.value = _playlists.value.map { current ->
                if (current.id == playlistId) {
                    current.copy(songs = current.songs + additions, updatedAt = now)
                } else {
                    current
                }
            }
            additions.mapTo(mutableSetOf()) { it.key }
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songKey: String): Boolean = withDatabase {
        mutationMutex.withLock {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withLock false
            if (playlist.songs.none { it.key == songKey }) return@withLock false
            val now = System.currentTimeMillis()
            dao.deleteSong(playlistId, songKey)
            dao.touchPlaylist(playlistId, now)
            onlineToLocalMatchCache.remove(songKey)
            _playlists.value = _playlists.value.map { current ->
                if (current.id == playlistId) {
                    current.copy(
                        songs = current.songs.filterNot { it.key == songKey },
                        updatedAt = now
                    )
                } else {
                    current
                }
            }
            true
        }
    }

    suspend fun removeSongsFromPlaylist(playlistId: String, songKeys: Collection<String>): Int = withDatabase {
        mutationMutex.withLock {
            val playlist = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withLock 0
            val keySet = songKeys.toSet()
            val remaining = playlist.songs.filterNot { it.key in keySet }
            val removedCount = playlist.songs.size - remaining.size
            if (removedCount == 0) return@withLock 0
            val now = System.currentTimeMillis()
            dao.deleteSongs(playlistId, keySet.toList())
            dao.touchPlaylist(playlistId, now)
            keySet.forEach { onlineToLocalMatchCache.remove(it) }
            _playlists.value = _playlists.value.map { current ->
                if (current.id == playlistId) {
                    current.copy(songs = remaining, updatedAt = now)
                } else {
                    current
                }
            }
            removedCount
        }
    }

    /**
     * 为歌单移除动作解析出所有应被删除的持久化 `stored.key` 集合。
     *
     * 混合歌单中，在线条目在离线保存后会解析为本地 `AudioFile`（路径变为 `/storage/...`，
     * 歌手分隔符、标题或扫描器读出的标签可能与原在线条目略有差异）。本方法按三层策略解析：
     * 1. 通过 `onlineToLocalMatchCache` 快照精确反查该展示项对应的原始 `stored.key`；
     * 2. 精确匹配 `stored.key` / `stored.path` / `playlistIdentityKey()`；
     * 3. 通过 `SongMatcher` 与宽松身份比对（兼容“歌手 - 歌名”文件名标题与未知歌手标签）兜底。
     */
    fun resolveStoredKeysForRemoval(
        playlist: UserPlaylist?,
        targetSongs: Collection<AudioFile>,
    ): Set<String> {
        if (playlist == null || targetSongs.isEmpty()) return emptySet()
        val storedSongs = playlist.songs
        if (storedSongs.isEmpty()) return emptySet()

        val result = linkedSetOf<String>()
        val targetPaths = targetSongs.mapNotNull { it.path.takeIf(String::isNotBlank)?.normalizedPath() }.toSet()
        val targetIdentityKeys = targetSongs.mapTo(mutableSetOf()) { it.playlistIdentityKey() }
        val targetIds = targetSongs.mapNotNull { it.id.takeIf { id -> id != 0L } }.toSet()

        targetSongs.forEach { song ->
            result.add(song.playlistIdentityKey())
            if (song.path.isNotBlank()) result.add(song.path)
        }

        // 1. 反向查阅在线→本地匹配缓存，100% 精准命中离线保存后被映射为本地文件的在线条目
        val cacheSnapshot = onlineToLocalMatchCache.snapshot()
        for (stored in storedSongs) {
            val cachedAudio = cacheSnapshot[stored.key] ?: continue
            val cachedNormPath = cachedAudio.path.normalizedPath()
            if (cachedAudio.playlistIdentityKey() in targetIdentityKeys ||
                (cachedNormPath.isNotBlank() && cachedNormPath in targetPaths) ||
                (cachedAudio.id != 0L && cachedAudio.id in targetIds)
            ) {
                result.add(stored.key)
                if (stored.path.isNotBlank()) result.add(stored.path)
            }
        }

        // 2. 精确标识 + 智能元数据匹配
        for (song in targetSongs) {
            val songIdKey = song.playlistIdentityKey()
            val songNormPath = song.path.normalizedPath()
            for (stored in storedSongs) {
                if (stored.key in result) continue
                val storedNormPath = stored.path.normalizedPath()
                val keyMatch = stored.key == songIdKey ||
                    stored.key == song.path ||
                    (songNormPath.isNotBlank() && storedNormPath == songNormPath) ||
                    (stored.id != 0L && stored.id == song.id)
                val metaMatch = sameLooseSongIdentity(stored, song) ||
                    SongMatcher.isMatched(
                        targetTitle = stored.title,
                        targetArtist = stored.artist,
                        candidateTitle = song.title,
                        candidateArtist = song.artist,
                        targetDurationMs = stored.duration,
                        candidateDurationMs = song.duration,
                        toleranceMs = 30_000L,
                    )
                // 兜底：若损坏文件被媒体库以“歌手 - 歌名”作为标题扫描，拆出后半段比对
                val fileNameTitleMatch = !keyMatch && !metaMatch && run {
                    val fileStem = song.path.substringAfterLast('/').substringBeforeLast('.')
                    val rawCandidate = song.title.ifBlank { fileStem }
                    val splitTitle = rawCandidate.substringAfter(" - ", "").trim()
                    if (splitTitle.isBlank()) {
                        false
                    } else {
                        val storedNormTitle = SongMatcher.normalizeForCompare(
                            SongMatcher.extractCoreTitle(stored.title, stored.artist)
                        )
                        val candidateNormTitle = SongMatcher.normalizeForCompare(
                            SongMatcher.extractCoreTitle(splitTitle, stored.artist)
                        )
                        storedNormTitle.isNotBlank() && storedNormTitle == candidateNormTitle
                    }
                }
                if (keyMatch || metaMatch || fileNameTitleMatch) {
                    result.add(stored.key)
                    if (stored.path.isNotBlank()) result.add(stored.path)
                }
            }
        }
        return result
    }

    /** Resolves stored identities against the live library so edited tags and artwork stay current. */
    fun resolveSongs(playlist: UserPlaylist?, librarySongs: List<AudioFile>): List<AudioFile> {
        playlist ?: return emptyList()
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val byPath = buildMap<String, AudioFile> {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }
        return deduplicateResolvedAudioList(
            playlist.songs.mapNotNull { stored ->
                byIdentity[stored.key]
                    ?: stored.path.pathCandidates().firstNotNullOfOrNull(byPath::get)
                    ?: stored.toAudioFile().takeIf { File(it.path).exists() }
            }
        )
    }

    /**
     * 一个歌单里的**在线曲目**（P2-05b）。
     */
    fun onlineSongsOf(playlist: UserPlaylist?): List<UserPlaylistSong> =
        playlist?.songs?.filter { it.isOnlineEntry() }.orEmpty()

    /**
     * 主线程零 I/O 极速预览：用于歌单详情页下钻转场首帧立即渲染列表，绝不阻塞动画。
     * 1. 优先 O(1) 命中整单快照缓存；
     * 2. 次选按 playlist.id 命中最近一次完整解析快照（消除后台元数据更新间隙导致的属性还原闪变）；
     * 3. 逐条拼装时，对在线条目与原本地条目统一复用 `onlineToLocalMatchCache`，彻底杜绝返回再进入时网络歌曲还原为本地属性。
     */
    fun peekCachedPlaylistSongs(
        playlist: UserPlaylist?,
        librarySongs: List<AudioFile>,
    ): List<AudioFile> {
        playlist ?: return emptyList()
        ensureLibraryCacheFresh(librarySongs)

        val snapKey = playlistSnapshotKey(playlist, librarySongs)
        resolvedPlaylistSnapshotCache.get(snapKey)?.let { return it }

        val currentLibFp = libraryFingerprint(librarySongs)
        val currentDownloads = completedDownloadCount()
        lastResolvedByPlaylistId[playlist.id]?.let { recent ->
            if (recent.updatedAt == playlist.updatedAt &&
                recent.songCount == playlist.songs.size &&
                recent.libraryFingerprint == currentLibFp &&
                recent.completedDownloads == currentDownloads
            ) {
                return recent.songs
            }
        }

        val cachedLookup = libraryLookupCache?.takeIf { it.sourceSongs === librarySongs }
        val rawList = playlist.songs.map { stored ->
            val cached = onlineToLocalMatchCache.get(stored.key)
            if (cached != null) {
                val fallbackCover = stored.albumArtPath.takeIf(::isValidExternalArtworkUrl).orEmpty().ifBlank {
                    artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                        ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                }
                if (isDefaultOrPlaceholderArtwork(cached.albumArtPath)) {
                    cached.copy(albumArtPath = fallbackCover)
                } else {
                    cached
                }
            } else if (stored.isOnlineEntry()) {
                val fallbackCover = stored.albumArtPath.takeIf(::isValidExternalArtworkUrl).orEmpty().ifBlank {
                    artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                        ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                }
                stored.toAudioFile().let { audio ->
                    if (isDefaultOrPlaceholderArtwork(audio.albumArtPath)) {
                        audio.copy(albumArtPath = fallbackCover)
                    } else audio
                }
            } else {
                val matchedLocal = cachedLookup?.byIdentity?.get(stored.key)
                if (matchedLocal != null) {
                    if (isDefaultOrPlaceholderArtwork(matchedLocal.albumArtPath)) {
                        val fallbackCover = artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                            ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                        if (fallbackCover.isNotBlank() || matchedLocal.albumArtPath.isNotBlank()) {
                            matchedLocal.copy(albumArtPath = fallbackCover)
                        } else matchedLocal
                    } else {
                        matchedLocal
                    }
                } else if (librarySongs.isEmpty() && stored.title.isNotBlank() && !stored.path.startsWith("content://", ignoreCase = true)) {
                    // 无本地音乐库且尚未写入单曲缓存时，若已有持久化/内存在线元数据或已确认路径不存在，首帧直接构造网络虚拟歌曲，避免闪现本地属性
                    val cachedMeta = artworkFallbackDelegate?.getCachedMetadata(stored.title, stored.artist)
                    val pathKnownMissing = stored.path.isBlank() || stored.path in missingLocalPathCache
                    if (cachedMeta != null || pathKnownMissing) {
                        buildVirtualOnlineAudio(stored, cachedMeta)
                    } else {
                        stored.toAudioFile().let { audio ->
                            if (isDefaultOrPlaceholderArtwork(audio.albumArtPath)) audio.copy(albumArtPath = "") else audio
                        }
                    }
                } else {
                    stored.toAudioFile().let { audio ->
                        if (isDefaultOrPlaceholderArtwork(audio.albumArtPath)) audio.copy(albumArtPath = "") else audio
                    }
                }
            }
        }
        return deduplicateResolvedAudioList(rawList)
    }

    private fun buildVirtualOnlineAudio(
        stored: UserPlaylistSong,
        cachedMeta: ResolvedOnlineTrackMetadata?,
    ): AudioFile {
        val cachedCover = cachedMeta?.coverUrl?.takeIf(::isValidExternalArtworkUrl)
            ?: artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
        val cleanedArtPath = stored.albumArtPath.takeIf(::isValidExternalArtworkUrl).orEmpty().ifBlank { cachedCover }
        val baseAudio = stored.toAudioFile()
        if (stored.title.isBlank()) {
            return baseAudio.copy(albumArtPath = cleanedArtPath)
        }
        val platformId = cachedMeta?.platformId?.takeIf(String::isNotBlank) ?: "qq"
        val virtualSongId = cachedMeta?.songId?.takeIf(String::isNotBlank)
            ?: "${stored.title}_${stored.artist}".hashCode().toString()
        val onlinePath = "online://$platformId/$virtualSongId"
        return baseAudio.copy(
            path = onlinePath,
            title = baseAudio.title.ifBlank { cachedMeta?.title.orEmpty() },
            artist = baseAudio.artist.ifBlank { cachedMeta?.artist.orEmpty() },
            album = baseAudio.album.ifBlank { cachedMeta?.album.orEmpty() },
            duration = if (baseAudio.duration > 0L) baseAudio.duration else (cachedMeta?.durationMs ?: 0L),
            albumArtPath = cleanedArtPath,
            encodingFormat = com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER,
        )
    }

    /**
     * 混合歌单的曲目智能解析：保留歌单原本的排列顺序。
     *
     * 特征（规则 3）：当在线歌曲在本地曲库中存在同名、同歌手且时长基本一致（误差不超过 5 秒）的本地歌曲时，
     * 优先将其解析为本地音源（同时保留在线网络封面），实现本地音源优先播放。
     */
    fun resolveOrderedPlaylistSongs(
        playlist: UserPlaylist?,
        librarySongs: List<AudioFile>,
    ): List<AudioFile> {
        playlist ?: return emptyList()
        ensureLibraryCacheFresh(librarySongs)

        val snapKey = playlistSnapshotKey(playlist, librarySongs)
        resolvedPlaylistSnapshotCache.get(snapKey)?.let { return it }

        val hasLocalLibrary = librarySongs.isNotEmpty()
        val lookup = getOrBuildLibraryLookup(librarySongs)
        val byIdentity = lookup.byIdentity
        val byPath = lookup.byPath

        // 仅在有在线歌曲或未索引的本地歌曲时才懒加载构建索引，避免无意义的开销
        val localSongIndex by lazy(LazyThreadSafetyMode.NONE) {
            if (hasLocalLibrary) {
                SongMatcher.buildIndex(librarySongs)
            } else {
                null
            }
        }

        // 离线下载目录文件快照（仅在有未命中的在线歌曲且需要磁盘探活时懒加载只扫描一次，避免几千次 File.exists 系统 I/O）
        val downloadDir = MusicSourceDownloadController.publicDownloadDirectory()
        val downloadedFilesSnapshot: Set<String>? by lazy(LazyThreadSafetyMode.NONE) {
            if (downloadDir.exists() && downloadDir.isDirectory) {
                downloadDir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet()
            } else {
                emptySet()
            }
        }

        // 下载任务快照（仅在有未命中的在线歌曲时懒加载过滤一次）
        val completedTasksSnapshot by lazy(LazyThreadSafetyMode.NONE) {
            MusicSourceDownloadController.tasks.value.filter {
                it.status == MusicSourceDownloadStatus.Completed && it.localUri.isNotBlank()
            }
        }

        fun isUsableLocalAudio(audio: AudioFile): Boolean {
            if (audio.path.startsWith("content://", ignoreCase = true)) return true
            // 已由曲库扫描器确认具备有效音频流参数（时长+采样率）的条目直接信任，避免每次打开歌单反复读磁盘
            if (audio.duration > 0L && audio.sampleRate > 0) return true
            val file = File(audio.path)
            if (!file.isFile) return false
            val len = file.length()
            if (len < 12L) return false
            val headerKey = "${audio.path}|$len|${file.lastModified()}"
            localAudioHeaderValidCache.get(headerKey)?.let { return it }
            val valid = MusicSourceDownloadController.isValidLocalAudioFile(file)
            localAudioHeaderValidCache.put(headerKey, valid)
            return valid
        }

        // 批量寻源封面计数器：单次解析最多触发前 60 首未有元数据/封面的异步寻源，配合后台批量防抖提交高效补齐
        var artworkResolutionCount = 0

        // 本地音源优先原则：无论歌单是否为混合歌单，在线条目若在本地曲库中有对应音频，
        // 一律优先使用本地音源播放，实现 0 延迟秒开，避免网络寻源开销与超时；
        // 本地条目若因换机或路径微调未命中原路径，自动通过智能匹配器自愈找回或转为在线虚拟歌曲并缓存结果。
        val uniqueResolved = ArrayList<AudioFile>(playlist.songs.size)
        val duplicateStoredKeys = LinkedHashSet<String>()

        for (stored in playlist.songs) {
            val resolvedAudio: AudioFile = if (stored.isOnlineEntry()) {
                val cacheKey = stored.key
                val cachedCover = artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                    ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                val cleanedArtPath = stored.albumArtPath.takeIf(::isValidExternalArtworkUrl).orEmpty().ifBlank { cachedCover }

                // 若当前在线条目仍无有效网络封面（含默认占位图），优先从 QQ 音乐平台异步寻源自愈
                if (cleanedArtPath.isBlank() && stored.title.isNotBlank() && artworkResolutionCount < 60) {
                    artworkResolutionCount++
                    artworkFallbackDelegate?.requestArtworkResolution(stored.title, stored.artist, stored.key)
                }

                fun buildOnlineAudio(): AudioFile {
                    return stored.toAudioFile().let { song ->
                        if (isDefaultOrPlaceholderArtwork(song.albumArtPath)) {
                            song.copy(albumArtPath = cleanedArtPath)
                        } else {
                            song
                        }
                    }
                }

                fun inheritMetadataAndCoverIfNeeded(audio: AudioFile): AudioFile {
                    // 1. 若匹配到的本地记录尚处于快速扫描的“文件名占位”状态（缺少歌手/专辑标签），
                    //    优先继承原在线条目的真实歌名、歌手与专辑，避免列表直接显示文件名。
                    val fileStem = audio.path.substringAfterLast('/').substringBeforeLast('.')
                    val looksLikeFileNamePlaceholder = audio.artist.isBlank() &&
                        (audio.title.isBlank() || audio.title.equals(fileStem, ignoreCase = true))
                    val enriched = if (looksLikeFileNamePlaceholder) {
                        audio.copy(
                            title = stored.title.ifBlank { audio.title },
                            artist = stored.artist.ifBlank { audio.artist },
                            album = audio.album.ifBlank { stored.album },
                        )
                    } else if (audio.album.isBlank() && stored.album.isNotBlank()) {
                        audio.copy(album = stored.album)
                    } else {
                        audio
                    }
                    // 2. 清洗默认占位图并补充有效网络封面作为外置回退，绝不展示默认封面。
                    return if (isDefaultOrPlaceholderArtwork(enriched.albumArtPath)) {
                        if (cleanedArtPath.isNotBlank() || enriched.albumArtPath.isNotBlank()) {
                            enriched.copy(albumArtPath = cleanedArtPath)
                        } else {
                            enriched
                        }
                    } else {
                        enriched
                    }
                }

                val cached = onlineToLocalMatchCache.get(cacheKey)
                if (cached != null) {
                    if (cached.path.startsWith("online://")) {
                        // 负缓存命中：此前已确认无本地匹配，直接返回在线音频，0ms 秒开（补齐最新封面）
                        inheritMetadataAndCoverIfNeeded(cached)
                    } else if (isUsableLocalAudio(cached)) {
                        // 正缓存命中：本地匹配文件完整有效，直接返回（补齐缺失标签与外置封面回退）
                        inheritMetadataAndCoverIfNeeded(cached)
                    } else {
                        // 之前匹配的本地物理文件被删除、移动或已损坏，重新走匹配
                        val onlineAudioFile = buildOnlineAudio()
                        var matchedLocal = if (hasLocalLibrary) {
                            SongMatcher.findBestLocalMatch(onlineAudioFile, librarySongs, localSongIndex)
                                ?.takeIf(::isUsableLocalAudio)
                        } else null
                        if (matchedLocal == null) {
                            matchedLocal = findDownloadedTaskMatch(onlineAudioFile, completedTasksSnapshot)
                        }
                        if (matchedLocal == null) {
                            matchedLocal = probeDownloadedFile(onlineAudioFile, downloadedFilesSnapshot)
                        }
                        val result = matchedLocal?.let(::inheritMetadataAndCoverIfNeeded) ?: onlineAudioFile
                        onlineToLocalMatchCache.put(cacheKey, result)
                        result
                    }
                } else {
                    // 缓存未命中：执行匹配流程，并将结果（无论是本地还是 online 虚拟音频）均写入 LRU 缓存，阻断重复扫描
                    val onlineAudioFile = buildOnlineAudio()
                    var matchedLocal = if (hasLocalLibrary) {
                        SongMatcher.findBestLocalMatch(onlineAudioFile, librarySongs, localSongIndex)
                            ?.takeIf(::isUsableLocalAudio)
                    } else null
                    if (matchedLocal == null) {
                        matchedLocal = findDownloadedTaskMatch(onlineAudioFile, completedTasksSnapshot)
                    }
                    if (matchedLocal == null) {
                        matchedLocal = probeDownloadedFile(onlineAudioFile, downloadedFilesSnapshot)
                    }
                    val result = matchedLocal?.let(::inheritMetadataAndCoverIfNeeded) ?: onlineAudioFile
                    onlineToLocalMatchCache.put(cacheKey, result)
                    result
                }
            } else {
                val cacheKey = stored.key
                val cached = onlineToLocalMatchCache.get(cacheKey)
                if (cached != null && cached.path.startsWith("online://")) {
                    // 高速缓存命中：此前已确认本地无此物理文件并识别为网络歌曲，直接 O(1) 复用，0 磁盘 I/O
                    val cachedMeta = artworkFallbackDelegate?.getCachedMetadata(stored.title, stored.artist)
                    val hasValidMetaCover = isValidExternalArtworkUrl(cachedMeta?.coverUrl)
                    val hasValidStoredCover = isValidExternalArtworkUrl(stored.albumArtPath)
                    val hasValidCachedCover = isValidExternalArtworkUrl(cached.albumArtPath)
                    if ((!hasValidMetaCover && !hasValidStoredCover && !hasValidCachedCover) &&
                        stored.title.isNotBlank() && artworkResolutionCount < 60
                    ) {
                        artworkResolutionCount++
                        artworkFallbackDelegate?.requestArtworkResolution(stored.title, stored.artist, stored.key)
                    }
                    if (cachedMeta != null) {
                        val merged = buildVirtualOnlineAudio(stored, cachedMeta)
                        if (merged != cached) {
                            onlineToLocalMatchCache.put(cacheKey, merged)
                        }
                        merged
                    } else {
                        val fallbackCover = stored.albumArtPath.takeIf(::isValidExternalArtworkUrl).orEmpty().ifBlank {
                            artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                                ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                        }
                        if (isDefaultOrPlaceholderArtwork(cached.albumArtPath)) {
                            cached.copy(albumArtPath = fallbackCover).also {
                                onlineToLocalMatchCache.put(cacheKey, it)
                            }
                        } else {
                            cached
                        }
                    }
                } else if (cached != null && isUsableLocalAudio(cached)) {
                    // 本地正缓存命中：若封面缺失或为默认占位图，清洗并优先从 QQ 音乐补齐真实封面
                    if (isDefaultOrPlaceholderArtwork(cached.albumArtPath) && stored.title.isNotBlank()) {
                        val cachedCover = artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                            ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                        if (cachedCover.isNotBlank()) {
                            cached.copy(albumArtPath = cachedCover).also {
                                onlineToLocalMatchCache.put(cacheKey, it)
                            }
                        } else {
                            if (artworkResolutionCount < 60) {
                                artworkResolutionCount++
                                artworkFallbackDelegate?.requestArtworkResolution(stored.title, stored.artist, stored.key)
                            }
                            if (cached.albumArtPath.isNotBlank()) {
                                cached.copy(albumArtPath = "").also { onlineToLocalMatchCache.put(cacheKey, it) }
                            } else cached
                        }
                    } else {
                        cached
                    }
                } else {
                    val matched = if (hasLocalLibrary) {
                        byIdentity[stored.key]
                            ?: stored.path.pathCandidates().firstNotNullOfOrNull(byPath::get)
                    } else {
                        null
                    } ?: stored.toAudioFile().takeIf { audio ->
                        val p = audio.path
                        when {
                            p.isBlank() -> false
                            p.startsWith("content://", ignoreCase = true) -> true
                            p in missingLocalPathCache -> false
                            File(p).exists() -> true
                            else -> {
                                missingLocalPathCache.add(p)
                                false
                            }
                        }
                    } ?: if (hasLocalLibrary) {
                        SongMatcher.findBestLocalMatch(stored.toAudioFile(), librarySongs, localSongIndex)
                    } else {
                        null
                    }

                    if (matched != null) {
                        // 若本地物理文件缺少外置封面路径或为默认占位图，清洗并优先从 QQ 音乐获取真实封面作为回退
                        val localResult = if (isDefaultOrPlaceholderArtwork(matched.albumArtPath) && stored.title.isNotBlank()) {
                            val cachedCover = artworkFallbackDelegate?.getCachedArtworkUrl(stored.title, stored.artist)
                                ?.takeIf(::isValidExternalArtworkUrl).orEmpty()
                            if (cachedCover.isNotBlank()) {
                                matched.copy(albumArtPath = cachedCover)
                            } else {
                                if (artworkResolutionCount < 60) {
                                    artworkResolutionCount++
                                    artworkFallbackDelegate?.requestArtworkResolution(stored.title, stored.artist, stored.key)
                                }
                                if (matched.albumArtPath.isNotBlank()) matched.copy(albumArtPath = "") else matched
                            }
                        } else {
                            matched
                        }
                        onlineToLocalMatchCache.put(cacheKey, localResult)
                        localResult
                    } else {
                        // 本地歌曲在手机中已无物理文件（被删除、换机或云同步且无本地音乐库）：
                        // 1. 优先从 QQ 音乐平台获取并补齐歌曲标签信息（专辑/歌手/时长/封面）；
                        // 2. 运行时转为在线播放身份（online://qq/...），并写入缓存确保返回再进入 0ms 秒开不还原；
                        // 3. 数据库仍保留原 stored.key，后续扫描到本地曲库或离线下载后自动无缝切回本地。
                        val cachedMeta = artworkFallbackDelegate?.getCachedMetadata(stored.title, stored.artist)
                        val hasValidCover = isValidExternalArtworkUrl(cachedMeta?.coverUrl) ||
                            isValidExternalArtworkUrl(stored.albumArtPath)
                        if ((cachedMeta == null || !hasValidCover) && stored.title.isNotBlank() && artworkResolutionCount < 60) {
                            artworkResolutionCount++
                            artworkFallbackDelegate?.requestArtworkResolution(stored.title, stored.artist, stored.key)
                        }
                        val virtualAudio = buildVirtualOnlineAudio(stored, cachedMeta)
                        if (virtualAudio.path.startsWith("online://")) {
                            onlineToLocalMatchCache.put(cacheKey, virtualAudio)
                        }
                        virtualAudio
                    }
                }
            }

            if (uniqueResolved.none { isIdenticalResolvedAudio(it, resolvedAudio) }) {
                uniqueResolved.add(resolvedAudio)
            } else if (stored.key.isNotBlank() && playlist.songs.count { it.key == stored.key } == 1) {
                // 两条不同 key 的记录解析到了同一首本地曲目或完全相同的四要素歌曲，记录冗余 key 后台自动清理
                duplicateStoredKeys.add(stored.key)
            }
        }

        if (duplicateStoredKeys.isNotEmpty() && !playlist.id.startsWith("attitude:")) {
            val targetPlaylistId = playlist.id
            databaseScope.launch {
                runCatching { removeSongsFromPlaylist(targetPlaylistId, duplicateStoredKeys) }
            }
        }

        resolvedPlaylistSnapshotCache.put(snapKey, uniqueResolved)
        lastResolvedByPlaylistId[playlist.id] = CachedPlaylistSnapshot(
            updatedAt = playlist.updatedAt,
            songCount = playlist.songs.size,
            libraryFingerprint = libraryFingerprint(librarySongs),
            completedDownloads = completedDownloadCount(),
            songs = uniqueResolved,
        )
        return uniqueResolved
    }

    /**
     * 实时计算单个歌单的本地/网络歌曲数量。
     * 复用 [resolveOrderedPlaylistSongs] 的完整本地曲库对齐与磁盘核验结果，
     * 保证歌单列表页与歌单详情页的统计口径完全一致。
     */
    fun computePlaylistSourceCounts(
        playlist: UserPlaylist?,
        librarySongs: List<AudioFile>,
    ): PlaylistSourceCounts {
        if (playlist == null || playlist.songs.isEmpty()) {
            return PlaylistSourceCounts(totalCount = 0, localCount = 0, onlineCount = 0)
        }
        val resolved = resolveOrderedPlaylistSongs(playlist, librarySongs)
        val onlineCount = resolved.count { it.path.startsWith("online://") }
        val localCount = (resolved.size - onlineCount).coerceAtLeast(0)
        return PlaylistSourceCounts(
            totalCount = resolved.size,
            localCount = localCount,
            onlineCount = onlineCount,
        )
    }

    /**
     * 歌单的合并视图：本地曲（包含已匹配到本地的在线音源）与在线曲。
     */
    fun playlistEntries(
        playlist: UserPlaylist?,
        librarySongs: List<AudioFile>,
    ): PlaylistEntries {
        val resolved = resolveOrderedPlaylistSongs(playlist, librarySongs)
        val (local, online) = resolved.partition { !it.path.startsWith("online://") }
        val onlineRows = playlist?.songs?.filter { it.isOnlineEntry() }.orEmpty()
        return PlaylistEntries(
            local = local,
            online = onlineRows,
        )
    }

    fun getPlaylistCoverUrl(playlistId: String): String? =
        AppPreferences.storage.decodeString("playlist_cover_url_$playlistId")

    fun setPlaylistCoverUrl(playlistId: String, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) {
            AppPreferences.storage.remove("playlist_cover_url_$playlistId")
        } else {
            AppPreferences.storage.encode("playlist_cover_url_$playlistId", coverUrl)
        }
    }

    /**
     * 把一批候选路径匹配到曲库（纯文本 / M3U8 / 链接导入共用）。
     *
     * 三层回退：① path 归一化后精确命中 → ② 曲库路径以候选结尾（吸收 Android 的
     * `primary/` / `storage/emulated/0/` 前缀差异）→ ③ 同文件名且在曲库里唯一。
     *
     * 这是从 [importTextPlaylist] 里**原样提取**出来的，逻辑一字未改，只是让其它导入路径
     * （M3U8 / 链接）也能复用，避免第 N 份抄本各自漂移。
     */
    fun matchLibrarySongs(
        candidates: Collection<String>,
        librarySongs: List<AudioFile>,
    ): List<AudioFile> {
        if (candidates.isEmpty() || librarySongs.isEmpty()) return emptyList()
        val exact = buildMap<String, AudioFile> {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }
        val byFileName = librarySongs.groupBy { it.path.substringAfterLast('/').lowercase() }
        return candidates.mapNotNull { requested ->
            requested.pathCandidates().firstNotNullOfOrNull(exact::get)
                ?: requested.pathCandidates().firstNotNullOfOrNull { candidate ->
                    librarySongs.firstOrNull { song ->
                        song.path.normalizedPath().endsWith("/${candidate.trimStart('/')}")
                    }
                }
                ?: byFileName[requested.substringAfterLast('/').lowercase()]?.singleOrNull()
        }.distinctBy { it.playlistIdentityKey() }
    }

    /**
     * 用**调用方已经解析好**的条目导入/合并一个歌单（M3U8 / JSON / 链接导入走它）。
     *
     * 与 [importTextPlaylist] 的分工：那个只按**路径**匹配本地曲，且 `#` 开头的行被整个跳过
     * （`#EXTINF` 里的歌名/歌手白丢）；这个接收调用方自己匹配好的本地曲与**在线行**，
     * 因此支持元数据匹配与在线曲目。
     *
     * 合并语义与既有导入保持一致：按**同名**歌单合并（忽略收藏歌单），按 `key` 去重 ——
     * 重复导入同一首不会产生重复行。
     *
     * @param onlineSongs 用 `UserPlaylistSong` 形态传入的在线条目（构造见 `P2-05b`：
     *   `key`/`path` = `online://<platformId>/<songId>`、`id` = `generateVirtualSongId(...)`）。
     * @return 最终写入的歌单；若合并后无任何变化则返回既有歌单。
     */
    suspend fun importResolvedPlaylist(
        name: String,
        localSongs: List<AudioFile>,
        onlineSongs: List<UserPlaylistSong> = emptyList(),
        coverUrl: String? = null,
        overwrite: Boolean = false,
    ): UserPlaylist? = withDatabase {
        mutationMutex.withLock {
            val baseName = name.trim().ifBlank { DEFAULT_IMPORT_PLAYLIST_NAME }
            val now = System.currentTimeMillis()
            val existing = _playlists.value.firstOrNull {
                !it.isFavorites && it.name.equals(baseName, ignoreCase = true)
            }
            val baseSongs = if (overwrite) emptyList() else existing.orEmptySongs()
            val incoming = filterUniqueAdditions(
                existing = baseSongs,
                candidates = localSongs.map { it.toUserPlaylistSong() } + onlineSongs,
            )
            if (incoming.isEmpty() && existing != null && !overwrite) return@withLock existing

            val importedSongs = baseSongs + incoming
            val targetCover = coverUrl ?: existing?.coverUrl ?: getPlaylistCoverUrl(existing?.id.orEmpty())
                ?: incoming.firstOrNull { it.albumArtPath.startsWith("http://", true) || it.albumArtPath.startsWith("https://", true) }?.albumArtPath
            val imported = UserPlaylist(
                id = existing?.id ?: "playlist-${UUID.randomUUID()}",
                name = existing?.name ?: baseName,
                songs = importedSongs,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                coverUrl = targetCover,
            )
            targetCover?.let { setPlaylistCoverUrl(imported.id, it) }
            dao.upsertPlaylist(
                imported.toEntity(existing?.let(_playlists.value::indexOf) ?: _playlists.value.size),
            )
            if (existing != null) dao.deleteSongsForPlaylist(existing.id)
            dao.insertSongs(imported.songs.mapIndexed { index, song -> song.toEntity(imported.id, index) })
            _playlists.value = if (existing == null) {
                _playlists.value + imported
            } else {
                _playlists.value.map { if (it.id == existing.id) imported else it }
            }
            imported
        }
    }

    /**
     * 导入/还原系统「我喜欢的音乐」歌单，支持本地曲与在线曲共同落库。
     */
    suspend fun importFavoritesPlaylist(
        localSongs: List<AudioFile>,
        onlineSongs: List<UserPlaylistSong> = emptyList(),
        overwrite: Boolean = false,
    ) = withDatabase {
        mutationMutex.withLock {
            val favorites = _playlists.value.firstOrNull { it.isFavorites } ?: return@withLock
            val baseSongs = if (overwrite) emptyList() else favorites.songs
            val incoming = filterUniqueAdditions(
                existing = baseSongs,
                candidates = localSongs.map { it.toUserPlaylistSong() } + onlineSongs,
            )
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = baseSongs + incoming
            val now = System.currentTimeMillis()
            dao.deleteSongsForPlaylist(favorites.id)
            dao.insertSongs(finalSongs.mapIndexed { index, song -> song.toEntity(favorites.id, index) })
            dao.touchPlaylist(favorites.id, now)
            val updated = favorites.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == favorites.id) updated else it }
        }
    }

    /**
     * 导入/还原系统「我不喜欢的音乐」歌单，支持本地曲与在线曲共同落库。
     */
    suspend fun importDislikedPlaylist(
        localSongs: List<AudioFile>,
        onlineSongs: List<UserPlaylistSong> = emptyList(),
        overwrite: Boolean = false,
    ) = withDatabase {
        mutationMutex.withLock {
            val disliked = _playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID }
                ?: createDislikedPlaylist().also { created ->
                    dao.upsertPlaylist(created.toEntity(_playlists.value.size))
                    _playlists.value = _playlists.value + created
                }
            val baseSongs = if (overwrite) emptyList() else disliked.songs
            val incoming = filterUniqueAdditions(
                existing = baseSongs,
                candidates = localSongs.map { it.toUserPlaylistSong() } + onlineSongs,
            )
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = baseSongs + incoming
            val now = System.currentTimeMillis()
            dao.deleteSongsForPlaylist(disliked.id)
            dao.insertSongs(finalSongs.mapIndexed { index, song -> song.toEntity(disliked.id, index) })
            dao.touchPlaylist(disliked.id, now)
            val updated = disliked.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == disliked.id) updated else it }
        }
    }

    /**
     * 严格保序导入/合并普通歌单（云同步还原与混合歌单专用）。
     *
     * 接收调用方已经按原始顺序转换好的 `List<UserPlaylistSong>`，不再拆分本地与在线列表，
     * 彻底解决混合歌单同步后本地全部排前、在线全部排后的顺序混乱缺陷。
     */
    suspend fun importResolvedPlaylistPreservingOrder(
        name: String,
        songs: List<UserPlaylistSong>,
        coverUrl: String? = null,
        overwrite: Boolean = false,
    ): UserPlaylist? = withDatabase {
        mutationMutex.withLock {
            val baseName = name.trim().ifBlank { DEFAULT_IMPORT_PLAYLIST_NAME }
            val now = System.currentTimeMillis()
            val existing = _playlists.value.firstOrNull {
                !it.isFavorites && it.name.equals(baseName, ignoreCase = true)
            }
            val baseSongs = if (overwrite) emptyList() else existing.orEmptySongs()
            val incoming = filterUniqueAdditions(existing = baseSongs, candidates = songs)
            if (incoming.isEmpty() && existing != null && !overwrite) return@withLock existing

            val importedSongs = baseSongs + incoming
            val targetCover = coverUrl ?: existing?.coverUrl ?: getPlaylistCoverUrl(existing?.id.orEmpty())
                ?: incoming.firstOrNull { it.albumArtPath.startsWith("http://", true) || it.albumArtPath.startsWith("https://", true) }?.albumArtPath
            val imported = UserPlaylist(
                id = existing?.id ?: "playlist-${UUID.randomUUID()}",
                name = existing?.name ?: baseName,
                songs = importedSongs,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                coverUrl = targetCover,
            )
            targetCover?.let { setPlaylistCoverUrl(imported.id, it) }
            dao.upsertPlaylist(
                imported.toEntity(existing?.let(_playlists.value::indexOf) ?: _playlists.value.size),
            )
            if (existing != null) dao.deleteSongsForPlaylist(existing.id)
            dao.insertSongs(imported.songs.mapIndexed { index, song -> song.toEntity(imported.id, index) })
            _playlists.value = if (existing == null) {
                _playlists.value + imported
            } else {
                _playlists.value.map { if (it.id == existing.id) imported else it }
            }
            imported
        }
    }

    /**
     * 严格保序导入/还原系统「我喜欢的音乐」歌单（云同步专用）。
     */
    suspend fun importFavoritesPlaylistPreservingOrder(
        songs: List<UserPlaylistSong>,
        overwrite: Boolean = false,
    ) = withDatabase {
        mutationMutex.withLock {
            val favorites = _playlists.value.firstOrNull { it.isFavorites } ?: return@withLock
            val baseSongs = if (overwrite) emptyList() else favorites.songs
            val incoming = filterUniqueAdditions(existing = baseSongs, candidates = songs)
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = baseSongs + incoming
            val now = System.currentTimeMillis()
            dao.deleteSongsForPlaylist(favorites.id)
            dao.insertSongs(finalSongs.mapIndexed { index, song -> song.toEntity(favorites.id, index) })
            dao.touchPlaylist(favorites.id, now)
            val updated = favorites.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == favorites.id) updated else it }
        }
    }

    /**
     * 严格保序导入/还原系统「我不喜欢的音乐」歌单（云同步专用）。
     */
    suspend fun importDislikedPlaylistPreservingOrder(
        songs: List<UserPlaylistSong>,
        overwrite: Boolean = false,
    ) = withDatabase {
        mutationMutex.withLock {
            val disliked = _playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID }
                ?: createDislikedPlaylist().also { created ->
                    dao.upsertPlaylist(created.toEntity(_playlists.value.size))
                    _playlists.value = _playlists.value + created
                }
            val baseSongs = if (overwrite) emptyList() else disliked.songs
            val incoming = filterUniqueAdditions(existing = baseSongs, candidates = songs)
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = baseSongs + incoming
            val now = System.currentTimeMillis()
            dao.deleteSongsForPlaylist(disliked.id)
            dao.insertSongs(finalSongs.mapIndexed { index, song -> song.toEntity(disliked.id, index) })
            dao.touchPlaylist(disliked.id, now)
            val updated = disliked.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == disliked.id) updated else it }
        }
    }

    /**
     * 将单曲添加至「我不喜欢的音乐」系统歌单。
     */
    suspend fun addSongToDisliked(song: AudioFile) = withDatabase {
        mutationMutex.withLock {
            val disliked = _playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID }
                ?: createDislikedPlaylist().also { created ->
                    dao.upsertPlaylist(created.toEntity(_playlists.value.size))
                    _playlists.value = _playlists.value + created
                }
            val songRow = song.toUserPlaylistSong()
            if (disliked.songs.any { isIdenticalPlaylistSong(it, songRow) }) return@withLock
            val finalSongs = disliked.songs + songRow
            val now = System.currentTimeMillis()
            dao.insertSongs(listOf(songRow.toEntity(disliked.id, disliked.songs.size)))
            dao.touchPlaylist(disliked.id, now)
            val updated = disliked.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == disliked.id) updated else it }
        }
    }

    /**
     * 将单曲从「我不喜欢的音乐」系统歌单移除。
     */
    suspend fun removeSongFromDisliked(songKey: String) = withDatabase {
        mutationMutex.withLock {
            val disliked = _playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID } ?: return@withLock
            if (disliked.songs.none { it.key == songKey }) return@withLock
            val finalSongs = disliked.songs.filterNot { it.key == songKey }
            val now = System.currentTimeMillis()
            dao.deleteSongsForPlaylist(disliked.id)
            dao.insertSongs(finalSongs.mapIndexed { index, s -> s.toEntity(disliked.id, index) })
            dao.touchPlaylist(disliked.id, now)
            val updated = disliked.copy(songs = finalSongs, updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == disliked.id) updated else it }
        }
    }

    /**
     * 清空「我不喜欢的音乐」系统歌单。
     */
    suspend fun clearDislikedPlaylist() = withDatabase {
        mutationMutex.withLock {
            val disliked = _playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID } ?: return@withLock
            dao.deleteSongsForPlaylist(disliked.id)
            val now = System.currentTimeMillis()
            dao.touchPlaylist(disliked.id, now)
            val updated = disliked.copy(songs = emptyList(), updatedAt = now)
            _playlists.value = _playlists.value.map { if (it.id == disliked.id) updated else it }
        }
    }

    suspend fun importTextPlaylist(uri: Uri, librarySongs: List<AudioFile>): PlaylistImportResult =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext PlaylistImportResult(null, 0, 0)
            val requestedPaths = decodeText(bytes)
                .lineSequence()
                .mapNotNull(::parsePlaylistLine)
                .distinctBy { it.normalizedPath() }
                .toList()

            val matched = matchLibrarySongs(requestedPaths, librarySongs)

            withDatabase {
                mutationMutex.withLock {
                    val baseName = displayName(uri).substringBeforeLast('.').trim()
                        .ifBlank { "Imported playlist" }
                    val now = System.currentTimeMillis()
                    val existing = _playlists.value.firstOrNull {
                        !it.isFavorites && it.name.equals(baseName, ignoreCase = true)
                    }
                    val baseSongs = existing.orEmptySongs()
                    val importedSongs = baseSongs +
                        filterUniqueAdditions(baseSongs, matched.map { it.toUserPlaylistSong() })
                    val imported = UserPlaylist(
                        id = existing?.id ?: "playlist-${UUID.randomUUID()}",
                        name = existing?.name ?: baseName,
                        songs = importedSongs,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now
                    )
                    dao.upsertPlaylist(imported.toEntity(existing?.let(_playlists.value::indexOf) ?: _playlists.value.size))
                    if (existing != null) dao.deleteSongsForPlaylist(existing.id)
                    dao.insertSongs(imported.songs.mapIndexed { index, song ->
                        song.toEntity(imported.id, index)
                    })
                    _playlists.value = if (existing == null) {
                        _playlists.value + imported
                    } else {
                        _playlists.value.map { if (it.id == existing.id) imported else it }
                    }
                    PlaylistImportResult(
                        imported,
                        matched.size,
                        (requestedPaths.size - matched.size).coerceAtLeast(0)
                    )
                }
            }
        }

    /**
     * 检查当前在线歌曲是否命中了下载管理器中已完成的物理文件任务。
     */
    private fun findDownloadedTaskMatch(
        onlineAudioFile: AudioFile,
        completedTasks: List<com.rawsmusic.module.data.source.playback.MusicSourceDownloadTask>? = null
    ): AudioFile? {
        val tasks = completedTasks ?: MusicSourceDownloadController.tasks.value.filter {
            it.status == MusicSourceDownloadStatus.Completed && it.localUri.isNotBlank()
        }
        if (tasks.isEmpty()) return null

        val targetTitle = onlineAudioFile.title
        val targetArtist = onlineAudioFile.artist
        val targetRemoteId = onlineAudioFile.path.removePrefix("online://")

        for (task in tasks) {
            val file = runCatching { Uri.parse(task.localUri) }.getOrNull()
                ?.path?.let(::File)
                ?: File(MusicSourceDownloadController.publicDownloadDirectory(), task.fileName)
            if (!MusicSourceDownloadController.isValidLocalAudioFile(file)) continue

            val taskTitle = task.item.title
            val taskArtist = task.item.artists.joinToString(" & ")
            val isIdentityMatch = task.item.remoteId == targetRemoteId || task.item.stableIdentity == targetRemoteId
            val isNameMatch = SongMatcher.isMatched(
                targetTitle = targetTitle,
                targetArtist = targetArtist,
                candidateTitle = taskTitle,
                candidateArtist = taskArtist,
                targetDurationMs = onlineAudioFile.duration,
                candidateDurationMs = 0L,
                toleranceMs = 30000L
            )

            if (isIdentityMatch || isNameMatch) {
                val ext = file.extension.uppercase(Locale.ROOT)
                val formatStr = when (ext) {
                    "FLAC" -> "FLAC"
                    "WAV" -> "WAV"
                    "MP3" -> "MP3"
                    "M4A", "AAC" -> "AAC"
                    else -> ext
                }
                val (bitrate, sampleRate, bits) = when (task.resolvedQuality) {
                    RawSourceQuality.HiRes -> Triple(9216, 192000, 24)
                    RawSourceQuality.Lossless -> Triple(1411, 44100, 16)
                    RawSourceQuality.High -> Triple(320, 44100, 16)
                    else -> Triple(128, 44100, 16)
                }
                return onlineAudioFile.copy(
                    id = file.absolutePath.hashCode().toLong(),
                    path = file.absolutePath,
                    title = if (onlineAudioFile.title.isNotBlank()) onlineAudioFile.title else taskTitle,
                    artist = if (onlineAudioFile.artist.isNotBlank()) onlineAudioFile.artist else taskArtist,
                    album = onlineAudioFile.album.ifBlank { task.item.album },
                    albumArtist = onlineAudioFile.albumArtist.ifBlank { taskArtist },
                    format = formatStr,
                    encodingFormat = formatStr,
                    fileSize = file.length(),
                    dateModified = file.lastModified(),
                    bitRate = bitrate,
                    sampleRate = sampleRate,
                    bitsPerSample = bits,
                )
            }
        }
        return null
    }

    /**
     * 在公共离线保存目录中探活匹配的音频文件（覆盖常见格式与艺名改名容错）。
     * [downloadedFilesSnapshot] 为预索引的文件名集合（全小写），可完全避免几千次高开销的系统磁盘 File.exists() 调用。
     */
    private fun probeDownloadedFile(
        onlineAudioFile: AudioFile,
        downloadedFilesSnapshot: Set<String>? = null
    ): AudioFile? {
        val dir = MusicSourceDownloadController.publicDownloadDirectory()
        val fileSnapshot = downloadedFilesSnapshot ?: run {
            if (dir.exists() && dir.isDirectory) {
                dir.list()?.map { it.lowercase(Locale.ROOT) }?.toSet()
            } else {
                null
            }
        }
        if (fileSnapshot == null || fileSnapshot.isEmpty()) return null

        val cleanT = SongMatcher.cleanText(onlineAudioFile.title)
        if (cleanT.isBlank()) return null
        val cleanA = SongMatcher.cleanText(onlineAudioFile.artist)
        val normA = SongMatcher.normalizeArtistAlias(cleanA)

        val extensions = listOf("flac", "wav", "mp3", "m4a", "aac", "ogg")
        val candidatePrefixes = buildList {
            if (cleanA.isNotBlank()) {
                add("$cleanA - $cleanT")
                if (normA != cleanA) add("$normA - $cleanT")
            }
            add(cleanT)
        }

        for (prefix in candidatePrefixes) {
            val sanitized = MusicSourceDownloadController.sanitizeFileName(prefix)
            for (ext in extensions) {
                val candidateName = "$sanitized.$ext".lowercase(Locale.ROOT)
                // 核心优化：内存 Set 快速比对，命中后再构造 File，避免成千上万次磁盘 I/O 系统调用
                if (candidateName in fileSnapshot) {
                    val candidateFile = File(dir, "$sanitized.$ext")
                    if (MusicSourceDownloadController.isValidLocalAudioFile(candidateFile)) {
                        val formatStr = ext.uppercase(Locale.ROOT)
                        return onlineAudioFile.copy(
                            id = candidateFile.absolutePath.hashCode().toLong(),
                            path = candidateFile.absolutePath,
                            format = formatStr,
                            encodingFormat = formatStr,
                            fileSize = candidateFile.length(),
                            dateModified = candidateFile.lastModified(),
                            bitRate = if (ext == "flac" || ext == "wav") 1411 else 320,
                            sampleRate = 44100,
                            bitsPerSample = if (ext == "flac" || ext == "wav") 16 else 0,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun UserPlaylist?.orEmptySongs(): List<UserPlaylistSong> = this?.songs.orEmpty()

    fun exportJson(): JSONObject = JSONObject().put(
        "playlists",
        JSONArray().also { array -> _playlists.value.forEach { array.put(playlistToJson(it)) } }
    )

    /** Wait for the Room-backed store to finish initial hydration before taking a backup snapshot. */
    suspend fun exportJsonForBackup(): JSONObject = withDatabase {
        JSONObject().put(
            "playlists",
            JSONArray().also { array -> _playlists.value.forEach { array.put(playlistToJson(it)) } },
        )
    }

    suspend fun restoreJson(json: JSONObject) {
        withDatabase { restoreJsonLocked(json, verify = false) }
    }

    /** Restore and read the Room snapshot back before reporting backup-import success. */
    suspend fun restoreJsonAndVerify(json: JSONObject): Boolean = withDatabase {
        restoreJsonLocked(json, verify = true)
    }

    private suspend fun restoreJsonLocked(json: JSONObject, verify: Boolean): Boolean {
        val array = json.optJSONArray("playlists") ?: return false
        val restored = buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
            }
        }.let(::ensureFavorites)
        return mutationMutex.withLock {
            replaceRoom(restored)
            deleteLegacyFiles()
            _playlists.value = restored
            !verify || loadRoomSnapshot() == restored
        }
    }

    private suspend fun <T> withDatabase(block: suspend () -> T): T =
        withContext(databaseDispatcher) {
            ready.await()
            BackupWriteGate.withExclusive { block() }
        }

    suspend fun awaitBackupReady() = ready.await()

    /** Caller holds BackupWriteGate; do not reacquire it when refreshing verified persistent data. */
    suspend fun reloadAfterBackup() = withContext(databaseDispatcher) {
        ready.await()
        mutationMutex.withLock { _playlists.value = loadRoomSnapshot() }
    }

    /** Caller holds BackupWriteGate, allowing a consistent snapshot across both durable stores. */
    suspend fun exportJsonUnderBackupGate(): JSONObject = withContext(databaseDispatcher) {
        ready.await()
        JSONObject().put("playlists", JSONArray().also { array ->
            loadRoomSnapshot().forEach { array.put(playlistToJson(it)) }
        })
    }

    private suspend fun replaceRoom(playlists: List<UserPlaylist>) {
        dao.replaceAll(
            playlists = playlists.mapIndexed { index, playlist -> playlist.toEntity(index) },
            songs = playlists.flatMap { playlist ->
                playlist.songs.mapIndexed { index, song -> song.toEntity(playlist.id, index) }
            }
        )
    }

    private suspend fun loadRoomSnapshot(): List<UserPlaylist> {
        val songsByPlaylist = dao.getSongs()
            .groupBy(UserPlaylistSongEntity::playlistId)
            .mapValues { (_, songs) -> songs.map(UserPlaylistSongEntity::toModel) }
        return ensureFavorites(
            dao.getPlaylists().map { playlist ->
                val rawSongs = songsByPlaylist[playlist.playlistId].orEmpty()
                val deduped = deduplicateStoredPlaylistSongs(rawSongs)
                if (deduped.size < rawSongs.size) {
                    dao.deleteSongsForPlaylist(playlist.playlistId)
                    dao.insertSongs(deduped.mapIndexed { index, song -> song.toEntity(playlist.playlistId, index) })
                }
                UserPlaylist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    songs = deduped,
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt,
                    coverUrl = getPlaylistCoverUrl(playlist.playlistId),
                )
            }
        )
    }

    private fun loadLegacyPlaylists(): List<UserPlaylist>? {
        val source = when {
            storeFile.exists() -> storeFile
            backupFile.exists() -> backupFile
            else -> return null
        }
        return runCatching {
            val array = JSONObject(source.readText(Charsets.UTF_8))
                .optJSONArray("playlists") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(jsonToPlaylist(it)) }
                }
            }.let(::ensureFavorites)
        }.getOrNull()
    }

    private fun deleteLegacyFiles() {
        storeFile.delete()
        backupFile.delete()
        File(storeFile.parentFile, "${storeFile.name}.tmp").delete()
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
    }.getOrNull().orEmpty()

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16LE"))
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16BE"))
        else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    private fun parsePlaylistLine(raw: String): String? {
        val line = raw.trim().trim('"')
        if (line.isBlank() || line.startsWith('#')) return null
        val candidate = line.substringAfterLast('\t').substringAfterLast('|').trim().trim('"')
        return runCatching {
            if (candidate.startsWith("file://", true)) {
                URLDecoder.decode(Uri.parse(candidate).path.orEmpty(), Charsets.UTF_8.name())
            } else {
                candidate
            }
        }.getOrDefault(candidate).replace('\\', '/')
    }

    private fun String.normalizedPath(): String = trim().replace('\\', '/').trimEnd('/').lowercase()

    private fun String.pathCandidates(): Set<String> {
        val normalized = normalizedPath().removePrefix("file://").trimStart('/')
        if (normalized.isBlank()) return emptySet()
        val result = linkedSetOf(normalized, "/$normalized")
        val relative = when {
            normalized.startsWith("primary/") -> normalized.removePrefix("primary/")
            normalized.startsWith("storage/emulated/0/") -> normalized.removePrefix("storage/emulated/0/")
            normalized.startsWith("sdcard/") -> normalized.removePrefix("sdcard/")
            else -> null
        }
        if (relative != null) {
            result += relative
            result += "primary/$relative"
            result += "/storage/emulated/0/$relative"
            result += "/sdcard/$relative"
        }
        return result
    }

    private fun createFavoritesPlaylist(): UserPlaylist {
        val now = System.currentTimeMillis()
        return UserPlaylist(FAVORITES_PLAYLIST_ID, "我喜欢的音乐", emptyList(), now, now)
    }

    private fun createDislikedPlaylist(): UserPlaylist {
        val now = System.currentTimeMillis()
        return UserPlaylist(DISLIKED_PLAYLIST_ID, "我不喜欢的音乐", emptyList(), now, now)
    }

    private fun ensureFavorites(playlists: List<UserPlaylist>): List<UserPlaylist> {
        val withFav = if (playlists.any { it.isFavorites }) playlists else listOf(createFavoritesPlaylist()) + playlists
        return if (withFav.any { it.isDisliked }) withFav else withFav + createDislikedPlaylist()
    }

    private fun playlistToJson(playlist: UserPlaylist): JSONObject = JSONObject()
        .put("id", playlist.id)
        .put("name", playlist.name)
        .put("createdAt", playlist.createdAt)
        .put("updatedAt", playlist.updatedAt)
        .put("coverUrl", playlist.coverUrl.orEmpty())
        .put("songs", JSONArray().also { songs ->
            playlist.songs.forEach { song ->
                songs.put(JSONObject()
                    .put("key", song.key).put("id", song.id).put("title", song.title)
                    .put("artist", song.artist).put("album", song.album).put("albumId", song.albumId)
                    .put("duration", song.duration).put("path", song.path).put("fileSize", song.fileSize)
                    .put("format", song.format).put("sampleRate", song.sampleRate).put("bitRate", song.bitRate)
                    .put("bitsPerSample", song.bitsPerSample).put("albumArtPath", song.albumArtPath)
                    .put("addedAt", song.addedAt))
            }
        })

    private fun jsonToPlaylist(json: JSONObject): UserPlaylist {
        val array = json.optJSONArray("songs") ?: JSONArray()
        val songs = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(UserPlaylistSong(
                    key = item.optString("key"), id = item.optLong("id"), title = item.optString("title"),
                    artist = item.optString("artist"), album = item.optString("album"),
                    albumId = item.optLong("albumId", -1), duration = item.optLong("duration"),
                    path = item.optString("path"), fileSize = item.optLong("fileSize"),
                    format = item.optString("format"), sampleRate = item.optInt("sampleRate"),
                    bitRate = item.optInt("bitRate"), bitsPerSample = item.optInt("bitsPerSample"),
                    albumArtPath = item.optString("albumArtPath"), addedAt = item.optLong("addedAt")
                ))
            }
        }
        val id = json.optString("id").ifBlank { "playlist-${UUID.randomUUID()}" }
        val coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() }
        if (!coverUrl.isNullOrBlank()) {
            setPlaylistCoverUrl(id, coverUrl)
        }
        return UserPlaylist(
            id = id,
            name = json.optString("name"),
            songs = songs,
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
            coverUrl = coverUrl ?: getPlaylistCoverUrl(id),
        )
    }
}

private fun UserPlaylist.toEntity(sortOrder: Int) = UserPlaylistEntity(
    playlistId = id,
    name = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortOrder = sortOrder
)

private fun UserPlaylistSong.toEntity(playlistId: String, sortOrder: Int) = UserPlaylistSongEntity(
    playlistId = playlistId,
    songKey = key,
    mediaId = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    path = path,
    fileSize = fileSize,
    format = format,
    sampleRate = sampleRate,
    bitRate = bitRate,
    bitsPerSample = bitsPerSample,
    albumArtPath = albumArtPath,
    addedAt = addedAt,
    sortOrder = sortOrder
)

private fun UserPlaylistSongEntity.toModel() = UserPlaylistSong(
    key = songKey,
    id = mediaId,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    path = path,
    fileSize = fileSize,
    format = format,
    sampleRate = sampleRate,
    bitRate = bitRate,
    bitsPerSample = bitsPerSample,
    albumArtPath = albumArtPath,
    addedAt = addedAt
)
