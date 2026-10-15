package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import com.rawsmusic.module.online.sync.CloudOnlineFavoriteDto
import com.rawsmusic.module.online.sync.CloudPlaylistBackup
import com.rawsmusic.module.online.sync.CloudPlaylistDto
import com.rawsmusic.module.online.sync.CloudPlaylistJson
import com.rawsmusic.module.online.sync.CloudPlaylistSyncService
import com.rawsmusic.module.online.sync.CloudSourceSyncService
import com.rawsmusic.module.online.sync.CloudSyncClient
import com.rawsmusic.module.online.sync.CloudSyncResult
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

private const val TAG = "CloudSync"

private enum class SyncTab {
    UPLOAD,
    DOWNLOAD,
}

private enum class CloudSyncTask {
    SAVE,
    FETCH_CLOUD,
    UPLOAD,
    RESTORE,
    DELETE_SELECTED,
    SOURCE_UPLOAD,
    SOURCE_RESTORE,
    CLEAR,
}

private const val HTTP_NOT_FOUND = 404

/**
 * 歌单与音源云同步设置页。
 *
 * 保留 OrbitPlayer 细粒度多选上传/下载预览与云端删除等全部高级能力。
 */
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    var serverUrl by remember { mutableStateOf(AppPreferences.CloudSync.serverUrl) }
    var username by remember { mutableStateOf(AppPreferences.CloudSync.username) }
    var password by remember { mutableStateOf("") }
    var passwordSaved by remember { mutableStateOf(AppPreferences.CloudSync.encryptedPassword().isNotBlank()) }

    var currentTab by remember { mutableStateOf(SyncTab.UPLOAD) }
    var mode by remember { mutableStateOf(AppPreferences.CloudSync.uploadMode) }
    var lastBackupAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastBackupAtMs) }
    var lastRestoreAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastRestoreAtMs) }
    var running by remember { mutableStateOf<CloudSyncTask?>(null) }
    var pendingClearConfirm by remember { mutableStateOf(false) }
    var pendingDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    // 本地数据收集
    val allPlaylists by PlaylistStore.getInstance(context).playlists.collectAsState()
    val localRegularPlaylists = remember(allPlaylists) { allPlaylists.filterNot { it.isFavorites } }
    val onlineFavorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()
    val playlistGroups by PlaylistGroupStore.playlistGroups.collectAsState()

    // 上传细粒度勾选状态
    val selectedLocalUploadIds = remember { mutableStateListOf<String>() }
    val selectedOnlineUploadKeys = remember { mutableStateListOf<String>() }
    var uploadFavoritesChecked by remember { mutableStateOf(true) }
    var uploadDislikedChecked by remember { mutableStateOf(true) }

    // 默认全选本地歌单和在线收藏
    LaunchedEffect(localRegularPlaylists, onlineFavorites) {
        if (selectedLocalUploadIds.isEmpty() && localRegularPlaylists.isNotEmpty()) {
            selectedLocalUploadIds.addAll(localRegularPlaylists.map { it.id })
        }
        if (selectedOnlineUploadKeys.isEmpty() && onlineFavorites.isNotEmpty()) {
            selectedOnlineUploadKeys.addAll(onlineFavorites.map { it.playlistKey })
        }
    }

    // 下载细粒度拉取与勾选状态
    var cloudBackup by remember { mutableStateOf<CloudPlaylistBackup?>(null) }
    val cloudPlaylists = remember(cloudBackup) {
        cloudBackup?.let { CloudPlaylistJson.parsePlaylists(it.playlistsJson).filter { dto -> CloudPlaylistJson.systemKindOf(dto) == null } } ?: emptyList()
    }
    val cloudOnlineFavorites = remember(cloudBackup) {
        cloudBackup?.let { CloudPlaylistJson.parseOnlineFavorites(it.onlineFavoritesJson) } ?: emptyList()
    }
    val selectedCloudPlaylistNames = remember { mutableStateListOf<String>() }
    val selectedCloudOnlineIds = remember { mutableStateListOf<String>() }
    var restoreFavoritesChecked by remember { mutableStateOf(true) }
    var restoreDislikedChecked by remember { mutableStateOf(true) }

    val busy = running != null
    val configured = CloudSyncClient.isConfigured()

    fun refreshTimestamps() {
        lastBackupAtMs = AppPreferences.CloudSync.lastBackupAtMs
        lastRestoreAtMs = AppPreferences.CloudSync.lastRestoreAtMs
    }

    fun loadCloudData() {
        if (!configured) return
        resultMessage = ""
        running = CloudSyncTask.FETCH_CLOUD
        scope.launch {
            try {
                when (val result = CloudPlaylistSyncService.fetchCloudPlaylists(context)) {
                    is CloudSyncResult.Success -> {
                        cloudBackup = result.data
                        selectedCloudPlaylistNames.clear()
                        selectedCloudOnlineIds.clear()
                        val dtos = CloudPlaylistJson.parsePlaylists(result.data.playlistsJson)
                            .filter { CloudPlaylistJson.systemKindOf(it) == null }
                        selectedCloudPlaylistNames.addAll(dtos.map { it.name })
                        val onlineDtos = CloudPlaylistJson.parseOnlineFavorites(result.data.onlineFavoritesJson)
                        selectedCloudOnlineIds.addAll(onlineDtos.map { it.id })
                        refreshTimestamps()
                    }
                    is CloudSyncResult.Failure -> {
                        if (result.code == HTTP_NOT_FOUND) {
                            cloudBackup = null
                            resultMessage = context.getString(R.string.cloud_sync_restore_empty)
                        } else {
                            AppLogger.e(TAG, "获取云端歌单失败：${result.message}")
                            AppNoticeBus.error("获取云端歌单失败: ${result.message}")
                        }
                    }
                }
            } finally {
                running = null
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == SyncTab.DOWNLOAD && cloudBackup == null && configured) {
            loadCloudData()
        }
    }

    SettingsPage(title = stringResource(R.string.cloud_sync_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.cloud_sync_page_summary),
            color = colors.secondaryText,
            fontSize = 11.sp,
            fontFamily = appFontFamily(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        SectionHeader(stringResource(R.string.cloud_sync_section_account))
        SettingsCard {
            TextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = stringResource(R.string.cloud_sync_server_url_label),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.cloud_sync_server_url_hint),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.cloud_sync_username_label),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.cloud_sync_password_label),
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    if (passwordSaved) R.string.cloud_sync_password_saved
                    else R.string.cloud_sync_password_not_set
                ),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.cloud_sync_action_save),
                onClick = {
                    resultMessage = ""
                    running = CloudSyncTask.SAVE
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                CloudSyncClient.saveCredentials(serverUrl, username, password)
                            }
                            when (result) {
                                is CloudSyncResult.Success -> {
                                    serverUrl = AppPreferences.CloudSync.serverUrl
                                    username = AppPreferences.CloudSync.username
                                    password = ""
                                    passwordSaved = AppPreferences.CloudSync.encryptedPassword().isNotBlank()
                                    AppNoticeBus.post(
                                        message = context.getString(R.string.cloud_sync_save_success),
                                        icon = AppNoticeIcon.INFO,
                                    )
                                }
                                is CloudSyncResult.Failure -> {
                                    AppLogger.e(TAG, "保存云同步配置失败：${result.message}")
                                    AppNoticeBus.error(
                                        context.getString(R.string.cloud_sync_save_failed, result.message)
                                    )
                                }
                            }
                        } finally {
                            running = null
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            if (running == CloudSyncTask.SAVE) {
                CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_saving))
            }
        }

        SectionHeader("洛雪音源云同步")
        SettingsCard {
            Text(
                text = "可将当前已安装的洛雪音源脚本备份至云端或直接恢复",
                color = colors.secondaryText,
                fontSize = 11.5.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        resultMessage = ""
                        running = CloudSyncTask.SOURCE_UPLOAD
                        scope.launch {
                            try {
                                when (val result = CloudSourceSyncService.upload(context)) {
                                    is CloudSyncResult.Success -> {
                                        refreshTimestamps()
                                        resultMessage = context.getString(
                                            R.string.cloud_sync_source_uploaded,
                                            result.data.uploaded,
                                        )
                                        AppNoticeBus.post(resultMessage, icon = AppNoticeIcon.INFO)
                                    }
                                    is CloudSyncResult.Failure -> {
                                        AppLogger.e(TAG, "上传音源备份失败：${result.message}")
                                        resultMessage = context.getString(
                                            R.string.cloud_sync_source_upload_failed,
                                            result.message,
                                        )
                                        AppNoticeBus.error(resultMessage)
                                    }
                                }
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cloud_sync_action_source_upload))
                }
                Button(
                    onClick = {
                        resultMessage = ""
                        running = CloudSyncTask.SOURCE_RESTORE
                        scope.launch {
                            try {
                                when (val result = CloudSourceSyncService.restore(context)) {
                                    is CloudSyncResult.Success -> {
                                        refreshTimestamps()
                                        resultMessage = context.getString(
                                            R.string.cloud_sync_source_restored,
                                            result.data.installed,
                                        )
                                        AppNoticeBus.post(resultMessage, icon = AppNoticeIcon.INFO)
                                    }
                                    is CloudSyncResult.Failure -> {
                                        if (result.code == HTTP_NOT_FOUND) {
                                            resultMessage = context.getString(R.string.cloud_sync_source_empty)
                                            AppNoticeBus.post(resultMessage, icon = AppNoticeIcon.INFO)
                                        } else {
                                            AppLogger.e(TAG, "下载音源备份失败：${result.message}")
                                            resultMessage = context.getString(
                                                R.string.cloud_sync_source_restore_failed,
                                                result.message,
                                            )
                                            AppNoticeBus.error(resultMessage)
                                        }
                                    }
                                }
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cloud_sync_action_source_restore))
                }
            }
            when (running) {
                CloudSyncTask.SOURCE_UPLOAD ->
                    CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_source_uploading))
                CloudSyncTask.SOURCE_RESTORE ->
                    CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_source_restoring))
                else -> Unit
            }
        }

        SectionHeader("歌单云同步细粒度管理")
        SettingsCard {
            // 双 Tab 导航：上传备份 / 从云端下载
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface.copy(alpha = 0.5f)),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (currentTab == SyncTab.UPLOAD) colors.primary.copy(alpha = 0.15f) else colors.surface.copy(alpha = 0.5f))
                        .clickable(enabled = !busy) { currentTab = SyncTab.UPLOAD }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⬆️ " + stringResource(R.string.cloud_sync_tab_upload),
                        fontSize = 13.5.sp,
                        fontWeight = if (currentTab == SyncTab.UPLOAD) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentTab == SyncTab.UPLOAD) colors.primary else colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (currentTab == SyncTab.DOWNLOAD) colors.primary.copy(alpha = 0.15f) else colors.surface.copy(alpha = 0.5f))
                        .clickable(enabled = !busy) { currentTab = SyncTab.DOWNLOAD }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⬇️ " + stringResource(R.string.cloud_sync_tab_download),
                        fontSize = 13.5.sp,
                        fontWeight = if (currentTab == SyncTab.DOWNLOAD) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentTab == SyncTab.DOWNLOAD) colors.primary else colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (currentTab == SyncTab.UPLOAD) {
                // 上传模式选择
                Text(
                    text = stringResource(R.string.cloud_sync_mode_label),
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = appFontFamily(),
                )
                RadioButtonPreference(
                    title = stringResource(R.string.cloud_sync_mode_merge),
                    summary = stringResource(R.string.cloud_sync_mode_merge_summary),
                    selected = mode == CloudSyncClient.MODE_MERGE,
                    enabled = !busy,
                    onClick = { mode = CloudSyncClient.MODE_MERGE },
                )
                RadioButtonPreference(
                    title = stringResource(R.string.cloud_sync_mode_overwrite),
                    summary = stringResource(R.string.cloud_sync_mode_overwrite_summary),
                    selected = mode == CloudSyncClient.MODE_OVERWRITE,
                    enabled = !busy,
                    onClick = { mode = CloudSyncClient.MODE_OVERWRITE },
                )

                Spacer(Modifier.height(10.dp))

                // 系统偏好勾选
                Text(
                    text = "系统偏好",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = appFontFamily(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { uploadFavoritesChecked = !uploadFavoritesChecked }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (uploadFavoritesChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { uploadFavoritesChecked = !uploadFavoritesChecked },
                        enabled = !busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_sync_system_favorites), fontSize = 13.5.sp, color = colors.onSurface)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { uploadDislikedChecked = !uploadDislikedChecked }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (uploadDislikedChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { uploadDislikedChecked = !uploadDislikedChecked },
                        enabled = !busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_sync_system_disliked), fontSize = 13.5.sp, color = colors.onSurface)
                }

                Spacer(Modifier.height(12.dp))

                // 本地普通歌单多选
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${stringResource(R.string.cloud_sync_local_playlists)} (${selectedLocalUploadIds.size}/${localRegularPlaylists.size})",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.cloud_sync_select_all),
                            fontSize = 12.sp,
                            color = colors.primary,
                            modifier = Modifier.clickable {
                                selectedLocalUploadIds.clear()
                                selectedLocalUploadIds.addAll(localRegularPlaylists.map { it.id })
                            },
                        )
                        Text(
                            text = stringResource(R.string.cloud_sync_select_invert),
                            fontSize = 12.sp,
                            color = colors.secondaryText,
                            modifier = Modifier.clickable {
                                val currentSet = selectedLocalUploadIds.toSet()
                                selectedLocalUploadIds.clear()
                                localRegularPlaylists.forEach {
                                    if (it.id !in currentSet) selectedLocalUploadIds.add(it.id)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (localRegularPlaylists.isEmpty()) {
                    Text("本地暂无自建普通歌单", fontSize = 11.5.sp, color = colors.secondaryText)
                } else {
                    localRegularPlaylists.forEach { playlist ->
                        val isChecked = playlist.id in selectedLocalUploadIds
                        val group = playlistGroups[playlist.id]?.takeIf { it.isNotBlank() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isChecked) selectedLocalUploadIds.remove(playlist.id)
                                    else selectedLocalUploadIds.add(playlist.id)
                                }
                                .padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    if (isChecked) selectedLocalUploadIds.remove(playlist.id)
                                    else selectedLocalUploadIds.add(playlist.id)
                                },
                                enabled = !busy,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, fontSize = 13.5.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${playlist.songs.size} 首歌曲${if (group != null) " · 分组: $group" else ""}",
                                    fontSize = 11.sp,
                                    color = colors.secondaryText,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 在线收藏歌单多选
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${stringResource(R.string.cloud_sync_online_favorites)} (${selectedOnlineUploadKeys.size}/${onlineFavorites.size})",
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.cloud_sync_select_all),
                            fontSize = 12.sp,
                            color = colors.primary,
                            modifier = Modifier.clickable {
                                selectedOnlineUploadKeys.clear()
                                selectedOnlineUploadKeys.addAll(onlineFavorites.map { it.playlistKey })
                            },
                        )
                        Text(
                            text = stringResource(R.string.cloud_sync_select_invert),
                            fontSize = 12.sp,
                            color = colors.secondaryText,
                            modifier = Modifier.clickable {
                                val currentSet = selectedOnlineUploadKeys.toSet()
                                selectedOnlineUploadKeys.clear()
                                onlineFavorites.forEach {
                                    if (it.playlistKey !in currentSet) selectedOnlineUploadKeys.add(it.playlistKey)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (onlineFavorites.isEmpty()) {
                    Text("暂无在线收藏歌单", fontSize = 11.5.sp, color = colors.secondaryText)
                } else {
                    onlineFavorites.forEach { fav ->
                        val key = fav.playlistKey
                        val isChecked = key in selectedOnlineUploadKeys
                        val platformName = com.rawsmusic.module.online.model.OnlinePlatform.fromId(fav.platform)?.displayName ?: fav.platform.uppercase()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isChecked) selectedOnlineUploadKeys.remove(key)
                                    else selectedOnlineUploadKeys.add(key)
                                }
                                .padding(vertical = 5.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    if (isChecked) selectedOnlineUploadKeys.remove(key)
                                    else selectedOnlineUploadKeys.add(key)
                                },
                                enabled = !busy,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fav.title, fontSize = 13.5.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "$platformName · ${fav.trackCount} 首${if (fav.groupName.isNotBlank()) " · 分组: ${fav.groupName}" else ""}",
                                    fontSize = 11.sp,
                                    color = colors.secondaryText,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 上传执行按钮
                val totalUploadCount = selectedLocalUploadIds.size + selectedOnlineUploadKeys.size +
                    (if (uploadFavoritesChecked) 1 else 0) + (if (uploadDislikedChecked) 1 else 0)

                TextButton(
                    text = "上传所选项 ($totalUploadCount)",
                    onClick = {
                        resultMessage = ""
                        running = CloudSyncTask.UPLOAD
                        scope.launch {
                            try {
                                when (
                                    val result = CloudPlaylistSyncService.upload(
                                        context = context,
                                        mode = mode,
                                        selectedLocalIds = selectedLocalUploadIds.toSet(),
                                        selectedOnlineKeys = selectedOnlineUploadKeys.toSet(),
                                        includeFavorites = uploadFavoritesChecked,
                                        includeDisliked = uploadDislikedChecked,
                                    )
                                ) {
                                    is CloudSyncResult.Success -> {
                                        refreshTimestamps()
                                        val message = context.getString(
                                            R.string.cloud_sync_upload_success,
                                            result.data,
                                        )
                                        resultMessage = message
                                        AppNoticeBus.post(message = message, icon = AppNoticeIcon.PLAYLIST)
                                    }
                                    is CloudSyncResult.Failure -> {
                                        AppLogger.e(TAG, "上传歌单失败：${result.message}")
                                        AppNoticeBus.error(
                                            context.getString(R.string.cloud_sync_upload_failed, result.message)
                                        )
                                    }
                                }
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && configured && totalUploadCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 下载 Tab
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "云端数据预览",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "🔄 " + stringResource(R.string.cloud_sync_refresh_cloud),
                        fontSize = 12.sp,
                        color = colors.primary,
                        modifier = Modifier.clickable(enabled = !busy && configured) { loadCloudData() },
                    )
                }

                if (running == CloudSyncTask.FETCH_CLOUD) {
                    CloudSyncProgressRow(stringResource(R.string.cloud_sync_fetching_cloud))
                }

                val backup = cloudBackup
                if (backup == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (!configured) stringResource(R.string.cloud_sync_not_configured) else "暂无云端备份或尚未拉取",
                        fontSize = 12.sp,
                        color = colors.secondaryText,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "云端歌单总数: ${backup.playlistCount} · 最近更新: ${formattedTimestamp(backup.updatedAt)}",
                        fontSize = 11.5.sp,
                        color = colors.secondaryText,
                    )

                    Spacer(Modifier.height(10.dp))

                    // 系统喜好还原勾选
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { restoreFavoritesChecked = !restoreFavoritesChecked }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (restoreFavoritesChecked) ToggleableState.On else ToggleableState.Off,
                            onClick = { restoreFavoritesChecked = !restoreFavoritesChecked },
                            enabled = !busy,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("还原「我喜欢的音乐」", fontSize = 13.5.sp, color = colors.onSurface)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { restoreDislikedChecked = !restoreDislikedChecked }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (restoreDislikedChecked) ToggleableState.On else ToggleableState.Off,
                            onClick = { restoreDislikedChecked = !restoreDislikedChecked },
                            enabled = !busy,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("还原「不喜欢的音乐」", fontSize = 13.5.sp, color = colors.onSurface)
                    }

                    Spacer(Modifier.height(12.dp))

                    // 云端普通歌单列表
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "云端歌单 (${selectedCloudPlaylistNames.size}/${cloudPlaylists.size})",
                            color = colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.cloud_sync_select_all),
                                fontSize = 12.sp,
                                color = colors.primary,
                                modifier = Modifier.clickable {
                                    selectedCloudPlaylistNames.clear()
                                    selectedCloudPlaylistNames.addAll(cloudPlaylists.map { it.name })
                                },
                            )
                            Text(
                                text = stringResource(R.string.cloud_sync_select_invert),
                                fontSize = 12.sp,
                                color = colors.secondaryText,
                                modifier = Modifier.clickable {
                                    val currentSet = selectedCloudPlaylistNames.toSet()
                                    selectedCloudPlaylistNames.clear()
                                    cloudPlaylists.forEach {
                                        if (it.name !in currentSet) selectedCloudPlaylistNames.add(it.name)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (cloudPlaylists.isEmpty()) {
                        Text("云端无自建歌单备份", fontSize = 11.5.sp, color = colors.secondaryText)
                    } else {
                        cloudPlaylists.forEach { dto ->
                            val isChecked = dto.name in selectedCloudPlaylistNames
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isChecked) selectedCloudPlaylistNames.remove(dto.name)
                                        else selectedCloudPlaylistNames.add(dto.name)
                                    }
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                                    onClick = {
                                        if (isChecked) selectedCloudPlaylistNames.remove(dto.name)
                                        else selectedCloudPlaylistNames.add(dto.name)
                                    },
                                    enabled = !busy,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(dto.name, fontSize = 13.5.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${dto.songs.size} 首歌曲${if (dto.groupName.isNotBlank()) " · 分组: ${dto.groupName}" else ""}",
                                        fontSize = 11.sp,
                                        color = colors.secondaryText,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 云端在线收藏列表
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "云端在线收藏 (${selectedCloudOnlineIds.size}/${cloudOnlineFavorites.size})",
                            color = colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.cloud_sync_select_all),
                                fontSize = 12.sp,
                                color = colors.primary,
                                modifier = Modifier.clickable {
                                    selectedCloudOnlineIds.clear()
                                    selectedCloudOnlineIds.addAll(cloudOnlineFavorites.map { it.id })
                                },
                            )
                            Text(
                                text = stringResource(R.string.cloud_sync_select_invert),
                                fontSize = 12.sp,
                                color = colors.secondaryText,
                                modifier = Modifier.clickable {
                                    val currentSet = selectedCloudOnlineIds.toSet()
                                    selectedCloudOnlineIds.clear()
                                    cloudOnlineFavorites.forEach {
                                        if (it.id !in currentSet) selectedCloudOnlineIds.add(it.id)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (cloudOnlineFavorites.isEmpty()) {
                        Text("云端无在线收藏备份", fontSize = 11.5.sp, color = colors.secondaryText)
                    } else {
                        cloudOnlineFavorites.forEach { dto ->
                            val isChecked = dto.id in selectedCloudOnlineIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (isChecked) selectedCloudOnlineIds.remove(dto.id)
                                        else selectedCloudOnlineIds.add(dto.id)
                                    }
                                    .padding(vertical = 5.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    state = if (isChecked) ToggleableState.On else ToggleableState.Off,
                                    onClick = {
                                        if (isChecked) selectedCloudOnlineIds.remove(dto.id)
                                        else selectedCloudOnlineIds.add(dto.id)
                                    },
                                    enabled = !busy,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(dto.title, fontSize = 13.5.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${dto.platform} · ${dto.trackCount} 首${if (dto.customGroup.isNotBlank()) " · 分组: ${dto.customGroup}" else ""}",
                                        fontSize = 11.sp,
                                        color = colors.secondaryText,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    val totalDownloadCount = selectedCloudPlaylistNames.size + selectedCloudOnlineIds.size +
                        (if (restoreFavoritesChecked) 1 else 0) + (if (restoreDislikedChecked) 1 else 0)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.cloud_sync_restore_selected, totalDownloadCount),
                            onClick = {
                                resultMessage = ""
                                running = CloudSyncTask.RESTORE
                                scope.launch {
                                    try {
                                        when (
                                            val result = CloudPlaylistSyncService.restore(
                                                context = context,
                                                selectedLocalNames = selectedCloudPlaylistNames.toSet(),
                                                selectedOnlineFavoriteIds = selectedCloudOnlineIds.toSet(),
                                                restoreFavorites = restoreFavoritesChecked,
                                                restoreDisliked = restoreDislikedChecked,
                                                backup = cloudBackup,
                                            )
                                        ) {
                                            is CloudSyncResult.Success -> {
                                                refreshTimestamps()
                                                val report = result.data
                                                val message = context.getString(
                                                    R.string.cloud_sync_restore_success,
                                                    report.playlistsWritten,
                                                    report.groupsCreated,
                                                    report.favoritesApplied,
                                                    report.dislikedApplied,
                                                    report.onlineFavoritesApplied,
                                                )
                                                resultMessage = message
                                                AppNoticeBus.post(message = message, icon = AppNoticeIcon.PLAYLIST)
                                            }
                                            is CloudSyncResult.Failure -> {
                                                AppLogger.e(TAG, "还原云端歌单失败：${result.message}")
                                                AppNoticeBus.error(
                                                    context.getString(R.string.cloud_sync_restore_failed, result.message)
                                                )
                                            }
                                        }
                                    } finally {
                                        running = null
                                    }
                                }
                            },
                            enabled = !busy && configured && totalDownloadCount > 0,
                            modifier = Modifier.weight(1f),
                        )

                        val deleteItemsCount = selectedCloudPlaylistNames.size + selectedCloudOnlineIds.size
                        TextButton(
                            text = stringResource(R.string.cloud_sync_delete_selected, deleteItemsCount),
                            onClick = { pendingDeleteSelectedConfirm = true },
                            enabled = !busy && configured && deleteItemsCount > 0,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (!configured) {
                Text(
                    text = stringResource(R.string.cloud_sync_not_configured),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            when (running) {
                CloudSyncTask.UPLOAD ->
                    CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_uploading))
                CloudSyncTask.RESTORE ->
                    CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_restoring))
                CloudSyncTask.DELETE_SELECTED ->
                    CloudSyncProgressRow("正在从云端删除所选歌单…")
                else -> Unit
            }
        }

        SectionHeader(stringResource(R.string.cloud_sync_section_status))
        SettingsCard {
            Text(
                text = stringResource(
                    R.string.cloud_sync_last_upload,
                    formattedTimestamp(lastBackupAtMs),
                ),
                color = colors.onSurface,
                fontSize = 15.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                text = stringResource(
                    R.string.cloud_sync_last_restore,
                    formattedTimestamp(lastRestoreAtMs),
                ),
                color = colors.onSurface,
                fontSize = 15.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (resultMessage.isNotBlank()) {
                Text(
                    text = resultMessage,
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        SectionHeader(stringResource(R.string.cloud_sync_section_danger))
        SettingsCard {
            TextButton(
                text = stringResource(R.string.cloud_sync_action_clear),
                onClick = { pendingClearConfirm = true },
                enabled = !busy && configured,
                modifier = Modifier.fillMaxWidth(),
            )
            if (running == CloudSyncTask.CLEAR) {
                CloudSyncProgressRow(context.getString(R.string.cloud_sync_state_clearing))
            }
        }
    }

    if (pendingDeleteSelectedConfirm) {
        val totalToDelete = selectedCloudPlaylistNames.size + selectedCloudOnlineIds.size
        RawMiuixOverlayDialog(
            show = true,
            title = stringResource(R.string.cloud_sync_delete_confirm_title),
            summary = stringResource(R.string.cloud_sync_delete_confirm_summary, totalToDelete),
            backgroundColor = colors.surface,
            onDismissRequest = { pendingDeleteSelectedConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cloud_sync_action_cancel),
                    onClick = { pendingDeleteSelectedConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        pendingDeleteSelectedConfirm = false
                        resultMessage = ""
                        running = CloudSyncTask.DELETE_SELECTED
                        scope.launch {
                            try {
                                when (
                                    val result = CloudPlaylistSyncService.deleteSelectedCloudPlaylists(
                                        context = context,
                                        localPlaylistNames = selectedCloudPlaylistNames.toList(),
                                        onlinePlaylistIds = selectedCloudOnlineIds.toList(),
                                    )
                                ) {
                                    is CloudSyncResult.Success -> {
                                        val msg = context.getString(
                                            R.string.cloud_sync_delete_selected_success,
                                            result.data,
                                        )
                                        resultMessage = msg
                                        AppNoticeBus.post(msg, icon = AppNoticeIcon.INFO)
                                        loadCloudData()
                                    }
                                    is CloudSyncResult.Failure -> {
                                        AppLogger.e(TAG, "从云端删除歌单失败：${result.message}")
                                        AppNoticeBus.error("删除失败: ${result.message}")
                                    }
                                }
                            } finally {
                                running = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "确认删除", fontFamily = appFontFamily())
                }
            }
        }
    }

    if (pendingClearConfirm) {
        CloudSyncClearConfirmDialog(
            onDismiss = { pendingClearConfirm = false },
            onConfirm = {
                pendingClearConfirm = false
                resultMessage = ""
                running = CloudSyncTask.CLEAR
                scope.launch {
                    try {
                        when (val result = CloudSyncClient.clearAllData(context)) {
                            is CloudSyncResult.Success -> {
                                cloudBackup = null
                                selectedCloudPlaylistNames.clear()
                                selectedCloudOnlineIds.clear()
                                AppNoticeBus.post(
                                    message = context.getString(R.string.cloud_sync_clear_success),
                                    icon = AppNoticeIcon.INFO,
                                )
                            }
                            is CloudSyncResult.Failure -> {
                                AppLogger.e(TAG, "清空云端数据失败：${result.message} (code=${result.code})")
                                AppNoticeBus.error(
                                    context.getString(R.string.cloud_sync_clear_failed, result.message)
                                )
                            }
                        }
                    } finally {
                        running = null
                    }
                }
            },
        )
    }
}

@Composable
private fun CloudSyncProgressRow(message: String) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            color = colors.secondaryText,
            fontSize = 12.sp,
            fontFamily = appFontFamily(),
        )
    }
}

@Composable
private fun CloudSyncClearConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.cloud_sync_clear_confirm_title),
        summary = stringResource(R.string.cloud_sync_clear_confirm_summary),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cloud_sync_action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.cloud_sync_clear_confirm_action),
                    fontFamily = appFontFamily(),
                )
            }
        }
    }
}

@Composable
private fun formattedTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return stringResource(R.string.cloud_sync_never)
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val date = remember(epochMs) { Date(epochMs) }
    return remember(formatter, date) { formatter.format(date) }
}
