package com.rawsmusic.ui.playlist

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.playlist.PlaylistM3u8Codec
import com.rawsmusic.module.data.playlist.PlaylistTransferStore
import com.rawsmusic.module.online.transfer.OnlineImportOutcome
import com.rawsmusic.module.online.transfer.OnlinePlaylistImportService
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "PlaylistTransferUI"
private val DIALOG_ITEM_GAP = 12.dp

enum class PlaylistExportFormat(
    val extension: String,
    val mimeType: String,
    @StringRes val labelRes: Int,
) {
    M3U8("m3u8", "audio/x-mpegurl", R.string.playlist_export_format_m3u8),
    JSON("json", "application/json", R.string.playlist_export_format_json),
}

/**
 * 歌单导出弹窗（Miuix 风格）。
 *
 * 支持选择 M3U8 与 JSON 两种格式，并支持保存至下载目录或调起系统分享。
 */
@Composable
fun ExportPlaylistDialog(
    playlist: UserPlaylist,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    var selectedFormat by remember { mutableStateOf(PlaylistExportFormat.M3U8) }
    val initialFileName = remember(playlist.name, selectedFormat) {
        val sanitized = PlaylistM3u8Codec.sanitizeFileName(playlist.name)
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        "${sanitized}_$dateStr.${selectedFormat.extension}"
    }
    var fileName by remember(selectedFormat) { mutableStateOf(initialFileName) }
    var isExporting by remember { mutableStateOf(false) }

    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_export_title),
        summary = playlist.name,
        backgroundColor = colors.surface,
        onDismissRequest = { if (!isExporting) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            // 格式选择 Tab
            Text(
                text = stringResource(R.string.playlist_export_format),
                color = colors.secondaryText,
                fontSize = 12.sp,
                fontFamily = appFontFamily(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.08f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaylistExportFormat.values().forEach { format ->
                    val isSelected = selectedFormat == format
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) colors.primary.copy(alpha = 0.16f) else colors.surface.copy(alpha = 0f),
                            )
                            .clickable(enabled = !isExporting) { selectedFormat = format }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(format.labelRes),
                            color = if (isSelected) colors.primary else colors.secondaryText,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            }

            // 文件名输入
            TextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = stringResource(R.string.playlist_export_filename),
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isExporting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.playlist_export_saving),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 动作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.playlist_cancel_action),
                    onClick = onDismiss,
                    enabled = !isExporting,
                    modifier = Modifier.weight(0.8f),
                )
                Button(
                    onClick = {
                        isExporting = true
                        scope.launch {
                            try {
                                val content = when (selectedFormat) {
                                    PlaylistExportFormat.M3U8 -> PlaylistM3u8Codec.encode(playlist)
                                    PlaylistExportFormat.JSON -> PlaylistTransferStore.exportJson(playlist)
                                }
                                val result = PlaylistTransferStore.saveToDownloads(
                                    context = context,
                                    fileName = fileName,
                                    content = content,
                                    mimeType = selectedFormat.mimeType,
                                )
                                result.fold(
                                    onSuccess = { pathMsg ->
                                        AppNoticeBus.post(
                                            message = pathMsg,
                                            icon = AppNoticeIcon.PLAYLIST,
                                        )
                                        onDismiss()
                                    },
                                    onFailure = { error ->
                                        AppNoticeBus.error(
                                            context.getString(R.string.playlist_export_failed, error.message.orEmpty()),
                                        )
                                    },
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                AppLogger.e(TAG, "导出失败", error)
                                AppNoticeBus.error(
                                    context.getString(R.string.playlist_export_failed, error.message.orEmpty()),
                                )
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    enabled = !isExporting && fileName.isNotBlank(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1.1f),
                ) {
                    Text(stringResource(R.string.playlist_export_save_download), fontFamily = appFontFamily())
                }
                Button(
                    onClick = {
                        isExporting = true
                        scope.launch {
                            try {
                                val file = when (selectedFormat) {
                                    PlaylistExportFormat.M3U8 -> PlaylistTransferStore.exportM3u8(context, playlist)
                                    PlaylistExportFormat.JSON -> PlaylistTransferStore.exportJson(context, playlist)
                                }
                                if (file != null) {
                                    PlaylistTransferStore.sharePlaylistFile(
                                        context = context,
                                        file = file,
                                        title = playlist.name,
                                        mimeType = selectedFormat.mimeType,
                                    )
                                    onDismiss()
                                } else {
                                    AppNoticeBus.error(context.getString(R.string.playlist_export_failed, ""))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                AppLogger.e(TAG, "分享导出文件失败", error)
                                AppNoticeBus.error(context.getString(R.string.playlist_export_failed, error.message.orEmpty()))
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    enabled = !isExporting,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1.1f),
                ) {
                    Text(stringResource(R.string.playlist_export_share), fontFamily = appFontFamily())
                }
            }
        }
    }
}

/**
 * 歌单导入弹窗（支持网络链接/文本清单导入 与 本地文件导入）。
 */
@Composable
fun ImportPlaylistDialog(
    librarySongs: List<AudioFile>,
    onDismiss: () -> Unit,
    onImportSuccess: ((UserPlaylist) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 链接/文本, 1: 本地文件
    var inputText by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    // 本地文件状态
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var isParsingFile by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isParsingFile = true
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    }
                }.orEmpty()

                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_playlist"
                selectedFileName = fileName
                fileContent = content

                // 快速预览
                val trimmed = content.trim()
                val (parsedTitle, songCount) = when {
                    trimmed.startsWith("{") -> {
                        val obj = JSONObject(trimmed)
                        val title = obj.optString("title", fileName.substringBeforeLast('.'))
                        val count = obj.optJSONArray("songs")?.length() ?: 0
                        title to count
                    }
                    trimmed.contains("#EXTM3U") || trimmed.contains("#EXTINF") -> {
                        val doc = PlaylistM3u8Codec.decode(trimmed)
                        doc.title.ifBlank { fileName.substringBeforeLast('.') } to doc.entries.size
                    }
                    else -> {
                        val count = trimmed.lines().count { it.isNotBlank() && !it.startsWith("#") }
                        fileName.substringBeforeLast('.') to count
                    }
                }
                previewText = context.getString(R.string.playlist_import_parsed_preview, parsedTitle, songCount)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "解析本地文件出错", error)
                AppNoticeBus.error(context.getString(R.string.playlist_import_parse_failed))
                selectedFileName = null
                fileContent = null
                previewText = null
            } finally {
                isParsingFile = false
            }
        }
    }

    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.playlist_import_title),
        backgroundColor = colors.surface,
        onDismissRequest = { if (!isImporting) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DIALOG_ITEM_GAP),
        ) {
            // Tab 切换（链接/文本 VS 本地文件）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.08f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tabs = listOf(
                    R.string.playlist_import_tab_link_text,
                    R.string.playlist_import_tab_file,
                )
                tabs.forEachIndexed { index, titleRes ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) colors.primary.copy(alpha = 0.16f) else colors.surface.copy(alpha = 0f),
                            )
                            .clickable(enabled = !isImporting) { selectedTab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(titleRes),
                            color = if (isSelected) colors.primary else colors.secondaryText,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            }

            if (selectedTab == 0) {
                // 链接/文本导入
                Text(
                    text = stringResource(R.string.playlist_import_link_text_hint),
                    color = colors.secondaryText,
                    fontSize = 11.5.sp,
                    fontFamily = appFontFamily(),
                )

                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = stringResource(R.string.playlist_import_link_text_hint),
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 辅助操作：从剪贴板粘贴
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.playlist_import_paste_clipboard),
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipMgr?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clipText.isNullOrBlank()) {
                                inputText = clipText
                            }
                        },
                        enabled = !isImporting,
                    )
                }
            } else {
                // 本地文件导入
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !isImporting && !isParsingFile,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.playlist_import_select_file),
                        fontFamily = appFontFamily(),
                    )
                }

                if (isParsingFile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.playlist_import_parsing),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                        )
                    }
                } else if (selectedFileName != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.onSurfaceVariant.copy(alpha = 0.06f))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.playlist_import_selected_file, selectedFileName.orEmpty()),
                            color = colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        previewText?.let { preview ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = preview,
                                color = colors.secondaryText,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }
            }

            if (isImporting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.playlist_import_processing),
                        color = colors.secondaryText,
                        fontSize = 13.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 动作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.playlist_cancel_action),
                    onClick = onDismiss,
                    enabled = !isImporting,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val contentToImport = if (selectedTab == 0) inputText.trim() else fileContent?.trim().orEmpty()
                        if (contentToImport.isBlank()) {
                            AppNoticeBus.error(context.getString(R.string.playlist_import_input_empty))
                            return@Button
                        }
                        isImporting = true
                        scope.launch {
                            try {
                                if (selectedTab == 0) {
                                    // 尝试作为平台网络链接导入
                                    val linkOutcome = OnlinePlaylistImportService.importFromLink(context, contentToImport)
                                    when (linkOutcome) {
                                        is OnlineImportOutcome.Success -> {
                                            AppNoticeBus.post(
                                                message = context.getString(
                                                    R.string.playlist_import_online_success,
                                                    linkOutcome.playlistName,
                                                    linkOutcome.songCount,
                                                ),
                                                icon = AppNoticeIcon.PLAYLIST,
                                            )
                                            onDismiss()
                                            return@launch
                                        }
                                        is OnlineImportOutcome.EmptyPlaylist -> {
                                            AppNoticeBus.error(context.getString(R.string.playlist_import_parse_failed))
                                            return@launch
                                        }
                                        is OnlineImportOutcome.PlaylistNotFound -> {
                                            AppNoticeBus.error(context.getString(R.string.playlist_import_parse_failed))
                                            return@launch
                                        }
                                        is OnlineImportOutcome.Failed -> {
                                            AppNoticeBus.error(
                                                context.getString(R.string.playlist_import_failed_with_reason, linkOutcome.reason),
                                            )
                                            return@launch
                                        }
                                        OnlineImportOutcome.LinkNotRecognized -> {
                                            // 不是平台链接，继续向下走文本/文件解析
                                        }
                                    }
                                }

                                // 文本 / 文件解析逻辑
                                val result = when {
                                    contentToImport.startsWith("{") -> {
                                        PlaylistTransferStore.importJson(
                                            context = context,
                                            text = contentToImport,
                                            librarySongs = librarySongs,
                                            fallbackName = selectedFileName?.substringBeforeLast('.')?.ifBlank { "JSON 导入歌单" } ?: "导入歌单",
                                        )
                                    }
                                    contentToImport.contains("#EXTM3U") || contentToImport.contains("#EXTINF") -> {
                                        PlaylistTransferStore.importM3u8(
                                            context = context,
                                            text = contentToImport,
                                            librarySongs = librarySongs,
                                            fallbackName = selectedFileName?.substringBeforeLast('.')?.ifBlank { "M3U8 导入歌单" } ?: "导入歌单",
                                        )
                                    }
                                    else -> {
                                        PlaylistTransferStore.importPlainText(
                                            context = context,
                                            text = contentToImport,
                                            librarySongs = librarySongs,
                                            playlistName = "文本导入歌单",
                                        )
                                    }
                                }

                                val importedPlaylist = result.playlist
                                if (importedPlaylist != null && result.totalCount > 0) {
                                    AppNoticeBus.post(
                                        message = context.getString(
                                            R.string.playlist_import_result_detail,
                                            importedPlaylist.name,
                                            result.localCount,
                                            result.onlineCount,
                                        ),
                                        icon = AppNoticeIcon.PLAYLIST,
                                    )
                                    onImportSuccess?.invoke(importedPlaylist)
                                    onDismiss()
                                } else {
                                    AppNoticeBus.error(context.getString(R.string.playlist_import_parse_failed))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                AppLogger.e(TAG, "导入失败", error)
                                AppNoticeBus.error(
                                    context.getString(R.string.playlist_import_failed_with_reason, error.message.orEmpty()),
                                )
                            } finally {
                                isImporting = false
                            }
                        }
                    },
                    enabled = !isImporting && (if (selectedTab == 0) inputText.isNotBlank() else !fileContent.isNullOrBlank()),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.playlist_import_button), fontFamily = appFontFamily())
                }
            }
        }
    }
}
