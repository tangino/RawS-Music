package com.rawsmusic.core.ui.scene.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.rawsmusic.core.common.ui.AppNoticeAnimation
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.module.online.store.PodcastStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import com.rawsmusic.module.online.repository.PodcastRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 容错解析多种标准和非标准的播客发布时间字符串为毫秒时间戳。
 */
private fun parsePubDateToMillis(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val clean = dateStr.trim()
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm:ss",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )
    for (fmt in formats) {
        try {
            val sdf = SimpleDateFormat(fmt, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            val parsed = sdf.parse(clean)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {}
    }
    return 0L
}

/**
 * 播客节目详情页面。
 */
@Composable
fun PodcastDetailPage(
    show: PodcastShow?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val scope = rememberCoroutineScope()

    val currentShow = show ?: PodcastNavHolder.currentShow
    if (currentShow == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("未选择播客节目", color = colors.secondaryText, fontFamily = appFontFamily())
        }
        return
    }

    var episodes by remember { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedDesc by remember { mutableStateOf(false) }
    // 新旧排序切换状态：true 为最新优先（从新到旧，降序），false 为最早优先（从旧到新，升序）
    var isNewestFirst by remember { mutableStateOf(true) }

    val favorites by PodcastStore.favorites.collectAsState()
    val isFavorite = favorites.any { it.id == currentShow.id }

    LaunchedEffect(currentShow.id) {
        isLoading = true
        episodes = PodcastRepository.getEpisodes(currentShow)
        isLoading = false
    }

    // 根据发布时间动态排序（若时间戳无法解析则按原始顺序/倒序兜底）
    val sortedEpisodes = remember(episodes, isNewestFirst) {
        if (episodes.isEmpty()) {
            emptyList()
        } else {
            val allZero = episodes.all { parsePubDateToMillis(it.pubDate) == 0L }
            if (allZero) {
                if (isNewestFirst) episodes else episodes.reversed()
            } else {
                episodes.sortedWith { a, b ->
                    val tA = parsePubDateToMillis(a.pubDate)
                    val tB = parsePubDateToMillis(b.pubDate)
                    if (tA > 0 && tB > 0 && tA != tB) {
                        if (isNewestFirst) tB.compareTo(tA) else tA.compareTo(tB)
                    } else if (tA > 0) {
                        if (isNewestFirst) -1 else 1
                    } else if (tB > 0) {
                        if (isNewestFirst) 1 else -1
                    } else {
                        0
                    }
                }
            }
        }
    }

    val podcastGreen = if (isDark) Color(0xFF1DB954) else Color(0xFF109D43)

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.side_rail_podcast),
                    tint = colors.onSurface,
                )
            }
            Text(
                text = currentShow.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                fontFamily = appFontFamily(),
            )
            IconButton(
                onClick = {
                    val newStatus = PodcastStore.toggleFavorite(currentShow)
                    AppNoticeBus.post(
                        if (newStatus) "已添加到播客收藏" else "已取消播客收藏",
                        AppNoticeIcon.PLAYLIST,
                        if (newStatus) AppNoticeAnimation.FAVORITE else AppNoticeAnimation.NONE,
                    )
                }
            ) {
                Icon(
                    painter = painterResource(
                        if (isFavorite) R.drawable.ic_playlist_favorite_filled else R.drawable.ic_playlist_favorite_outline
                    ),
                    contentDescription = if (isFavorite) "取消收藏" else "收藏播客",
                    tint = if (isFavorite) Color(0xFFFA5252) else colors.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 节目 Header 头部信息区
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 大正方形封面
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.28f))
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surface),
                    ) {
                        BitmapImage(
                            key = currentShow.coverUrl,
                            contentDescription = currentShow.title,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 256,
                            targetHeight = 256,
                            showDefaultArtwork = false,
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = currentShow.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = appFontFamily(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentShow.publisher,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.secondaryText,
                            fontFamily = appFontFamily(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 来源胶囊
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(podcastGreen.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = currentShow.sourceType.displayName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = podcastGreen,
                                    fontFamily = appFontFamily(),
                                )
                            }
                            // 分类
                            Text(
                                text = currentShow.category,
                                fontSize = 11.sp,
                                color = colors.secondaryText,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                }

                // 节目简介（可点击展开）
                if (currentShow.description.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface.copy(alpha = 0.6f))
                            .clickable { expandedDesc = !expandedDesc }
                            .padding(12.dp)
                    ) {
                        Text(
                            text = currentShow.description,
                            fontSize = 12.sp,
                            color = colors.secondaryText,
                            maxLines = if (expandedDesc) 20 else 2,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = appFontFamily(),
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 一键播放最新/全部单集与收藏专辑按钮条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(4.dp, RoundedCornerShape(22.dp), spotColor = podcastGreen.copy(alpha = 0.3f))
                            .clip(RoundedCornerShape(22.dp))
                            .background(podcastGreen)
                            .clickable {
                                if (sortedEpisodes.isNotEmpty()) {
                                    PodcastRepository.playEpisode(context, sortedEpisodes.first(), sortedEpisodes, 0, show = currentShow)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "播放全部 (${sortedEpisodes.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }

                    // 收藏专辑胶囊按钮
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (isFavorite) Color(0xFFFA5252).copy(alpha = 0.16f)
                                else colors.surface.copy(alpha = if (isDark) 0.5f else 0.7f)
                            )
                            .border(
                                1.dp,
                                if (isFavorite) Color(0xFFFA5252).copy(alpha = 0.5f)
                                else colors.outline.copy(alpha = 0.2f),
                                RoundedCornerShape(22.dp)
                            )
                            .clickable {
                                val newStatus = PodcastStore.toggleFavorite(currentShow)
                                AppNoticeBus.post(
                                    if (newStatus) "已添加到播客收藏" else "已取消播客收藏",
                                    AppNoticeIcon.PLAYLIST,
                                    if (newStatus) AppNoticeAnimation.FAVORITE else AppNoticeAnimation.NONE,
                                )
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(
                                    if (isFavorite) R.drawable.ic_playlist_favorite_filled else R.drawable.ic_playlist_favorite_outline
                                ),
                                contentDescription = null,
                                tint = if (isFavorite) Color(0xFFFA5252) else colors.onSurface,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isFavorite) "已收藏" else "收藏",
                                fontSize = 13.sp,
                                fontWeight = if (isFavorite) FontWeight.Bold else FontWeight.Medium,
                                color = if (isFavorite) Color(0xFFFA5252) else colors.onSurface,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (sortedEpisodes.isNotEmpty()) "往期单集 (${sortedEpisodes.size})" else "往期单集",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                    )

                    // 排序切换胶囊按钮 (最新优先 ↓ / 最早优先 ↑)
                    if (sortedEpisodes.size > 1) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
                                .border(1.dp, colors.outline.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                .clickable { isNewestFirst = !isNewestFirst }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isNewestFirst) "最新优先 ↓" else "最早优先 ↑",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                }
            }

            // 单集列表
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在获取最新单集列表...",
                            fontSize = 13.sp,
                            color = colors.secondaryText,
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            } else if (sortedEpisodes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无单集内容",
                            fontSize = 13.sp,
                            color = colors.secondaryText,
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            } else {
                itemsIndexed(sortedEpisodes, key = { index, ep -> "${ep.id}_${isNewestFirst}_$index" }) { index, episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        onClick = {
                            val vUrl = episode.videoUrl ?: if (episode.isVideo) episode.audioUrl else null
                            if (episode.isVideo && !vUrl.isNullOrBlank()) {
                                // 视频单集：直接调起系统高清视频播放器或外部播放器
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(Uri.parse(vUrl), "video/*")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    // 记录到历史中
                                    PodcastStore.recordPlay(episode, currentShow)
                                }.onFailure {
                                    // 若无外部视频播放器，则回退走内置音频引擎
                                    PodcastRepository.playEpisode(context, episode, sortedEpisodes, index, show = currentShow)
                                }
                            } else {
                                PodcastRepository.playEpisode(context, episode, sortedEpisodes, index, show = currentShow)
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
}

/**
 * 单集行项目组件。
 */
@Composable
private fun PodcastEpisodeRow(
    episode: PodcastEpisode,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.22f else 0.38f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.08f else 0.14f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = episode.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (episode.isVideo) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1DB954).copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "视频",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                            fontFamily = appFontFamily(),
                        )
                    }
                }
                if (episode.pubDate.isNotBlank()) {
                    Text(
                        text = episode.pubDate,
                        fontSize = 11.sp,
                        color = colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
                if (episode.durationMs > 0) {
                    val minutes = episode.durationMs / 60000
                    val seconds = (episode.durationMs % 60000) / 1000
                    Text(
                        text = "%02d:%02d".format(minutes, seconds),
                        fontSize = 11.sp,
                        color = colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // 播放小按钮
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "播放",
                tint = if (colors.background.luminance() < 0.5f) Color(0xFF1DB954) else Color(0xFF109D43),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

