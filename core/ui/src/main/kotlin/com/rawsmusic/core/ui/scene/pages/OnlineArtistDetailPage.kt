package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.activity.compose.BackHandler
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.module.online.model.OnlinePlatform
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
import com.rawsmusic.core.common.prefs.PlayedPlaylistManager
import com.rawsmusic.module.online.model.OnlineAlbum
import com.rawsmusic.module.online.model.OnlineArtist
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

private const val ARGUMENT_SEPARATOR = "|"

/** 把「平台 + 歌手 id + 歌手名字 + 头像」编码成导航参数。 */
fun onlineArtistDetailArgument(
    platform: OnlinePlatform,
    artistId: String,
    artistName: String = "",
    avatarUrl: String = "",
): String = "${platform.id}$ARGUMENT_SEPARATOR${Uri.encode(artistId)}$ARGUMENT_SEPARATOR${Uri.encode(artistName)}$ARGUMENT_SEPARATOR${Uri.encode(avatarUrl)}"

@Composable
internal fun OnlineArtistDetailPage(
    argument: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rememberedArgument by rememberSaveable { mutableStateOf(argument.takeIf { it.isNotBlank() }.orEmpty()) }
    if (argument.isNotBlank() && argument != rememberedArgument) {
        rememberedArgument = argument
    }
    val effectiveArgument = argument.takeIf { it.isNotBlank() } ?: rememberedArgument

    val parts = remember(effectiveArgument) { effectiveArgument.split(ARGUMENT_SEPARATOR) }
    val platform = remember(effectiveArgument) { OnlinePlatform.fromId(parts.getOrElse(0) { "" }) ?: OnlinePlatform.NETEASE }
    val artistId = remember(effectiveArgument) { Uri.decode(parts.getOrElse(1) { "" }) }
    val artistName = remember(effectiveArgument) { Uri.decode(parts.getOrElse(2) { "" }) }
    val avatarUrl = remember(effectiveArgument) { Uri.decode(parts.getOrElse(3) { "" }) }
    val artist = remember(effectiveArgument) {
        OnlineArtist(
            id = artistId,
            name = artistName,
            avatarUrl = avatarUrl,
            platform = platform,
        )
    }
    OnlineArtistDetailPage(
        artist = artist,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * 歌手详情下钻页（1:1 还原 OrbitPlayer 的 OnlineArtistDetailView）。
 *
 * 核心功能：
 * 1. 顶部 Header：歌手大圆形头像、姓名、单曲数量、所属平台胶囊
 * 2. 快捷按钮：一键播放全部热门歌曲
 * 3. 二级 Tab 切换：【热门歌曲】与【专辑列表】
 * 4. 歌曲列表点击即刻起播，整单入队，触发多源换源搜救
 * 5. 专辑列表点击支持下钻查看专辑内曲目
 * 6. 支持系统返回键（BackHandler）与顶部返回按钮
 */
@Composable
internal fun OnlineArtistDetailPage(
    artist: OnlineArtist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = OnlineMusicRepository.getInstance()
    val platform = artist.platform
    val colors = themeColors()

    var currentArtist by remember(artist) { mutableStateOf(artist) }
    var selectedDetailTab by remember { mutableIntStateOf(0) } // 0: 热门歌曲, 1: 专辑列表
    var selectedAlbum by remember { mutableStateOf<OnlineAlbum?>(null) }
    var albumSongs by remember { mutableStateOf<List<OnlineSongItem>>(emptyList()) }
    var albumLoading by remember { mutableStateOf(false) }

    var hotSongs by remember { mutableStateOf<List<OnlineSongItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<OnlineAlbum>>(emptyList()) }
    var loadedArtistKey by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var songPage by remember { mutableIntStateOf(1) }
    var albumPage by remember { mutableIntStateOf(1) }
    var hasMoreSongs by remember { mutableStateOf(true) }
    var hasMoreAlbums by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var addToPlaylistSong by remember { mutableStateOf<OnlineSongItem?>(null) }
    val latestSceneMotionFrozen by rememberUpdatedState(LocalSceneBackgroundFrozen.current)

    val loadFailedMessage = stringResource(R.string.online_artist_load_failed)
    val noSourceMessage = stringResource(R.string.online_detail_no_source)
    val noMatchMessage = stringResource(R.string.online_detail_no_match)
    val playFailedMessage = stringResource(R.string.online_detail_play_failed)

    // 拦截返回键：仅在处于专辑下钻状态时拦截并返回歌手页；未下钻时不拦截，交由全局导航/播放器返回处理
    BackHandler(enabled = selectedAlbum != null) {
        selectedAlbum = null
        albumSongs = emptyList()
    }

    // 加载歌手详情与初始歌曲/专辑
    LaunchedEffect(artist.id, platform) {
        val targetArtistKey = "${platform.id}:${artist.id}"
        if (loadedArtistKey == targetArtistKey && (hotSongs.isNotEmpty() || albums.isNotEmpty())) {
            loading = false
            return@LaunchedEffect
        }
        if (loadedArtistKey != targetArtistKey) {
            hotSongs = emptyList()
            albums = emptyList()
        }
        loading = true
        errorText = null
        songPage = 1
        albumPage = 1
        hasMoreSongs = true
        hasMoreAlbums = true

        var resolvedArtist = artist
        var detailHotSongs = emptyList<OnlineSongItem>()
        var detailAlbums = emptyList<OnlineAlbum>()

        val detailRes = repository.getArtistDetail(artist.id, platform)
        detailRes.onSuccess { detail ->
            if (detail.artist.name.isNotBlank() && !detail.artist.name.startsWith("歌手")) {
                resolvedArtist = detail.artist
            }
            detailHotSongs = detail.hotSongs
            detailAlbums = detail.albums
        }

        // 兜底一：若详情返回的热门歌曲为空，调用 getArtistSongs 拉取
        if (detailHotSongs.isEmpty()) {
            repository.getArtistSongs(artist.id, page = 1, pageSize = 30, platform = platform)
                .onSuccess { list ->
                    if (list.isNotEmpty()) detailHotSongs = list
                }
        }

        // 兜底二：若依然为空且歌手名称明确，通过全网歌手名搜歌检索热门曲目
        val targetName = resolvedArtist.name.ifBlank { artist.name }
        if (detailHotSongs.isEmpty() && targetName.isNotBlank() && !targetName.startsWith("歌手")) {
            repository.searchSongs(targetName, page = 1, pageSize = 30, platform = platform)
                .onSuccess { list ->
                    if (list.isNotEmpty()) detailHotSongs = list
                }
        }

        // 兜底三：若专辑为空，调用 getArtistAlbums 拉取
        if (detailAlbums.isEmpty()) {
            repository.getArtistAlbums(artist.id, page = 1, pageSize = 30, platform = platform)
                .onSuccess { list ->
                    if (list.isNotEmpty()) detailAlbums = list
                }
        }

        if (latestSceneMotionFrozen) {
            snapshotFlow { latestSceneMotionFrozen }.first { !it }
        }
        currentArtist = resolvedArtist
        hotSongs = detailHotSongs
        albums = detailAlbums
        loadedArtistKey = targetArtistKey
        hasMoreSongs = detailHotSongs.isNotEmpty()
        hasMoreAlbums = detailAlbums.isNotEmpty()
        loading = false
    }

    // 监听选中的专辑下钻拉取
    LaunchedEffect(selectedAlbum) {
        val album = selectedAlbum ?: return@LaunchedEffect
        albumLoading = true
        repository.getAlbumDetail(album.id, platform)
            .onSuccess { pair ->
                albumSongs = pair.second
                albumLoading = false
            }
            .onFailure {
                AppNoticeBus.error("加载专辑曲目失败")
                albumLoading = false
            }
    }

    val playbackSnapshot by MusicSourcePlaybackController.snapshot.collectAsState()
    var resolvingSongKey by remember { mutableStateOf<String?>(null) }

    fun playSong(item: OnlineSongItem, index: Int, queue: List<OnlineSongItem>) {
        // 歌手热门曲目与专辑属于非歌单内容，原当前播放歌单沉淀为上次播放，不再误入歌单卡片
        PlayedPlaylistManager.recordNonPlaylistPlayed()
        val songKey = "${item.platform.id}_${item.id}"
        resolvingSongKey = songKey
        scope.launch {
            try {
                when (val result = OnlinePlaybackLauncher.play(context, item, queue = queue, index = index)) {
                    OnlinePlayResult.Started -> Unit
                    OnlinePlayResult.NoSourceInstalled -> AppNoticeBus.error(
                        context.getString(R.string.online_detail_no_source)
                    )
                    OnlinePlayResult.NoMatchFound -> AppNoticeBus.error(
                        context.getString(R.string.online_detail_no_match, item.title)
                    )
                    is OnlinePlayResult.Failed -> {
                        val tip = when {
                            result.reason.contains("Timeout", ignoreCase = true) ->
                                context.getString(R.string.online_network_timeout)
                            result.reason.contains("UnknownHost", ignoreCase = true) ||
                                result.reason.contains("Connect", ignoreCase = true) ->
                                context.getString(R.string.online_network_error)
                            result.reason.isNotBlank() ->
                                context.getString(R.string.online_detail_play_failed_with_reason, item.title, result.reason)
                            else ->
                                context.getString(R.string.online_detail_play_failed, item.title)
                        }
                        AppNoticeBus.error(tip)
                    }
                }
            } finally {
                if (resolvingSongKey == songKey) {
                    resolvingSongKey = null
                }
            }
        }
    }

    fun loadMoreSongs() {
        if (loadingMore || !hasMoreSongs) return
        loadingMore = true
        val next = songPage + 1
        scope.launch {
            repository.getArtistSongs(artist.id, page = next, pageSize = 30, platform = platform)
                .onSuccess { list ->
                    if (list.isEmpty()) {
                        hasMoreSongs = false
                    } else {
                        hotSongs = hotSongs + list
                        songPage = next
                        hasMoreSongs = list.size >= 15
                    }
                    loadingMore = false
                }
                .onFailure {
                    loadingMore = false
                }
        }
    }

    fun loadMoreAlbums() {
        if (loadingMore || !hasMoreAlbums) return
        loadingMore = true
        val next = albumPage + 1
        scope.launch {
            repository.getArtistAlbums(artist.id, page = next, pageSize = 30, platform = platform)
                .onSuccess { list ->
                    if (list.isEmpty()) {
                        hasMoreAlbums = false
                    } else {
                        albums = albums + list
                        albumPage = next
                        hasMoreAlbums = list.size >= 10
                    }
                    loadingMore = false
                }
                .onFailure {
                    loadingMore = false
                }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        SmallTopAppBar(
            title = if (selectedAlbum != null) selectedAlbum!!.title else currentArtist.name,
            color = Color.Transparent,
            titleColor = colors.onSurface,
            navigationIcon = {
                IconButton(onClick = {
                    if (selectedAlbum != null) {
                        selectedAlbum = null
                        albumSongs = emptyList()
                    } else {
                        onBack()
                    }
                }) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.common_cancel),
                        tint = colors.onSurface,
                    )
                }
            },
        )

        // ── 专辑下钻详情视图 ──
        if (selectedAlbum != null) {
            val album = selectedAlbum!!
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
            ) {
                item {
                    ArtistAlbumHeader(album = album, songCount = albumSongs.size, onPlayAll = {
                        if (albumSongs.isNotEmpty()) playSong(albumSongs.first(), 0, albumSongs)
                    })
                }

                if (albumLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.primary)
                        }
                    }
                } else if (albumSongs.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("专辑暂无曲目", color = colors.secondaryText, fontSize = 14.sp)
                        }
                    }
                } else {
                    itemsIndexed(albumSongs, key = { _, s -> "${s.platform.id}_${s.id}" }) { index, song ->
                        val isSongResolving = resolvingSongKey == "${song.platform.id}_${song.id}" ||
                            ((playbackSnapshot.status == MusicSourcePlaybackStatus.Resolving ||
                                playbackSnapshot.status == MusicSourcePlaybackStatus.Preparing) &&
                                (playbackSnapshot.currentItem?.remoteId == song.id ||
                                    (playbackSnapshot.currentItem?.title.equals(song.title, ignoreCase = true) &&
                                        playbackSnapshot.currentItem?.artists?.contains(song.artist) == true)))
                        OnlineDetailSongRow(
                            index = index + 1,
                            song = song,
                            isLoading = isSongResolving,
                            onAddToPlaylist = { addToPlaylistSong = song },
                            onClick = { playSong(song, index, albumSongs) },
                        )
                    }
                }
            }
        } else {
            // ── 歌手主体视图 ──
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
            ) {
                item {
                    ArtistDetailHeader(
                        artist = currentArtist,
                        songCount = hotSongs.size,
                        onPlayAll = {
                            if (hotSongs.isNotEmpty()) playSong(hotSongs.first(), 0, hotSongs)
                        },
                    )
                }

                // 二级 Tab 选择行：【热门歌曲】与【专辑列表】
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val tabs = listOf("热门歌曲 (${hotSongs.size})", "全部专辑 (${albums.size})")
                        tabs.forEachIndexed { idx, label ->
                            val isSelected = selectedDetailTab == idx
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) colors.primary.copy(alpha = 0.16f) else colors.surface)
                                    .border(
                                        width = if (isSelected) 1.2.dp else 0.5.dp,
                                        color = if (isSelected) colors.primary else colors.outline.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { selectedDetailTab = idx }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) colors.primary else colors.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = colors.primary)
                        }
                    }
                } else if (errorText != null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text(errorText.orEmpty(), color = colors.secondaryText, fontSize = 14.sp)
                        }
                    }
                } else if (selectedDetailTab == 0) {
                    // ── 热门歌曲列表 ──
                    if (hotSongs.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("暂无歌曲", color = colors.secondaryText, fontSize = 14.sp)
                            }
                        }
                    } else {
                        itemsIndexed(hotSongs, key = { idx, s -> "${s.platform.id}_${s.id}_$idx" }) { index, song ->
                            val isSongResolving = resolvingSongKey == "${song.platform.id}_${song.id}" ||
                                ((playbackSnapshot.status == MusicSourcePlaybackStatus.Resolving ||
                                    playbackSnapshot.status == MusicSourcePlaybackStatus.Preparing) &&
                                    (playbackSnapshot.currentItem?.remoteId == song.id ||
                                        (playbackSnapshot.currentItem?.title.equals(song.title, ignoreCase = true) &&
                                            playbackSnapshot.currentItem?.artists?.contains(song.artist) == true)))
                            OnlineDetailSongRow(
                                index = index + 1,
                                song = song,
                                isLoading = isSongResolving,
                                onAddToPlaylist = { addToPlaylistSong = song },
                                onClick = { playSong(song, index, hotSongs) },
                            )
                        }
                        if (hasMoreSongs) {
                            item {
                                ArtistLoadMoreRow(
                                    loading = loadingMore,
                                    onLoadMore = { loadMoreSongs() },
                                )
                            }
                        }
                    }
                } else {
                    // ── 专辑列表 ──
                    if (albums.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Text("暂无专辑", color = colors.secondaryText, fontSize = 14.sp)
                            }
                        }
                    } else {
                        itemsIndexed(albums, key = { idx, a -> "${a.platform.id}_${a.id}_$idx" }) { _, album ->
                            ArtistAlbumRowItem(
                                album = album,
                                onClick = { selectedAlbum = album },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (hasMoreAlbums) {
                            item {
                                ArtistLoadMoreRow(
                                    loading = loadingMore,
                                    onLoadMore = { loadMoreAlbums() },
                                )
                            }
                        }
                    }
                }
            }
        }

        OnlineAddToPlaylistDialog(
            song = addToPlaylistSong,
            onDismiss = { addToPlaylistSong = null },
        )
    }
}

/** 歌手头部 Header 卡片（圆形头像、平台标签、单曲数量与播放全部大按钮） */
@Composable
private fun ArtistDetailHeader(
    artist: OnlineArtist,
    songCount: Int,
    onPlayAll: () -> Unit,
) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface.copy(alpha = 0.74f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.5.dp, colors.primary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (artist.avatarUrl.isNotBlank()) {
                    BitmapImage(
                        key = artist.avatarUrl,
                        contentDescription = artist.name,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        targetWidth = 160,
                        targetHeight = 160,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_online_artist),
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    color = colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = artist.platform.displayName,
                        color = colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    val count = if (artist.songCount > 0) artist.songCount else songCount
                    Text(
                        text = if (count > 0) "$count 首单曲" else "单曲浏览",
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        TextButton(
            text = "播放热门单曲" + if (songCount > 0) " ($songCount)" else "",
            onClick = onPlayAll,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 专辑头部卡片 */
@Composable
private fun ArtistAlbumHeader(
    album: OnlineAlbum,
    songCount: Int,
    onPlayAll: () -> Unit,
) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface.copy(alpha = 0.74f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (album.coverUrl.isNotBlank()) {
                    BitmapImage(
                        key = album.coverUrl,
                        contentDescription = album.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        targetWidth = 160,
                        targetHeight = 160,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_2_fill),
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    color = colors.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = album.artist.ifBlank { album.platform.displayName },
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                val pubTime = album.publishTime
                if (!pubTime.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "发行时间：$pubTime",
                        color = colors.secondaryText.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        TextButton(
            text = "播放专辑全部曲目 ($songCount)",
            onClick = onPlayAll,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 专辑单项条目 */
@Composable
private fun ArtistAlbumRowItem(
    album: OnlineAlbum,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (album.coverUrl.isNotBlank()) {
                BitmapImage(
                    key = album.coverUrl,
                    contentDescription = album.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    targetWidth = 108,
                    targetHeight = 108,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_music_2_fill),
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                color = colors.onSurface,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOfNotNull(
                    album.publishTime?.takeIf { it.isNotBlank() },
                    if (album.songCount > 0) "${album.songCount}首" else null,
                ).joinToString(" · ").ifBlank { album.platform.displayName },
                color = colors.secondaryText,
                fontSize = 11.5.sp,
                maxLines = 1,
            )
        }
    }
}

/** 加载更多条目 */
@Composable
private fun ArtistLoadMoreRow(
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(enabled = !loading, onClick = onLoadMore),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
        } else {
            Text(
                text = stringResource(R.string.online_artist_load_more),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}
