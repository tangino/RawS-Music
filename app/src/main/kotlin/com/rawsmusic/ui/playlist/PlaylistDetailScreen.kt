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
    // 本地曲与在线曲**并列**取（见 PlaylistStore.playlistEntries / P2-05b）：
    // 只用 resolveSongs 的话，在线曲目会被静默丢掉 —— 导入的 QQ/酷狗歌单会"看起来成功但列表是空的"。
    val entries = playlistStore.playlistEntries(playlist, librarySongs)
    // 混合歌单保持原顺序，在线歌曲若在本地曲库有同名同歌手同时间音源，优先匹配为本地音源
    val songs = playlistStore.resolveOrderedPlaylistSongs(playlist, librarySongs)
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
            entries.local.isNotEmpty() && entries.online.isNotEmpty() ->
                "共 ${songs.size} 首 (本地 ${entries.local.size} · 网络 ${entries.online.size})"
            entries.online.isNotEmpty() ->
                "共 ${songs.size} 首 (网络歌单)"
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
