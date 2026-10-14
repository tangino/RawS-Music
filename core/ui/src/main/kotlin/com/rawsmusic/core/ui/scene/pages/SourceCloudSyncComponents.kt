package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.online.sync.CloudSyncClient
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 音源门户「云同步」tab 的展示层组件。
 *
 * 与 [SourceCloudSyncTab] 分文件只是为了控制单文件规模；这里**没有任何业务逻辑**，
 * 动作编排全在 tab 那边，所以组件只收已算好的文案与开关。
 */

/** 与 `SourceConfigurationPage` 的错误文字同色，保持门户内一致。 */
internal val CloudSyncWarningColor = Color(0xFFE15B64)

@Composable
internal fun CloudSyncSectionTitle(title: String) {
    val colors = themeColors()
    Text(
        text = title,
        color = colors.onSurface,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = appFontFamily(),
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

/** 卡片壳，与音源门户其余 tab 的观感一致。 */
@Composable
internal fun CloudSyncSurface(content: @Composable () -> Unit) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

@Composable
internal fun CloudSyncInfoRow(label: String) {
    val colors = themeColors()
    Text(
        text = label,
        color = colors.onSurface,
        fontSize = 14.sp,
        fontFamily = appFontFamily(),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * 「进行中」提示：按钮/输入已禁用，这里再显式给一个转圈 + 文案，
 * 避免用户点完觉得界面没反应。
 */
@Composable
internal fun CloudSyncProgressRow(message: String) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
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
 * 结果面板：成功摘要在上，告警（缺正文 / 跳过 / 哈希不匹配 / 安装失败清单）在下，用告警色区分。
 * 常驻在页面里，用户划走 `AppNoticeBus` 的提示后仍能看到这几类结果。
 */
@Composable
internal fun CloudSyncResultPanel(
    message: String,
    warning: String,
    backupUpdatedAtMs: Long,
) {
    val colors = themeColors()
    if (message.isBlank() && warning.isBlank()) return
    Spacer(Modifier.height(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.primaryContainer.copy(alpha = 0.60f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (message.isNotBlank()) {
            Text(
                text = message,
                color = colors.onSurface,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = appFontFamily(),
            )
        }
        if (warning.isNotBlank()) {
            if (message.isNotBlank()) Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = CloudSyncWarningColor,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = appFontFamily(),
            )
        }
        if (backupUpdatedAtMs > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.source_cloud_sync_backup_updated_at,
                    cloudSyncTimestamp(backupUpdatedAtMs),
                ),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
            )
        }
    }
}

@Composable
internal fun CloudSyncInstalledSourceRow(source: InstalledLxSource) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.name,
                color = colors.onSurface,
                fontSize = 15.sp,
                fontFamily = appFontFamily(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (source.enabled) R.string.source_cloud_sync_installed_enabled
                    else R.string.source_cloud_sync_installed_disabled
                ),
                color = if (source.enabled) colors.primary else colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(
                    stringResource(
                        R.string.source_cloud_sync_installed_version,
                        source.version.ifBlank { stringResource(R.string.source_undeclared_version) },
                    )
                )
                if (source.author.isNotBlank()) {
                    append(" · ")
                    append(
                        stringResource(R.string.source_cloud_sync_installed_author, source.author)
                    )
                }
            },
            color = colors.secondaryText,
            fontSize = 11.sp,
            fontFamily = appFontFamily(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (source.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = source.lastError,
                color = CloudSyncWarningColor,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = appFontFamily(),
            )
        }
    }
}

/** 危险操作行：不可恢复的动作统一用错误色，和普通按钮区分开。 */
@Composable
internal fun CloudSyncDangerRow(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        color = if (enabled) CloudSyncWarningColor else CloudSyncWarningColor.copy(alpha = 0.45f),
        fontSize = 14.sp,
        fontFamily = appFontFamily(),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

/**
 * 不可恢复的操作（删备份 / 清空云端）必须二次确认。
 *
 * ⚠️ 清空走 [CloudSyncClient.clearAllData]，不是删音源备份：后者只删音源，
 * 前者才清该账号的全部云端数据（契约 §2）。
 */
@Composable
internal fun CloudSyncConfirmDialog(
    title: String,
    summary: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = title,
        summary = summary,
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 时间戳 → 本地化可读日期时间；`0` 表示从未（[AppPreferences.CloudSync.lastBackupAtMs] 约定）。
 *
 * 用 [DateFormat.getDateTimeInstance]（跟随系统 Locale），而不是硬编码 `yyyy-MM-dd`，
 * 否则非中英区域会拿到错误格式。
 */
@Composable
internal fun cloudSyncTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return stringResource(R.string.source_cloud_sync_never)
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val date = remember(epochMs) { Date(epochMs) }
    return remember(formatter, date) { formatter.format(date) }
}
