package com.rawsmusic.ui.playlist

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadStatus
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 歌单网络歌曲离线保存（下载）弹窗。
 *
 * 遵循 Miuix 极简设计规范，允许用户自主勾选待保存歌曲与目标音质，并展示当前生效的保存目录。
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

    // 过滤出该歌单中的全部网络歌曲
    val onlineSongs = remember(songs) {
        songs.filter { it.path.startsWith("online://") }
    }

    // 判断歌曲是否已完成离线保存（存在已完成的下载任务或物理文件存在）
    val completedSongTitles = remember {
        MusicSourceDownloadController.tasks.value
            .filter { it.status == MusicSourceDownloadStatus.Completed }
            .map { it.item.title }
            .toSet()
    }

    fun isAlreadyLocal(song: AudioFile): Boolean {
        return song.title in completedSongTitles
    }

    // 勾选状态映射：默认将未保存到本地的网络歌曲置为已勾选
    val selectedKeys = remember(onlineSongs) {
        mutableStateMapOf<String, Boolean>().apply {
            onlineSongs.forEach { song ->
                this[song.path] = !isAlreadyLocal(song)
            }
        }
    }

    val selectedCount = selectedKeys.values.count { it }
    val allSelected = onlineSongs.isNotEmpty() && selectedCount == onlineSongs.size

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
                        onlineSongs.size,
                        onlineSongs.count { isAlreadyLocal(it) },
                        selectedCount
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
                            onlineSongs.forEach { song ->
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

            // 歌曲列表区域
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.05f))
                    .padding(vertical = 4.dp),
            ) {
                items(onlineSongs, key = { it.path }) { song ->
                    val checked = selectedKeys[song.path] ?: false
                    val alreadyLocal = isAlreadyLocal(song)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedKeys[song.path] = !checked
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        if (alreadyLocal) {
                            Spacer(Modifier.width(6.dp))
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
                        val toDownload = onlineSongs.filter { selectedKeys[it.path] == true }
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

