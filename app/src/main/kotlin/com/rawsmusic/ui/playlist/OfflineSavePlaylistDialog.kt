package com.rawsmusic.ui.playlist

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadStatus
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadTask
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 歌单网络歌曲离线保存（下载）弹窗。
 *
 * 遵循 Miuix 极简设计规范，允许用户自主勾选待保存歌曲与目标音质，
 * 实时展示每首歌曲的下载状态、百分比进度、字节数，并支持单曲重试与取消。
 */
@Composable
fun OfflineSavePlaylistDialog(
    songs: List<AudioFile>,
    playlist: UserPlaylist,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    val allTasks by MusicSourceDownloadController.tasks.collectAsState()

    // 汇总歌单中的全部在线来源歌曲（包括当前仍在下载或刚完成下载转本地的条目）
    val onlineOriginSongs = remember(playlist, songs) {
        val currentOnlineByPath = songs.filter { it.path.startsWith("online://") }.associateBy { it.path }
        val storedOnline = playlist.songs.filter { it.isOnlineEntry() }
        if (storedOnline.isNotEmpty()) {
            storedOnline.map { stored ->
                currentOnlineByPath[stored.path] ?: stored.toAudioFile()
            }
        } else {
            songs.filter { it.path.startsWith("online://") }
        }
    }

    // 哪些在线来源歌曲当前已被解析为本地有效文件
    val resolvedLocalPaths = remember(playlist, songs) {
        val stillOnline = songs.filter { it.path.startsWith("online://") }.map { it.path }.toSet()
        playlist.songs
            .filter { it.isOnlineEntry() && it.path !in stillOnline }
            .map { it.path }
            .toSet()
    }

    fun taskFor(song: AudioFile): MusicSourceDownloadTask? =
        findDownloadTaskForOnlineSong(song, allTasks)

    fun isAlreadyLocal(song: AudioFile): Boolean {
        if (song.path in resolvedLocalPaths) return true
        val task = taskFor(song)
        return isValidCompletedDownloadTask(task)
    }

    // 勾选状态映射：默认仅勾选未保存到本地且当前未在下载中的网络歌曲
    val selectedKeys = remember(onlineOriginSongs) {
        mutableStateMapOf<String, Boolean>().apply {
            onlineOriginSongs.forEach { song ->
                val task = taskFor(song)
                val active = task?.isActive == true
                this[song.path] = !isAlreadyLocal(song) && !active
            }
        }
    }

    val selectedCount = selectedKeys.values.count { it }
    val allSelected = onlineOriginSongs.isNotEmpty() && selectedCount == onlineOriginSongs.size
    val localReadyCount = onlineOriginSongs.count { isAlreadyLocal(it) }

    // 目标音质偏好
    var selectedQuality by remember {
        mutableStateOf(RawSourceQuality.fromKey(AppPreferences.Download.defaultQuality))
    }

    val downloadDir = remember {
        MusicSourceDownloadController.publicDownloadDirectory().absolutePath
    }

    ActivityOverlayBackOwner(active = true)
    WindowDialog(
        show = true,
        title = stringResource(R.string.download_dialog_title),
        summary = playlist.name,
        modifier = Modifier.widthIn(max = LocalRawWindowLayoutProfile.current.dialogMaxWidth),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 统计与全选控制条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.download_dialog_summary,
                        onlineOriginSongs.size,
                        localReadyCount,
                        selectedCount,
                    ),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val newTarget = !allSelected
                            onlineOriginSongs.forEach { song ->
                                selectedKeys[song.path] = newTarget
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogCheckSquare(checked = allSelected, activeColor = colors.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(com.rawsmusic.core.ui.R.string.library_action_select_all),
                        color = colors.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = appFontFamily(),
                    )
                }
            }

            // 歌曲列表与单曲实时下载进度区域
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.05f))
                    .padding(vertical = 4.dp),
            ) {
                items(onlineOriginSongs, key = { it.path }) { song ->
                    val checked = selectedKeys[song.path] ?: false
                    val task = taskFor(song)
                    val alreadyLocal = isAlreadyLocal(song)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedKeys[song.path] = !checked
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogCheckSquare(checked = checked, activeColor = colors.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    color = colors.onSurface,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = appFontFamily(),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = song.artist.ifBlank { stringResource(com.rawsmusic.core.ui.R.string.source_unknown_singer) },
                                    color = colors.secondaryText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = appFontFamily(),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            when {
                                task != null && task.isActive -> {
                                    val statusLabel = when (task.status) {
                                        MusicSourceDownloadStatus.Resolving -> "解析中"
                                        MusicSourceDownloadStatus.Queued -> "排队中"
                                        MusicSourceDownloadStatus.Paused -> "已暂停"
                                        MusicSourceDownloadStatus.Downloading -> {
                                            if (task.totalBytes > 0L) {
                                                "${(task.progressFraction * 100f).toInt()}%"
                                            } else {
                                                "下载中"
                                            }
                                        }
                                        else -> ""
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(colors.primary.copy(alpha = 0.12f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = statusLabel,
                                                color = colors.primary,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = appFontFamily(),
                                            )
                                        }
                                        Text(
                                            text = "取消",
                                            color = colors.secondaryText,
                                            fontSize = 10.sp,
                                            fontFamily = appFontFamily(),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { MusicSourceDownloadController.cancel(task.id) }
                                                .padding(horizontal = 5.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                alreadyLocal -> {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(colors.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.download_already_local),
                                            color = colors.primary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = appFontFamily(),
                                        )
                                    }
                                }
                                task != null && task.status == MusicSourceDownloadStatus.Failed -> {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFE53935).copy(alpha = 0.12f))
                                            .clickable { MusicSourceDownloadController.retry(context, task.id) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = "重试",
                                            color = Color(0xFFE53935),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            fontFamily = appFontFamily(),
                                        )
                                    }
                                }
                            }
                        }

                        // 活跃下载时展示单曲进度条与字节数
                        if (task != null && task.isActive) {
                            Spacer(Modifier.height(6.dp))
                            if (task.status == MusicSourceDownloadStatus.Downloading && task.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = task.progressFraction,
                                    color = colors.primary,
                                    trackColor = colors.onSurfaceVariant.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 28.dp)
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${formatDownloadBytes(task.bytesDownloaded)} / ${formatDownloadBytes(task.totalBytes)}",
                                    color = colors.secondaryText,
                                    fontSize = 10.sp,
                                    fontFamily = appFontFamily(),
                                    modifier = Modifier.padding(start = 28.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    color = colors.primary,
                                    trackColor = colors.onSurfaceVariant.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 28.dp)
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                )
                            }
                        } else if (task != null && task.status == MusicSourceDownloadStatus.Failed && task.error.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = task.error,
                                color = Color(0xFFE53935),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = appFontFamily(),
                                modifier = Modifier.padding(start = 28.dp),
                            )
                        }
                    }
                }
            }

            // 音质选择
            Text(
                text = stringResource(R.string.download_dialog_quality),
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                    RawSourceQuality.Standard to "标准 (128K)",
                    RawSourceQuality.High to "高品质 (320K)",
                    RawSourceQuality.Lossless to "无损 (FLAC)",
                    RawSourceQuality.HiRes to "Hi-Res",
                ).forEach { (quality, label) ->
                    val isSelected = selectedQuality == quality
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) colors.primary.copy(alpha = 0.16f) else Color.Transparent,
                            )
                            .clickable {
                                selectedQuality = quality
                                AppPreferences.Download.defaultQuality = quality.name
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) colors.primary else colors.secondaryText,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = appFontFamily(),
                            maxLines = 1,
                        )
                    }
                }
            }

            // 当前保存路径提示
            Text(
                text = stringResource(R.string.download_dialog_save_path, downloadDir),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            // 动作按钮栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.8f),
                )
                Button(
                    onClick = {
                        val toDownload = onlineOriginSongs.filter { selectedKeys[it.path] == true }
                        if (toDownload.isEmpty()) {
                            onDismiss()
                            return@Button
                        }
                        scope.launch {
                            var queuedCount = 0
                            toDownload.forEach { song ->
                                val onlineItem = OnlineSourceMediaMapper.toOnlineSongItem(song) ?: return@forEach
                                val rawItem = OnlineSourceMediaMapper.toRawSourceMediaItem(onlineItem)
                                MusicSourceDownloadController.enqueue(context, rawItem, selectedQuality)
                                queuedCount++
                            }
                            if (queuedCount > 0) {
                                AppNoticeBus.post(
                                    message = context.getString(R.string.download_queued_notice, queuedCount),
                                    icon = AppNoticeIcon.DOWNLOAD,
                                )
                            }
                            onDismiss()
                        }
                    },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1.2f),
                ) {
                    Text(
                        text = stringResource(R.string.download_dialog_confirm, selectedCount),
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }
    }
}

/**
 * 为在线歌曲匹配其对应的下载任务：优先按稳定标识（remote://platform/songId）精确命中，
 * 其次按歌名匹配，且优先返回正在进行中的任务。
 */
internal fun findDownloadTaskForOnlineSong(
    song: AudioFile,
    tasks: List<MusicSourceDownloadTask>,
): MusicSourceDownloadTask? {
    if (tasks.isEmpty()) return null
    val expectedIdentity = if (song.path.startsWith("online://")) {
        "remote://" + song.path.removePrefix("online://")
    } else {
        ""
    }
    val candidates = tasks.filter { task ->
        (expectedIdentity.isNotBlank() && task.item.stableIdentity == expectedIdentity) ||
            (song.title.isNotBlank() && task.item.title.equals(song.title, ignoreCase = true) &&
                (song.artist.isBlank() ||
                    task.item.artists.any { song.artist.contains(it, ignoreCase = true) }))
    }
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { it.isActive } ?: candidates.first()
}

/**
 * 筛选属于当前歌单的全部下载任务（含已转为本地的原始在线条目）。
 */
internal fun collectPlaylistDownloadTasks(
    playlist: UserPlaylist?,
    allTasks: List<MusicSourceDownloadTask>,
): List<MusicSourceDownloadTask> {
    if (playlist == null || allTasks.isEmpty()) return emptyList()
    val onlineSongs = playlist.songs.filter { it.isOnlineEntry() }
    if (onlineSongs.isEmpty()) return emptyList()

    val remoteIdentities = onlineSongs.mapTo(mutableSetOf()) {
        "remote://" + it.path.removePrefix("online://")
    }
    val titlesLower = onlineSongs.mapNotNull { it.title.trim().takeIf(String::isNotBlank)?.lowercase(Locale.ROOT) }.toSet()

    return allTasks.filter { task ->
        task.item.stableIdentity in remoteIdentities ||
            task.item.title.trim().lowercase(Locale.ROOT) in titlesLower
    }
}

/**
 * 校验已完成的下载任务对应的本地物理文件是否真实存在且文件头合法。
 */
internal fun isValidCompletedDownloadTask(task: MusicSourceDownloadTask?): Boolean {
    if (task == null || task.status != MusicSourceDownloadStatus.Completed) return false
    val file = runCatching { Uri.parse(task.localUri) }.getOrNull()
        ?.path
        ?.let(::File)
        ?: task.fileName.takeIf(String::isNotBlank)?.let {
            File(MusicSourceDownloadController.publicDownloadDirectory(), it)
        }
        ?: return false
    return MusicSourceDownloadController.isValidLocalAudioFile(file)
}

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
private fun DialogCheckSquare(checked: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) activeColor else Color.Transparent)
            .border(
                width = 1.2.dp,
                color = if (checked) activeColor else Color.Gray.copy(alpha = 0.55f),
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
