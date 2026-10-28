package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.prefs.PlayHistoryItem
import com.rawsmusic.core.common.prefs.PlayHistoryManager
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史播放歌曲详情页。
 * 支持浏览全部历史记录、直接点击播放、全部播放、随机播放、单曲移除、清空历史，
 * 以及设置历史记录保留上限（上限最大 500 首）。
 */
@Composable
fun PlayHistoryPage(
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val historyItems by PlayHistoryManager.history.collectAsState()
    val maxCount = PlayHistoryManager.maxHistoryCount

    var showLimitDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val audioFiles = remember(historyItems) {
        historyItems.map { it.toAudioFile() }
    }

    // 页面本身不再铺不透明背景：全局背景由 AppMainLayout 的
    // PersistentSceneBackgroundLayers 统一负责（FLOW / ALBUM_COLOR / STATIC / SIMPLE）。
    // 以前这里铺 MiuixTheme.colorScheme.background，浅色主题下会把全局背景盖成纯白。
    val staticForeground = usesReferenceStaticForeground()
    val foreground = if (staticForeground) Color.White else MiuixTheme.colorScheme.onBackground
    val contentForeground = if (staticForeground) Color.White else MiuixTheme.colorScheme.onSurface
    val secondaryForeground = if (staticForeground) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val accentForeground = if (staticForeground) Color.White else MiuixTheme.colorScheme.primary
    val playAllSurface = if (staticForeground) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    val capsuleSurface = if (staticForeground) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f)
    }
    val coverSurface = if (staticForeground) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .rawStableStatusBarsPadding()
        ) {
            // 顶栏操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.library_action_back),
                        tint = foreground
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "历史播放",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = foreground
                    )
                    Text(
                        text = "已记录 ${historyItems.size} 首 · 上限 $maxCount 首",
                        fontSize = 11.5.sp,
                        color = secondaryForeground.copy(alpha = 0.75f)
                    )
                }

                // 记录上限设置按钮
                IconButton(onClick = { showLimitDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置记录上限",
                        tint = foreground
                    )
                }

                // 清空历史按钮
                if (historyItems.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "清空历史",
                            tint = foreground.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // 快捷播放控制条
            if (historyItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放全部胶囊按钮
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(playAllSurface)
                            .clickable {
                                if (audioFiles.isNotEmpty()) {
                                    onPlayQueue(audioFiles, 0)
                                }
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = accentForeground,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "播放全部",
                            color = accentForeground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 随机播放胶囊按钮
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(capsuleSurface)
                            .clickable {
                                if (audioFiles.isNotEmpty()) {
                                    val shuffled = audioFiles.shuffled()
                                    onPlayQueue(shuffled, 0)
                                }
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shuffle_custom),
                            contentDescription = null,
                            tint = contentForeground,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "随机播放",
                            color = contentForeground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 列表或空状态
            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(coverSurface.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_custom_recent),
                                contentDescription = null,
                                tint = secondaryForeground.copy(alpha = 0.4f),
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "暂无历史播放记录",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = contentForeground
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "播放任何单曲后将自动记录到此处",
                            fontSize = 12.5.sp,
                            color = secondaryForeground.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = historyItems,
                        key = { _, item -> "${item.playedAt}_${item.id}_${item.path}" }
                    ) { index, item ->
                        HistorySongRow(
                            index = index + 1,
                            item = item,
                            onClick = {
                                val target = item.toAudioFile()
                                onPlayQueue(audioFiles, index)
                            },
                            onRemove = {
                                PlayHistoryManager.removeSong(item)
                            }
                        )
                    }
                }
            }
        }
    }

    // 记录上限设置对话框
    if (showLimitDialog) {
        HistoryLimitSettingDialog(
            currentLimit = maxCount,
            onDismiss = { showLimitDialog = false },
            onConfirm = { newLimit ->
                PlayHistoryManager.maxHistoryCount = newLimit
                showLimitDialog = false
                AppNoticeBus.post("记录上限已保存为 $newLimit 首")
            }
        )
    }

    // 清空历史确认对话框
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = "清空播放历史",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "确定要清空全部 ${historyItems.size} 首历史播放记录吗？此操作无法撤销。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            },
            confirmButton = {
                TextButton(
                    text = "确认清空",
                    onClick = {
                        PlayHistoryManager.clearHistory()
                        showClearDialog = false
                        AppNoticeBus.post("已清空播放历史")
                    }
                )
            },
            dismissButton = {
                TextButton(
                    text = "取消",
                    onClick = { showClearDialog = false }
                )
            }
        )
    }
}

@Composable
private fun HistorySongRow(
    index: Int,
    item: PlayHistoryItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val cover = item.coverKey.ifBlank { item.albumArtPath }
    val staticForeground = usesReferenceStaticForeground()
    val contentForeground = if (staticForeground) Color.White else MiuixTheme.colorScheme.onSurface
    val secondaryForeground = if (staticForeground) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val coverSurface = if (staticForeground) {
        Color.Black.copy(alpha = 0.28f)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面或序号
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(coverSurface),
            contentAlignment = Alignment.Center
        ) {
            if (cover.isNotBlank()) {
                BitmapImage(
                    key = cover,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 128,
                    targetHeight = 128,
                    showDefaultArtwork = false
                )
            } else {
                Text(
                    text = index.toString(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = secondaryForeground
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 歌名与艺术家信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { "未知歌曲" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = contentForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.artist.isNotBlank()) "${item.artist} · ${item.album.ifBlank { "单曲" }}" else item.album.ifBlank { "未知艺术家" },
                    fontSize = 11.5.sp,
                    color = secondaryForeground.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.playedAt > 0L) {
                    Text(
                        text = " · " + formatPlayedRelativeTime(item.playedAt),
                        fontSize = 11.sp,
                        color = secondaryForeground.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                }
            }
        }

        // 单曲移除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除此条记录",
                tint = secondaryForeground.copy(alpha = 0.45f),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * 历史记录上限设置弹窗（上限 500 首）
 */
@Composable
private fun HistoryLimitSettingDialog(
    currentLimit: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var limitInput by remember { mutableStateOf(currentLimit.toString()) }
    var selectedValue by remember { mutableIntStateOf(currentLimit) }

    val presets = listOf(50, 100, 200, 300, 500)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "历史记录上限设置",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "设置历史播放歌曲保留的最大数量（范围 10 ~ 500 首），超出部分将自动移除最旧记录。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(16.dp))

                // 快捷预设档位
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        val isSelected = selectedValue == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MiuixTheme.colorScheme.surfaceContainer
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedValue = preset
                                    limitInput = preset.toString()
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.toString(),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 手动输入微调
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义数量：",
                        fontSize = 13.5.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = limitInput,
                        onValueChange = { input ->
                            val digitsOnly = input.filter { it.isDigit() }.take(3)
                            limitInput = digitsOnly
                            digitsOnly.toIntOrNull()?.let {
                                selectedValue = it.coerceIn(PlayHistoryManager.MIN_LIMIT_FLOOR, PlayHistoryManager.MAX_LIMIT_CEILING)
                            }
                        },
                        textStyle = TextStyle(
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "首 (上限 500)",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = "保存",
                onClick = {
                    val finalVal = (limitInput.toIntOrNull() ?: selectedValue)
                        .coerceIn(PlayHistoryManager.MIN_LIMIT_FLOOR, PlayHistoryManager.MAX_LIMIT_CEILING)
                    onConfirm(finalVal)
                }
            )
        },
        dismissButton = {
            TextButton(
                text = "取消",
                onClick = onDismiss
            )
        }
    )
}

/** 友好时间格式化 */
private fun formatPlayedRelativeTime(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timeMs
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3600_000L -> "${(diff / 60_000L).coerceAtLeast(1)}分钟前"
        diff < 86400_000L -> "${diff / 3600_000L}小时前"
        diff < 172800_000L -> "昨天"
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timeMs))
    }
}
