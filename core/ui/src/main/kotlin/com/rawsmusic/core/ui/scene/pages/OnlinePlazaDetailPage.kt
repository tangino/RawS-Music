package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
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
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
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

    val platform = remember(argument) { OnlinePlatform.fromId(argument.substringBefore(ARGUMENT_SEPARATOR)) }
    val playlistId = remember(argument) {
        Uri.decode(argument.substringAfter(ARGUMENT_SEPARATOR, ""))
    }

    var detail by remember { mutableStateOf<Pair<OnlinePlaylist, List<OnlineSongItem>>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var addToPlaylistSong by remember { mutableStateOf<OnlineSongItem?>(null) }

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

    LaunchedEffect(argument, reloadToken) {
        if (platform == null || playlistId.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorText = null
        repository.getPlaylistDetail(playlistId = playlistId, platform = platform)
            .onSuccess { detail = it }
            .onFailure {
                detail = null
                errorText = loadFailedMessage
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        SmallTopAppBar(
            title = playlist?.title ?: stringResource(R.string.nav_scene_online_playlist_detail),
            color = Color.Transparent,
            titleColor = colors.onSurface,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.nav_scene_online_playlist_detail),
                        tint = colors.onSurface,
                    )
                }
            },
        )

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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 180.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        playlist?.let { header ->
                            OnlineDetailHeader(
                                playlist = header,
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
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
) {
    val colors = themeColors()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Folder,
                    contentDescription = null,
                    tint = colors.secondaryText.copy(alpha = 0.35f),
                    modifier = Modifier.size(44.dp)
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
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlist.trackCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.online_detail_track_count, playlist.trackCount),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                    )
                }
                if (playlist.playCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.online_detail_play_count,
                            formatDetailPlayCount(playlist.playCount),
                        ),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                    )
                }
                playlist.creatorName?.takeIf { it.isNotBlank() }?.let { creator ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.online_detail_creator, creator),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(R.string.online_detail_play_all),
                onClick = onPlayAll,
            )
            TextButton(
                text = stringResource(
                    if (isFavorite) R.string.online_detail_unfavorite else R.string.online_detail_favorite,
                ),
                onClick = onToggleFavorite,
            )
        }
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
