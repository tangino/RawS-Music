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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.referenceStaticForeground
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
import com.rawsmusic.module.online.auth.PlatformAccountManager
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.recommend.GuessLikeOptimizer
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
 * 猜你喜欢 / 私人雷达 页面（支持动态种子、负反馈抑制、以此歌开启电台）
 */
@Composable
internal fun GuessYouLikePage(
    onBack: () -> Unit,
    currentSong: AudioFile? = null,
    currentCoverPath: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MiuixTheme.colorScheme
    val colors = themeColors()

    val accounts by PlatformAccountManager.accounts.collectAsState()
    val qqAccount = accounts[OnlinePlatform.QQ]
    val isLoggedIn = qqAccount != null

    var pageIndex by remember { mutableIntStateOf(1) }
    var songs by remember { mutableStateOf<List<OnlineSongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isStartingRadio by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val playbackSnapshot by MusicSourcePlaybackController.snapshot.collectAsState()

    fun loadRecommendSongs(page: Int) {
        scope.launch {
            isLoading = true
            try {
                if (OnlinePlaybackLauncher.isRadioActive) {
                    val refreshed = OnlinePlaybackLauncher.refreshRadioBatch(OnlinePlatform.QQ)
                    if (refreshed != null && refreshed.isNotEmpty()) {
                        songs = refreshed
                        listState.animateScrollToItem(0)
                        return@launch
                    }
                }
                val currentSeed = playbackSnapshot.currentItem?.remoteId.orEmpty()
                val result = OnlineMusicRepository.getInstance().getRecommendSongs(
                    platform = OnlinePlatform.QQ,
                    page = page,
                    pageSize = 30,
                    seedSongMids = if (currentSeed.isNotBlank()) listOf(currentSeed) else emptyList(),
                )
                val newSongs = result.getOrNull() ?: emptyList()
                if (newSongs.isNotEmpty()) {
                    songs = newSongs
                    if (page > 1) {
                        listState.animateScrollToItem(0)
                    }
                }
            } catch (e: Exception) {
                com.rawsmusic.core.common.utils.AppLogger.e("GuessYouLikePage", "加载推荐失败", e)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(pageIndex) {
        if (pageIndex == 1 && OnlinePlaybackLauncher.isRadioActive && OnlinePlaybackLauncher.currentRadioSongs.value.isNotEmpty()) {
            songs = OnlinePlaybackLauncher.currentRadioSongs.value
            isLoading = false
        } else {
            loadRecommendSongs(pageIndex)
        }
    }

    // 实时监听电台队列的自动更新，新批次替换旧批次并保留当前播放歌曲，UI 平滑滚动与即时刷新
    LaunchedEffect(Unit) {
        OnlinePlaybackLauncher.currentRadioSongs.collect { radioSongs ->
            if (OnlinePlaybackLauncher.isRadioActive && radioSongs.isNotEmpty()) {
                val isDifferentList = songs.isEmpty() ||
                    songs.size != radioSongs.size ||
                    songs.firstOrNull()?.id != radioSongs.firstOrNull()?.id ||
                    (songs.size > 1 && radioSongs.size > 1 && songs[1].id != radioSongs[1].id)
                songs = radioSongs
                if (isDifferentList) {
                    // 当新批次替换发生时，平滑回到列表顶部（当前播放曲目处），确保 UI 直观呈现新批次
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    // 核心规则：只能以猜你喜欢列表中当前播放的歌曲作为 Header 背景！
    // 1. 严格检查当前发声播放的歌曲（从底层 snapshot 或 currentSong）是否真正属于当前猜你喜欢（songs）列表；
    // 2. 属于该列表时，Header 封面使用正在播放的该歌曲封面（随自动切歌或点击切歌动态更新）；
    // 3. 不属于该列表时（或未播放），绝对不展示外部歌曲封面，仅使用猜你喜欢列表自身的首曲封面作为默认展示。
    val snapshotItem = playbackSnapshot.currentItem
    val playingRemoteId = snapshotItem?.remoteId?.takeIf { it.isNotBlank() }
    val playingTitle = snapshotItem?.title?.takeIf { it.isNotBlank() }
        ?: currentSong?.title?.takeIf { it.isNotBlank() }
    val playingArtist = snapshotItem?.artists?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: currentSong?.artist?.takeIf { it.isNotBlank() }

    val matchedPlayingSongInList = if (!playingRemoteId.isNullOrBlank() || !playingTitle.isNullOrBlank()) {
        songs.firstOrNull { song ->
            (playingRemoteId != null && (song.id == playingRemoteId || song.id.equals(playingRemoteId, ignoreCase = true))) ||
            (playingTitle != null && song.title.equals(playingTitle, ignoreCase = true) &&
                (playingArtist.isNullOrBlank() || song.artist.isBlank() || song.artist.equals(playingArtist, ignoreCase = true)))
        }
    } else null

    val activePlayingTitle = matchedPlayingSongInList?.title

    val currentArtwork = remember(matchedPlayingSongInList, currentCoverPath, currentSong, snapshotItem, songs) {
        if (matchedPlayingSongInList != null) {
            // 当前确实在播放猜你喜欢中的这首歌：优先高清 coverUrl，后备底栏或快照封面
            matchedPlayingSongInList.coverUrl?.takeIf { it.isNotBlank() }
                ?: currentCoverPath?.takeIf { it.isNotBlank() }
                ?: currentSong.resolvePlaybackArtworkKey(null)?.takeIf { it.isNotBlank() }
                ?: snapshotItem?.artworkUrl?.takeIf { it.isNotBlank() }
        } else {
            // 当前未播放猜你喜欢歌曲：严格禁止使用外部播放歌曲，只取猜你喜欢自身的首曲封面作为默认背景
            songs.firstOrNull()?.coverUrl?.takeIf { it.isNotBlank() }
        }
    }
    val hasArtwork = currentArtwork != null
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val isScrolledPastHero by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    if (isWideScreen) {
        // ========== 大屏设备双栏响应式布局 ==========
        Row(
            modifier = modifier
                .fillMaxSize()
                .rawStableStatusBarsPadding(),
        ) {
            // 1. 左栏：封面栏、雷达信息以及操作按钮
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
                        text = stringResource(R.string.online_plaza_guess_like_title),
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

                // 雷达用户信息
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
                        val avatarUrl = qqAccount?.avatarUrl.orEmpty()
                        if (avatarUrl.isNotBlank()) {
                            BitmapImage(
                                key = avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                targetWidth = 128,
                                targetHeight = 128,
                                showDefaultArtwork = false,
                            )
                        } else {
                            Icon(
                                imageVector = MiuixIcons.Regular.Music,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isLoggedIn) "专属私人雷达" else "精选推荐电台",
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
                                    text = if (isLoggedIn) "QQ 音乐" else "推荐",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))

                        Text(
                            text = if (isLoggedIn) {
                                "当前用户: ${qqAccount.nickname.ifBlank { qqAccount.userId }} · 基于听歌偏好"
                            } else {
                                stringResource(R.string.online_plaza_guess_like_subtitle_guest)
                            },
                            fontSize = 11.sp,
                            color = colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 大屏幕：操作按钮置于左侧精选推荐电台控件下方
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (isStartingRadio) return@Button
                            isStartingRadio = true
                            scope.launch {
                                AppNoticeBus.post(
                                    message = context.getString(R.string.online_plaza_guess_like_loading),
                                    icon = AppNoticeIcon.PLAYLIST,
                                )
                                val result = OnlinePlaybackLauncher.launchGuessLikeRadio(
                                    context = context,
                                    platform = OnlinePlatform.QQ,
                                    onSongsLoaded = { newSongs ->
                                        songs = newSongs
                                    },
                                )
                                when (result) {
                                    is OnlinePlayResult.Started -> {
                                        AppNoticeBus.post(
                                            message = context.getString(R.string.online_plaza_guess_like_success),
                                            icon = AppNoticeIcon.PLAYLIST,
                                        )
                                    }
                                    is OnlinePlayResult.Failed -> AppNoticeBus.error(result.reason)
                                    OnlinePlayResult.NoSourceInstalled -> AppNoticeBus.error("未安装或启用可用音源")
                                    OnlinePlayResult.NoMatchFound -> AppNoticeBus.error("未匹配到可播放曲目")
                                }
                                isStartingRadio = false
                            }
                        },
                        enabled = !isStartingRadio,
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
                            if (isStartingRadio) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = scheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                androidx.compose.foundation.Image(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(scheme.onPrimary),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.online_plaza_guess_like_btn),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onPrimary,
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (!isLoading) {
                                if (songs.isNotEmpty()) {
                                    com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordSongsExposed(songs)
                                }
                                pageIndex++
                            }
                        },
                        enabled = !isLoading,
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

            // 2. 右栏：纯净展示歌曲列表
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 8.dp, end = 16.dp, bottom = 16.dp),
            ) {
                // 歌曲列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                    contentPadding = PaddingValues(bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    renderSongList(
                        songs = songs,
                        isLoading = isLoading,
                        playbackSnapshot = playbackSnapshot,
                        activePlayingTitle = activePlayingTitle,
                        onSongPlay = { song, index ->
                            scope.launch {
                                OnlinePlaybackLauncher.playRadioTrack(
                                    context = context,
                                    item = song,
                                    queue = songs,
                                    index = index,
                                    currentPage = pageIndex,
                                )
                            }
                        },
                        onSeedRadio = { song ->
                            scope.launch {
                                AppNoticeBus.post(
                                    message = "正在以此歌为种子开启雷达电台...",
                                    icon = AppNoticeIcon.PLAYLIST,
                                )
                                OnlinePlaybackLauncher.launchGuessLikeRadio(
                                    context = context,
                                    platform = OnlinePlatform.QQ,
                                    seedSong = song,
                                    onSongsLoaded = { newSongs ->
                                        songs = newSongs
                                    },
                                )
                            }
                        },
                        onNegativeFeedback = { song ->
                            GuessLikeOptimizer.recordNegativeFeedback(song.id, song.artist)
                            songs = songs.filterNot { it.id == song.id }
                            AppNoticeBus.post(
                                message = "已为您减少此类推荐",
                                icon = AppNoticeIcon.INFO,
                            )
                        },
                    )
                }
            }
        }
    } else {
        // ========== 手机竖屏沉浸式单栏布局 ==========
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
                item(key = "guess_like_header_hero") {
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
                                        // 核心：使用 BlendMode.DstIn 透明度蒙版将封面底部平滑渐隐至完全透明，
                                        // 不添加任何硬编码色块，自然透出系统底层动态流光背景，杜绝割裂与生硬横条。
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

                        // 头部内容：将信息整体下移至封面底部，上半部完全留白展示人物画面，去掉半透明边框，缩小按钮
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

                            // 头像与信息行（去掉半透明边框与沉重底色，轻盈悬浮）
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
                                    val avatarUrl = qqAccount?.avatarUrl.orEmpty()
                                    if (avatarUrl.isNotBlank()) {
                                        BitmapImage(
                                            key = avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            targetWidth = 128,
                                            targetHeight = 128,
                                            showDefaultArtwork = false,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MiuixIcons.Regular.Music,
                                            contentDescription = null,
                                            tint = if (hasArtwork) Color.White else scheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isLoggedIn) "专属私人雷达" else "精选推荐电台",
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
                                            text = if (isLoggedIn) "QQ 音乐" else "推荐",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (hasArtwork) Color.White else scheme.primary,
                                        )
                                    }
                                }

                                Spacer(Modifier.height(2.dp))

                                Text(
                                    text = if (isLoggedIn) {
                                        "当前用户: ${qqAccount.nickname.ifBlank { qqAccount.userId }} · 基于听歌偏好"
                                    } else {
                                        stringResource(R.string.online_plaza_guess_like_subtitle_guest)
                                    },
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

                        // 操作按钮栏：自适应高度与充裕内边距，确保文字与图标完整舒适展示
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    if (isStartingRadio) return@Button
                                    isStartingRadio = true
                                    scope.launch {
                                        AppNoticeBus.post(
                                            message = context.getString(R.string.online_plaza_guess_like_loading),
                                            icon = AppNoticeIcon.PLAYLIST,
                                        )
                                        val result = OnlinePlaybackLauncher.launchGuessLikeRadio(
                                            context = context,
                                            platform = OnlinePlatform.QQ,
                                            onSongsLoaded = { newSongs ->
                                                songs = newSongs
                                            },
                                        )
                                        when (result) {
                                            is OnlinePlayResult.Started -> {
                                                AppNoticeBus.post(
                                                    message = context.getString(R.string.online_plaza_guess_like_success),
                                                    icon = AppNoticeIcon.PLAYLIST,
                                                )
                                            }
                                            is OnlinePlayResult.Failed -> AppNoticeBus.error(result.reason)
                                            OnlinePlayResult.NoSourceInstalled -> AppNoticeBus.error("未安装或启用可用音源")
                                            OnlinePlayResult.NoMatchFound -> AppNoticeBus.error("未匹配到可播放曲目")
                                        }
                                        isStartingRadio = false
                                    }
                                },
                                enabled = !isStartingRadio,
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
                                    if (isStartingRadio) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = scheme.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        androidx.compose.foundation.Image(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(scheme.onPrimary),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.online_plaza_guess_like_btn),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = scheme.onPrimary,
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    if (!isLoading) {
                                        if (songs.isNotEmpty()) {
                                            com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordSongsExposed(songs)
                                        }
                                        pageIndex++
                                    }
                                },
                                enabled = !isLoading,
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
                                    androidx.compose.foundation.Image(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(if (hasArtwork) Color.White else colors.onSurface),
                                        modifier = Modifier.size(15.dp),
                                    )
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
            renderSongList(
                songs = songs,
                isLoading = isLoading,
                playbackSnapshot = playbackSnapshot,
                activePlayingTitle = activePlayingTitle,
                onSongPlay = { song, index ->
                    scope.launch {
                        OnlinePlaybackLauncher.playRadioTrack(
                            context = context,
                            item = song,
                            queue = songs,
                            index = index,
                            currentPage = pageIndex,
                        )
                    }
                },
                onSeedRadio = { song ->
                    scope.launch {
                        AppNoticeBus.post(
                            message = "正在以此歌为种子开启雷达电台...",
                            icon = AppNoticeIcon.PLAYLIST,
                        )
                        OnlinePlaybackLauncher.launchGuessLikeRadio(
                            context = context,
                            platform = OnlinePlatform.QQ,
                            seedSong = song,
                            onSongsLoaded = { newSongs ->
                                songs = newSongs
                            },
                        )
                    }
                },
                onNegativeFeedback = { song ->
                    GuessLikeOptimizer.recordNegativeFeedback(song.id, song.artist)
                    songs = songs.filterNot { it.id == song.id }
                    AppNoticeBus.post(
                        message = "已为您减少此类推荐",
                        icon = AppNoticeIcon.INFO,
                    )
                },
            )
        }

        // 2. 悬浮吸顶标题栏（完全透明无遮罩层，保留通透沉浸感与返回按键）
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
                    text = stringResource(R.string.online_plaza_guess_like_title),
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

/** 辅助扩展函数：供单栏与双栏复用的歌曲列表渲染扩展 */
private fun androidx.compose.foundation.lazy.LazyListScope.renderSongList(
    songs: List<OnlineSongItem>,
    isLoading: Boolean,
    playbackSnapshot: com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot,
    activePlayingTitle: String? = null,
    onSongPlay: (OnlineSongItem, Int) -> Unit,
    onSeedRadio: (OnlineSongItem) -> Unit,
    onNegativeFeedback: (OnlineSongItem) -> Unit,
) {
    if (isLoading && songs.isEmpty()) {
        item(key = "loading_indicator") {
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
        item(key = "empty_indicator") {
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
                                .clip(CircleShape)
                                .background(scheme.primary),
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 操作：以此歌为种子开启雷达电台
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onSeedRadio(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_playlist_play),
                        contentDescription = "以此歌开启电台",
                        colorFilter = ColorFilter.tint(colors.secondaryText),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // 操作：负反馈（不感兴趣/减少此类推荐）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onNegativeFeedback(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = Icons.Default.Close,
                        contentDescription = "减少推荐",
                        colorFilter = ColorFilter.tint(colors.secondaryText.copy(alpha = 0.6f)),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    item(key = "bottom_spacer") {
        Spacer(Modifier.height(180.dp))
    }
}
