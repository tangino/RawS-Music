package com.rawsmusic.ui.playlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import kotlinx.coroutines.launch
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore

/**
 * 歌单分组相关的弹窗集合。
 *
 * 统一用 [RawMiuixOverlayDialog]（内部包了 Miuix `OverlayDialog` 并注册预测性返回归属），
 * 不新写裸 `Dialog`/`AlertDialog`。所有文案走 `playlist_group_*` / 既有 `playlist_*` 资源。
 */

private val DIALOG_ITEM_GAP = 8.dp

/**
 * 分组/歌单动作弹窗的可见性状态。
 *
 * 为什么用状态容器而不是在页面上摊开六个 `var`：这一组弹窗共用「先关旧弹窗再开新弹窗」的
 * 流转（菜单 → 改名 / 删除 / 新建），摊开后页面里会多出上百行纯装配代码；
 * 页面侧只 remember 一个容器，弹窗装配集中在 [PlaylistGroupDialogHost]。
 */
@Stable
internal class PlaylistGroupChromeState {
    var manageGroupsDialog by mutableStateOf(false)
    var groupMenuTarget by mutableStateOf<String?>(null)
    var createGroupDialog by mutableStateOf(false)
    var renameGroupTarget by mutableStateOf<String?>(null)
    var deleteGroupTarget by mutableStateOf<String?>(null)
    var playlistActionsTarget by mutableStateOf<UserPlaylist?>(null)
    var moveToGroupTarget by mutableStateOf<UserPlaylist?>(null)
    var exportPlaylistTarget by mutableStateOf<UserPlaylist?>(null)
    var onlinePlaylistActionsTarget by mutableStateOf<OnlinePlaylistFavoriteEntity?>(null)
    var moveOnlineToGroupTarget by mutableStateOf<OnlinePlaylistFavoriteEntity?>(null)
    var deleteOnlineTarget by mutableStateOf<OnlinePlaylistFavoriteEntity?>(null)
}

@Composable
internal fun rememberPlaylistGroupChromeState(): PlaylistGroupChromeState =
    remember { PlaylistGroupChromeState() }

/**
 * 装配分组菜单、新建/改名/删除分组、以及歌单卡动作菜单（改名 / 移动到分组 / 删除）。
 *
 * 所有写操作都经 [launchPlaylistGroupOp]，由它把 `PlaylistGroupOpResult` 翻成提示；
 * 改名与删除歌单沿用页面既有的确认弹窗，所以只回抛给调用方。
 */
@Composable
internal fun PlaylistGroupDialogHost(
    state: PlaylistGroupChromeState,
    groupNames: List<String>,
    playlistGroupMap: Map<String, String>,
    groupSongCounts: Map<String, Int> = emptyMap(),
    onGroupCreated: ((String) -> Unit)? = null,
    onRenamePlaylist: (UserPlaylist) -> Unit,
    onDeletePlaylist: (UserPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (state.manageGroupsDialog) {
        PlaylistGroupsManagementDialog(
            groupNames = groupNames,
            groupSongCounts = groupSongCounts,
            onDismiss = { state.manageGroupsDialog = false },
            onCreateGroup = {
                state.createGroupDialog = true
            },
            onRenameGroup = { groupName ->
                state.renameGroupTarget = groupName
            },
            onDeleteGroup = { groupName ->
                state.deleteGroupTarget = groupName
            },
            onMoveUp = { groupIndex ->
                if (groupIndex > 1) {
                    val reordered = groupNames.toMutableList()
                        .apply { add(groupIndex - 1, removeAt(groupIndex)) }
                    scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_reordered)) {
                        PlaylistGroupStore.reorder(context, reordered)
                        null
                    }
                }
            },
            onMoveDown = { groupIndex ->
                if (groupIndex >= 1 && groupIndex < groupNames.lastIndex) {
                    val reordered = groupNames.toMutableList()
                        .apply { add(groupIndex + 1, removeAt(groupIndex)) }
                    scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_reordered)) {
                        PlaylistGroupStore.reorder(context, reordered)
                        null
                    }
                }
            },
        )
    }

    state.groupMenuTarget?.let { groupName ->
        val groupIndex = groupNames.indexOf(groupName)
        PlaylistGroupMenuDialog(
            groupName = groupName,
            canRename = groupName != DEFAULT_PLAYLIST_GROUP,
            canDelete = groupName != DEFAULT_PLAYLIST_GROUP,
            // 「默认」恒定在首位且不参与排序（传进 reorder 也会被忽略），所以可上移的最小下标是 1。
            canMoveUp = groupIndex > 1,
            canMoveDown = groupIndex >= 1 && groupIndex < groupNames.lastIndex,
            onDismiss = { state.groupMenuTarget = null },
            onCreateGroup = {
                state.groupMenuTarget = null
                state.createGroupDialog = true
            },
            onRenameGroup = {
                state.groupMenuTarget = null
                state.renameGroupTarget = groupName
            },
            onDeleteGroup = {
                state.groupMenuTarget = null
                state.deleteGroupTarget = groupName
            },
            onMoveUp = {
                state.groupMenuTarget = null
                val reordered = groupNames.toMutableList()
                    .apply { add(groupIndex - 1, removeAt(groupIndex)) }
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_reordered)) {
                    // reorder 只写 sort_order，没有枚举结果，按成功处理。
                    PlaylistGroupStore.reorder(context, reordered)
                    null
                }
            },
            onMoveDown = {
                state.groupMenuTarget = null
                val reordered = groupNames.toMutableList()
                    .apply { add(groupIndex + 1, removeAt(groupIndex)) }
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_reordered)) {
                    PlaylistGroupStore.reorder(context, reordered)
                    null
                }
            },
        )
    }

    if (state.createGroupDialog) {
        PlaylistGroupNameDialog(
            title = stringResource(R.string.playlist_group_action_create),
            initialName = "",
            onDismiss = { state.createGroupDialog = false },
            onConfirm = { name ->
                state.createGroupDialog = false
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_created, name)) {
                    val result = PlaylistGroupStore.create(context, name)
                    if (result == com.rawsmusic.module.data.prefs.PlaylistGroupOpResult.Success) {
                        onGroupCreated?.invoke(name)
                    }
                    result
                }
            },
        )
    }

    state.renameGroupTarget?.let { groupName ->
        PlaylistGroupNameDialog(
            title = stringResource(R.string.playlist_group_action_rename),
            initialName = groupName,
            onDismiss = { state.renameGroupTarget = null },
            onConfirm = { newName ->
                state.renameGroupTarget = null
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_renamed, newName)) {
                    PlaylistGroupStore.rename(context, groupName, newName)
                }
            },
        )
    }

    state.deleteGroupTarget?.let { groupName ->
        PlaylistGroupDeleteDialog(
            groupName = groupName,
            onDismiss = { state.deleteGroupTarget = null },
            onConfirm = {
                state.deleteGroupTarget = null
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_deleted, groupName)) {
                    PlaylistGroupStore.delete(context, groupName)
                }
            },
        )
    }

    state.playlistActionsTarget?.let { playlist ->
        PlaylistActionsDialog(
            playlistName = playlist.name,
            onDismiss = { state.playlistActionsTarget = null },
            onRename = {
                state.playlistActionsTarget = null
                onRenamePlaylist(playlist)
            },
            onMoveToGroup = {
                state.playlistActionsTarget = null
                state.moveToGroupTarget = playlist
            },
            onExport = {
                state.playlistActionsTarget = null
                state.exportPlaylistTarget = playlist
            },
            onDelete = {
                state.playlistActionsTarget = null
                onDeletePlaylist(playlist)
            },
        )
    }

    state.exportPlaylistTarget?.let { playlist ->
        ExportPlaylistDialog(
            playlist = playlist,
            onDismiss = { state.exportPlaylistTarget = null },
        )
    }

    state.moveToGroupTarget?.let { playlist ->
        val assignedGroup = playlistGroupMap[playlist.id]
        PlaylistMoveToGroupDialog(
            playlistName = playlist.name,
            groupNames = groupNames,
            // 归属指向一个不存在的分组时按「默认」高亮，与实际落点一致。
            currentGroupName = assignedGroup?.takeIf { it in groupNames } ?: DEFAULT_PLAYLIST_GROUP,
            onDismiss = { state.moveToGroupTarget = null },
            onSelect = { targetGroup ->
                state.moveToGroupTarget = null
                val targetLabel = if (targetGroup == DEFAULT_PLAYLIST_GROUP) {
                    context.getString(R.string.playlist_group_default_name)
                } else {
                    targetGroup
                }
                scope.launchPlaylistGroupOp(context, context.getString(R.string.playlist_group_moved, targetLabel)) {
                    PlaylistGroupStore.movePlaylist(context, playlist.id, targetGroup)
                }
            },
        )
    }

    state.onlinePlaylistActionsTarget?.let { favorite ->
        OnlinePlaylistActionsDialog(
            playlistName = favorite.title,
            onDismiss = { state.onlinePlaylistActionsTarget = null },
            onMoveToGroup = {
                state.onlinePlaylistActionsTarget = null
                state.moveOnlineToGroupTarget = favorite
            },
            onDelete = {
                state.onlinePlaylistActionsTarget = null
                state.deleteOnlineTarget = favorite
            },
        )
    }

    state.moveOnlineToGroupTarget?.let { favorite ->
        PlaylistMoveToGroupDialog(
            playlistName = favorite.title,
            groupNames = groupNames,
            currentGroupName = favorite.groupName.takeIf { it in groupNames } ?: DEFAULT_PLAYLIST_GROUP,
            onDismiss = { state.moveOnlineToGroupTarget = null },
            onSelect = { targetGroup ->
                state.moveOnlineToGroupTarget = null
                val targetLabel = if (targetGroup == DEFAULT_PLAYLIST_GROUP) {
                    context.getString(R.string.playlist_group_default_name)
                } else {
                    targetGroup
                }
                scope.launch {
                    try {
                        OnlinePlaylistFavoriteStore.setGroup(context, favorite.playlistKey, targetGroup)
                        AppNoticeBus.post(
                            message = context.getString(R.string.playlist_group_moved, targetLabel),
                            icon = com.rawsmusic.core.common.ui.AppNoticeIcon.PLAYLIST,
                        )
                    } catch (e: Exception) {
                        AppNoticeBus.error(context.getString(R.string.playlist_group_op_failed))
                    }
                }
            },
        )
    }

    state.deleteOnlineTarget?.let { favorite ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { state.deleteOnlineTarget = null },
            title = { androidx.compose.material3.Text(stringResource(R.string.playlist_delete_title), fontFamily = appFontFamily()) },
            text = { androidx.compose.material3.Text(stringResource(R.string.playlist_delete_message), fontFamily = appFontFamily()) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    state.deleteOnlineTarget = null
                    scope.launch {
                        try {
                            OnlinePlaylistFavoriteStore.remove(context, favorite.playlistKey)
                            AppNoticeBus.post(
                                message = context.getString(R.string.notice_playlist_deleted, favorite.title),
                                icon = com.rawsmusic.core.common.ui.AppNoticeIcon.PLAYLIST,
                                animation = com.rawsmusic.core.common.ui.AppNoticeAnimation.DELETE,
                            )
                        } catch (e: Exception) {
                            AppNoticeBus.error(context.getString(R.string.notice_playlist_delete_failed))
                        }
                    }
                }) { androidx.compose.material3.Text(stringResource(com.rawsmusic.core.ui.R.string.online_favorites_remove), color = androidx.compose.ui.graphics.Color.Red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { state.deleteOnlineTarget = null }) {
                    androidx.compose.material3.Text(stringResource(R.string.playlist_cancel_action))
                }
            }
        )
    }
}

/** 在线歌单卡的长按 / more 菜单：移动到分组、取消收藏。 */
@Composable
internal fun OnlinePlaylistActionsDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onMoveToGroup: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_group_playlist_actions),
        summary = playlistName,
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            TextButton(
                text = stringResource(R.string.playlist_group_move_title),
                onClick = onMoveToGroup,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(com.rawsmusic.core.ui.R.string.online_favorites_remove),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 新建 / 改名共用：分组名唯一由数据层把关，这里只挡空串。 */
@Composable
internal fun PlaylistGroupNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = themeColors()
    var name by remember(initialName) { mutableStateOf(initialName) }
    RawMiuixOverlayDialog(
        show = true,
        title = title,
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.playlist_group_name_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.playlist_cancel_action),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onConfirm(name.trim()) },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.playlist_confirm_action), fontFamily = appFontFamily())
                }
            }
        }
    }
}

/**
 * 分组长按菜单。
 *
 * 「默认」是保留分组（见 `PlaylistGroupStore` 类注释），所以改名/删除对它关闭；
 * 排序对它也关闭：它由数据层固定在第一位，传进 `reorder` 也会被忽略。
 */
@Composable
internal fun PlaylistGroupMenuDialog(
    groupName: String,
    canRename: Boolean,
    canDelete: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onCreateGroup: () -> Unit,
    onRenameGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = playlistGroupDisplayName(groupName),
        summary = stringResource(R.string.playlist_group_menu_summary),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            TextButton(
                text = stringResource(R.string.playlist_group_action_create),
                onClick = onCreateGroup,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.playlist_group_action_rename),
                onClick = onRenameGroup,
                enabled = canRename,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.playlist_group_action_delete),
                onClick = onDeleteGroup,
                enabled = canDelete,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.playlist_group_action_move_up),
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.playlist_group_action_move_down),
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 删除分组是破坏性操作（歌单会回落「默认」），单独二次确认。 */
@Composable
internal fun PlaylistGroupDeleteDialog(
    groupName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_group_delete_title, playlistGroupDisplayName(groupName)),
        summary = stringResource(R.string.playlist_group_delete_summary),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                text = stringResource(R.string.playlist_cancel_action),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.playlist_confirm_action), fontFamily = appFontFamily())
            }
        }
    }
}

/** 真实歌单的「移动到分组」。当前所在分组会标出来，点它等于什么都没做。 */
@Composable
internal fun PlaylistMoveToGroupDialog(
    playlistName: String,
    groupNames: List<String>,
    currentGroupName: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_group_move_title),
        summary = playlistName,
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            groupNames.forEach { name ->
                val selected = name == currentGroupName
                val displayName = playlistGroupDisplayName(name)
                TextButton(
                    text = if (selected) {
                        displayName + stringResource(R.string.playlist_group_move_current)
                    } else {
                        displayName
                    },
                    onClick = { onSelect(name) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 歌单卡的长按 / more 菜单：改名、移动到分组、删除都在这里，避免长按被单一动作占用。 */
@Composable
internal fun PlaylistActionsDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMoveToGroup: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_group_playlist_actions),
        summary = playlistName,
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            TextButton(
                text = stringResource(R.string.playlist_rename),
                onClick = onRename,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.playlist_group_move_title),
                onClick = onMoveToGroup,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.playlist_export_action),
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.playlist_delete_action),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 全局歌单分组管理面板：展示所有分组卡片、歌单数、快捷新建、上移下移排序、重命名与删除。
 */
@Composable
internal fun PlaylistGroupsManagementDialog(
    groupNames: List<String>,
    groupSongCounts: Map<String, Int>,
    onDismiss: () -> Unit,
    onCreateGroup: () -> Unit,
    onRenameGroup: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_group_manage_title),
        summary = stringResource(R.string.playlist_group_manage_summary),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部：“新建分组”强调操作栏
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.primary.copy(alpha = 0.12f),
                border = BorderStroke(0.5.dp, colors.primary.copy(alpha = 0.28f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCreateGroup() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.playlist_group_action_create),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        fontFamily = appFontFamily()
                    )
                }
            }

            // 分组列表滚动容器
            val customGroups = groupNames.filter { it != DEFAULT_PLAYLIST_GROUP }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 系统默认保留分组
                val defaultCount = groupSongCounts[DEFAULT_PLAYLIST_GROUP] ?: 0
                GroupManagementItemCard(
                    displayName = stringResource(R.string.playlist_group_default_name),
                    count = defaultCount,
                    isReserved = true,
                    canMoveUp = false,
                    canMoveDown = false,
                    onMoveUp = {},
                    onMoveDown = {},
                    onRename = {},
                    onDelete = {},
                )

                // 用户自建分组列表
                if (customGroups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.playlist_group_empty_hint),
                            fontSize = 12.sp,
                            color = colors.onSurface.copy(alpha = 0.5f),
                            fontFamily = appFontFamily()
                        )
                    }
                } else {
                    groupNames.forEachIndexed { index, name ->
                        if (name == DEFAULT_PLAYLIST_GROUP) return@forEachIndexed
                        val count = groupSongCounts[name] ?: 0
                        GroupManagementItemCard(
                            displayName = name,
                            count = count,
                            isReserved = false,
                            canMoveUp = index > 1,
                            canMoveDown = index < groupNames.lastIndex,
                            onMoveUp = { onMoveUp(index) },
                            onMoveDown = { onMoveDown(index) },
                            onRename = { onRenameGroup(name) },
                            onDelete = { onDeleteGroup(name) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.playlist_confirm_action),
                    fontFamily = appFontFamily()
                )
            }
        }
    }
}

@Composable
private fun GroupManagementItemCard(
    displayName: String,
    count: Int,
    isReserved: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = themeColors()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = 0.65f),
        border = BorderStroke(0.5.dp, colors.outline.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧图标
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (isReserved) colors.primary.copy(alpha = 0.10f) else colors.onSurface.copy(alpha = 0.06f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isReserved) R.drawable.ic_settings else R.drawable.ic_folder_2_fill),
                    contentDescription = null,
                    tint = if (isReserved) colors.primary else colors.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            // 中间名称与数量
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                        fontFamily = appFontFamily()
                    )
                    if (isReserved) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.playlist_group_system_reserved),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                                fontFamily = appFontFamily()
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.playlist_group_count_format, count),
                    fontSize = 11.sp,
                    color = colors.onSurface.copy(alpha = 0.55f),
                    fontFamily = appFontFamily()
                )
            }

            // 右侧操作按钮
            if (!isReserved) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.playlist_group_action_move_up),
                            tint = if (canMoveUp) colors.onSurface.copy(alpha = 0.8f) else colors.onSurface.copy(alpha = 0.25f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.playlist_group_action_move_down),
                            tint = if (canMoveDown) colors.onSurface.copy(alpha = 0.8f) else colors.onSurface.copy(alpha = 0.25f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.playlist_group_action_rename),
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.playlist_group_action_delete),
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
