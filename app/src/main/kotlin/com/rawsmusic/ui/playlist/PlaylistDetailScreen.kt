package com.rawsmusic.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.player.shareSelectedAudio
import com.rawsmusic.core.ui.widget.selection.SongMultiSelectActionBar
import com.rawsmusic.core.ui.widget.selection.rememberSongMultiSelectState
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.ui.settings.themeColors
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PlaylistDetailScreen(
    playlistStore: PlaylistStore,
    playlist: UserPlaylist?,
    librarySongs: List<AudioFile>,
    playingSongId: Long,
    onBack: () -> Unit,
    onPlaySong: (List<AudioFile>, Int) -> Unit,
    onRemoveSong: (AudioFile) -> Unit,
    onRemoveSongs: ((List<AudioFile>) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 使用 remember(playlist, librarySongs) 缓存解析结果，避免每次重组在主线程重复计算
    val songs = remember(playlist, librarySongs) {
        playlistStore.resolveOrderedPlaylistSongs(playlist, librarySongs)
    }
    val onlineCount = remember(songs) { songs.count { it.path.startsWith("online://") } }
    val localCount = remember(songs, onlineCount) { songs.size - onlineCount }
    val state = rememberComposeVirtualListState("playlist-${playlist?.id.orEmpty()}")
    val multiSelectState = rememberSongMultiSelectState()
    var exportDialog by remember { mutableStateOf(false) }
    val colors = themeColors()

    // 处于多选模式时，按系统返回键优先退出多选
    BackHandler(enabled = multiSelectState.isActive) {
        multiSelectState.exit()
    }

    val headerTrailingContent: (@Composable () -> Unit)? = if (songs.isNotEmpty() || (playlist != null && !playlist.id.startsWith("attitude:"))) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 多选模式切换入口
                if (songs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (multiSelectState.isActive) {
                                multiSelectState.exit()
                            } else {
                                multiSelectState.enter()
                            }
                        },
                        modifier = Modifier.size(42.dp),
                    ) {
                        if (multiSelectState.isActive) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Close,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_select_all_line),
                                contentDescription = stringResource(com.rawsmusic.core.ui.R.string.library_action_select),
                                tint = colors.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // 导出歌单入口（非多选时且非态度歌单时显示）
                if (!multiSelectState.isActive && playlist != null && !playlist.id.startsWith("attitude:")) {
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

    val selectedPositions = remember(songs, multiSelectState.isActive, multiSelectState.selectedKeys) {
        multiSelectState.computeListSelectedPositions(songs)
    }

    LibraryListScaffold(
        title = playlist?.name ?: stringResource(R.string.playlist_fallback_name),
        sceneId = NavScene.PLAYLIST_DETAIL_PAGE.name,
        statisticsText = statsText,
        onBack = {
            if (multiSelectState.isActive) {
                multiSelectState.exit()
            } else {
                onBack()
            }
        },
        virtualListState = state,
        onSelect = { if (songs.isNotEmpty()) multiSelectState.enter() },
        onPlay = { if (songs.isNotEmpty()) onPlaySong(songs, 0) },
        onShuffle = { if (songs.isNotEmpty()) onPlaySong(songs.shuffled(), 0) },
        headerTrailingContent = headerTrailingContent,
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
                selectedPositions = selectedPositions,
                state = state,
                contentTopPadding = topPadding,
                modifier = Modifier.fillMaxSize().then(backdropSource),
                onSongClick = { song, index ->
                    if (multiSelectState.isActive) {
                        multiSelectState.toggle(song)
                    } else {
                        onPlaySong(songs, index)
                    }
                },
                onSongLongClick = { song, _ ->
                    if (!multiSelectState.isActive) {
                        multiSelectState.enter(song)
                    } else {
                        multiSelectState.toggle(song)
                    }
                }
            )
        }
    }

    // 多选操作底栏（下一首、加入队列、添加到歌单、从歌单移出、分享等）
    SongMultiSelectActionBar(
        state = multiSelectState,
        songs = songs,
        playlistName = playlist?.name.orEmpty(),
        onPlayNext = { selected ->
            val pc = (context as? MainActivity)?.playerController
            selected.asReversed().forEach { song -> pc?.playNext(song) }
            AppNoticeBus.post(
                message = context.getString(R.string.ui_play_next_added),
                icon = AppNoticeIcon.PLAY_NEXT,
            )
            multiSelectState.exit()
        },
        onAddToQueue = { selected ->
            val pc = (context as? MainActivity)?.playerController
            selected.forEach { song -> pc?.addToQueue(song) }
            AppNoticeBus.post(
                message = context.getString(R.string.ui_queue_added),
                icon = AppNoticeIcon.QUEUE,
            )
            multiSelectState.exit()
        },
        onAddToPlaylist = { selected ->
            (context as? MainActivity)?.showPlaylistPickerForSongs(selected)
            multiSelectState.exit()
        },
        onRemoveFromPlaylist = if (playlist != null) {
            { selected ->
                if (onRemoveSongs != null) {
                    onRemoveSongs(selected)
                } else {
                    selected.forEach(onRemoveSong)
                }
                multiSelectState.exit()
            }
        } else null,
        onShare = { selected ->
            shareSelectedAudio(context, selected)
        },
    )

    if (exportDialog && playlist != null) {
        ExportPlaylistDialog(
            playlist = playlist,
            onDismiss = { exportDialog = false },
        )
    }
}
