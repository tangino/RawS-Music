package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.prefs.AppPreferences
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

private const val TAG = "CloudSync"

/**
 * 歌单云同步设置页。
 *
 * ## 只调已经测过的编排层，不自己拼 JSON
 *
 * 上传 / 还原一律走 [CloudPlaylistSyncService]：它内部已经处理了「两个系统歌单不当普通歌单建」
 * 「`系统` 不建成分组」「本地曲 `song_key` 前缀由 core 计算」这些契约细节。页面这边只负责
 * 收集配置、展示进度与结果，**不碰序列化**。
 *
 * ## 密码永不回显
 *
 * `AppPreferences.CloudSync` 只有 `encryptedPassword()`（密文），没有明文 getter，所以密码框
 * 初始值恒为空串：留空表示「沿用已保存的密文」，输入了才会覆盖。这样密文也不会被误当明文写回。
 */
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    var serverUrl by remember { mutableStateOf(AppPreferences.CloudSync.serverUrl) }
    var username by remember { mutableStateOf(AppPreferences.CloudSync.username) }
    // 永远不从已保存的密文里取初值：密码框只承载「本次要写入的新密码」。
    var password by remember { mutableStateOf("") }
    var passwordSaved by remember { mutableStateOf(AppPreferences.CloudSync.encryptedPassword().isNotBlank()) }
    // 上传模式缺省沿用上次选择（CloudSyncClient.backupPlaylists 也会回写这个偏好）。
    var mode by remember { mutableStateOf(AppPreferences.CloudSync.uploadMode) }
    var lastBackupAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastBackupAtMs) }
    var lastRestoreAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastRestoreAtMs) }
    var running by remember { mutableStateOf<CloudSyncTask?>(null) }
    var pendingClearConfirm by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }

    val busy = running != null
    // 只认「已保存」的配置：上传/还原走的是 AppPreferences 里的值，没保存就点等于拿旧配置发请求。
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
                    // Keystore 加密是阻塞调用，放 IO；结果回来后仍在主线程更新 Compose 状态。
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
                                    passwordSaved =
                                        AppPreferences.CloudSync.encryptedPassword().isNotBlank()
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

        SectionHeader(stringResource(R.string.cloud_sync_section_actions))
        SettingsCard {
            Text(
                text = stringResource(R.string.cloud_sync_mode_label),
                color = colors.onSurface,
                fontSize = 15.sp,
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
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cloud_sync_action_upload),
                    onClick = {
                        resultMessage = ""
                        running = CloudSyncTask.UPLOAD
                        scope.launch {
                            try {
                                when (val result = CloudPlaylistSyncService.upload(context, mode)) {
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
                                        AppLogger.e(TAG, "上传歌单失败：${result.message} (code=${result.code})")
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
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.cloud_sync_action_restore),
                    onClick = {
                        resultMessage = ""
                        running = CloudSyncTask.RESTORE
                        scope.launch {
                            try {
                                when (val result = CloudPlaylistSyncService.restore(context)) {
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
                                        // 404 不是错误，是「云端还没有备份」——契约 §6 第 5 条。
                                        if (result.code == HTTP_NOT_FOUND) {
                                            resultMessage = context.getString(R.string.cloud_sync_restore_empty)
                                            AppNoticeBus.post(
                                                message = resultMessage,
                                                icon = AppNoticeIcon.INFO,
                                            )
                                        } else {
                                            AppLogger.e(
                                                TAG,
                                                "还原云端歌单失败：${result.message} (code=${result.code})",
                                            )
                                            AppNoticeBus.error(
                                                context.getString(
                                                    R.string.cloud_sync_restore_failed,
                                                    result.message,
                                                )
                                            )
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
                )
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
                else -> Unit
            }
        }

        SectionHeader(stringResource(R.string.cloud_sync_section_sources))
        SettingsCard {
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

/** 页面正在跑的耗时动作；`null` 表示空闲。用它统一禁用按钮并显示进度。 */
private enum class CloudSyncTask { SAVE, UPLOAD, RESTORE, SOURCE_UPLOAD, SOURCE_RESTORE, CLEAR }

/** 契约里 `restore` 在「云端无备份」时返回的状态码（见 `CloudSyncClient.restorePlaylists`）。 */
private const val HTTP_NOT_FOUND = 404

/**
 * 「进行中」提示：按钮已禁用，这里再显式给一个转圈 + 文案，
 * 避免用户点完觉得界面没反应。
 */
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

/**
 * 清空云端数据不可恢复，必须二次确认。
 *
 * ⚠️ 用 [CloudSyncClient.clearAllData]，不是 `deletePlaylists(deleteAll = true)`：后者只清歌单，
 * 前者才清该账号的全部云端数据（契约 §2）。
 */
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

/**
 * 时间戳 → 本地化可读日期时间。`0` 表示从未（[AppPreferences.CloudSync.lastBackupAtMs] 约定）。
 *
 * 用 java 的 [DateFormat.getDateTimeInstance]（跟随系统 Locale），而不是硬编码 `yyyy-MM-dd`，
 * 否则非中文/英文区域会拿到错误格式。
 */
@Composable
private fun formattedTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return stringResource(R.string.cloud_sync_never)
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val date = remember(epochMs) { Date(epochMs) }
    return remember(formatter, date) { formatter.format(date) }
}
