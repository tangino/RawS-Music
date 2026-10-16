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

    var lastBackupAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastBackupAtMs) }
    var lastRestoreAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastRestoreAtMs) }
    var running by remember { mutableStateOf<CloudSyncTask?>(null) }
    var pendingClearConfirm by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    val busy = running != null
    val configured = CloudSyncClient.isConfigured()

    fun refreshTimestamps() {
        lastBackupAtMs = AppPreferences.CloudSync.lastBackupAtMs
        lastRestoreAtMs = AppPreferences.CloudSync.lastRestoreAtMs
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
