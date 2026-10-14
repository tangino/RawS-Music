package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.online.sync.CloudSourceRestoreReport
import com.rawsmusic.module.online.sync.CloudSourceSyncService
import com.rawsmusic.module.online.sync.CloudSourceUploadReport
import com.rawsmusic.module.online.sync.CloudSyncClient
import com.rawsmusic.module.online.sync.CloudSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

private const val TAG = "CloudSync"

/** 契约里 `restore` 在「云端无备份」时返回的状态码（见 `CloudSyncClient.restoreSources`）。 */
private const val HTTP_NOT_FOUND = 404

/** 本机音源列表最多直接列出多少条；超出的折叠成一行说明，避免页面无限长。 */
private const val MAX_LISTED_SOURCES = 50

/** 页面正在跑的耗时动作；`null` 表示空闲。用它统一禁用输入与按钮并显示进度。 */
private enum class SourceCloudTask { SAVE, LOGIN, UPLOAD, RESTORE, DELETE, CLEAR }

/**
 * 音源门户的「云同步」tab。
 *
 * ## 为什么只调编排层
 *
 * 上传 / 还原一律走 [CloudSourceSyncService]：内联脚本正文、按 sha256 内容寻址安装、
 * 缺正文时跳过并回报，都是它的职责。页面这边**不碰序列化、不碰 HTTP**，只收集配置与展示结果。
 *
 * ## 密码永不回显
 *
 * `AppPreferences.CloudSync` 只有 `encryptedPassword()`（密文），没有明文 getter，所以密码框
 * 初值**恒为空串**：留空 = 沿用已保存的密文，输入了才覆盖。这样密文也不会被误当明文写回。
 *
 * ## 结果怎么呈现
 *
 * 与 `:app` 的云同步页一致：`AppNoticeBus` 给一次性提示，页面内再留一份常驻结果文本
 * （用户划走提示后仍能看到「装了几个 / 跳过几个 / 失败哪几个」）。
 */
@Composable
internal fun SourceCloudSyncTab(
    installedSources: List<InstalledLxSource>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    var serverUrl by remember { mutableStateOf(AppPreferences.CloudSync.serverUrl) }
    var username by remember { mutableStateOf(AppPreferences.CloudSync.username) }
    // 永远不从已保存的密文里取初值，见类注释。
    var password by remember { mutableStateOf("") }
    var passwordSaved by remember {
        mutableStateOf(AppPreferences.CloudSync.encryptedPassword().isNotBlank())
    }
    var configured by remember { mutableStateOf(CloudSyncClient.isConfigured()) }
    var lastBackupAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastBackupAtMs) }
    var lastRestoreAtMs by remember { mutableStateOf(AppPreferences.CloudSync.lastRestoreAtMs) }

    var running by remember { mutableStateOf<SourceCloudTask?>(null) }
    var resultMessage by rememberSaveable { mutableStateOf("") }
    var warningMessage by rememberSaveable { mutableStateOf("") }
    // 备份本身的时间戳（来自还原结果），与 lastRestoreAtMs（还原动作时间）不是一回事。
    var backupUpdatedAtMs by rememberSaveable { mutableStateOf(0L) }
    var confirmDeleteBackup by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val busy = running != null

    fun refreshPreferences() {
        serverUrl = AppPreferences.CloudSync.serverUrl
        username = AppPreferences.CloudSync.username
        passwordSaved = AppPreferences.CloudSync.encryptedPassword().isNotBlank()
        configured = CloudSyncClient.isConfigured()
        lastBackupAtMs = AppPreferences.CloudSync.lastBackupAtMs
        lastRestoreAtMs = AppPreferences.CloudSync.lastRestoreAtMs
    }

    // 还原会安装音源，列表变化后时间戳也可能变（Preferences 不是 Compose State，必须手动同步）。
    LaunchedEffect(installedSources.size, installedSources.maxOfOrNull { it.updatedAtMs }) {
        lastBackupAtMs = AppPreferences.CloudSync.lastBackupAtMs
        lastRestoreAtMs = AppPreferences.CloudSync.lastRestoreAtMs
    }

    fun clearResults() {
        resultMessage = ""
        warningMessage = ""
    }

    fun onSaved(result: CloudSyncResult<Unit>) {
        when (result) {
            is CloudSyncResult.Success -> {
                refreshPreferences()
                password = ""
                resultMessage = context.getString(R.string.source_cloud_sync_result_save_success)
                AppNoticeBus.post(message = resultMessage, icon = AppNoticeIcon.INFO)
            }
            is CloudSyncResult.Failure -> {
                AppLogger.e(TAG, "保存音源云同步配置失败：${result.message} (code=${result.code})")
                resultMessage = context.getString(
                    R.string.source_cloud_sync_result_save_failed,
                    result.message,
                )
                AppNoticeBus.error(resultMessage)
            }
        }
    }

    fun onUploaded(result: CloudSyncResult<CloudSourceUploadReport>) {
        when (result) {
            is CloudSyncResult.Success -> {
                refreshPreferences()
                resultMessage = context.getString(
                    R.string.source_cloud_sync_result_uploaded,
                    result.data.uploaded,
                )
                // incomplete 非空 = 备份里有读不到正文的空壳，必须显式告警，不能只说「上传成功」。
                warningMessage = if (result.data.incomplete.isEmpty()) {
                    ""
                } else {
                    context.getString(
                        R.string.source_cloud_sync_warn_incomplete,
                        result.data.incomplete.size,
                    ) + "\n" + result.data.incomplete.joinToString()
                }
                if (warningMessage.isBlank()) {
                    AppNoticeBus.post(message = resultMessage, icon = AppNoticeIcon.INFO)
                } else {
                    AppNoticeBus.error(warningMessage)
                }
            }
            is CloudSyncResult.Failure -> {
                AppLogger.e(TAG, "上传音源失败：${result.message} (code=${result.code})")
                resultMessage = context.getString(
                    R.string.source_cloud_sync_result_upload_failed,
                    result.message,
                )
                AppNoticeBus.error(resultMessage)
            }
        }
    }

    fun onRestored(result: CloudSyncResult<CloudSourceRestoreReport>) {
        when (result) {
            is CloudSyncResult.Success -> {
                refreshPreferences()
                val report = result.data
                backupUpdatedAtMs = report.updatedAt
                resultMessage = context.getString(
                    R.string.source_cloud_sync_result_restored,
                    report.installed,
                )
                warningMessage = buildString {
                    if (report.skippedIncomplete.isNotEmpty()) {
                        append(
                            context.getString(
                                R.string.source_cloud_sync_result_skipped_incomplete,
                                report.skippedIncomplete.size,
                            )
                        )
                        append('\n')
                        append(report.skippedIncomplete.joinToString())
                    }
                    if (report.hashMismatched.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(
                            context.getString(
                                R.string.source_cloud_sync_result_hash_mismatch,
                                report.hashMismatched.size,
                            )
                        )
                        append('\n')
                        append(report.hashMismatched.joinToString())
                    }
                    if (report.failures.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(
                            context.getString(
                                R.string.source_cloud_sync_result_failed_items,
                                report.failures.size,
                            )
                        )
                        append('\n')
                        append(report.failures.joinToString("\n"))
                    }
                }
                if (warningMessage.isBlank()) {
                    AppNoticeBus.post(message = resultMessage, icon = AppNoticeIcon.DOWNLOAD)
                } else {
                    AppNoticeBus.error(warningMessage)
                }
            }
            is CloudSyncResult.Failure -> {
                // 404 不是错误，是「云端还没有音源备份」——契约 §6 第 5 条。
                if (result.code == HTTP_NOT_FOUND) {
                    resultMessage = context.getString(R.string.source_cloud_sync_result_restore_empty)
                    AppNoticeBus.post(message = resultMessage, icon = AppNoticeIcon.INFO)
                } else {
                    AppLogger.e(TAG, "还原音源失败：${result.message} (code=${result.code})")
                    resultMessage = context.getString(
                        R.string.source_cloud_sync_result_restore_failed,
                        result.message,
                    )
                    AppNoticeBus.error(resultMessage)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceSectionTopBar(stringResource(R.string.source_cloud_sync_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                // 迷你播放器 + 底部 tab 会盖住底部，留出与配置页一致的余量。
                .padding(bottom = 174.dp),
        ) {
            Text(
                text = stringResource(R.string.source_cloud_sync_summary),
                color = colors.secondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = appFontFamily(),
            )

            // ---------------------------------------------------------- 服务器与账号
            CloudSyncSectionTitle(stringResource(R.string.source_cloud_sync_section_account))

            TextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = stringResource(R.string.source_cloud_sync_server_url_label),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.source_cloud_sync_server_url_hint),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.source_cloud_sync_username_label),
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.source_cloud_sync_password_label),
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    if (passwordSaved) R.string.source_cloud_sync_password_saved
                    else R.string.source_cloud_sync_password_not_set
                ),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.source_cloud_sync_action_save),
                    onClick = {
                        clearResults()
                        running = SourceCloudTask.SAVE
                        scope.launch {
                            try {
                                // Keystore 加密是阻塞调用，放 IO；回来后仍在主线程更新状态。
                                onSaved(
                                    withContext(Dispatchers.IO) {
                                        CloudSyncClient.saveCredentials(serverUrl, username, password)
                                    }
                                )
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && serverUrl.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.source_cloud_sync_action_login),
                    onClick = {
                        clearResults()
                        running = SourceCloudTask.LOGIN
                        scope.launch {
                            try {
                                when (val result = CloudSyncClient.authenticate(context)) {
                                    is CloudSyncResult.Success -> {
                                        refreshPreferences()
                                        resultMessage = result.data
                                        AppNoticeBus.post(
                                            message = result.data,
                                            icon = AppNoticeIcon.INFO,
                                        )
                                    }
                                    is CloudSyncResult.Failure -> {
                                        resultMessage = result.message
                                        AppNoticeBus.error(result.message)
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
            if (running == SourceCloudTask.SAVE) {
                CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_saving))
            }
            if (running == SourceCloudTask.LOGIN) {
                CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_logging_in))
            }
            if (!configured) {
                Text(
                    text = stringResource(R.string.source_cloud_sync_not_configured),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---------------------------------------------------------- 同步操作
            CloudSyncSectionTitle(stringResource(R.string.source_cloud_sync_section_sync))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.source_cloud_sync_action_upload),
                    onClick = {
                        clearResults()
                        running = SourceCloudTask.UPLOAD
                        scope.launch {
                            try {
                                onUploaded(CloudSourceSyncService.upload(context))
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.source_cloud_sync_action_restore),
                    onClick = {
                        clearResults()
                        running = SourceCloudTask.RESTORE
                        scope.launch {
                            try {
                                onRestored(CloudSourceSyncService.restore(context))
                            } finally {
                                running = null
                            }
                        }
                    },
                    enabled = !busy && configured,
                    modifier = Modifier.weight(1f),
                )
            }
            if (running == SourceCloudTask.UPLOAD) {
                CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_uploading))
            }
            if (running == SourceCloudTask.RESTORE) {
                CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_restoring))
            }

            CloudSyncResultPanel(
                message = resultMessage,
                warning = warningMessage,
                backupUpdatedAtMs = backupUpdatedAtMs,
            )

            // ---------------------------------------------------------- 状态
            CloudSyncSectionTitle(stringResource(R.string.source_cloud_sync_section_status))
            CloudSyncInfoRow(
                label = stringResource(
                    R.string.source_cloud_sync_last_upload,
                    cloudSyncTimestamp(lastBackupAtMs),
                )
            )
            CloudSyncInfoRow(
                label = stringResource(
                    R.string.source_cloud_sync_last_restore,
                    cloudSyncTimestamp(lastRestoreAtMs),
                )
            )

            // ---------------------------------------------------------- 本机已安装音源
            CloudSyncSectionTitle(
                stringResource(
                    R.string.source_cloud_sync_section_installed,
                    installedSources.size,
                )
            )
            if (installedSources.isEmpty()) {
                CloudSyncSurface {
                    Text(
                        text = stringResource(R.string.source_cloud_sync_installed_empty),
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            } else {
                // 普通内容列表用 forEach 展平（本页整体已 verticalScroll，嵌套滚动列表会被约束成 0 高）。
                installedSources.take(MAX_LISTED_SOURCES).forEach { source ->
                    CloudSyncInstalledSourceRow(source)
                    Spacer(Modifier.height(8.dp))
                }
                if (installedSources.size > MAX_LISTED_SOURCES) {
                    Text(
                        text = stringResource(
                            R.string.source_cloud_sync_installed_more,
                            installedSources.size - MAX_LISTED_SOURCES,
                        ),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }

            // ---------------------------------------------------------- 危险操作
            CloudSyncSectionTitle(stringResource(R.string.source_cloud_sync_section_danger))
            CloudSyncSurface {
                CloudSyncDangerRow(
                    title = stringResource(R.string.source_cloud_sync_action_delete_backup),
                    enabled = !busy && configured,
                    onClick = { confirmDeleteBackup = true },
                )
                Spacer(Modifier.height(6.dp))
                CloudSyncDangerRow(
                    title = stringResource(R.string.source_cloud_sync_action_clear_all),
                    enabled = !busy && configured,
                    onClick = { confirmClearAll = true },
                )
                if (running == SourceCloudTask.DELETE) {
                    CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_deleting))
                }
                if (running == SourceCloudTask.CLEAR) {
                    CloudSyncProgressRow(stringResource(R.string.source_cloud_sync_state_clearing))
                }
            }
        }
    }

    if (confirmDeleteBackup) {
        CloudSyncConfirmDialog(
            title = stringResource(R.string.source_cloud_sync_delete_confirm_title),
            summary = stringResource(R.string.source_cloud_sync_delete_confirm_summary),
            confirmLabel = stringResource(R.string.source_cloud_sync_action_confirm_delete),
            onDismiss = { confirmDeleteBackup = false },
            onConfirm = {
                confirmDeleteBackup = false
                clearResults()
                running = SourceCloudTask.DELETE
                scope.launch {
                    try {
                        when (val result = CloudSyncClient.deleteSourceBackup(context)) {
                            is CloudSyncResult.Success -> {
                                resultMessage =
                                    context.getString(R.string.source_cloud_sync_result_deleted)
                                AppNoticeBus.post(
                                    message = resultMessage,
                                    icon = AppNoticeIcon.INFO,
                                )
                            }
                            is CloudSyncResult.Failure -> {
                                AppLogger.e(TAG, "删除云端音源备份失败：${result.message}")
                                resultMessage = context.getString(
                                    R.string.source_cloud_sync_result_delete_failed,
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
        )
    }

    if (confirmClearAll) {
        CloudSyncConfirmDialog(
            title = stringResource(R.string.source_cloud_sync_clear_confirm_title),
            summary = stringResource(R.string.source_cloud_sync_clear_confirm_summary),
            confirmLabel = stringResource(R.string.source_cloud_sync_action_confirm_clear),
            onDismiss = { confirmClearAll = false },
            onConfirm = {
                confirmClearAll = false
                clearResults()
                running = SourceCloudTask.CLEAR
                scope.launch {
                    try {
                        when (val result = CloudSyncClient.clearAllData(context)) {
                            is CloudSyncResult.Success -> {
                                resultMessage =
                                    context.getString(R.string.source_cloud_sync_result_cleared)
                                AppNoticeBus.post(
                                    message = resultMessage,
                                    icon = AppNoticeIcon.INFO,
                                )
                            }
                            is CloudSyncResult.Failure -> {
                                AppLogger.e(TAG, "清空云端数据失败：${result.message}")
                                resultMessage = context.getString(
                                    R.string.source_cloud_sync_result_clear_failed,
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
        )
    }
}
