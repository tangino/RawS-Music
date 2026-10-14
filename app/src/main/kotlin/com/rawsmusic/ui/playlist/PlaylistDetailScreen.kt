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
    // 在线条目用 toAudioFile() 就能进同一个虚拟列表：它的 id 是负数虚拟 id、path 就是 online:// 串，
    // 都不会与本地曲冲突；播放时由调用方按 path 前缀分流（见 AppPageRenderer）。
    val songs = entries.local + entries.online.map { it.toAudioFile() }
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

    LibraryListScaffold(
        title = playlist?.name ?: stringResource(R.string.playlist_fallback_name),
        sceneId = NavScene.PLAYLIST_DETAIL_PAGE.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_songs, songs.size),
        onBack = onBack,
        virtualListState = state,
        // 随机播放只取本地曲：在线曲需要逐首解析候选（见 P2-05b），把它混进队列会让播放器
        // 拿到 online:// 这样的假路径。
        onShuffle = { if (entries.local.isNotEmpty()) onPlaySong(entries.local.shuffled(), 0) },
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
