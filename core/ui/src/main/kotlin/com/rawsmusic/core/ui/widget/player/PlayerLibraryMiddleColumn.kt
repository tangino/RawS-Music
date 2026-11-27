package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.DISLIKED_PLAYLIST_ID
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.queueIdentity
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.common.utils.SongMatcher
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.pages.appFontFamily
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Playlist
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 大屏横屏播放页中间栏状态控制器（持久化展开/折叠状态、列表封面显隐开关、以及双栏宽屏扩展状态）。
 */
@Stable
class PlayerMiddleColumnState internal constructor(
    initialExpanded: Boolean,
    initialShowCovers: Boolean,
    initialWideMode: Boolean,
) {
    var expanded by mutableStateOf(initialExpanded)
        private set

    var showCovers by mutableStateOf(initialShowCovers)
        private set

    var wideMode by mutableStateOf(initialWideMode)
        private set

    fun updateExpanded(value: Boolean) {
        expanded = value
        AppPreferences.UI.landscapePlayerMiddleColumnExpanded = value
        if (!value && wideMode) {
            updateWideMode(false)
        }
    }

    fun toggleExpanded() {
        updateExpanded(!expanded)
    }

    fun toggleShowCovers() {
        val next = !showCovers
        showCovers = next
        AppPreferences.UI.landscapePlayerMiddleColumnShowCovers = next
    }

    fun updateWideMode(value: Boolean) {
        wideMode = value
        AppPreferences.UI.landscapePlayerMiddleColumnWideMode = value
    }

    fun toggleWideMode() {
        updateWideMode(!wideMode)
    }
}

@Composable
fun rememberPlayerMiddleColumnState(): PlayerMiddleColumnState {
    return remember {
        PlayerMiddleColumnState(
            initialExpanded = AppPreferences.UI.landscapePlayerMiddleColumnExpanded,
            initialShowCovers = AppPreferences.UI.landscapePlayerMiddleColumnShowCovers,
            initialWideMode = AppPreferences.UI.landscapePlayerMiddleColumnWideMode,
        )
    }
}

private enum class MiddleLibraryTab {
    QUEUE,
    SONGS,
    PLAYLISTS,
    FOLDERS,
    ARTISTS,
    ALBUMS,
}

private sealed interface MiddleDrillDownTarget {
    val title: String
    data class LocalPlaylist(val playlistId: String, override val title: String) : MiddleDrillDownTarget
    data class OnlinePlaylist(val entity: OnlinePlaylistFavoriteEntity, override val title: String) : MiddleDrillDownTarget
    data class FolderTarget(val folderPath: String, override val title: String) : MiddleDrillDownTarget
    data class ArtistTarget(val artistName: String, override val title: String) : MiddleDrillDownTarget
    data class AlbumTarget(val albumName: String, override val title: String) : MiddleDrillDownTarget
}

/**
 * 大屏幕横屏播放页中间栏：支持切换「当前队列 / 歌曲 / 歌单 / 文件夹 / 歌手 / 专辑」、
 * 二级详情钻取、列表内搜索过滤、以及切换歌曲列表是否显示封面。
 * 采用与 InlinePlayerQueue 一致的无边框通透视觉风格，自然融入播放页流光背景。
 */
@Composable
fun PlayerLibraryMiddleColumn(
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    showCovers: Boolean,
    onToggleShowCovers: () -> Unit,
    wideMode: Boolean = false,
    onToggleWideMode: () -> Unit = {},
    onCollapse: () -> Unit,
    onQueueSongClick: (AudioFile, Int) -> Unit,
    onPlayLibraryQueue: (List<AudioFile>, Int) -> Unit,
    onAppendLibraryQueue: ((List<AudioFile>) -> Unit)? = null,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    accentColor: Color,
    chipBackgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontFamily = appFontFamily()
    val coroutineScope = rememberCoroutineScope()

    val playlistStore = remember(context) { PlaylistStore.getInstance(context) }
    val allLibrarySongs by MusicRepository.songs.collectAsState()
    val repoFolders by MusicRepository.folders.collectAsState()
    val repoArtists by MusicRepository.artists.collectAsState()
    val repoAlbums by MusicRepository.albums.collectAsState()
    val userPlaylists by playlistStore.playlists.collectAsState()
    val onlineFavorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()
    val currentPlayedPlaylist by com.rawsmusic.core.common.prefs.PlayedPlaylistManager.currentPlaylist.collectAsState()

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) {
            runCatching { OnlinePlaylistFavoriteStore.refresh(context) }
            if (MusicRepository.songs.value.isEmpty()) {
                runCatching { MusicRepository.refreshAllSuspend() }
            } else if (
                MusicRepository.folders.value.isEmpty() &&
                MusicRepository.artists.value.isEmpty() &&
                MusicRepository.albums.value.isEmpty()
            ) {
                runCatching { MusicRepository.refreshLibraryIndexes(MusicRepository.songs.value) }
            }
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(MiddleLibraryTab.QUEUE) }
    var drillDownTarget by remember { mutableStateOf<MiddleDrillDownTarget?>(null) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 当播放器队列仅有 1 首歌曲且当前来源为在线歌单时，异步拉取所属在线歌单完整队列
    var currentPlayedOnlineSongs by remember { mutableStateOf<List<AudioFile>>(emptyList()) }
    LaunchedEffect(
        currentPlayedPlaylist?.id,
        currentPlayedPlaylist?.isOnline,
        currentPlayedPlaylist?.platformId,
        queueSongs.size,
        allLibrarySongs,
    ) {
        val entry = currentPlayedPlaylist
        if (queueSongs.size <= 1 && entry != null && entry.isOnline && entry.id.isNotBlank() &&
            entry.id != "daily_recommend" && entry.id != "guess_you_like"
        ) {
            val loaded = withContext(Dispatchers.IO) {
                val platform = OnlinePlatform.entries.firstOrNull {
                    it.id.equals(entry.platformId, ignoreCase = true)
                } ?: OnlinePlatform.NETEASE
                val detailResult = runCatching {
                    OnlineMusicRepository.getInstance().getPlaylistDetail(entry.id, platform)
                }.getOrNull()
                val items = detailResult?.getOrNull()?.second.orEmpty()
                val localIndex = if (allLibrarySongs.isNotEmpty()) {
                    SongMatcher.buildIndex(allLibrarySongs)
                } else {
                    null
                }
                items.map { item ->
                    val onlineAudio = OnlineSourceMediaMapper.toUserPlaylistSong(item).toAudioFile()
                    SongMatcher.findBestLocalMatch(onlineAudio, allLibrarySongs, localIndex) ?: onlineAudio
                }
            }
            currentPlayedOnlineSongs = loaded
        } else if (entry == null || !entry.isOnline) {
            currentPlayedOnlineSongs = emptyList()
        }
    }

    // 同队列补全：若从歌单进来则显示歌单队列，若从曲库进来则显示曲库队列
    val effectiveQueueSongs = remember(
        queueSongs,
        currentSong,
        currentPlayedPlaylist,
        userPlaylists,
        allLibrarySongs,
        currentPlayedOnlineSongs,
    ) {
        if (queueSongs.size > 1) {
            queueSongs
        } else {
            val entry = currentPlayedPlaylist
            val fallback = when {
                entry != null && !entry.isOnline -> {
                    val pl = userPlaylists.firstOrNull { it.id == entry.id }
                    playlistStore.resolveOrderedPlaylistSongs(pl, allLibrarySongs)
                }
                entry != null && entry.isOnline -> {
                    currentPlayedOnlineSongs
                }
                else -> {
                    if (
                        currentSong != null &&
                        allLibrarySongs.any {
                            it.id == currentSong.id ||
                                (currentSong.path.isNotBlank() && it.path == currentSong.path)
                        }
                    ) {
                        allLibrarySongs
                    } else {
                        emptyList()
                    }
                }
            }
            if (fallback.size > queueSongs.size) fallback else queueSongs
        }
    }

    // 在线歌单二级详情异步加载状态
    var onlinePlaylistLoading by remember { mutableStateOf(false) }
    var onlinePlaylistSongs by remember { mutableStateOf<List<AudioFile>>(emptyList()) }
    var onlinePlaylistRawItems by remember { mutableStateOf<List<OnlineSongItem>>(emptyList()) }

    LaunchedEffect(drillDownTarget, allLibrarySongs) {
        val target = drillDownTarget
        if (target is MiddleDrillDownTarget.OnlinePlaylist) {
            onlinePlaylistLoading = true
            val (rawItems, loaded) = withContext(Dispatchers.IO) {
                val platform = OnlinePlatform.entries.firstOrNull {
                    it.id.equals(target.entity.platform, ignoreCase = true)
                } ?: OnlinePlatform.NETEASE
                val detailResult = OnlineMusicRepository.getInstance()
                    .getPlaylistDetail(target.entity.remoteId, platform)
                val items = detailResult.getOrNull()?.second.orEmpty()
                val localIndex = if (allLibrarySongs.isNotEmpty()) {
                    SongMatcher.buildIndex(allLibrarySongs)
                } else {
                    null
                }
                val mapped = items.map { item ->
                    val onlineAudio = OnlineSourceMediaMapper.toUserPlaylistSong(item).toAudioFile()
                    SongMatcher.findBestLocalMatch(onlineAudio, allLibrarySongs, localIndex) ?: onlineAudio
                }
                items to mapped
            }
            onlinePlaylistRawItems = rawItems
            onlinePlaylistSongs = loaded
            onlinePlaylistLoading = false
        } else {
            onlinePlaylistRawItems = emptyList()
            onlinePlaylistSongs = emptyList()
            onlinePlaylistLoading = false
        }
    }

    BackHandler(enabled = drillDownTarget != null || searchVisible) {
        when {
            searchVisible && searchQuery.isNotEmpty() -> searchQuery = ""
            searchVisible -> searchVisible = false
            drillDownTarget != null -> drillDownTarget = null
        }
    }

    // 聚合兜底：若仓库索引尚未构建完，基于 allLibrarySongs 即时计算
    val folders = remember(repoFolders, allLibrarySongs) {
        repoFolders.ifEmpty {
            allLibrarySongs
                .filter { it.path.isNotBlank() && !it.path.startsWith("online://") }
                .groupBy { it.path.substringBeforeLast("/") }
                .map { (path, list) ->
                    Folder(
                        path = path,
                        name = path.substringAfterLast("/").ifBlank { path },
                        songCount = list.size,
                    )
                }
        }
    }

    val artists = remember(repoArtists, allLibrarySongs) {
        repoArtists.ifEmpty {
            allLibrarySongs
                .filter { it.artist.isNotBlank() }
                .groupBy { it.artist }
                .map { (name, list) ->
                    Artist(
                        name = name,
                        songCount = list.size,
                        albumCount = list.map { it.album }.distinct().size,
                        coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.coverKey.orEmpty(),
                    )
                }
        }
    }

    val albums = remember(repoAlbums, allLibrarySongs) {
        repoAlbums.ifEmpty {
            allLibrarySongs
                .filter { it.album.isNotBlank() }
                .groupBy { it.album }
                .map { (name, list) ->
                    Album(
                        name = name,
                        artist = list.first().artist,
                        songCount = list.size,
                        year = list.first().year,
                        coverPath = list.firstOrNull { it.albumArtPath.isNotBlank() }?.coverKey ?: list.first().coverKey,
                        hasHiRes = list.any { it.isHiRes },
                    )
                }
        }
    }

    // 解析当前展示的歌曲列表（当处于队列、全部歌曲、或点进某个分类详情时）
    val activeSongList: List<AudioFile>? = remember(
        selectedTab,
        drillDownTarget,
        effectiveQueueSongs,
        allLibrarySongs,
        userPlaylists,
        onlinePlaylistSongs,
    ) {
        val target = drillDownTarget
        if (target != null) {
            when (target) {
                is MiddleDrillDownTarget.LocalPlaylist -> {
                    val pl = userPlaylists.firstOrNull { it.id == target.playlistId }
                    playlistStore.resolveOrderedPlaylistSongs(pl, allLibrarySongs)
                }
                is MiddleDrillDownTarget.OnlinePlaylist -> onlinePlaylistSongs
                is MiddleDrillDownTarget.FolderTarget -> {
                    allLibrarySongs.filter { it.path.substringBeforeLast("/") == target.folderPath }
                        .ifEmpty { MusicRepository.getSongsByFolder(target.folderPath) }
                }
                is MiddleDrillDownTarget.ArtistTarget -> {
                    MusicRepository.getSongsByArtist(target.artistName)
                        .ifEmpty { allLibrarySongs.filter { it.artist.contains(target.artistName, ignoreCase = true) } }
                }
                is MiddleDrillDownTarget.AlbumTarget -> {
                    allLibrarySongs.filter { it.album == target.albumName }
                        .ifEmpty { MusicRepository.getSongsByAlbum(target.albumName) }
                }
            }
        } else {
            when (selectedTab) {
                MiddleLibraryTab.QUEUE -> effectiveQueueSongs
                MiddleLibraryTab.SONGS -> allLibrarySongs
                else -> null
            }
        }
    }

    val trimmedQuery = searchQuery.trim()
    val filteredSongList: List<Pair<Int, AudioFile>>? = remember(activeSongList, trimmedQuery) {
        activeSongList?.let { list ->
            if (trimmedQuery.isBlank()) {
                list.mapIndexed { index, song -> index to song }
            } else {
                list.mapIndexedNotNull { index, song ->
                    val matched = song.title.contains(trimmedQuery, ignoreCase = true) ||
                        song.displayName.contains(trimmedQuery, ignoreCase = true) ||
                        song.artist.contains(trimmedQuery, ignoreCase = true) ||
                        song.album.contains(trimmedQuery, ignoreCase = true)
                    if (matched) index to song else null
                }
            }
        }
    }

    val songListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val songGridState = rememberLazyGridState()
    val categoryGridState = rememberLazyGridState()

    val currentQueueIdentity = remember(currentSong) { currentSong?.queueIdentity() }
    val effectiveQueueCurrentIndex = remember(
        effectiveQueueSongs,
        queueSongs,
        queueCurrentIndex,
        currentQueueIdentity,
        currentSong,
    ) {
        if (effectiveQueueSongs.size == queueSongs.size && queueCurrentIndex in effectiveQueueSongs.indices) {
            queueCurrentIndex
        } else if (currentQueueIdentity != null) {
            effectiveQueueSongs.indexOfFirst { song ->
                song.queueIdentity() == currentQueueIdentity ||
                    (currentSong != null && currentSong.path.isNotBlank() && song.path == currentSong.path)
            }
        } else {
            -1
        }
    }

    val currentSongIndexInFiltered = remember(
        filteredSongList,
        currentQueueIdentity,
        currentSong,
        selectedTab,
        drillDownTarget,
        effectiveQueueCurrentIndex,
    ) {
        val list = filteredSongList ?: return@remember -1
        if (drillDownTarget == null && selectedTab == MiddleLibraryTab.QUEUE && effectiveQueueCurrentIndex >= 0) {
            list.indexOfFirst { it.first == effectiveQueueCurrentIndex }
        } else if (currentQueueIdentity != null) {
            list.indexOfFirst { (_, song) ->
                song.queueIdentity() == currentQueueIdentity ||
                    (currentSong != null && currentSong.path.isNotBlank() && song.path == currentSong.path)
            }
        } else {
            -1
        }
    }

    fun locateCurrentSong() {
        if (currentSongIndexInFiltered < 0) return
        coroutineScope.launch {
            val targetIdx = (currentSongIndexInFiltered - 2).coerceAtLeast(0)
            runCatching {
                if (wideMode) {
                    songGridState.animateScrollToItem(targetIdx)
                } else {
                    songListState.animateScrollToItem(targetIdx)
                }
            }
        }
    }

    var busyCardKey by remember { mutableStateOf<String?>(null) }

    fun playOnlineFavoritePlaylist(entity: OnlinePlaylistFavoriteEntity) {
        val actionKey = "play_${entity.playlistKey}"
        if (busyCardKey != null) return
        busyCardKey = actionKey
        coroutineScope.launch {
            try {
                val mapped = withContext(Dispatchers.IO) {
                    val platform = OnlinePlatform.entries.firstOrNull {
                        it.id.equals(entity.platform, ignoreCase = true)
                    } ?: OnlinePlatform.NETEASE
                    val items = OnlineMusicRepository.getInstance()
                        .getPlaylistDetail(entity.remoteId, platform)
                        .getOrNull()?.second.orEmpty()
                    val localIndex = if (allLibrarySongs.isNotEmpty()) SongMatcher.buildIndex(allLibrarySongs) else null
                    items.map { item ->
                        val onlineAudio = OnlineSourceMediaMapper.toUserPlaylistSong(item).toAudioFile()
                        SongMatcher.findBestLocalMatch(onlineAudio, allLibrarySongs, localIndex) ?: onlineAudio
                    }
                }
                if (mapped.isNotEmpty()) {
                    onPlayLibraryQueue(mapped, 0)
                } else {
                    AppNoticeBus.error(context.getString(R.string.online_detail_empty))
                }
            } catch (_: Throwable) {
                AppNoticeBus.error(context.getString(R.string.online_detail_load_failed))
            } finally {
                if (busyCardKey == actionKey) busyCardKey = null
            }
        }
    }

    fun appendOnlineFavoritePlaylistToQueue(entity: OnlinePlaylistFavoriteEntity) {
        val actionKey = "queue_${entity.playlistKey}"
        if (busyCardKey != null) return
        busyCardKey = actionKey
        coroutineScope.launch {
            try {
                val rawItems = withContext(Dispatchers.IO) {
                    val platform = OnlinePlatform.entries.firstOrNull {
                        it.id.equals(entity.platform, ignoreCase = true)
                    } ?: OnlinePlatform.NETEASE
                    OnlineMusicRepository.getInstance()
                        .getPlaylistDetail(entity.remoteId, platform)
                        .getOrNull()?.second.orEmpty()
                }
                if (rawItems.isEmpty()) {
                    AppNoticeBus.error(context.getString(R.string.online_detail_empty))
                    return@launch
                }
                val result = OnlinePlaybackLauncher.appendToQueue(context, rawItems)
                when {
                    result.startedPlayback -> AppNoticeBus.post(
                        context.getString(R.string.online_detail_added_to_queue_and_playing, result.addedCount)
                    )
                    result.addedCount > 0 -> AppNoticeBus.post(
                        context.getString(R.string.online_detail_added_to_queue, result.addedCount)
                    )
                    result.playResult is OnlinePlayResult.NoSourceInstalled -> AppNoticeBus.error(
                        context.getString(R.string.online_detail_no_source)
                    )
                    result.playResult is OnlinePlayResult.NoMatchFound -> AppNoticeBus.error(
                        context.getString(R.string.online_detail_no_match, rawItems.first().title)
                    )
                    result.playResult is OnlinePlayResult.Failed -> AppNoticeBus.error(
                        context.getString(R.string.online_detail_play_failed, rawItems.first().title)
                    )
                    else -> AppNoticeBus.post(
                        context.getString(R.string.online_detail_already_in_queue)
                    )
                }
            } catch (_: Throwable) {
                AppNoticeBus.error(context.getString(R.string.online_detail_load_failed))
            } finally {
                if (busyCardKey == actionKey) busyCardKey = null
            }
        }
    }

    fun appendOnlinePlaylistToQueue() {
        if (onlinePlaylistRawItems.isEmpty()) return
        coroutineScope.launch {
            val result = OnlinePlaybackLauncher.appendToQueue(context, onlinePlaylistRawItems)
            when {
                result.startedPlayback -> AppNoticeBus.post(
                    context.getString(R.string.online_detail_added_to_queue_and_playing, result.addedCount)
                )
                result.addedCount > 0 -> AppNoticeBus.post(
                    context.getString(R.string.online_detail_added_to_queue, result.addedCount)
                )
                result.playResult is OnlinePlayResult.NoSourceInstalled -> AppNoticeBus.error(
                    context.getString(R.string.online_detail_no_source)
                )
                result.playResult is OnlinePlayResult.NoMatchFound -> AppNoticeBus.error(
                    context.getString(R.string.online_detail_no_match, onlinePlaylistRawItems.first().title)
                )
                result.playResult is OnlinePlayResult.Failed -> AppNoticeBus.error(
                    context.getString(R.string.online_detail_play_failed, onlinePlaylistRawItems.first().title)
                )
                else -> AppNoticeBus.post(
                    context.getString(R.string.online_detail_already_in_queue)
                )
            }
        }
    }

    // 首次切换到队列或切换宽屏模式时自动滚动到正在播放的歌曲附近
    LaunchedEffect(selectedTab, drillDownTarget, effectiveQueueSongs.size, wideMode) {
        if (currentSongIndexInFiltered >= 0) {
            val targetIdx = (currentSongIndexInFiltered - 2).coerceAtLeast(0)
            runCatching {
                if (wideMode) {
                    songGridState.scrollToItem(targetIdx)
                } else {
                    songListState.scrollToItem(targetIdx)
                }
            }
        } else {
            runCatching {
                if (wideMode) songGridState.scrollToItem(0) else songListState.scrollToItem(0)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 6.dp),
    ) {
        // 顶部栏：一级分类 Tab 或 二级返回标题栏 + 功能按钮（搜索、定位、封面开关、宽屏扩展按钮）
        val target = drillDownTarget
        if (target == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(if (wideMode) 6.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiddleLibraryTab.entries.forEach { tab ->
                        val selected = tab == selectedTab
                        val label = when (tab) {
                            MiddleLibraryTab.QUEUE -> stringResource(R.string.player_middle_tab_queue)
                            MiddleLibraryTab.SONGS -> stringResource(R.string.player_middle_tab_songs)
                            MiddleLibraryTab.PLAYLISTS -> stringResource(R.string.player_middle_tab_playlists)
                            MiddleLibraryTab.FOLDERS -> stringResource(R.string.player_middle_tab_folders)
                            MiddleLibraryTab.ARTISTS -> stringResource(R.string.player_middle_tab_artists)
                            MiddleLibraryTab.ALBUMS -> stringResource(R.string.player_middle_tab_albums)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) primaryTextColor.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    selectedTab = tab
                                    drillDownTarget = null
                                }
                                .padding(horizontal = if (wideMode) 13.dp else 11.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (selected) primaryTextColor else secondaryTextColor.copy(alpha = 0.78f),
                                fontSize = if (wideMode) 13.5.sp else 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = fontFamily,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (wideMode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MiddleInlineCompactSearch(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        primaryTextColor = primaryTextColor,
                        secondaryTextColor = secondaryTextColor,
                        modifier = Modifier.width(172.dp),
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                MiddleHeaderActions(
                    searchVisible = searchVisible,
                    showSearchToggle = !wideMode,
                    onToggleSearch = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    },
                    canLocateCurrent = currentSongIndexInFiltered >= 0,
                    onLocateCurrent = ::locateCurrentSong,
                    showCovers = showCovers,
                    onToggleShowCovers = onToggleShowCovers,
                    wideMode = wideMode,
                    onToggleWideMode = onToggleWideMode,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                )
            }
        } else if (wideMode) {
            // 宽屏二级详情横幅：玻璃信息卡 + 播放全部 + 在线歌单加入队列 + 内联搜索 + 收起扩展
            val bannerCoverKey = remember(target, userPlaylists, allLibrarySongs, artists, albums, activeSongList) {
                when (target) {
                    is MiddleDrillDownTarget.OnlinePlaylist -> target.entity.coverUrl
                    is MiddleDrillDownTarget.LocalPlaylist -> {
                        val pl = userPlaylists.firstOrNull { it.id == target.playlistId }
                        pl?.coverUrl?.takeIf { it.isNotBlank() }
                            ?: activeSongList?.firstOrNull()?.let { it.resolvePlaybackArtworkKey(it.albumArtPath).orEmpty() }.orEmpty()
                    }
                    is MiddleDrillDownTarget.ArtistTarget ->
                        artists.firstOrNull { it.name == target.artistName }?.coverPath.orEmpty()
                    is MiddleDrillDownTarget.AlbumTarget ->
                        albums.firstOrNull { it.name == target.albumName }?.coverPath.orEmpty()
                    is MiddleDrillDownTarget.FolderTarget ->
                        activeSongList?.firstOrNull()?.let { it.resolvePlaybackArtworkKey(it.albumArtPath).orEmpty() }.orEmpty()
                }
            }
            val songCount = activeSongList?.size ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(primaryTextColor.copy(alpha = 0.06f))
                    .border(0.6.dp, primaryTextColor.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(primaryTextColor.copy(alpha = 0.08f))
                        .clickable { drillDownTarget = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.library_action_back),
                        tint = primaryTextColor,
                        modifier = Modifier.size(17.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(primaryTextColor.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (target is MiddleDrillDownTarget.ArtistTarget) Icons.Rounded.Person else MiuixIcons.Regular.ListView,
                        contentDescription = null,
                        tint = secondaryTextColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                    if (bannerCoverKey.isNotBlank()) {
                        BitmapImage(
                            key = bannerCoverKey,
                            contentDescription = target.title,
                            contentScale = ContentScale.Crop,
                            targetWidth = 160,
                            targetHeight = 160,
                            surface = ArtworkSurface.List,
                            fadeInMillis = 0,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        color = primaryTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.player_middle_song_count, songCount),
                        color = secondaryTextColor,
                        fontSize = 11.5.sp,
                        fontFamily = fontFamily,
                        maxLines = 1,
                    )
                }

                if (songCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(primaryTextColor.copy(alpha = 0.14f))
                            .clickable {
                                val list = activeSongList ?: return@clickable
                                if (list.isNotEmpty()) onPlayLibraryQueue(list, 0)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.player_middle_play_all),
                            color = primaryTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = fontFamily,
                            maxLines = 1,
                        )
                    }
                    val canAppendOnline = target is MiddleDrillDownTarget.OnlinePlaylist && onlinePlaylistRawItems.isNotEmpty()
                    val canAppendLocal = target !is MiddleDrillDownTarget.OnlinePlaylist && onAppendLibraryQueue != null
                    if (canAppendOnline || canAppendLocal) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(primaryTextColor.copy(alpha = 0.08f))
                                .border(0.6.dp, primaryTextColor.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                                .clickable {
                                    if (canAppendOnline) {
                                        appendOnlinePlaylistToQueue()
                                    } else {
                                        val list = activeSongList ?: return@clickable
                                        if (list.isNotEmpty()) onAppendLibraryQueue?.invoke(list)
                                    }
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.player_middle_add_to_queue),
                                color = primaryTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = fontFamily,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                MiddleInlineCompactSearch(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                    modifier = Modifier.width(140.dp),
                )

                Spacer(modifier = Modifier.width(4.dp))

                MiddleHeaderActions(
                    searchVisible = searchVisible,
                    showSearchToggle = false,
                    onToggleSearch = {},
                    canLocateCurrent = currentSongIndexInFiltered >= 0,
                    onLocateCurrent = ::locateCurrentSong,
                    showCovers = showCovers,
                    onToggleShowCovers = onToggleShowCovers,
                    wideMode = wideMode,
                    onToggleWideMode = onToggleWideMode,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                )
            }
        } else {
            // 窄屏二级详情顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { drillDownTarget = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.library_action_back),
                        tint = primaryTextColor,
                        modifier = Modifier.size(17.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        color = primaryTextColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val count = activeSongList?.size ?: 0
                    Text(
                        text = stringResource(R.string.player_middle_song_count, count),
                        color = secondaryTextColor,
                        fontSize = 10.sp,
                        fontFamily = fontFamily,
                        maxLines = 1,
                    )
                }

                if (target is MiddleDrillDownTarget.OnlinePlaylist && onlinePlaylistRawItems.isNotEmpty()) {
                    MiddleActionIconButton(
                        icon = MiuixIcons.Regular.Playlist,
                        contentDescription = stringResource(R.string.player_middle_add_to_queue),
                        active = false,
                        primaryTextColor = primaryTextColor,
                        secondaryTextColor = secondaryTextColor,
                        onClick = ::appendOnlinePlaylistToQueue,
                    )
                }

                MiddleHeaderActions(
                    searchVisible = searchVisible,
                    showSearchToggle = true,
                    onToggleSearch = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    },
                    canLocateCurrent = currentSongIndexInFiltered >= 0,
                    onLocateCurrent = ::locateCurrentSong,
                    showCovers = showCovers,
                    onToggleShowCovers = onToggleShowCovers,
                    wideMode = wideMode,
                    onToggleWideMode = onToggleWideMode,
                    primaryTextColor = primaryTextColor,
                    secondaryTextColor = secondaryTextColor,
                )
            }
        }

        // 内联过滤搜索栏（窄屏按需展开；宽屏已常驻在顶栏右侧）
        AnimatedVisibility(
            visible = !wideMode && searchVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(primaryTextColor.copy(alpha = 0.07f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = secondaryTextColor,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.player_middle_search_hint),
                            color = secondaryTextColor.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            fontFamily = fontFamily,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = primaryTextColor,
                            fontSize = 12.sp,
                            fontFamily = fontFamily,
                        ),
                        cursorBrush = SolidColor(primaryTextColor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = secondaryTextColor,
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .clickable { searchQuery = "" },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 主体内容区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                onlinePlaylistLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = primaryTextColor,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.player_middle_loading_online),
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            fontFamily = fontFamily,
                        )
                    }
                }

                filteredSongList != null -> {
                    val songsToRender = filteredSongList
                    if (songsToRender.isEmpty()) {
                        MiddleEmptyPlaceholder(
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                        )
                    } else if (wideMode) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (drillDownTarget == null) {
                                val summaryText = if (selectedTab == MiddleLibraryTab.QUEUE) {
                                    stringResource(R.string.player_middle_queue_summary, songsToRender.size)
                                } else {
                                    stringResource(R.string.player_middle_songs_summary, songsToRender.size)
                                }
                                Text(
                                    text = summaryText,
                                    color = secondaryTextColor.copy(alpha = 0.82f),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = fontFamily,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                )
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = songGridState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                gridItemsIndexed(
                                    items = songsToRender,
                                    key = { _, (originalIndex, song) ->
                                        "wide_${song.queueIdentity()}_$originalIndex"
                                    },
                                ) { _, (originalIndex, song) ->
                                    val isPlayingSong = if (drillDownTarget == null && selectedTab == MiddleLibraryTab.QUEUE && effectiveQueueCurrentIndex >= 0) {
                                        originalIndex == effectiveQueueCurrentIndex
                                    } else {
                                        currentQueueIdentity != null && (
                                            song.queueIdentity() == currentQueueIdentity ||
                                                (currentSong != null && currentSong.path.isNotBlank() && song.path == currentSong.path)
                                            )
                                    }
                                    MiddleSongRow(
                                        index = originalIndex + 1,
                                        song = song,
                                        isCurrent = isPlayingSong,
                                        showCover = showCovers,
                                        wideCardStyle = true,
                                        primaryTextColor = primaryTextColor,
                                        secondaryTextColor = secondaryTextColor,
                                        onClick = {
                                            if (drillDownTarget == null && selectedTab == MiddleLibraryTab.QUEUE) {
                                                if (effectiveQueueSongs.size > queueSongs.size) {
                                                    onPlayLibraryQueue(effectiveQueueSongs, originalIndex)
                                                } else {
                                                    onQueueSongClick(song, originalIndex)
                                                }
                                            } else {
                                                val sourceList = activeSongList ?: return@MiddleSongRow
                                                onPlayLibraryQueue(sourceList, originalIndex)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = songListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = songsToRender,
                                key = { _, (originalIndex, song) ->
                                    "${song.queueIdentity()}_$originalIndex"
                                },
                            ) { _, (originalIndex, song) ->
                                val isPlayingSong = if (drillDownTarget == null && selectedTab == MiddleLibraryTab.QUEUE && effectiveQueueCurrentIndex >= 0) {
                                    originalIndex == effectiveQueueCurrentIndex
                                } else {
                                    currentQueueIdentity != null && (
                                        song.queueIdentity() == currentQueueIdentity ||
                                            (currentSong != null && currentSong.path.isNotBlank() && song.path == currentSong.path)
                                        )
                                }
                                MiddleSongRow(
                                    index = originalIndex + 1,
                                    song = song,
                                    isCurrent = isPlayingSong,
                                    showCover = showCovers,
                                    wideCardStyle = false,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        if (drillDownTarget == null && selectedTab == MiddleLibraryTab.QUEUE) {
                                            if (effectiveQueueSongs.size > queueSongs.size) {
                                                onPlayLibraryQueue(effectiveQueueSongs, originalIndex)
                                            } else {
                                                onQueueSongClick(song, originalIndex)
                                            }
                                        } else {
                                            val sourceList = activeSongList ?: return@MiddleSongRow
                                            onPlayLibraryQueue(sourceList, originalIndex)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                selectedTab == MiddleLibraryTab.PLAYLISTS -> {
                    val favTitle = stringResource(R.string.player_middle_favorites)
                    val disTitle = stringResource(R.string.player_middle_disliked)
                    val visibleLocalPlaylists = remember(userPlaylists, trimmedQuery, favTitle, disTitle) {
                        userPlaylists.filter { pl ->
                            if (pl.id == DISLIKED_PLAYLIST_ID && pl.songs.isEmpty()) return@filter false
                            if (trimmedQuery.isBlank()) return@filter true
                            val displayName = when (pl.id) {
                                FAVORITES_PLAYLIST_ID -> favTitle
                                DISLIKED_PLAYLIST_ID -> disTitle
                                else -> pl.name
                            }
                            displayName.contains(trimmedQuery, ignoreCase = true)
                        }
                    }
                    val visibleOnlinePlaylists = remember(onlineFavorites, trimmedQuery) {
                        if (trimmedQuery.isBlank()) {
                            onlineFavorites
                        } else {
                            onlineFavorites.filter {
                                it.title.contains(trimmedQuery, ignoreCase = true) ||
                                    it.platform.contains(trimmedQuery, ignoreCase = true)
                            }
                        }
                    }

                    if (visibleLocalPlaylists.isEmpty() && visibleOnlinePlaylists.isEmpty()) {
                        MiddleEmptyPlaceholder(
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                        )
                    } else if (wideMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 148.dp),
                            state = categoryGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            gridItemsIndexed(
                                items = visibleLocalPlaylists,
                                key = { _, pl -> "wide_local_pl_${pl.id}" },
                            ) { _, pl ->
                                val title = when (pl.id) {
                                    FAVORITES_PLAYLIST_ID -> favTitle
                                    DISLIKED_PLAYLIST_ID -> disTitle
                                    else -> pl.name
                                }
                                val coverKey = remember(pl, allLibrarySongs) {
                                    pl.coverUrl?.takeIf { it.isNotBlank() }
                                        ?: pl.songs.firstOrNull()?.let { first ->
                                            first.albumArtPath.ifBlank {
                                                allLibrarySongs.firstOrNull { it.id == first.id || it.path == first.path }?.coverKey.orEmpty()
                                            }
                                        }.orEmpty()
                                }
                                MiddleWideMediaGridCard(
                                    title = title,
                                    subtitle = stringResource(R.string.player_middle_song_count, pl.songs.size),
                                    badgeText = null,
                                    topTagText = null,
                                    coverKey = coverKey,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onPlayAll = if (pl.songs.isNotEmpty()) {
                                        {
                                            val resolved = playlistStore.resolveOrderedPlaylistSongs(pl, allLibrarySongs)
                                            if (resolved.isNotEmpty()) onPlayLibraryQueue(resolved, 0)
                                        }
                                    } else {
                                        null
                                    },
                                    onAddToQueue = if (pl.songs.isNotEmpty() && onAppendLibraryQueue != null) {
                                        {
                                            val resolved = playlistStore.resolveOrderedPlaylistSongs(pl, allLibrarySongs)
                                            if (resolved.isNotEmpty()) onAppendLibraryQueue.invoke(resolved)
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.LocalPlaylist(
                                            playlistId = pl.id,
                                            title = title,
                                        )
                                    },
                                )
                            }

                            gridItemsIndexed(
                                items = visibleOnlinePlaylists,
                                key = { _, item -> "wide_online_pl_${item.playlistKey}" },
                            ) { _, item ->
                                val platformLabel = OnlinePlatform.entries.firstOrNull {
                                    it.id.equals(item.platform, ignoreCase = true)
                                }?.displayName ?: item.platform
                                val isCardBusy = busyCardKey == "play_${item.playlistKey}" ||
                                    busyCardKey == "queue_${item.playlistKey}"
                                val onlineSubtitle = buildString {
                                    append(stringResource(R.string.player_middle_online_tag, platformLabel))
                                    if (item.trackCount > 0) {
                                        append(" · ")
                                        append(stringResource(R.string.player_middle_song_count, item.trackCount))
                                    }
                                }
                                MiddleWideMediaGridCard(
                                    title = item.title,
                                    subtitle = onlineSubtitle,
                                    badgeText = null,
                                    topTagText = null,
                                    coverKey = item.coverUrl,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    isActionBusy = isCardBusy,
                                    onPlayAll = { playOnlineFavoritePlaylist(item) },
                                    onAddToQueue = { appendOnlineFavoritePlaylistToQueue(item) },
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.OnlinePlaylist(
                                            entity = item,
                                            title = item.title,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = visibleLocalPlaylists,
                                key = { _, pl -> "local_pl_${pl.id}" },
                            ) { _, pl ->
                                val title = when (pl.id) {
                                    FAVORITES_PLAYLIST_ID -> favTitle
                                    DISLIKED_PLAYLIST_ID -> disTitle
                                    else -> pl.name
                                }
                                val coverKey = remember(pl, allLibrarySongs) {
                                    pl.coverUrl?.takeIf { it.isNotBlank() }
                                        ?: pl.songs.firstOrNull()?.let { first ->
                                            first.albumArtPath.ifBlank {
                                                allLibrarySongs.firstOrNull { it.id == first.id || it.path == first.path }?.coverKey.orEmpty()
                                            }
                                        }.orEmpty()
                                }
                                MiddleCategoryRow(
                                    title = title,
                                    subtitle = stringResource(R.string.player_middle_song_count, pl.songs.size),
                                    coverKey = coverKey,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.LocalPlaylist(
                                            playlistId = pl.id,
                                            title = title,
                                        )
                                    },
                                )
                            }

                            itemsIndexed(
                                items = visibleOnlinePlaylists,
                                key = { _, item -> "online_pl_${item.playlistKey}" },
                            ) { _, item ->
                                val platformLabel = OnlinePlatform.entries.firstOrNull {
                                    it.id.equals(item.platform, ignoreCase = true)
                                }?.displayName ?: item.platform
                                val sub = buildString {
                                    append(stringResource(R.string.player_middle_online_tag, platformLabel))
                                    if (item.trackCount > 0) {
                                        append(" · ")
                                        append(stringResource(R.string.player_middle_song_count, item.trackCount))
                                    }
                                }
                                MiddleCategoryRow(
                                    title = item.title,
                                    subtitle = sub,
                                    coverKey = item.coverUrl,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.OnlinePlaylist(
                                            entity = item,
                                            title = item.title,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                selectedTab == MiddleLibraryTab.FOLDERS -> {
                    val visibleFolders = remember(folders, trimmedQuery) {
                        if (trimmedQuery.isBlank()) {
                            folders
                        } else {
                            folders.filter {
                                it.name.contains(trimmedQuery, ignoreCase = true) ||
                                    it.path.contains(trimmedQuery, ignoreCase = true)
                            }
                        }
                    }
                    if (visibleFolders.isEmpty()) {
                        MiddleEmptyPlaceholder(
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                        )
                    } else if (wideMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = categoryGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItemsIndexed(
                                items = visibleFolders,
                                key = { _, folder -> "wide_folder_${folder.path}" },
                            ) { _, folder ->
                                MiddleWideFolderGridCard(
                                    title = folder.name.ifBlank { folder.path },
                                    countText = stringResource(R.string.player_middle_song_count, folder.songCount),
                                    pathText = folder.path,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.FolderTarget(
                                            folderPath = folder.path,
                                            title = folder.name.ifBlank { folder.path },
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = visibleFolders,
                                key = { _, folder -> "folder_${folder.path}" },
                            ) { _, folder ->
                                MiddleCategoryRow(
                                    title = folder.name.ifBlank { folder.path },
                                    subtitle = "${stringResource(R.string.player_middle_song_count, folder.songCount)} · ${folder.path}",
                                    coverKey = "",
                                    showCover = false,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.FolderTarget(
                                            folderPath = folder.path,
                                            title = folder.name.ifBlank { folder.path },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                selectedTab == MiddleLibraryTab.ARTISTS -> {
                    val visibleArtists = remember(artists, trimmedQuery) {
                        if (trimmedQuery.isBlank()) {
                            artists
                        } else {
                            artists.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
                        }
                    }
                    if (visibleArtists.isEmpty()) {
                        MiddleEmptyPlaceholder(
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                        )
                    } else if (wideMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 122.dp),
                            state = categoryGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            gridItemsIndexed(
                                items = visibleArtists,
                                key = { _, artist -> "wide_artist_${artist.name}" },
                            ) { _, artist ->
                                MiddleWideArtistGridCard(
                                    name = artist.name,
                                    subtitle = stringResource(R.string.player_middle_song_count, artist.songCount),
                                    coverKey = artist.coverPath,
                                    showCover = showCovers,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.ArtistTarget(
                                            artistName = artist.name,
                                            title = artist.name,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = visibleArtists,
                                key = { _, artist -> "artist_${artist.name}" },
                            ) { _, artist ->
                                MiddleCategoryRow(
                                    title = artist.name,
                                    subtitle = stringResource(R.string.player_middle_song_count, artist.songCount),
                                    coverKey = artist.coverPath,
                                    showCover = showCovers,
                                    fallbackIcon = Icons.Rounded.Person,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.ArtistTarget(
                                            artistName = artist.name,
                                            title = artist.name,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                selectedTab == MiddleLibraryTab.ALBUMS -> {
                    val visibleAlbums = remember(albums, trimmedQuery) {
                        if (trimmedQuery.isBlank()) {
                            albums
                        } else {
                            albums.filter {
                                it.name.contains(trimmedQuery, ignoreCase = true) ||
                                    it.artist.contains(trimmedQuery, ignoreCase = true)
                            }
                        }
                    }
                    if (visibleAlbums.isEmpty()) {
                        MiddleEmptyPlaceholder(
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                        )
                    } else if (wideMode) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 148.dp),
                            state = categoryGridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            gridItemsIndexed(
                                items = visibleAlbums,
                                key = { _, album -> "wide_album_${album.name}_${album.artist}" },
                            ) { _, album ->
                                MiddleWideMediaGridCard(
                                    title = album.name,
                                    subtitle = album.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                                    badgeText = stringResource(R.string.player_middle_song_count, album.songCount),
                                    topTagText = null,
                                    coverKey = album.coverPath,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onPlayAll = {
                                        val albumSongs = allLibrarySongs.filter { it.album == album.name }
                                            .ifEmpty { MusicRepository.getSongsByAlbum(album.name) }
                                        if (albumSongs.isNotEmpty()) onPlayLibraryQueue(albumSongs, 0)
                                    },
                                    onAddToQueue = if (onAppendLibraryQueue != null) {
                                        {
                                            val albumSongs = allLibrarySongs.filter { it.album == album.name }
                                                .ifEmpty { MusicRepository.getSongsByAlbum(album.name) }
                                            if (albumSongs.isNotEmpty()) onAppendLibraryQueue.invoke(albumSongs)
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.AlbumTarget(
                                            albumName = album.name,
                                            title = album.name,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = visibleAlbums,
                                key = { _, album -> "album_${album.name}_${album.artist}" },
                            ) { _, album ->
                                val sub = buildString {
                                    if (album.artist.isNotBlank()) {
                                        append(album.artist)
                                        append(" · ")
                                    }
                                    append(stringResource(R.string.player_middle_song_count, album.songCount))
                                }
                                MiddleCategoryRow(
                                    title = album.name,
                                    subtitle = sub,
                                    coverKey = album.coverPath,
                                    showCover = showCovers,
                                    fallbackIcon = MiuixIcons.Regular.ListView,
                                    primaryTextColor = primaryTextColor,
                                    secondaryTextColor = secondaryTextColor,
                                    onClick = {
                                        drillDownTarget = MiddleDrillDownTarget.AlbumTarget(
                                            albumName = album.name,
                                            title = album.name,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiddleHeaderActions(
    searchVisible: Boolean,
    showSearchToggle: Boolean,
    onToggleSearch: () -> Unit,
    canLocateCurrent: Boolean,
    onLocateCurrent: () -> Unit,
    showCovers: Boolean,
    onToggleShowCovers: () -> Unit,
    wideMode: Boolean,
    onToggleWideMode: () -> Unit,
    primaryTextColor: Color,
    secondaryTextColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canLocateCurrent) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onLocateCurrent),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_now_playing_locator),
                    contentDescription = stringResource(R.string.player_middle_locate_current),
                    colorFilter = ColorFilter.tint(secondaryTextColor.copy(alpha = 0.85f)),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (showSearchToggle) {
            MiddleActionIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.library_action_search),
                active = searchVisible,
                primaryTextColor = primaryTextColor,
                secondaryTextColor = secondaryTextColor,
                onClick = onToggleSearch,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (showCovers) primaryTextColor.copy(alpha = 0.10f)
                    else Color.Transparent
                )
                .clickable(onClick = onToggleShowCovers),
            contentAlignment = Alignment.Center,
        ) {
            val iconTint = if (showCovers) primaryTextColor else secondaryTextColor.copy(alpha = 0.78f)
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = 1.4.dp.toPx()
                drawRoundRect(
                    color = iconTint,
                    style = Stroke(width = stroke),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                )
                if (showCovers) {
                    drawCircle(
                        color = iconTint,
                        radius = size.minDimension * 0.16f,
                        center = Offset(size.width * 0.36f, size.height * 0.36f),
                    )
                    drawLine(
                        color = iconTint,
                        start = Offset(size.width * 0.20f, size.height * 0.78f),
                        end = Offset(size.width * 0.52f, size.height * 0.50f),
                        strokeWidth = stroke,
                    )
                    drawLine(
                        color = iconTint,
                        start = Offset(size.width * 0.52f, size.height * 0.50f),
                        end = Offset(size.width * 0.82f, size.height * 0.78f),
                        strokeWidth = stroke,
                    )
                } else {
                    drawLine(
                        color = iconTint,
                        start = Offset(size.width * 0.18f, size.height * 0.18f),
                        end = Offset(size.width * 0.82f, size.height * 0.82f),
                        strokeWidth = stroke,
                    )
                }
            }
        }
        // 中栏扩展/恢复按钮：点击后隐藏右侧歌词栏，中栏占据两栏宽屏空间
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (wideMode) primaryTextColor.copy(alpha = 0.14f)
                    else Color.Transparent
                )
                .clickable(onClick = onToggleWideMode),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (wideMode) primaryTextColor else secondaryTextColor.copy(alpha = 0.85f)
            Canvas(modifier = Modifier.size(14.dp)) {
                val stroke = 1.5.dp.toPx()
                val w = size.width
                val h = size.height
                if (!wideMode) {
                    // 向外双箭头（扩展占据两栏）
                    drawLine(tint, Offset(w * 0.58f, h * 0.12f), Offset(w * 0.88f, h * 0.12f), stroke)
                    drawLine(tint, Offset(w * 0.88f, h * 0.12f), Offset(w * 0.88f, h * 0.42f), stroke)
                    drawLine(tint, Offset(w * 0.88f, h * 0.12f), Offset(w * 0.56f, h * 0.44f), stroke)

                    drawLine(tint, Offset(w * 0.42f, h * 0.88f), Offset(w * 0.12f, h * 0.88f), stroke)
                    drawLine(tint, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.12f, h * 0.58f), stroke)
                    drawLine(tint, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.44f, h * 0.56f), stroke)
                } else {
                    // 向内双箭头（恢复三栏并显示歌词）
                    drawLine(tint, Offset(w * 0.56f, h * 0.18f), Offset(w * 0.56f, h * 0.44f), stroke)
                    drawLine(tint, Offset(w * 0.56f, h * 0.44f), Offset(w * 0.82f, h * 0.44f), stroke)
                    drawLine(tint, Offset(w * 0.88f, h * 0.12f), Offset(w * 0.56f, h * 0.44f), stroke)

                    drawLine(tint, Offset(w * 0.44f, h * 0.82f), Offset(w * 0.44f, h * 0.56f), stroke)
                    drawLine(tint, Offset(w * 0.44f, h * 0.56f), Offset(w * 0.18f, h * 0.56f), stroke)
                    drawLine(tint, Offset(w * 0.12f, h * 0.88f), Offset(w * 0.44f, h * 0.56f), stroke)
                }
            }
        }
    }
}

@Composable
private fun MiddleInlineCompactSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    modifier: Modifier = Modifier,
) {
    val fontFamily = appFontFamily()
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(primaryTextColor.copy(alpha = 0.07f))
            .border(0.5.dp, primaryTextColor.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = secondaryTextColor.copy(alpha = 0.78f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.player_middle_search_hint),
                    color = secondaryTextColor.copy(alpha = 0.6f),
                    fontSize = 11.5.sp,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = primaryTextColor,
                    fontSize = 11.5.sp,
                    fontFamily = fontFamily,
                ),
                cursorBrush = SolidColor(primaryTextColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = secondaryTextColor,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun MiddleWideMediaGridCard(
    title: String,
    subtitle: String,
    badgeText: String?,
    topTagText: String?,
    coverKey: String,
    showCover: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    isActionBusy: Boolean = false,
    onPlayAll: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fontFamily = appFontFamily()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(primaryTextColor.copy(alpha = 0.055f))
            .border(0.6.dp, primaryTextColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(primaryTextColor.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.55f),
                modifier = Modifier.size(30.dp),
            )
            if (showCover && coverKey.isNotBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    targetWidth = 256,
                    targetHeight = 256,
                    surface = ArtworkSurface.List,
                    fadeInMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!topTagText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = topTagText,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        maxLines = 1,
                    )
                }
            }
            if (!badgeText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(if (onPlayAll != null || onAddToQueue != null) Alignment.TopEnd else Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        maxLines = 1,
                    )
                }
            }
            if (onPlayAll != null || onAddToQueue != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isActionBusy) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.58f))
                                .border(0.6.dp, Color.White.copy(alpha = 0.24f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = Color.White,
                            )
                        }
                    } else {
                        if (onAddToQueue != null) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.56f))
                                    .border(0.6.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                                    .clickable(onClick = onAddToQueue),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Playlist,
                                    contentDescription = stringResource(R.string.player_middle_add_to_queue),
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        if (onPlayAll != null) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .border(0.7.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                    .clickable(onClick = onPlayAll),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play),
                                    contentDescription = stringResource(R.string.player_middle_play_all),
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = title,
            color = primaryTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = secondaryTextColor.copy(alpha = 0.85f),
                fontSize = 10.5.sp,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiddleWideArtistGridCard(
    name: String,
    subtitle: String,
    coverKey: String,
    showCover: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    val fontFamily = appFontFamily()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(primaryTextColor.copy(alpha = 0.055f))
            .border(0.6.dp, primaryTextColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(primaryTextColor.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.65f),
                modifier = Modifier.size(28.dp),
            )
            if (showCover && coverKey.isNotBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    targetWidth = 192,
                    targetHeight = 192,
                    surface = ArtworkSurface.List,
                    fadeInMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = name,
            color = primaryTextColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = secondaryTextColor.copy(alpha = 0.82f),
            fontSize = 10.5.sp,
            fontFamily = fontFamily,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MiddleWideFolderGridCard(
    title: String,
    countText: String,
    pathText: String,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    val fontFamily = appFontFamily()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(primaryTextColor.copy(alpha = 0.055f))
            .border(0.6.dp, primaryTextColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(primaryTextColor.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.ListView,
                contentDescription = null,
                tint = primaryTextColor.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = primaryTextColor,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = countText,
                    color = secondaryTextColor.copy(alpha = 0.85f),
                    fontSize = 10.5.sp,
                    fontFamily = fontFamily,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = pathText,
                color = secondaryTextColor.copy(alpha = 0.72f),
                fontSize = 10.5.sp,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiddleActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (active) primaryTextColor.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) primaryTextColor else secondaryTextColor.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MiddleSongRow(
    index: Int,
    song: AudioFile,
    isCurrent: Boolean,
    showCover: Boolean,
    wideCardStyle: Boolean = false,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    val fontFamily = appFontFamily()
    val unknownArtist = stringResource(R.string.common_unknown_artist)
    val rowBackground = when {
        isCurrent -> primaryTextColor.copy(alpha = if (wideCardStyle) 0.13f else 0.09f)
        wideCardStyle -> primaryTextColor.copy(alpha = 0.045f)
        else -> Color.Transparent
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(rowBackground)
        .let { base ->
            if (wideCardStyle) {
                base.border(
                    width = 0.6.dp,
                    color = if (isCurrent) primaryTextColor.copy(alpha = 0.22f) else primaryTextColor.copy(alpha = 0.07f),
                    shape = RoundedCornerShape(14.dp),
                )
            } else {
                base
            }
        }
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = if (showCover) 7.dp else 9.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号或正在播放指示图标
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(primaryTextColor),
                )
            } else {
                Text(
                    text = index.toString(),
                    color = secondaryTextColor.copy(alpha = 0.68f),
                    fontSize = 11.sp,
                    fontFamily = fontFamily,
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        if (showCover) {
            val coverKey = remember(song.path, song.fileSize, song.dateModified, song.albumArtPath) {
                song.resolvePlaybackArtworkKey(song.albumArtPath).orEmpty()
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(primaryTextColor.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverKey.isNotBlank()) {
                    BitmapImage(
                        key = coverKey,
                        contentDescription = song.displayName,
                        contentScale = ContentScale.Crop,
                        targetWidth = 128,
                        targetHeight = 128,
                        surface = ArtworkSurface.List,
                        fadeInMillis = 0,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName,
                color = primaryTextColor,
                fontSize = 13.5.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                append(song.artist.ifBlank { unknownArtist })
                if (song.album.isNotBlank()) {
                    append(" · ")
                    append(song.album)
                }
            }
            Text(
                text = subtitle,
                color = if (isCurrent) primaryTextColor.copy(alpha = 0.76f) else secondaryTextColor,
                fontSize = 11.sp,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (song.duration > 0L) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = AudioUtils.formatDuration(song.duration),
                color = secondaryTextColor.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontFamily = fontFamily,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MiddleCategoryRow(
    title: String,
    subtitle: String,
    coverKey: String,
    showCover: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    val fontFamily = appFontFamily()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(primaryTextColor.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = secondaryTextColor.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            if (showCover && coverKey.isNotBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    targetWidth = 128,
                    targetHeight = 128,
                    surface = ArtworkSurface.List,
                    fadeInMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = primaryTextColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = secondaryTextColor,
                    fontSize = 11.sp,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiddleEmptyPlaceholder(
    primaryTextColor: Color,
    secondaryTextColor: Color,
) {
    val fontFamily = appFontFamily()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = MiuixIcons.Regular.ListView,
                contentDescription = null,
                tint = primaryTextColor.copy(alpha = 0.24f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.player_middle_empty),
                color = secondaryTextColor,
                fontSize = 12.sp,
                fontFamily = fontFamily,
            )
        }
    }
}