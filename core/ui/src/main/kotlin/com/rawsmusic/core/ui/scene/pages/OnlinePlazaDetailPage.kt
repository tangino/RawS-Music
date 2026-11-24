package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.alpha
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
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import com.rawsmusic.core.common.prefs.PlayedPlaylistManager
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Playlist

private const val ARGUMENT_SEPARATOR = "|"

/** 把「平台 + 歌单 id」编码成导航参数。歌单 id 可能含特殊字符，统一 URI 编码。 */
fun onlinePlaylistDetailArgument(platform: OnlinePlatform, playlistId: String): String =
    "${platform.id}$ARGUMENT_SEPARATOR${Uri.encode(playlistId)}"

/**
 * 在线歌单详情。
 *
 * 参数来自 `NavigationState.currentArgument`，编码见 [onlinePlaylistDetailArgument]。
 *
 * 播放走 [OnlinePlaybackLauncher]：广场只有平台元数据、没有播放地址，需要先用用户已装的
 * 音源检索候选再起播（详见该对象的注释）。
 */
@Composable
internal fun OnlinePlaylistDetailPage(
    argument: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = OnlineMusicRepository.getInstance()

    // 记忆有效参数，防止在重组瞬态或进入播放页返回时参数短暂为空导致误报“歌单参数无效”
    var rememberedArgument by rememberSaveable { mutableStateOf(argument.takeIf { it.isNotBlank() }.orEmpty()) }
    if (argument.isNotBlank() && argument != rememberedArgument) {
        rememberedArgument = argument
    }
    val effectiveArgument = argument.takeIf { it.isNotBlank() } ?: rememberedArgument

    val platform = remember(effectiveArgument) {
        OnlinePlatform.fromId(effectiveArgument.substringBefore(ARGUMENT_SEPARATOR))
    }
    val playlistId = remember(effectiveArgument) {
        Uri.decode(effectiveArgument.substringAfter(ARGUMENT_SEPARATOR, ""))
    }

    var detail by remember { mutableStateOf<Pair<OnlinePlaylist, List<OnlineSongItem>>?>(null) }
    var loadedArgument by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var addToPlaylistSong by remember { mutableStateOf<OnlineSongItem?>(null) }
    val latestSceneMotionFrozen by rememberUpdatedState(LocalSceneBackgroundFrozen.current)

    val favorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()
    val loadFailedMessage = stringResource(R.string.online_detail_load_failed)
    val noSourceMessage = stringResource(R.string.online_detail_no_source)
    val noMatchMessage = stringResource(R.string.online_detail_no_match)
    val playFailedMessage = stringResource(R.string.online_detail_play_failed)
    val addedMessage = stringResource(R.string.online_detail_added)
    val removedMessage = stringResource(R.string.online_detail_removed)

    val playlist = detail?.first
    val songs = detail?.second.orEmpty()
    val favoriteKey = remember(platform, playlistId) {
        platform?.let { OnlineSourceMediaMapper.onlinePlaylistKey(it, playlistId) }
    }
    val isFavorite = favoriteKey != null && favorites.any { it.playlistKey == favoriteKey }

    LaunchedEffect(effectiveArgument, reloadToken) {
        if (platform == null || playlistId.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        // 保活复用：当再次进入同一个歌单且已有缓存数据时，跳过重复拉取，实现零延迟顺滑转场。
        if (loadedArgument == effectiveArgument && detail != null && reloadToken == 0) {
            loading = false
            return@LaunchedEffect
        }
        if (loadedArgument != effectiveArgument) {
            detail = null
        }
        loading = true
        errorText = null
        val result = repository.getPlaylistDetail(playlistId = playlistId, platform = platform)
        if (latestSceneMotionFrozen) {
            snapshotFlow { latestSceneMotionFrozen }.first { !it }
        }
        result
            .onSuccess {
                detail = it
                loadedArgument = effectiveArgument
            }
            .onFailure {
                if (detail == null) {
                    errorText = loadFailedMessage
                }
                AppNoticeBus.error(loadFailedMessage)
            }
        loading = false
    }

    // 进入详情时同步一次收藏，保证「收藏」按钮状态正确。
    LaunchedEffect(Unit) {
        runCatching { OnlinePlaylistFavoriteStore.refresh(context) }
    }

    val playbackSnapshot by MusicSourcePlaybackController.snapshot.collectAsState()
    var resolvingSongKey by remember { mutableStateOf<String?>(null) }

    fun playSong(item: OnlineSongItem, index: Int) {
        if (playlist != null) {
            PlayedPlaylistManager.recordPlayedPlaylist(
                id = playlist.id,
                name = playlist.title,
                isOnline = true,
                platformId = playlist.platform.id,
                coverUrl = playlist.coverUrl,
                trackCount = playlist.trackCount.coerceAtLeast(songs.size),
            )
        }
        val songKey = "${item.platform.id}_${item.id}"
        resolvingSongKey = songKey
        scope.launch {
            try {
                when (val result = OnlinePlaybackLauncher.play(context, item, queue = songs, index = index)) {
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

    val listState = rememberLazyListState()
    // 仅当头部主标题滚出视口后，才在顶部栏淡入标题，避免页面初始状态上下标题重复
    val showCollapsedTitle by remember(playlist) {
        derivedStateOf {
            playlist != null && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 140)
        }
    }
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = if (showCollapsedTitle) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "online_detail_topbar_title_alpha",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.nav_scene_online_playlist_detail),
                    tint = colors.onSurface,
                )
            }
            Text(
                text = playlist?.title.orEmpty(),
                color = colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .alpha(topBarTitleAlpha),
            )
            platform?.let { currentPlatform ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = currentPlatform.displayName,
                        color = colors.onSurface.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                platform == null || playlistId.isBlank() ->
                    OnlineDetailMessage(stringResource(R.string.online_detail_invalid_argument))
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
                errorText != null -> OnlineDetailError(errorText.orEmpty()) { reloadToken++ }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 180.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "online_detail_header") {
                        playlist?.let { header ->
                            OnlineDetailHeader(
                                playlist = header,
                                trackCount = header.trackCount.coerceAtLeast(songs.size),
                                isFavorite = isFavorite,
                                onToggleFavorite = {
                                    scope.launch {
                                        runCatching {
                                            if (isFavorite) {
                                                OnlinePlaylistFavoriteStore.remove(context, favoriteKey)
                                                AppNoticeBus.post(removedMessage)
                                            } else {
                                                OnlinePlaylistFavoriteStore.add(context, header)
                                                AppNoticeBus.post(addedMessage)
                                            }
                                        }.onFailure { AppNoticeBus.error(loadFailedMessage) }
                                    }
                                },
                                onPlayAll = { if (songs.isNotEmpty()) playSong(songs.first(), 0) },
                            )
                        }
                    }

                    if (songs.isEmpty()) {
                        item { OnlineDetailMessage(stringResource(R.string.online_detail_empty)) }
                    } else {
                        itemsIndexed(
                            items = songs,
                            key = { _, song -> "${song.platform.id}_${song.id}" },
                        ) { index, song ->
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
                                onClick = { playSong(song, index) },
                            )
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

@Composable
private fun OnlineDetailHeader(
    playlist: OnlinePlaylist,
    trackCount: Int,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
) {
    val colors = themeColors()
    var descExpanded by remember(playlist.id) { mutableStateOf(false) }
    val cleanDescription = remember(playlist.description) {
        playlist.description?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(top = 4.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // ── 封面 + 右下角播放量角标（与广场卡片统一视觉语言，不再在右侧文字堆里重复） ──
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Folder,
                    contentDescription = null,
                    tint = colors.secondaryText.copy(alpha = 0.35f),
                    modifier = Modifier.size(44.dp),
                )
                if (playlist.coverUrl.isNotBlank()) {
                    BitmapImage(
                        key = playlist.coverUrl,
                        contentDescription = playlist.title,
                        modifier = Modifier.fillMaxSize(),
                        targetWidth = 256,
                        targetHeight = 256,
                        showDefaultArtwork = false,
                    )
                }
                if (playlist.playCount > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.56f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "▶",
                            fontSize = 7.5.sp,
                            color = Color.White,
                        )
                        Text(
                            text = formatDetailPlayCount(playlist.playCount),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // ── 右侧唯一主标题 + 创作者 + 简介 + 曲目数 ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 116.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = playlist.title,
                        color = colors.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val creator = playlist.creatorName?.trim().orEmpty()
                    if (creator.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val avatar = playlist.creatorAvatarUrl?.trim().orEmpty()
                            if (avatar.isNotEmpty()) {
                                BitmapImage(
                                    key = avatar,
                                    contentDescription = creator,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape),
                                    targetWidth = 64,
                                    targetHeight = 64,
                                    showDefaultArtwork = false,
                                )
                            }
                            Text(
                                text = creator,
                                color = colors.onSurface.copy(alpha = 0.78f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (cleanDescription.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = cleanDescription,
                            color = colors.onSurface.copy(alpha = 0.62f),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            maxLines = if (descExpanded) 8 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descExpanded = !descExpanded },
                        )
                    }
                }

                if (trackCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.online_detail_track_count, trackCount),
                        color = colors.onSurface.copy(alpha = 0.65f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 通栏主次操作按钮 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.primary)
                    .clickable(onClick = onPlayAll)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.online_detail_play_all),
                    color = colors.onPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isFavorite) colors.primary.copy(alpha = 0.14f)
                        else colors.surface.copy(alpha = 0.78f),
                    )
                    .border(
                        width = 0.6.dp,
                        color = if (isFavorite) colors.primary.copy(alpha = 0.45f)
                        else colors.outline.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_playlist_favorite_filled
                        else R.drawable.ic_playlist_favorite_outline,
                    ),
                    contentDescription = null,
                    tint = if (isFavorite) colors.primary else colors.onSurface,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (isFavorite) R.string.online_detail_unfavorite else R.string.online_detail_favorite,
                    ),
                    color = if (isFavorite) colors.primary else colors.onSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.outline.copy(alpha = 0.14f)),
        )
    }
}

@Composable
internal fun OnlineDetailSongRow(
    index: Int,
    song: OnlineSongItem,
    isLoading: Boolean = false,
    onAddToPlaylist: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            color = colors.secondaryText,
            fontSize = 12.sp,
            modifier = Modifier.width(26.dp),
        )
        Spacer(Modifier.width(4.dp))
        val cover = song.coverUrl
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Music,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
            if (!cover.isNullOrBlank()) {
                BitmapImage(
                    key = cover,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    targetWidth = 128,
                    targetHeight = 128,
                    showDefaultArtwork = false,
                )
            }
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isVip) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFF5252).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .border(
                                width = 0.8.dp,
                                color = Color(0xFFFF5252),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "VIP",
                            color = Color(0xFFFF5252),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, song.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song.durationMs > 0L) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatDuration(song.durationMs),
                color = colors.secondaryText,
                fontSize = 11.sp,
            )
        }
        if (onAddToPlaylist != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAddToPlaylist),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Playlist,
                    contentDescription = stringResource(R.string.online_add_to_playlist),
                    tint = colors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OnlineDetailMessage(message: String) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
    }
}

@Composable
private fun OnlineDetailError(message: String, onRetry: () -> Unit) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.online_detail_retry),
                onClick = onRetry,
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun formatDetailPlayCount(count: Long): String = when {
    count >= 100_000_000L -> "${count / 100_000_000L}亿"
    count >= 10_000L -> "${count / 10_000L}万"
    else -> count.toString()
}
