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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.prefs.PlayedPlaylistManager
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.flow.referenceStaticForeground
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
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
 *
 * 布局完全重构对齐“猜你喜欢”页面体系：
 * 1. 大屏设备采用双栏响应式布局：左栏大封面卡片 + 推荐信息 + 双操作按钮；右栏展示歌曲列表；
 * 2. 手机竖屏采用沉浸式单栏布局：顶部半透明渐隐大图 Hero 区域沉底轻量化信息 + 悬浮吸顶标题栏 + 流畅歌曲列表。
 */
@Composable
internal fun DailyRecommendPage(
    onBack: () -> Unit,
    currentSong: AudioFile? = null,
    currentCoverPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = themeColors()
    val scheme = MiuixTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f

    var loading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
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

    val playbackSnapshot by MusicSourcePlaybackController.snapshot.collectAsState()

    suspend fun loadData(force: Boolean = false) {
        if (force) {
            isRefreshing = true
        } else {
            loading = true
        }
        try {
            val result = OnlineMusicRepository.getInstance().getDailyRecommendData(forceRefresh = force)
            dailyData = result
        } catch (e: Exception) {
            AppNoticeBus.post("获取每日推荐失败: ${e.message}", AppNoticeIcon.ERROR)
        } finally {
            loading = false
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadData(force = false)
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600
    val listState = rememberLazyListState()

    // 核心规则：只能以每日推荐列表中当前播放的歌曲作为 Header 背景！
    // 1. 严格检查当前发声播放的歌曲（从底层 snapshot 或 currentSong）是否真正属于当前每日推荐（dailyData.songs）列表；
    // 2. 属于该列表时，Header 封面使用正在播放的该歌曲封面（随自动切歌或点击切歌动态更新）；
    // 3. 不属于该列表时（或未播放），绝对不展示外部歌曲封面，仅使用每日推荐列表自身的首曲封面作为默认展示。
    val snapshotItem = playbackSnapshot.currentItem
    val playingRemoteId = snapshotItem?.remoteId?.takeIf { it.isNotBlank() }
    val playingTitle = snapshotItem?.title?.takeIf { it.isNotBlank() }
        ?: currentSong?.title?.takeIf { it.isNotBlank() }
    val playingArtist = snapshotItem?.artists?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: currentSong?.artist?.takeIf { it.isNotBlank() }

    val matchedPlayingSongInList = if (!playingRemoteId.isNullOrBlank() || !playingTitle.isNullOrBlank()) {
        dailyData.songs.firstOrNull { song ->
            (playingRemoteId != null && (song.id == playingRemoteId || song.id.equals(playingRemoteId, ignoreCase = true))) ||
            (playingTitle != null && song.title.equals(playingTitle, ignoreCase = true) &&
                (playingArtist.isNullOrBlank() || song.artist.isBlank() || song.artist.equals(playingArtist, ignoreCase = true)))
        }
    } else null

    val activePlayingTitle = matchedPlayingSongInList?.title

    val currentArtwork = remember(
        matchedPlayingSongInList,
        currentCoverPath,
        currentSong,
        snapshotItem,
        dailyData.songs,
    ) {
        if (matchedPlayingSongInList != null) {
            // 当前确实在播放每日推荐中的这首歌：优先高清 coverUrl，后备底栏或快照封面
            matchedPlayingSongInList.coverUrl?.takeIf { it.isNotBlank() }
                ?: currentCoverPath?.takeIf { it.isNotBlank() }
                ?: currentSong.resolvePlaybackArtworkKey()?.takeIf { it.isNotBlank() }
                ?: snapshotItem?.artworkUrl?.takeIf { it.isNotBlank() }
        } else {
            // 当前未播放每日推荐歌曲：严格禁止使用外部播放歌曲，只取每日推荐自身的首曲封面作为默认背景
            dailyData.songs.firstOrNull()?.coverUrl?.takeIf { it.isNotBlank() }
        }
    }
    val hasArtwork = currentArtwork != null

    val isScrolledPastHero by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    if (isWideScreen) {
        // ========== 大屏设备双栏响应式布局（完全对齐猜你喜欢大屏结构） ==========
        Row(
            modifier = modifier
                .fillMaxSize()
                .rawStableStatusBarsPadding(),
        ) {
            // 1. 左栏：封面栏、信息条以及操作按钮
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
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = null,
                            tint = colors.onSurface,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "每日推荐",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 左侧大封面卡片（大尺寸方形图，完整高清展示封面，不被遮挡）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!currentArtwork.isNullOrBlank()) {
                        BitmapImage(
                            key = currentArtwork,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            targetWidth = 720,
                            targetHeight = 720,
                            showDefaultArtwork = false,
                        )
                    } else {
                        Icon(
                            imageVector = MiuixIcons.Regular.Music,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 推荐信息条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = dailyData.dateDay,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = scheme.primary,
                            )
                            Text(
                                text = dailyData.dateMonth,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.primary.copy(alpha = 0.85f),
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dailyData.title.ifBlank { "今日私享推荐" },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(scheme.primary.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = if (dailyData.diversityScore > 0) "多样性 ${dailyData.diversityScore}分" else "QQ 音乐",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = dailyData.subTitle,
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 操作按钮栏：自适应高度与充裕内边距，确保文字与图标舒适展示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (dailyData.songs.isNotEmpty()) {
                                // 每日推荐非歌单，彻底清空当前播放歌单卡片信息
                                PlayedPlaylistManager.recordNonPlaylistPlayed()
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
                            .heightIn(min = 40.dp),
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
                                text = "播放全部",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onPrimary,
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (!isRefreshing && !loading) {
                                coroutineScope.launch { loadData(force = true) }
                            }
                        },
                        enabled = !isRefreshing && !loading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.onSurface,
                                )
                            } else {
                                androidx.compose.foundation.Image(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(colors.onSurface),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
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

            // 2. 右栏：纯净展示每日推荐歌曲列表
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
                    renderDailySongList(
                        songs = dailyData.songs,
                        isLoading = loading,
                        playbackSnapshot = playbackSnapshot,
                        activePlayingTitle = activePlayingTitle,
                        onSongPlay = { song, index ->
                            PlayedPlaylistManager.recordNonPlaylistPlayed()
                            coroutineScope.launch {
                                val playRes = OnlinePlaybackLauncher.play(context, song, dailyData.songs, index)
                                if (playRes is OnlinePlayResult.Failed) {
                                    AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                                }
                            }
                        },
                    )
                }
            }
        }
    } else {
        // ========== 手机竖屏沉浸式单栏布局（完全对齐猜你喜欢沉浸式结构） ==========
        Box(
            modifier = modifier.fillMaxSize(),
        ) {
            // 1. 页面内容可滚动列表（封面大图作为首个 Item，跟随手势平滑向上滚动）
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                contentPadding = PaddingValues(bottom = 180.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 沉浸式头部区域：大图封面背景跟随滚动 + 整体信息沉底轻量化
                item(key = "daily_header_hero") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasArtwork) Modifier.height(360.dp) else Modifier.wrapContentHeight()
                            )
                    ) {
                        // 封面大图：在 Header 内部横向占满、竖向贴顶，随列表自然向上滚动
                        if (currentArtwork != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                    .drawWithContent {
                                        drawContent()
                                        // 核心：使用 BlendMode.DstIn 透明度蒙版将封面底部平滑渐隐至完全透明
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                0.0f to Color.Black,
                                                0.40f to Color.Black,
                                                1.0f to Color.Transparent,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                            ) {
                                BitmapImage(
                                    key = currentArtwork,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    targetWidth = 1080,
                                    targetHeight = 1080,
                                    showDefaultArtwork = false,
                                )

                                // 顶部柔和微暗遮罩（渐隐至透明，底端无任何实体色块）
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                0.0f to Color.Black.copy(alpha = 0.35f),
                                                0.40f to Color.Black.copy(alpha = 0.12f),
                                                0.80f to Color.Transparent,
                                            )
                                        )
                                    )
                            }
                        }

                        // 头部内容：将信息整体下移至封面底部，上半部完全留白展示精美封面
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (hasArtwork) Modifier.align(Alignment.BottomCenter)
                                    else Modifier.rawStableStatusBarsPadding()
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (!hasArtwork) {
                                Spacer(Modifier.height(56.dp))
                            }

                            // 日期日历徽标与信息行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (hasArtwork) Color.Black.copy(alpha = 0.35f)
                                            else scheme.primary.copy(alpha = 0.15f)
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = dailyData.dateDay,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (hasArtwork) Color.White else scheme.primary,
                                        )
                                        Text(
                                            text = dailyData.dateMonth,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (hasArtwork) Color.White.copy(alpha = 0.85f) else scheme.primary.copy(alpha = 0.85f),
                                        )
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = dailyData.title.ifBlank { "今日私享推荐" },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (hasArtwork) Color.White else referenceStaticForeground(scheme.onBackground),
                                            style = if (hasArtwork) {
                                                TextStyle(
                                                    shadow = Shadow(
                                                        color = Color.Black.copy(alpha = 0.6f),
                                                        offset = Offset(0f, 2f),
                                                        blurRadius = 4f,
                                                    )
                                                )
                                            } else TextStyle.Default,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(
                                                    if (hasArtwork) Color.Black.copy(alpha = 0.35f)
                                                    else scheme.primary.copy(alpha = 0.12f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = if (dailyData.diversityScore > 0) "多样性 ${dailyData.diversityScore}分" else "QQ 音乐",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (hasArtwork) Color.White else scheme.primary,
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(2.dp))

                                    Text(
                                        text = dailyData.subTitle,
                                        fontSize = 11.sp,
                                        color = if (hasArtwork) Color.White.copy(alpha = 0.85f)
                                        else referenceStaticForeground(scheme.onSurfaceVariantSummary, 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = if (hasArtwork) {
                                            TextStyle(
                                                shadow = Shadow(
                                                    color = Color.Black.copy(alpha = 0.5f),
                                                    offset = Offset(0f, 1f),
                                                    blurRadius = 3f,
                                                )
                                            )
                                        } else TextStyle.Default,
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // 左右并排双操作按钮栏
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        if (dailyData.songs.isNotEmpty()) {
                                            // 每日推荐非歌单，彻底清空当前播放歌单卡片信息
                                            PlayedPlaylistManager.recordNonPlaylistPlayed()
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
                                        .heightIn(min = 40.dp),
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
                                            text = "播放全部",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = scheme.onPrimary,
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (!isRefreshing && !loading) {
                                            coroutineScope.launch { loadData(force = true) }
                                        }
                                    },
                                    enabled = !isRefreshing && !loading,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 40.dp),
                                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    colors = if (hasArtwork) {
                                        ButtonDefaults.buttonColors(
                                            color = Color.Black.copy(alpha = 0.35f),
                                        )
                                    } else ButtonDefaults.buttonColors(),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(15.dp),
                                                strokeWidth = 2.dp,
                                                color = if (hasArtwork) Color.White else colors.onSurface,
                                            )
                                        } else {
                                            androidx.compose.foundation.Image(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                colorFilter = ColorFilter.tint(if (hasArtwork) Color.White else colors.onSurface),
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "换一批",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (hasArtwork) Color.White else colors.onSurface,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                // 歌曲列表区域
                renderDailySongList(
                    songs = dailyData.songs,
                    isLoading = loading,
                    playbackSnapshot = playbackSnapshot,
                    activePlayingTitle = activePlayingTitle,
                    onSongPlay = { song, index ->
                        // 每日推荐非歌单，彻底清空当前播放歌单卡片信息
                        PlayedPlaylistManager.recordNonPlaylistPlayed()
                        coroutineScope.launch {
                            val playRes = OnlinePlaybackLauncher.play(context, song, dailyData.songs, index)
                            if (playRes is OnlinePlayResult.Failed) {
                                AppNoticeBus.post(playRes.reason, AppNoticeIcon.ERROR)
                            }
                        }
                    },
                )
            }

            // 2. 悬浮吸顶标题栏（透明无遮罩层，字体在封面下为白色加投影，滑出后变为系统主题色）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .rawStableStatusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = null,
                            tint = if (isScrolledPastHero || !hasArtwork) colors.onSurface else Color.White,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "每日推荐",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isScrolledPastHero || !hasArtwork) colors.onSurface else Color.White,
                        style = if (!isScrolledPastHero && hasArtwork) {
                            TextStyle(
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    offset = Offset(0f, 2f),
                                    blurRadius = 4f,
                                )
                            )
                        } else TextStyle.Default,
                    )
                }
            }
        }
    }
}

/** 辅助扩展函数：供单栏与双栏复用的每日推荐歌曲列表渲染扩展 */
private fun androidx.compose.foundation.lazy.LazyListScope.renderDailySongList(
    songs: List<OnlineSongItem>,
    isLoading: Boolean,
    playbackSnapshot: com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot,
    activePlayingTitle: String? = null,
    onSongPlay: (OnlineSongItem, Int) -> Unit,
) {
    if (isLoading && songs.isEmpty()) {
        item(key = "daily_loading_indicator") {
            val scheme = MiuixTheme.colorScheme
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = scheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    } else if (!isLoading && songs.isEmpty()) {
        item(key = "daily_empty_indicator") {
            val colors = themeColors()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无推荐曲目，点击换一批重试",
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                )
            }
        }
    } else {
        itemsIndexed(
            items = songs,
            key = { index, song -> "${song.platform.id}_${song.id}_$index" },
        ) { index, song ->
            val scheme = MiuixTheme.colorScheme
            val colors = themeColors()
            val current = playbackSnapshot.currentItem
            val isPlayingThis = (!activePlayingTitle.isNullOrBlank() && song.title.equals(activePlayingTitle, ignoreCase = true)) ||
                ((playbackSnapshot.status == MusicSourcePlaybackStatus.Playing) && (current?.remoteId == song.id || current?.title == song.title))
            val isResolvingThis = (playbackSnapshot.status == MusicSourcePlaybackStatus.Resolving || playbackSnapshot.status == MusicSourcePlaybackStatus.Preparing) && (current?.remoteId == song.id || current?.title == song.title)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSongPlay(song, index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面或序号
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val cover = song.coverUrl
                    if (!cover.isNullOrBlank()) {
                        BitmapImage(
                            key = cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 120,
                            targetHeight = 120,
                            showDefaultArtwork = true,
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.secondaryText,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = if (isPlayingThis || isResolvingThis) scheme.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (song.isVip) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .border(0.5.dp, scheme.primary, RoundedCornerShape(3.dp))
                                    .padding(horizontal = 3.dp, vertical = 0.5.dp),
                            ) {
                                Text(
                                    text = "VIP",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.primary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = "${song.artist} · ${song.album.ifBlank { "单曲" }}",
                        fontSize = 11.sp,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isPlayingThis || isResolvingThis) {
                    Spacer(Modifier.width(8.dp))
                    if (isResolvingThis) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = scheme.primary,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(scheme.primary),
                        )
                    }
                }
            }
        }
    }
}
