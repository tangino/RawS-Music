package com.rawsmusic.core.ui.widget.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import androidx.compose.ui.window.PopupProperties
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.predictiveBottomSheetMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 沉浸式浮动批量多选操作底栏（Action Bar）。
 *
 * 遵循 Miuix 极简圆角与毛玻璃规范，提供快捷操作：
 * 1. 添加到歌单
 * 2. 从歌曲库移除（软移除）
 * 3. 删除歌曲（支持安全确认弹窗与可选物理删除）
 * 4. 辅助操作（加入队列、下一首播放、分享等）
 */
@Composable
fun SongMultiSelectActionBar(
    state: SongMultiSelectState,
    songs: List<AudioFile>,
    onAddToPlaylist: ((List<AudioFile>) -> Unit)? = null,
    onRemoveFromLibrary: ((List<AudioFile>) -> Unit)? = null,
    onDeleteSongs: ((List<AudioFile>, Boolean) -> Unit)? = null,
    onRemoveFromPlaylist: ((List<AudioFile>) -> Unit)? = null,
    playlistName: String = "",
    onAddToQueue: ((List<AudioFile>) -> Unit)? = null,
    onPlayNext: ((List<AudioFile>) -> Unit)? = null,
    onShare: ((List<AudioFile>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!state.isActive) return

    val scheme = MiuixTheme.colorScheme
    val selectedSongs = remember(state.selectedKeys, songs) { state.getSelectedSongs(songs) }
    val allSelected = remember(state.selectedKeys, songs) { state.isAllSelected(songs) }
    val hasSelection = selectedSongs.isNotEmpty()
    val canRange = state.selectedCount >= 2

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRemoveFromPlaylistDialog by remember { mutableStateOf(false) }

    val isAnyDialogShowing = showRemoveDialog || showDeleteDialog || showRemoveFromPlaylistDialog

    ActivityOverlayBackOwner(active = state.isActive && !isAnyDialogShowing)

    val predictiveProgress = rememberPredictiveDialogProgress(
        enabled = state.isActive && !isAnyDialogShowing,
        onDismissRequest = { state.exit() },
    )

    if (state.isActive && !isAnyDialogShowing) {
        Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = {},
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        AnimatedVisibility(
            visible = state.isActive,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 400),
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 200),
            ),
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .rawStableNavigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .predictiveBottomSheetMotion(
                        progress = predictiveProgress,
                        translationY = 140.dp,
                    )
                    .graphicsLayer {
                        shadowElevation = 4.dp.toPx()
                        shape = RoundedCornerShape(22.dp)
                        clip = false
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                // 顶部控制条：全选 / 选择区间 / 计数 / 关闭
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 全选
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { state.toggleAll(songs) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionCheckSquare(checked = allSelected)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.library_action_select_all),
                                color = scheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // 选择区间
                        Row(
                            modifier = Modifier
                                .graphicsLayer { alpha = if (canRange) 1f else 0.35f }
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = canRange) { state.toggleRange(songs) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_selection_range),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(scheme.onSurface),
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.songs_selection_range),
                                color = scheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    // 计数居中
                    Text(
                        text = stringResource(R.string.songs_selection_selected_count, state.selectedCount),
                        color = scheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    // 退出多选
                    IconButton(
                        onClick = { state.exit() },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                            tint = scheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // 分隔细线
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outline.copy(alpha = 0.12f)),
                )

                // 核心操作按钮栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 1. 下一首播放
                    if (onPlayNext != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_play_next),
                            drawableRes = R.drawable.ic_speed_fill,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) onPlayNext(selectedSongs)
                            },
                        )
                    }

                    // 2. 加入队列
                    if (onAddToQueue != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_add_to_queue),
                            icon = MiuixIcons.Regular.Music,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) onAddToQueue(selectedSongs)
                            },
                        )
                    }

                    // 3. 添加到歌单
                    if (onAddToPlaylist != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_add_to_playlist),
                            icon = MiuixIcons.Regular.ListView,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) onAddToPlaylist(selectedSongs)
                            },
                        )
                    }

                    // 4. 从歌单移除
                    if (onRemoveFromPlaylist != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_remove_from_playlist),
                            drawableRes = R.drawable.ic_delete,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) showRemoveFromPlaylistDialog = true
                            },
                        )
                    }

                    // 5. 从库移除（软移除）
                    if (onRemoveFromLibrary != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_remove_from_library),
                            drawableRes = R.drawable.ic_delete,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) showRemoveDialog = true
                            },
                        )
                    }

                    // 6. 分享
                    if (onShare != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_share),
                            drawableRes = R.drawable.ic_share,
                            enabled = hasSelection,
                            onClick = {
                                if (hasSelection) onShare(selectedSongs)
                            },
                        )
                    }

                    // 7. 删除歌曲（物理彻底删除选项）
                    if (onDeleteSongs != null) {
                        ActionBarActionButton(
                            title = stringResource(R.string.songs_action_delete_selected),
                            icon = MiuixIcons.Regular.Delete,
                            enabled = hasSelection,
                            danger = true,
                            onClick = {
                                if (hasSelection) showDeleteDialog = true
                            },
                        )
                    }
                }
            }
        }
    }
    }

    // 从歌单移出确认对话框
    if (showRemoveFromPlaylistDialog && onRemoveFromPlaylist != null) {
        RemoveFromPlaylistConfirmDialog(
            count = selectedSongs.size,
            playlistName = playlistName,
            onDismiss = { showRemoveFromPlaylistDialog = false },
            onConfirm = {
                showRemoveFromPlaylistDialog = false
                onRemoveFromPlaylist(selectedSongs)
                state.exit()
            },
        )
    }

    // 从歌曲库移除确认对话框（软移除）
    if (showRemoveDialog && onRemoveFromLibrary != null) {
        RemoveFromLibraryConfirmDialog(
            count = selectedSongs.size,
            onDismiss = { showRemoveDialog = false },
            onConfirm = {
                showRemoveDialog = false
                onRemoveFromLibrary(selectedSongs)
                state.exit()
            },
        )
    }

    // 删除歌曲确认对话框（含可选物理删除）
    if (showDeleteDialog && onDeleteSongs != null) {
        DeleteSongsConfirmDialog(
            count = selectedSongs.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { deletePhysicalFiles ->
                showDeleteDialog = false
                onDeleteSongs(selectedSongs, deletePhysicalFiles)
                state.exit()
            },
        )
    }
}

/** 底部操作栏单项按钮 */
@Composable
private fun ActionBarActionButton(
    title: String,
    icon: ImageVector? = null,
    drawableRes: Int? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val dangerColor = Color(0xFFEF5350)
    val contentColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        danger -> dangerColor
        else -> scheme.onSurface
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        } else if (drawableRes != null) {
            Image(
                painter = painterResource(drawableRes),
                contentDescription = title,
                colorFilter = ColorFilter.tint(contentColor),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 统一方块复选框 */
@Composable
private fun SelectionCheckSquare(checked: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) scheme.primary else Color.Transparent)
            .border(
                width = 1.2.dp,
                color = if (checked) scheme.primary else scheme.onSurface.copy(alpha = 0.55f),
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = scheme.onPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 从歌曲库移除确认对话框（软移除，不删物理文件） */
@Composable
fun RemoveFromLibraryConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var dialogReady by remember { mutableStateOf(false) }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        delay(100L)
        dialogReady = true
    }

    Dialog(
        onDismissRequest = { if (dialogReady) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = { if (dialogReady) onDismiss() }
                )
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    ),
                color = scheme.surface,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = stringResource(R.string.songs_action_remove_dialog_title),
                        color = scheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        text = stringResource(R.string.songs_action_remove_dialog_message, count),
                        color = scheme.onSurface.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiuixTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        MiuixButton(
                            onClick = onConfirm,
                            colors = MiuixButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.songs_action_remove_confirm),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 删除歌曲安全确认对话框（支持勾选同时彻底删除物理文件） */
@Composable
fun DeleteSongsConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (deletePhysicalFiles: Boolean) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var deletePhysical by remember { mutableStateOf(false) }
    var dialogReady by remember { mutableStateOf(false) }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        delay(100L)
        dialogReady = true
    }

    Dialog(
        onDismissRequest = { if (dialogReady) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = { if (dialogReady) onDismiss() }
                )
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    ),
                color = scheme.surface,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = stringResource(R.string.songs_action_delete_dialog_title),
                        color = scheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        text = stringResource(R.string.songs_action_delete_dialog_message, count),
                        color = scheme.onSurface.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 物理删除复选框选项
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { deletePhysical = !deletePhysical }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionCheckSquare(checked = deletePhysical)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.songs_action_delete_physical_checkbox),
                            color = if (deletePhysical) Color(0xFFEF5350) else scheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = if (deletePhysical) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiuixTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        MiuixButton(
                            onClick = { onConfirm(deletePhysical) },
                            colors = MiuixButtonDefaults.buttonColors(
                                color = Color(0xFFEF4444)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.songs_action_delete_confirm),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 从歌单移出确认对话框（不删物理文件） */
@Composable
fun RemoveFromPlaylistConfirmDialog(
    count: Int,
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    var dialogReady by remember { mutableStateOf(false) }
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        delay(100L)
        dialogReady = true
    }

    Dialog(
        onDismissRequest = { if (dialogReady) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = { if (dialogReady) onDismiss() }
                )
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {}
                    ),
                color = scheme.surface,
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Text(
                        text = stringResource(R.string.songs_action_remove_from_playlist_dialog_title),
                        color = scheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Text(
                        text = stringResource(
                            R.string.songs_action_remove_from_playlist_dialog_message,
                            count,
                            playlistName.ifBlank { stringResource(R.string.playlist_title) }
                        ),
                        color = scheme.onSurface.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiuixTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        MiuixButton(
                            onClick = onConfirm,
                            colors = MiuixButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.songs_action_remove_confirm),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
