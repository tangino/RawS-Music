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
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.core.common.utils.SongMatcher
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.UUID
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

/** 导入时没有可用名字的兜底歌单名。与 `importTextPlaylist` 里既有的兜底保持一致。 */
private const val DEFAULT_IMPORT_PLAYLIST_NAME = "Imported playlist"

class PlaylistStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: PlaylistStore? = null

        fun getInstance(context: Context): PlaylistStore =
            instance ?: synchronized(this) {
                instance ?: PlaylistStore(context.applicationContext).also { instance = it }
            }
    }

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

    // 内存 LRU 映射缓存：将 online 歌曲 Key 映射到匹配成功的本地 AudioFile，避免重复遍历曲库进行高频计算
    private val onlineToLocalMatchCache = android.util.LruCache<String, AudioFile>(500)

    fun invalidateMatchCache() {
        onlineToLocalMatchCache.evictAll()
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

    fun isFavorite(song: AudioFile): Boolean = song.playlistIdentityKey() in favoriteSongKeys()

    suspend fun toggleFavorite(song: AudioFile): Boolean = withDatabase {
        mutationMutex.withLock {
            val favorite = _playlists.value.firstOrNull { it.isFavorites } ?: return@withLock false
            val key = song.playlistIdentityKey()
            val removing = favorite.songs.any { it.key == key }
            val now = System.currentTimeMillis()
            val songs = if (removing) favorite.songs.filterNot { it.key == key }
                else favorite.songs + song.toUserPlaylistSong()
            MusicDatabase.getInstance(context).withTransaction {
                if (removing) dao.deleteSong(favorite.id, key)
                else dao.insertSongs(listOf(songs.last().toEntity(favorite.id, favorite.songs.size)))
                dao.touchPlaylist(favorite.id, now)
            }
            _playlists.value = _playlists.value.map {
                if (it.id == favorite.id) it.copy(songs = songs, updatedAt = now) else it
            }
            !removing
        }
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
            val keys = playlist.songs.mapTo(mutableSetOf()) { it.key }
            val additions = songs.filter { keys.add(it.key) }
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

    /** Resolves stored identities against the live library so edited tags and artwork stay current. */
    fun resolveSongs(playlist: UserPlaylist?, librarySongs: List<AudioFile>): List<AudioFile> {
        playlist ?: return emptyList()
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val byPath = buildMap<String, AudioFile> {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }
        return playlist.songs.mapNotNull { stored ->
            byIdentity[stored.key]
                ?: stored.path.pathCandidates().firstNotNullOfOrNull(byPath::get)
                ?: stored.toAudioFile().takeIf { File(it.path).exists() }
        }
    }

    /**
     * 一个歌单里的**在线曲目**（P2-05b）。
     */
    fun onlineSongsOf(playlist: UserPlaylist?): List<UserPlaylistSong> =
        playlist?.songs?.filter { it.isOnlineEntry() }.orEmpty()

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
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val byPath = buildMap<String, AudioFile> {
            librarySongs.forEach { song -> song.path.pathCandidates().forEach { putIfAbsent(it, song) } }
        }

        // 仅在有在线歌曲时才构建局部索引，避免无意义的开销
        val hasOnline = playlist.songs.any { it.isOnlineEntry() }
        val localSongIndex = if (hasOnline && librarySongs.isNotEmpty()) {
            SongMatcher.buildIndex(librarySongs)
        } else {
            null
        }

        // 本地音源优先原则：无论歌单是否为混合歌单，在线条目若在本地曲库中有对应音频，
        // 一律优先使用本地音源播放，实现 0 延迟秒开，避免网络寻源开销与超时；
        // 本地条目若因换机或路径微调未命中原路径，自动通过智能匹配器自愈找回。
        return playlist.songs.mapNotNull { stored ->
            if (stored.isOnlineEntry()) {
                val cacheKey = stored.key
                val cached = onlineToLocalMatchCache.get(cacheKey)
                if (cached != null) {
                    if (cached.path.startsWith("online://")) {
                        // 负缓存命中：此前已确认无本地匹配，直接返回在线音频，0ms 秒开
                        cached
                    } else if (File(cached.path).exists()) {
                        // 正缓存命中：本地匹配文件依然存在，直接返回
                        cached
                    } else {
                        // 文件已被移动或删除，重新尝试匹配
                        val onlineAudioFile = stored.toAudioFile()
                        val matchedLocal = SongMatcher.findBestLocalMatch(onlineAudioFile, librarySongs, localSongIndex)
                        val result = matchedLocal ?: onlineAudioFile
                        onlineToLocalMatchCache.put(cacheKey, result)
                        result
                    }
                } else {
                    val onlineAudioFile = stored.toAudioFile()
                    val matchedLocal = SongMatcher.findBestLocalMatch(onlineAudioFile, librarySongs, localSongIndex)
                    val result = matchedLocal ?: onlineAudioFile
                    // 无论命中与否均写入缓存（未命中则缓存 onlineAudioFile 本身作为负缓存），阻断重复扫描
                    onlineToLocalMatchCache.put(cacheKey, result)
                    result
                }
            } else {
                val matched = byIdentity[stored.key]
                    ?: stored.path.pathCandidates().firstNotNullOfOrNull(byPath::get)
                    ?: stored.toAudioFile().takeIf { File(it.path).exists() }
                    ?: SongMatcher.findBestLocalMatch(stored.toAudioFile(), librarySongs, localSongIndex)
                if (matched != null) {
                    matched
                } else {
                    // 本地物理音频文件在当前设备不存在（例如云同步还原后尚未下载本地音频）：
                    // 具备混合歌单特征：不丢弃歌曲条目，平滑降级为可在线寻源试听的网络虚拟歌曲（保留完整元数据）
                    val virtualId = if (stored.id > 0) stored.id.toString() else "${stored.title}_${stored.artist}".hashCode().toString()
                    stored.toAudioFile().copy(
                        path = "online://netease/$virtualId"
                    )
                }
            }
        }
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
            val existingKeys = if (overwrite) mutableSetOf() else (existing?.songs?.mapTo(mutableSetOf()) { it.key } ?: mutableSetOf())
            val incoming = (localSongs.map { it.toUserPlaylistSong() } + onlineSongs)
                .filter { existingKeys.add(it.key) }
            if (incoming.isEmpty() && existing != null && !overwrite) return@withLock existing

            val importedSongs = if (overwrite) incoming else (existing.orEmptySongs() + incoming)
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
            val existingKeys = if (overwrite) mutableSetOf() else favorites.songs.mapTo(mutableSetOf()) { it.key }
            val incoming = (localSongs.map { it.toUserPlaylistSong() } + onlineSongs)
                .filter { existingKeys.add(it.key) }
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = if (overwrite) incoming else (favorites.songs + incoming)
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
            val existingKeys = if (overwrite) mutableSetOf() else disliked.songs.mapTo(mutableSetOf()) { it.key }
            val incoming = (localSongs.map { it.toUserPlaylistSong() } + onlineSongs)
                .filter { existingKeys.add(it.key) }
            if (incoming.isEmpty() && !overwrite) return@withLock

            val finalSongs = if (overwrite) incoming else (disliked.songs + incoming)
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
            if (disliked.songs.any { it.key == songRow.key }) return@withLock
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
                    val existingKeys = existing?.songs?.mapTo(mutableSetOf()) { it.key } ?: mutableSetOf()
                    val importedSongs = existing.orEmptySongs() +
                        matched.map { it.toUserPlaylistSong() }.filter { existingKeys.add(it.key) }
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
                UserPlaylist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    songs = songsByPlaylist[playlist.playlistId].orEmpty(),
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
