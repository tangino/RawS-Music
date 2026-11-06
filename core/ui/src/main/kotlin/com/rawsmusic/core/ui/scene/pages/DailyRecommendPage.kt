package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.DailyRecommendData
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 每日推荐专属详情页面。
 * 布局完全对齐“猜你喜欢”：大正方形圆角封面卡片 + 推荐信息条 + 左右并排双操作按钮（播放全部 + 换一批），
 * 左右边距与下方的歌曲列表卡片严格 100% 对齐。
 */
@Composable
internal fun DailyRecommendPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = themeColors()
    val scheme = MiuixTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f

    var loading by remember { mutableStateOf(true) }
    var dailyData by remember {
        val cal = java.util.Calendar.getInstance()
        val dayStr = String.format("%02d", cal.get(java.util.Calendar.DAY_OF_MONTH))
        val monthNames = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        val monthStr = monthNames.getOrElse(cal.get(java.util.Calendar.MONTH)) { "TODAY" }
        mutableStateOf(
            DailyRecommendData(
                dateDay = dayStr,
                dateMonth = monthStr,
                title = "每日推荐",
                subTitle = "精选 30首 · 每天与好音乐相遇",
            )
        )
    }

    suspend fun loadData(force: Boolean = false) {
        loading = true
        try {
            val result = OnlineMusicRepository.getInstance().getDailyRecommendData(forceRefresh = force)
            dailyData = result
        } catch (e: Exception) {
            AppNoticeBus.post("获取每日推荐失败: ${e.message}", AppNoticeIcon.ERROR)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadData(force = false)
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val listState = rememberLazyListState()

    val coverKey = dailyData.songs.firstOrNull()?.coverUrl
    val hasArtwork = !coverKey.isNullOrBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding(),
    ) {
        if (loading && dailyData.songs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        } else if (isWideScreen) {
            // ========== 大屏设备双栏响应式布局 ==========
            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                // 左栏：320dp 封面、信息与操作按钮
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 16.dp),
                ) {
                    // 顶部返回栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colors.onSurface,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "每日推荐",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 大尺寸正方形封面卡片
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(scheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasArtwork) {
                            BitmapImage(
                                key = coverKey,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                targetWidth = 720,
                                targetHeight = 720,
                                showDefaultArtwork = false,
                            )
                        } else {
                            Icon(
                                imageVector = MiuixIcons.Music,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // 推荐信息条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.primary.copy(alpha = if (isDark) 0.25f else 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Music,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "今日私享推荐",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface,
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(colors.primary.copy(alpha = if (isDark) 0.22f else 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (dailyData.diversityScore > 0) "多样性 ${dailyData.diversityScore}分" else "QQ 音乐",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.primary,
                                    )
                                }
                            }

                            Spacer(Modifier.height(3.dp))

                            Text(
                                text = dailyData.subTitle,
                                fontSize = 11.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // 双药丸操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                if (dailyData.songs.isNotEmpty()) {
                                    coroutineScope.launch {
                                        val playRes = OnlinePlaybackLauncher.play(context, dailyData.songs[0], dailyData.songs, 0)
                                        if (playRes is OnlinePlayResult.Failed) {
                                            AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(scheme.onPrimary),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "播放全部 (${dailyData.songs.size})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onPrimary,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    loadData(force = true)
                                    AppNoticeBus.post("已刷新推荐", AppNoticeIcon.INFO)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.onSurface),
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "换一批",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                )
                            }
                        }
                    }
                }

                // 右栏：歌曲列表
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 8.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                        contentPadding = PaddingValues(bottom = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = dailyData.songs,
                            key = { _, song -> song.id },
                        ) { index, song ->
                            DailySongItemRow(
                                index = index + 1,
                                song = song,
                                onClick = {
                                    coroutineScope.launch {
                                        val playRes = OnlinePlaybackLauncher.play(context, song, dailyData.songs, index)
                                        if (playRes is OnlinePlayResult.Failed) {
                                            AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // ========== 手机竖屏布局（左右两边严格与下边卡片对齐） ==========
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // 顶部返回与标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = colors.onSurface,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "每日推荐",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                }

                // 内容列表：所有元素左右边距一致（Padding 16.dp），大封面、信息卡片、按钮与歌曲卡片 100% 对齐
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 1. 正方形大圆角封面卡片（1:1 比例，大圆角 24.dp，左右与下方歌曲列表严格对齐）
                    item(key = "daily_big_cover") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (hasArtwork) {
                                BitmapImage(
                                    key = coverKey,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    targetWidth = 720,
                                    targetHeight = 720,
                                    showDefaultArtwork = false,
                                )
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Music,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                        }
                    }

                    // 2. 推荐信息卡片（左右严格对齐）
                    item(key = "daily_info_card") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.primary.copy(alpha = if (isDark) 0.25f else 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Music,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "今日私享推荐",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(colors.primary.copy(alpha = if (isDark) 0.22f else 0.12f))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (dailyData.diversityScore > 0) "多样性 ${dailyData.diversityScore}分" else "QQ 音乐",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.primary,
                                        )
                                    }
                                }

                                Spacer(Modifier.height(3.dp))

                                Text(
                                    text = dailyData.subTitle,
                                    fontSize = 12.sp,
                                    color = colors.secondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // 3. 左右并排双药丸操作按钮（播放全部 + 换一批，左右严格对齐）
                    item(key = "daily_action_buttons") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    if (dailyData.songs.isNotEmpty()) {
                                        coroutineScope.launch {
                                            val playRes = OnlinePlaybackLauncher.play(context, dailyData.songs[0], dailyData.songs, 0)
                                            if (playRes is OnlinePlayResult.Failed) {
                                                AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    androidx.compose.foundation.Image(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(scheme.onPrimary),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "播放全部 (${dailyData.songs.size})",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = scheme.onPrimary,
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        loadData(force = true)
                                        AppNoticeBus.post("已刷新推荐", AppNoticeIcon.INFO)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    androidx.compose.foundation.Image(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colors.onSurface),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "换一批",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    // 4. 歌曲列表间隔
                    item(key = "daily_song_list_header_gap") {
                        Spacer(Modifier.height(4.dp))
                    }

                    // 5. 30 首歌曲列表卡片项（左右严格与上方各卡片完全对齐）
                    itemsIndexed(
                        items = dailyData.songs,
                        key = { _, song -> song.id },
                    ) { index, song ->
                        DailySongItemRow(
                            index = index + 1,
                            song = song,
                            onClick = {
                                coroutineScope.launch {
                                    val playRes = OnlinePlaybackLauncher.play(context, song, dailyData.songs, index)
                                    if (playRes is OnlinePlayResult.Failed) {
                                        AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                                    }
                                }
                            }
                        )
                    }

                    // 6. 底部留白（避免被底部播放控制器遮挡）
                    item(key = "daily_bottom_spacer") {
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }
        }
    }
}

/**
 * 歌曲行卡片
 */
@Composable
private fun DailySongItemRow(
    index: Int,
    song: OnlineSongItem,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colors.secondaryText,
            modifier = Modifier.width(28.dp),
        )

        // 封面图
        val songCover = song.coverUrl
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (!songCover.isNullOrBlank()) {
                BitmapImage(
                    key = songCover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 120,
                    targetHeight = 120,
                    showDefaultArtwork = false,
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.Music,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isVip) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE6A23C).copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "VIP",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE6A23C),
                        )
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            Text(
                text = "${song.artist} · ${song.album}",
                fontSize = 12.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
