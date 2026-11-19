package com.rawsmusic.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.player.shareSelectedAudio
import com.rawsmusic.core.ui.widget.selection.SongMultiSelectActionBar
import com.rawsmusic.core.ui.widget.selection.rememberSongMultiSelectState
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadStatus
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadTask
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.ui.settings.appFontFamily
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
    val matchVersion by playlistStore.matchCacheVersion.collectAsState()
    val allDownloadTasks by MusicSourceDownloadController.tasks.collectAsState()
    // 监听本地匹配缓存版本（离线下载完成时自动自愈并刷新）
    val songs = remember(playlist, librarySongs, matchVersion) {
        playlistStore.resolveOrderedPlaylistSongs(playlist, librarySongs)
    }
    val playlistDownloadTasks = remember(playlist, allDownloadTasks) {
        collectPlaylistDownloadTasks(playlist, allDownloadTasks)
    }
    val activeDownloadTasks = remember(playlistDownloadTasks) {
        playlistDownloadTasks.filter { it.isActive }
    }
    val failedDownloadTasks = remember(playlistDownloadTasks) {
        playlistDownloadTasks
            .distinctBy { it.item.stableIdentity }
            .filter { it.status == MusicSourceDownloadStatus.Failed }
    }
    var dismissedFailedSignature by remember(playlist?.id) { mutableStateOf("") }
    val failedSignature = remember(failedDownloadTasks) {
        failedDownloadTasks.joinToString("|") { "${it.id}:${it.updatedAtMs}" }
    }
    val showFailedBanner = activeDownloadTasks.isEmpty() &&
        failedDownloadTasks.isNotEmpty() &&
        failedSignature != dismissedFailedSignature

    val onlineCount = remember(songs) { songs.count { it.path.startsWith("online://") } }
    val localCount = remember(songs, onlineCount) { songs.size - onlineCount }
    val state = rememberComposeVirtualListState("playlist-${playlist?.id.orEmpty()}")
    val multiSelectState = rememberSongMultiSelectState()
    var exportDialog by remember { mutableStateOf(false) }
    var offlineSaveDialog by remember { mutableStateOf(false) }
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

                // 离线保存入口（非多选且存在网络歌曲或存在进行中下载任务时显示）
                if (!multiSelectState.isActive && (onlineCount > 0 || activeDownloadTasks.isNotEmpty()) && playlist != null) {
                    IconButton(
                        onClick = { offlineSaveDialog = true },
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (activeDownloadTasks.isNotEmpty()) {
                                val lead = activeDownloadTasks.firstOrNull {
                                    it.status == MusicSourceDownloadStatus.Downloading && it.totalBytes > 0L
                                }
                                if (lead != null) {
                                    CircularProgressIndicator(
                                        progress = lead.progressFraction,
                                        color = colors.primary,
                                        trackColor = colors.primary.copy(alpha = 0.18f),
                                        strokeWidth = 2.2.dp,
                                        modifier = Modifier.size(28.dp),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = colors.primary,
                                        strokeWidth = 2.2.dp,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_source_download),
                                contentDescription = stringResource(R.string.download_action_offline_save),
                                tint = colors.primary,
                                modifier = Modifier.size(if (activeDownloadTasks.isNotEmpty()) 16.dp else 22.dp),
                            )
                        }
                    }
                }

                // 导出歌单入口（非多选时显示）
                if (!multiSelectState.isActive && playlist != null) {
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
        modifier = Modifier.fillMaxSize().bottomChromeNestedScroll()
    ) { topPadding, backdropSource ->
        val listTopPadding = when {
            activeDownloadTasks.isNotEmpty() -> topPadding + 78.dp
            showFailedBanner -> topPadding + 68.dp
            else -> topPadding
        }
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
                contentTopPadding = listTopPadding,
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

        // 歌单实时离线下载进度横幅（活跃下载期间浮于列表顶部）
        AnimatedVisibility(
            visible = activeDownloadTasks.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topPadding + 4.dp, start = 14.dp, end = 14.dp),
        ) {
            PlaylistDownloadProgressBanner(
                activeTasks = activeDownloadTasks,
                onClickDetail = { offlineSaveDialog = true },
                onCancelAll = {
                    activeDownloadTasks.forEach { MusicSourceDownloadController.cancel(it.id) }
                },
            )
        }

        // 歌单离线下载失败提示横幅（支持一键重试）
        AnimatedVisibility(
            visible = showFailedBanner,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topPadding + 4.dp, start = 14.dp, end = 14.dp),
        ) {
            PlaylistDownloadFailedBanner(
                failedTasks = failedDownloadTasks,
                onRetryAll = {
                    failedDownloadTasks.forEach { MusicSourceDownloadController.retry(context, it.id) }
                },
                onDismiss = { dismissedFailedSignature = failedSignature },
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
        onDownloadSongs = if (onlineCount > 0) {
            { selected ->
                val onlineSelected = selected.filter { it.path.startsWith("online://") }
                if (onlineSelected.isNotEmpty()) {
                    val quality = RawSourceQuality.fromKey(AppPreferences.Download.defaultQuality)
                    var queuedCount = 0
                    onlineSelected.forEach { song ->
                        val onlineItem = OnlineSourceMediaMapper.toOnlineSongItem(song) ?: return@forEach
                        val rawItem = OnlineSourceMediaMapper.toRawSourceMediaItem(onlineItem)
                        MusicSourceDownloadController.enqueue(context, rawItem, quality)
                        queuedCount++
                    }
                    if (queuedCount > 0) {
                        AppNoticeBus.post(
                            message = context.getString(R.string.download_queued_notice, queuedCount),
                            icon = AppNoticeIcon.DOWNLOAD,
                        )
                    }
                }
                multiSelectState.exit()
            }
        } else null,
        onShare = { selected ->
            shareSelectedAudio(context, selected)
        },
    )

    if (offlineSaveDialog && playlist != null) {
        OfflineSavePlaylistDialog(
            songs = songs,
            playlist = playlist,
            onDismiss = { offlineSaveDialog = false },
        )
    }

    if (exportDialog && playlist != null) {
        ExportPlaylistDialog(
            playlist = playlist,
            resolvedSongs = songs,
            onDismiss = { exportDialog = false },
        )
    }
}

/**
 * 歌单详情页顶部实时离线保存进度卡片。
 */
@Composable
private fun PlaylistDownloadProgressBanner(
    activeTasks: List<MusicSourceDownloadTask>,
    onClickDetail: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val colors = themeColors()
    val lead = activeTasks.firstOrNull { it.status == MusicSourceDownloadStatus.Downloading }
        ?: activeTasks.firstOrNull()
        ?: return

    val statusText = when (lead.status) {
        MusicSourceDownloadStatus.Resolving -> "正在解析音源..."
        MusicSourceDownloadStatus.Queued -> "排队等待下载..."
        MusicSourceDownloadStatus.Paused -> "下载已暂停"
        MusicSourceDownloadStatus.Downloading -> {
            if (lead.totalBytes > 0L) {
                val percent = (lead.progressFraction * 100f).toInt()
                "正在离线保存 $percent% · ${formatDownloadBytes(lead.bytesDownloaded)} / ${formatDownloadBytes(lead.totalBytes)}"
            } else {
                "正在离线保存..."
            }
        }
        else -> "正在处理..."
    }
    val remainingSuffix = if (activeTasks.size > 1) "（共 ${activeTasks.size} 首进行中）" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClickDetail)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${lead.item.title}$remainingSuffix",
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusText,
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "取消",
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = appFontFamily(),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancelAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (lead.status == MusicSourceDownloadStatus.Downloading && lead.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = lead.progressFraction,
                color = colors.primary,
                trackColor = colors.onSurfaceVariant.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        } else {
            LinearProgressIndicator(
                color = colors.primary,
                trackColor = colors.onSurfaceVariant.copy(alpha = 0.14f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/**
 * 歌单详情页顶部离线下载失败提示卡片（支持一键重试或关闭）。
 */
@Composable
private fun PlaylistDownloadFailedBanner(
    failedTasks: List<MusicSourceDownloadTask>,
    onRetryAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = themeColors()
    val first = failedTasks.firstOrNull() ?: return
    val titleText = if (failedTasks.size == 1) {
        "「${first.item.title}」离线保存失败"
    } else {
        "${failedTasks.size} 首歌曲离线保存失败"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = first.error.ifBlank { "网络连接中断，请点击重试" },
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "重试",
            color = colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = appFontFamily(),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetryAll)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = "关闭",
            color = colors.secondaryText,
            fontSize = 12.sp,
            fontFamily = appFontFamily(),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
