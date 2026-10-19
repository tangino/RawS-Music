package com.rawsmusic.core.ui.scene.pages

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.HomeFullCoverSourceAnchor
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.player.LyricDirectRenderClock
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.absoluteValue

/**
 * 经典 Mac OS X (iTunes / Finder) 风格的原生 3D Cover Flow 视图。
 *
 * 移植自 OrBitPlayer 的 CoverFlowLayout 核心：
 * 1. 顶部 3D 封面流：具备 Y 轴立体旋转倾斜（0°~48°）、左右密集交叠梯级景深、真实拟物镜面倒影
 * 2. 动量物理滑行引擎：基于高动量超低摩擦衰减曲线（exponentialDecay 0.32f）与顺滑微弹簧（spring 320f），提供如机械飞轮般的行云流水质感
 * 3. 2800dp 超宽视口扩容（Viewport Expansion）：保证两侧各 8~10 张封面 100% 处于测量与 GPU 渲染活跃范围内，绝无边缘裁断与远端抖动
 * 4. 纯 Compose graphicsLayer 渲染，不改动布局树坐标，状态读取直通 GPU 图层矩阵，消除 Canvas 离屏数学舍入误差与黑边撕裂
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeOrbitCoverFlow(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    isPlaying: Boolean,
    reflectionAlpha: Float = 0.85f,
    onSelectSong: (AudioFile, Int) -> Unit,
    onPlayPause: () -> Unit = {},
    onArtworkDoubleTap: () -> Unit = {},
    onArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    hideCenterForFullscreenTransition: Boolean = false,
    showLyrics: Boolean = false,
    lyricRenderClock: LyricDirectRenderClock? = null,
    lyricSong: Song? = null,
    lyricPositionMs: Long = 0L,
    currentLyric: String = "",
    currentLyricTranslation: String = "",
    lyricAlbumAccent: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    val displaySongs = remember(songs, currentSong) {
        if (songs.isNotEmpty()) {
            songs
        } else if (currentSong != null) {
            listOf(currentSong)
        } else {
            // 曲库与队列均为空时的优雅拟物占位封面（7张对称层叠）
            generatePlaceholderSongs()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp

    val initialIndex = remember(displaySongs, currentSong) {
        val found = if (currentSong != null) {
            displaySongs.indexOfFirst {
                it.path == currentSong.path || (it.id > 0 && it.id == currentSong.id)
            }
        } else -1
        if (found >= 0) found else (displaySongs.size / 2).coerceIn(0, (displaySongs.size - 1).coerceAtLeast(0))
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (displaySongs.size - 1).coerceAtLeast(0)),
        pageCount = { displaySongs.size }
    )

    // 针对 Cover Flow 专门调优的高动量超低摩擦衰减曲线与顺滑微弹簧吸附（行云流水般的机械飞轮质感）
    val smoothDecay = remember {
        exponentialDecay<Float>(
            frictionMultiplier = 0.32f,
            absVelocityThreshold = 0.08f
        )
    }
    val smoothSnap = remember {
        spring<Float>(
            dampingRatio = 0.88f,
            stiffness = 320f
        )
    }
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(40),
        decayAnimationSpec = smoothDecay,
        snapAnimationSpec = smoothSnap
    )

    // 响应外部播放歌曲变更时自动驱动 Cover Flow 居中
    LaunchedEffect(currentSong, displaySongs) {
        if (currentSong != null && displaySongs.isNotEmpty()) {
            val targetIdx = displaySongs.indexOfFirst {
                it.path == currentSong.path || (it.id > 0 && it.id == currentSong.id)
            }
            if (targetIdx in displaySongs.indices && pagerState.currentPage != targetIdx) {
                pagerState.animateScrollToPage(
                    page = targetIdx,
                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    val cardSize: Dp = if (isLandscape) 210.dp else 220.dp
    val reflectionHeight: Dp = cardSize * 0.40f
    val cameraDistancePx = 20f * density.density
    val cardWidthPx = with(density) { cardSize.roundToPx().toFloat() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        contentAlignment = Alignment.TopCenter
    ) {
        val containerWidth = maxWidth
        val containerWidthPx = with(density) { containerWidth.roundToPx().toFloat() }

        // 将视口向两侧大幅扩容各 1400dp，总宽扩充 2800dp
        val extraViewportWidth = 2800.dp
        val extraViewportWidthPx = with(density) { extraViewportWidth.roundToPx().toFloat() }
        val expandedWidthPx = containerWidthPx + extraViewportWidthPx
        val horizontalContentPadding = with(density) {
            (((expandedWidthPx - cardWidthPx) / 2f).toInt()).toDp().coerceAtLeast(0.dp)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // 3D 封面水平轮播舞台（透明无背景，透出全局壁纸）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardSize + reflectionHeight + 6.dp)
                    .clipToBounds(),
                contentAlignment = Alignment.TopCenter
            ) {
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fixed(cardSize),
                    contentPadding = PaddingValues(horizontal = horizontalContentPadding),
                    pageSpacing = 0.dp,
                    beyondViewportPageCount = 9,
                    flingBehavior = flingBehavior,
                    modifier = Modifier
                        .fillMaxHeight()
                        .layout { measurable, constraints ->
                            val looseConstraints = constraints.copy(
                                minWidth = expandedWidthPx.toInt(),
                                maxWidth = expandedWidthPx.toInt()
                            )
                            val placeable = measurable.measure(looseConstraints)
                            layout(constraints.maxWidth, placeable.height) {
                                val offsetX = -((expandedWidthPx - constraints.maxWidth) / 2f).toInt()
                                placeable.place(offsetX, 0)
                            }
                        }
                ) { page ->
                    val song = displaySongs.getOrNull(page) ?: return@HorizontalPager
                    val isCurrentPage = pagerState.currentPage == page
                    val isPlayingThis = isPlaying && currentSong != null &&
                        (currentSong.path == song.path || (currentSong.id > 0 && currentSong.id == song.id))

                    // 基于相对当前页偏移的绝对正数层叠景深：越靠近中心越高，左右对称递减
                    val baseZIndex = when {
                        page < pagerState.currentPage -> 500f + (page - pagerState.currentPage)
                        page > pagerState.currentPage -> 500f - (page - pagerState.currentPage)
                        else -> 1000f
                    }

                    var centerBoundsInRoot by remember { mutableStateOf<Rect?>(null) }

                    Box(
                        modifier = Modifier
                            .size(width = cardSize, height = cardSize + reflectionHeight)
                            .zIndex(baseZIndex)
                            .coverFlowPageTransform(
                                page = page,
                                pagerState = pagerState,
                                cardWidthPx = cardWidthPx,
                                isLandscape = isLandscape,
                                cameraDistancePx = cameraDistancePx
                            )
                            .onGloballyPositioned { coordinates ->
                                if (isCurrentPage) {
                                    val bounds = coordinates.boundsInRoot()
                                    centerBoundsInRoot = bounds
                                    onCurrentArtworkBoundsChanged(song, bounds)
                                }
                            }
                            .pointerInput(page, isCurrentPage, isPlayingThis, currentSong) {
                                detectTapGestures(
                                    onTap = {
                                        if (!isCurrentPage) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    page = page,
                                                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                                )
                                            }
                                        }
                                    },
                                    onDoubleTap = {
                                        if (isCurrentPage) {
                                            if (!song.isPlaceholderCover()) {
                                                val isThisSongCurrent = currentSong != null &&
                                                    (currentSong.path == song.path || (currentSong.id > 0 && currentSong.id == song.id))
                                                if (isThisSongCurrent) {
                                                    onPlayPause()
                                                } else {
                                                    onSelectSong(song, page)
                                                }
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    page = page,
                                                    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                                                )
                                                if (!song.isPlaceholderCover()) {
                                                    onSelectSong(song, page)
                                                }
                                            }
                                        }
                                    },
                                    onLongPress = {
                                        if (isCurrentPage) {
                                            centerBoundsInRoot?.let { bounds ->
                                                onArtworkLongPress(
                                                    HomeFullCoverSourceAnchor(
                                                        boundsInRoot = bounds,
                                                        cornerRadiusDp = 10f
                                                    )
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // 1. 封面主体卡片
                            Box(
                                modifier = Modifier
                                    .size(cardSize)
                                    .graphicsLayer {
                                        alpha = if (isCurrentPage && hideCenterForFullscreenTransition) {
                                            0f
                                        } else {
                                            1f
                                        }
                                    }
                            ) {
                                CoverArtworkCard(
                                    song = song,
                                    isPlayingThis = isPlayingThis,
                                    isPlaying = isPlaying,
                                    isCurrentCenter = isCurrentPage,
                                    showSongInfo = false,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // 2. 真实拟物镜面倒影 (Reflection) - 直接反转封面，原封面底边像素绝对精准对齐倒影顶部接缝
                            if (reflectionAlpha > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .size(width = cardSize, height = reflectionHeight)
                                        .clipToBounds()
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                            alpha = if (isCurrentPage && hideCenterForFullscreenTransition) {
                                                0f
                                            } else {
                                                reflectionAlpha
                                            }
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            // 倒影透明度：从顶部平滑过渡到底部完全透明消隐，随 reflectionAlpha 联动
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = reflectionAlpha),
                                                        Color.White.copy(alpha = reflectionAlpha * 0.40f),
                                                        Color.Transparent
                                                    )
                                                ),
                                                blendMode = BlendMode.DstIn
                                            )
                                        },
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                Box(
                                    modifier = Modifier
                                        .layout { measurable, constraints ->
                                            val sizePx = cardSize.roundToPx()
                                            val placeable = measurable.measure(
                                                Constraints.fixed(sizePx, sizePx)
                                            )
                                            layout(constraints.maxWidth, constraints.maxHeight) {
                                                placeable.place(0, 0)
                                            }
                                        }
                                        .graphicsLayer {
                                            transformOrigin = TransformOrigin(0.5f, 0f)
                                            scaleY = -1f
                                            translationY = size.height
                                        }
                                ) {
                                    CoverArtworkCard(
                                        song = song,
                                        isPlayingThis = false,
                                        isPlaying = false,
                                        isCurrentCenter = false,
                                        showSongInfo = false,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(4.dp))

            // 居中展示当前选中项的标题与副标题信息 (参考 orbitplayer CoverFlowLayout)
            val currentSelectedSong = displaySongs.getOrNull(pagerState.currentPage)
            if (currentSelectedSong != null && (!showLyrics || lyricRenderClock == null)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = currentSelectedSong.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    if (currentSelectedSong.artist.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentSelectedSong.artist,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 当开启歌词时在倒影下方展示歌词
            if (showLyrics && lyricRenderClock != null) {
                HomeHorizontalCarouselLyric(
                    renderClock = lyricRenderClock,
                    song = lyricSong,
                    positionMs = lyricPositionMs,
                    fallbackPrimary = currentLyric,
                    fallbackTranslation = currentLyricTranslation,
                    albumAccent = lyricAlbumAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 经典 Cover Flow 3D 景深与立体倾斜几何变换矩阵。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.coverFlowPageTransform(
    page: Int,
    pagerState: PagerState,
    cardWidthPx: Float,
    isLandscape: Boolean,
    cameraDistancePx: Float
): Modifier = this.graphicsLayer {
    val offset = -(pagerState.currentPage - page + pagerState.currentPageOffsetFraction)
    val absOffset = offset.absoluteValue
    val sign = if (offset >= 0f) 1f else -1f

    // 与 Pager 槽位严格一致的固定像素宽度，杜绝浮点误差累加
    val centerSpread = cardWidthPx * (if (isLandscape) 0.60f else 0.56f)
    val wingOverlapStep = cardWidthPx * (if (isLandscape) 0.24f else 0.20f)

    val targetX = if (absOffset <= 1f) {
        offset * centerSpread
    } else {
        sign * (centerSpread + (absOffset - 1f) * wingOverlapStep)
    }
    val rawX = offset * cardWidthPx
    val translationPx = targetX - rawX

    val maxRotationAngle = 48f
    val rotation = if (absOffset <= 1f) {
        -offset * maxRotationAngle
    } else {
        -sign * maxRotationAngle
    }
    val scale = when {
        absOffset <= 1f -> 1f - absOffset * 0.10f
        else -> (0.90f - (absOffset - 1f) * 0.015f).coerceAtLeast(0.76f)
    }

    // 核心可视区（当前及两侧前 6 张封面）保持 100% 全清通透；
    // 远端边缘（第 6.2 张到第 8.2 张）自然平滑渐隐融入深黑舞台背景；超过 8.2 张隐形
    val edgeAlpha = when {
        absOffset <= 6.2f -> 1f
        absOffset >= 8.2f -> 0f
        else -> (1f - (absOffset - 6.2f) / 2.0f).coerceIn(0f, 1f)
    }

    translationX = translationPx
    rotationY = rotation
    scaleX = scale
    scaleY = scale
    cameraDistance = cameraDistancePx
    alpha = edgeAlpha
}

@Composable
private fun CoverArtworkCard(
    song: AudioFile,
    isPlayingThis: Boolean,
    isPlaying: Boolean,
    isCurrentCenter: Boolean,
    showSongInfo: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 顶部微圆角、底部平直直角，与镜面倒影顶边缘零距离贴合
    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 1.dp, bottomEnd = 1.dp)
    val artworkKey = remember(song) {
        song.resolvePlaybackArtworkKey(null)?.takeIf { it.isNotBlank() } ?: song.coverKey
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!song.isPlaceholderCover() && artworkKey.isNotBlank()) {
            BitmapImage(
                key = artworkKey,
                contentDescription = song.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetWidth = 480,
                targetHeight = 480
            )
        } else {
            // 极简拟物黑胶唱片质感占位图
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF333333),
                                Color(0xFF1A1A1A),
                                Color(0xFF0F0F0F)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 黑胶内圈纹理
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }

        // 封面内部下方展示歌曲名称和歌手（优雅的半透明渐变层）
        if (showSongInfo && !song.isPlaceholderCover()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.52f),
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
                    .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = song.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.artist.ifBlank { stringResource(R.string.common_unknown_artist) },
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (isPlayingThis) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

private const val PLACEHOLDER_COVER_PREFIX = "raws_placeholder_cover_"

private fun AudioFile.isPlaceholderCover(): Boolean = path.startsWith(PLACEHOLDER_COVER_PREFIX)

private fun generatePlaceholderSongs(): List<AudioFile> = (0 until 7).map { index ->
    AudioFile(
        id = -100L - index,
        path = "$PLACEHOLDER_COVER_PREFIX$index",
        title = "RawS Music",
        artist = "OrBit Cover Flow",
        album = "Classic",
        duration = 0L,
        albumArtPath = ""
    )
}
