package com.rawsmusic.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.ui.settings.themeColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PlaylistDetailScreen(
    playlistStore: PlaylistStore,
    playlist: UserPlaylist?,
    librarySongs: List<AudioFile>,
    playingSongId: Long,
    onBack: () -> Unit,
    onPlaySong: (List<AudioFile>, Int) -> Unit,
    onRemoveSong: (AudioFile) -> Unit
) {
    // 使用 remember(playlist, librarySongs) 缓存解析结果，避免每次重组在主线程重复计算
    val songs = remember(playlist, librarySongs) {
        playlistStore.resolveOrderedPlaylistSongs(playlist, librarySongs)
    }
    val onlineCount = remember(songs) { songs.count { it.path.startsWith("online://") } }
    val localCount = remember(songs, onlineCount) { songs.size - onlineCount }
    val state = rememberComposeVirtualListState("playlist-${playlist?.id.orEmpty()}")
    var exportDialog by remember { mutableStateOf(false) }
    val colors = themeColors()

    val exportHeaderAction: (@Composable () -> Unit)? = if (playlist != null && !playlist.id.startsWith("attitude:")) {
        {
            IconButton(
                onClick = { exportDialog = true },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist_export),
                    contentDescription = stringResource(R.string.playlist_export_action),
                    tint = colors.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    } else null

    val statsText = when {
        localCount > 0 && onlineCount > 0 ->
            "混合歌单 · 共 ${songs.size} 首 (本地 $localCount · 网络 $onlineCount)"
        onlineCount > 0 ->
            "网络歌单 · 共 ${songs.size} 首"
        else ->
            stringResource(com.rawsmusic.core.ui.R.string.library_statistics_songs, songs.size)
    }
        LibraryListScaffold(
            title = playlist?.name ?: stringResource(R.string.playlist_fallback_name),
            sceneId = NavScene.PLAYLIST_DETAIL_PAGE.name,
            statisticsText = statsText,
            onBack = onBack,
            virtualListState = state,
            onPlay = { if (songs.isNotEmpty()) onPlaySong(songs, 0) },
            onShuffle = { if (songs.isNotEmpty()) onPlaySong(songs.shuffled(), 0) },
        headerTrailingContent = exportHeaderAction,
        contentOverlap = 0.dp,
        modifier = Modifier.fillMaxSize()
    ) { topPadding, backdropSource ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().then(backdropSource),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.playlist_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 15.sp
                )
            }
        } else {
            ComposeVirtualListFull(
                songs = songs,
                playingSongId = playingSongId,
                state = state,
                contentTopPadding = topPadding,
                modifier = Modifier.fillMaxSize().then(backdropSource),
                onSongClick = { _, index -> onPlaySong(songs, index) },
                onSongLongClick = { song, _ -> onRemoveSong(song) }
            )
        }
    }

    if (exportDialog && playlist != null) {
        ExportPlaylistDialog(
            playlist = playlist,
            onDismiss = { exportDialog = false },
        )
    }
}
