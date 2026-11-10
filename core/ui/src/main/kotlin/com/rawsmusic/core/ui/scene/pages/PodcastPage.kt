package com.rawsmusic.core.ui.scene.pages

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.auth.spotify.SpotifyAccountStore
import com.rawsmusic.module.online.auth.spotify.SpotifyAuthService
import com.rawsmusic.module.online.auth.spotify.SpotifySyncReport
import com.rawsmusic.module.online.auth.spotify.SpotifyUserProfile
import com.rawsmusic.module.online.model.PodcastCategory
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastHistoryItem
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.repository.PodcastRepository
import com.rawsmusic.module.online.store.PodcastStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search

/**
 * 播客详情页面传递共享状态。
 */
object PodcastNavHolder {
    var currentShow: PodcastShow? = null
}

/**
 * 播客页面 Tab 导航枚举。
 */
private sealed class PodcastPageTab {
    object Home : PodcastPageTab()
    object VideoExplore : PodcastPageTab()
    object Favorites : PodcastPageTab()
    object History : PodcastPageTab()
    data class Category(val category: PodcastCategory) : PodcastPageTab()
}

/**
 * 播客探索广场页面。
 *
 * 核心特性：
 * 1. 采用开放播客生态（iTunes / RSS 索引与直链解码播放）；
 * 2. 界面颜色完全继承系统统一主题（`themeColors().background` 等）；
 * 3. 自适应滚动分类导航条：小屏幕下支持自由横向平滑滚动，绝不裁切；
 * 4. 内置【我的收藏】和【播放历史】板块，支持在历史记录中直接播放及一键跳转到具体的播客专辑。
 */
@Composable
fun PodcastPage(
    onBack: () -> Unit,
    onOpenShow: (PodcastShow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val isWideScreen = configuration.screenWidthDp >= 600

    val categories = remember { PodcastRepository.categories }
    var currentTab by remember { mutableStateOf<PodcastPageTab>(PodcastPageTab.Home) }

    var shows by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var featuredShows by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var recommendedVideos by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResultPool by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var displayedSearchShows by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var hasMoreSearch by remember { mutableStateOf(false) }
    var isLoadingMoreSearch by remember { mutableStateOf(false) }
    var searchStage by remember { mutableStateOf(1) }
    var canFetchDeeperNetwork by remember { mutableStateOf(true) }
    val seenSearchIds = remember { mutableSetOf<String>() }

    val favorites by PodcastStore.favorites.collectAsState()
    val history by PodcastStore.history.collectAsState()
    val spotifyProfile by SpotifyAccountStore.userProfile.collectAsState()
    var showSpotifyDialog by remember { mutableStateOf(false) }
    var isSyncingSpotify by remember { mutableStateOf(false) }

    // 加载指定分类下的推荐
    fun loadCategory(category: PodcastCategory) {
        currentTab = PodcastPageTab.Category(category)
        isSearching = false
        isLoading = true
        scope.launch {
            shows = PodcastRepository.getCategoryShows(category)
            isLoading = false
        }
    }

    // 执行全网深度搜索（首屏秒开，支持跨区域多梯级网络无限上滑加载）
    fun doSearch() {
        if (searchQuery.isBlank()) return
        focusManager.clearFocus()
        isSearching = true
        isLoading = true
        hasMoreSearch = false
        displayedSearchShows = emptyList()
        searchResultPool = emptyList()
        seenSearchIds.clear()
        searchStage = 1
        canFetchDeeperNetwork = true

        scope.launch {
            val (stageList, hasNextStage) = PodcastRepository.searchShowsByStage(searchQuery, stage = 1, seenSearchIds)
            searchResultPool = stageList
            displayedSearchShows = stageList.take(20)
            canFetchDeeperNetwork = hasNextStage
            hasMoreSearch = stageList.size > 20 || hasNextStage
            isLoading = false
        }
    }

    // 上滑加载更多搜索结果（池化翻页与多阶网络深潜无缝串联，突破 150 条单次限制）
    fun loadMoreSearch() {
        if (!hasMoreSearch || isLoadingMoreSearch) return
        isLoadingMoreSearch = true
        scope.launch {
            val currentCount = displayedSearchShows.size
            if (currentCount < searchResultPool.size) {
                // 本地池中尚有数据，快速追加渲染
                val nextCount = (currentCount + 20).coerceAtMost(searchResultPool.size)
                displayedSearchShows = searchResultPool.take(nextCount)
                hasMoreSearch = displayedSearchShows.size < searchResultPool.size || canFetchDeeperNetwork
                isLoadingMoreSearch = false
            } else if (canFetchDeeperNetwork) {
                // 当前批次已滚到底部，自动发起更深梯队（英联邦各大区与语义关联词）的网络请求
                val nextStage = searchStage + 1
                searchStage = nextStage
                val (deeperShows, hasNextStage) = PodcastRepository.searchShowsByStage(searchQuery, stage = nextStage, seenSearchIds)
                canFetchDeeperNetwork = hasNextStage
                if (deeperShows.isNotEmpty()) {
                    val updatedPool = searchResultPool + deeperShows
                    searchResultPool = updatedPool
                    val nextCount = (currentCount + 20).coerceAtMost(updatedPool.size)
                    displayedSearchShows = updatedPool.take(nextCount)
                    hasMoreSearch = displayedSearchShows.size < updatedPool.size || canFetchDeeperNetwork
                } else {
                    hasMoreSearch = canFetchDeeperNetwork
                }
                isLoadingMoreSearch = false
            } else {
                hasMoreSearch = false
                isLoadingMoreSearch = false
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        scope.launch {
            featuredShows = PodcastRepository.getCategoryShows(categories.first())
            shows = featuredShows
            isLoading = false
        }
        scope.launch {
            recommendedVideos = PodcastRepository.getRecommendedVideoShows()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        // 顶部操作栏
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
                text = stringResource(R.string.podcast_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 4.dp),
                fontFamily = appFontFamily(),
            )

            // 引擎标签
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1DB954).copy(alpha = 0.16f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Podcast RSS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                    fontFamily = appFontFamily(),
                )
            }

            Spacer(Modifier.weight(1f))

            // 搜索输入框（弹性自适应宽度，避免在小屏下挤占空间）
            Box(
                modifier = Modifier
                    .widthIn(min = 120.dp, max = if (isWideScreen) 260.dp else 170.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.52f))
                    .border(1.dp, colors.outline.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = "搜索",
                        tint = if (searchQuery.isNotEmpty()) {
                            if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43)
                        } else {
                            colors.secondaryText
                        },
                        modifier = Modifier
                            .size(17.dp)
                            .clickable { doSearch() },
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = colors.onSurface,
                            fontFamily = appFontFamily(),
                        ),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索播客...",
                                    fontSize = 12.sp,
                                    color = colors.secondaryText.copy(alpha = 0.6f),
                                    fontFamily = appFontFamily(),
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清空",
                            tint = colors.secondaryText,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    searchQuery = ""
                                    isSearching = false
                                    hasMoreSearch = false
                                    searchResultPool = emptyList()
                                    displayedSearchShows = emptyList()
                                    seenSearchIds.clear()
                                }
                        )
                    }
                }
            }
        }

        // 小胶囊分类导航条（使用 LazyRow，完美避免宽度被挤压/裁切，支持小屏幕顺滑惯性滚动）
        AnimatedVisibility(
            visible = !isSearching,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 0. 【精选首页】胶囊 (Spotify 模式)
                item {
                    val isHomeSelected = currentTab is PodcastPageTab.Home
                    PodcastChip(
                        label = "精选探索",
                        isSelected = isHomeSelected,
                        accentColor = Color(0xFF1DB954),
                        onClick = { currentTab = PodcastPageTab.Home },
                    )
                }

                // 视频专区胶囊
                item {
                    val isVideoSelected = currentTab is PodcastPageTab.VideoExplore
                    PodcastChip(
                        label = "视频专区",
                        isSelected = isVideoSelected,
                        accentColor = Color(0xFF1DB954),
                        onClick = { currentTab = PodcastPageTab.VideoExplore },
                    )
                }

                // 1. 【我的收藏】胶囊
                item {
                    val isFavSelected = currentTab is PodcastPageTab.Favorites
                    PodcastChip(
                        label = if (favorites.isNotEmpty()) "我的收藏 (${favorites.size})" else "我的收藏",
                        isSelected = isFavSelected,
                        iconRes = if (isFavSelected) R.drawable.ic_playlist_favorite_filled else R.drawable.ic_playlist_favorite_outline,
                        accentColor = Color(0xFFFA5252),
                        onClick = { currentTab = PodcastPageTab.Favorites },
                    )
                }

                // 2. 【播放历史】胶囊
                item {
                    val isHistSelected = currentTab is PodcastPageTab.History
                    PodcastChip(
                        label = if (history.isNotEmpty()) "播放历史 (${history.size})" else "播放历史",
                        isSelected = isHistSelected,
                        iconRes = R.drawable.ic_nav_custom_recent,
                        accentColor = Color(0xFF1DB954),
                        onClick = { currentTab = PodcastPageTab.History },
                    )
                }

                // 3. 【Spotify 账号关联/管理】胶囊
                item {
                    if (spotifyProfile == null) {
                        PodcastChip(
                            label = "Spotify 登录",
                            isSelected = false,
                            accentColor = Color(0xFF1DB954),
                            onClick = {
                                val intent = Intent().setClassName(context, "com.rawsmusic.ui.settings.SpotifyLoginActivity")
                                context.startActivity(intent)
                            },
                        )
                    } else {
                        val isPremium = spotifyProfile?.product.equals("premium", ignoreCase = true)
                        val disp = spotifyProfile?.displayName ?: "Spotify"
                        PodcastChip(
                            label = "$disp${if (isPremium) " 👑" else ""}",
                            isSelected = false,
                            accentColor = Color(0xFF1DB954),
                            onClick = { showSpotifyDialog = true },
                        )
                    }
                }

                // 网页直连免白名单同步快捷入口
                item {
                    PodcastChip(
                        label = "网页直连同步",
                        isSelected = false,
                        accentColor = Color(0xFF1DB954),
                        onClick = {
                            val intent = Intent().setClassName(context, "com.rawsmusic.ui.settings.SpotifyWebSyncActivity")
                            context.startActivity(intent)
                        },
                    )
                }

                // 4. 各大内容分类胶囊
                items(categories) { cat ->
                    val isCatSelected = (currentTab as? PodcastPageTab.Category)?.category?.id == cat.id
                    PodcastChip(
                        label = cat.name,
                        isSelected = isCatSelected,
                        accentColor = Color(0xFF1DB954),
                        onClick = { loadCategory(cat) },
                    )
                }
            }
        }

        // 页面主视图内容
        val gridColumns = resolveOnlineGridColumns()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                // 搜索结果展示
                isSearching -> {
                    PodcastGridContent(
                        shows = displayedSearchShows,
                        isLoading = isLoading,
                        columns = gridColumns,
                        emptyText = "未搜到相关播客节目",
                        onOpenShow = onOpenShow,
                        hasMore = hasMoreSearch,
                        isLoadingMore = isLoadingMoreSearch,
                        onLoadMore = { loadMoreSearch() },
                    )
                }

                // 0. 精选探索首页（Spotify Funkis Home 复合流模式：Shortcuts + 继续收听 + 推荐视频播客 + 精选货架）
                currentTab is PodcastPageTab.Home -> {
                    SpotifyPodcastHomeContent(
                        featuredShows = featuredShows,
                        recommendedVideos = recommendedVideos,
                        favorites = favorites,
                        history = history,
                        isLoading = isLoading,
                        onOpenShow = onOpenShow,
                        onPlayEpisode = { item ->
                            PodcastRepository.playEpisode(context, item.episode, show = item.show)
                        },
                        onBrowseMoreVideos = {
                            currentTab = PodcastPageTab.VideoExplore
                        },
                    )
                }

                // 视频专属探索页面
                currentTab is PodcastPageTab.VideoExplore -> {
                    PodcastVideoExploreContent(
                        recommendedVideos = recommendedVideos,
                        onOpenShow = onOpenShow,
                    )
                }

                // 我的收藏展示
                currentTab is PodcastPageTab.Favorites -> {
                    if (favorites.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_playlist_favorite_outline),
                                    contentDescription = null,
                                    tint = colors.secondaryText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "暂无收藏的播客",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.secondaryText,
                                    fontFamily = appFontFamily(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "在播客详情页点击收藏，随时收听喜爱的内容",
                                    fontSize = 12.sp,
                                    color = colors.secondaryText.copy(alpha = 0.7f),
                                    fontFamily = appFontFamily(),
                                )
                            }
                        }
                    } else {
                        PodcastGridContent(
                            shows = favorites,
                            isLoading = false,
                            columns = gridColumns,
                            emptyText = "暂无收藏的播客",
                            onOpenShow = onOpenShow,
                        )
                    }
                }

                // 播放历史展示
                currentTab is PodcastPageTab.History -> {
                    PodcastHistoryContent(
                        history = history,
                        onPlayEpisode = { item ->
                            PodcastRepository.playEpisode(context, item.episode, show = item.show)
                        },
                        onOpenShow = onOpenShow,
                        onClearHistory = {
                            PodcastStore.clearHistory()
                            AppNoticeBus.post("已清空播客播放历史", AppNoticeIcon.INFO)
                        },
                        onRemoveItem = { item ->
                            PodcastStore.removeHistoryItem(item.episode.id)
                        },
                    )
                }

                // 常规分类展示
                else -> {
                    PodcastGridContent(
                        shows = shows,
                        isLoading = isLoading,
                        columns = gridColumns,
                        emptyText = "暂无相关播客节目",
                        onOpenShow = onOpenShow,
                    )
                }
            }
        }

        // Spotify 个人资料与管理弹窗
        if (showSpotifyDialog && spotifyProfile != null) {
            SpotifyProfileDialog(
                profile = spotifyProfile!!,
                isSyncing = isSyncingSpotify,
                onDismiss = { showSpotifyDialog = false },
                onLaunchWebSync = {
                    val intent = Intent().setClassName(context, "com.rawsmusic.ui.settings.SpotifyWebSyncActivity")
                    context.startActivity(intent)
                },
                onSyncFollowedShows = {
                    if (isSyncingSpotify) return@SpotifyProfileDialog
                    isSyncingSpotify = true
                    scope.launch {
                        val report = SpotifyAuthService.syncFollowedShowsToLocal()
                        if (report.success) {
                            if (report.newAddedCount > 0) {
                                AppNoticeBus.post(
                                    "同步成功！已导入 ${report.newAddedCount} 个关注播客（检测到 ${report.totalShowsFound} 个）",
                                    AppNoticeIcon.PLAYLIST
                                )
                            } else if (report.alreadyFavoritedCount > 0) {
                                AppNoticeBus.post(
                                    "已完成同步：发现 ${report.totalShowsFound} 个关注播客，均已在本地收藏中",
                                    AppNoticeIcon.INFO
                                )
                            } else {
                                AppNoticeBus.post(
                                    "Spotify 账号中未检测到关注的播客或单集（请在 Spotify 客户端关注节目后重试）",
                                    AppNoticeIcon.INFO
                                )
                            }
                        } else {
                            AppNoticeBus.error("同步失败: ${report.errorMessage ?: "网络连接异常"}")
                        }
                        isSyncingSpotify = false
                    }
                },
                onLogout = {
                    SpotifyAccountStore.logout()
                    showSpotifyDialog = false
                    AppNoticeBus.post("已退出 Spotify 账号", AppNoticeIcon.INFO)
                },
            )
        }
    }
}

/**
 * Spotify 用户个人资料卡片与操作对话框。
 */
@Composable
private fun SpotifyProfileDialog(
    profile: SpotifyUserProfile,
    isSyncing: Boolean,
    onDismiss: () -> Unit,
    onLaunchWebSync: () -> Unit,
    onSyncFollowedShows: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val isPremium = profile.product.equals("premium", ignoreCase = true)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.outline.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 用户头像或绿色微标
                val avatar = profile.avatarUrl
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1DB954).copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!avatar.isNullOrBlank()) {
                        BitmapImage(
                            key = avatar,
                            contentDescription = profile.displayName,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 140,
                            targetHeight = 140,
                            showDefaultArtwork = false,
                        )
                    } else {
                        Text(
                            text = profile.displayName.take(1).uppercase(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DB954),
                            fontFamily = appFontFamily(),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 昵称与会员标识
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.displayName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = appFontFamily(),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isPremium) Color(0xFFFFD700).copy(alpha = 0.2f)
                                else Color(0xFF1DB954).copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isPremium) "Premium 👑" else "Free",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPremium) Color(0xFFD48806) else Color(0xFF1DB954),
                            fontFamily = appFontFamily(),
                        )
                    }
                }

                val email = profile.email
                if (!email.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = email,
                        fontSize = 12.sp,
                        color = colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }

                Spacer(Modifier.height(18.dp))

                // 1. 【推荐】网页端免白名单直连同步
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(Color(0xFF1DB954))
                        .clickable(onClick = {
                            onDismiss()
                            onLaunchWebSync()
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "网页端直连无限制同步",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = appFontFamily(),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "(免白名单限制)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.9f),
                            fontFamily = appFontFamily(),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 2. 官方 API 同步（需在开发者后台加白名单）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(colors.primaryContainer.copy(alpha = 0.6f))
                        .clickable(enabled = !isSyncing, onClick = onSyncFollowedShows),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isSyncing) "正在同步 Spotify 播客..." else "官方 API 同步 (需开发者白名单)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onPrimaryContainer,
                        fontFamily = appFontFamily(),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 3. 退出登录按钮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Color(0xFFFA5252).copy(alpha = 0.12f))
                        .clickable(onClick = onLogout),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "退出 Spotify 登录",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFA5252),
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }
    }
}

/**
 * 统一胶囊 Chip 组件。
 */
@Composable
private fun PodcastChip(
    label: String,
    isSelected: Boolean,
    iconRes: Int? = null,
    accentColor: Color = Color(0xFF1DB954),
    onClick: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    val bg = if (isSelected) {
        accentColor.copy(alpha = if (isDark) 0.25f else 0.16f)
    } else {
        colors.surface.copy(alpha = if (isDark) 0.35f else 0.52f)
    }
    val borderColor = if (isSelected) {
        accentColor
    } else {
        colors.outline.copy(alpha = if (isDark) 0.15f else 0.22f)
    }
    val textColor = if (isSelected) {
        if (isDark) accentColor else if (accentColor == Color(0xFF1DB954)) Color(0xFF0F9D43) else accentColor
    } else {
        colors.onSurface
    }

    Box(
        modifier = Modifier
            .shadow(2.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                fontFamily = appFontFamily(),
            )
        }
    }
}

/**
 * 播客网格列表展示组件。
 */
@Composable
private fun PodcastGridContent(
    shows: List<PodcastShow>,
    isLoading: Boolean,
    columns: Int,
    emptyText: String = "暂无相关播客节目",
    onOpenShow: (PodcastShow) -> Unit,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
) {
    val colors = themeColors()

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "正在探索播客世界...",
                fontSize = 14.sp,
                color = colors.secondaryText,
                fontFamily = appFontFamily(),
            )
        }
    } else if (shows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyText,
                fontSize = 14.sp,
                color = colors.secondaryText,
                fontFamily = appFontFamily(),
            )
        }
    } else {
        val gridState = rememberLazyGridState()

        // 触底自动加载更多逻辑（距离底部还有 4 个 item 时触发）
        if (onLoadMore != null && hasMore && !isLoading && !isLoadingMore) {
            val shouldLoadMore = remember {
                derivedStateOf {
                    val totalItems = gridState.layoutInfo.totalItemsCount
                    val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    totalItems > 0 && lastVisibleItem >= totalItems - 4
                }
            }
            LaunchedEffect(shouldLoadMore.value) {
                if (shouldLoadMore.value) {
                    onLoadMore()
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shows, key = { it.id }) { show ->
                PodcastShowCard(show = show, onClick = { onOpenShow(show) })
            }

            // 加载更多状态指示或已加载全部提示
            if (isLoadingMore) {
                item(span = { GridItemSpan(columns) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在加载更多播客...",
                            fontSize = 12.sp,
                            color = colors.secondaryText,
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            } else if (!hasMore && shows.size >= 20 && onLoadMore != null) {
                item(span = { GridItemSpan(columns) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "已加载全部播客 (共 ${shows.size} 个)",
                            fontSize = 12.sp,
                            color = colors.secondaryText.copy(alpha = 0.6f),
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            }

            item(span = { GridItemSpan(columns) }) {
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
}

/**
 * 播客播放历史列表展示组件。
 *
 * 满足需求 4：支持点击播放历史单集，并允许用户从历史记录中跳转到具体的播客专辑。
 */
@Composable
private fun PodcastHistoryContent(
    history: List<PodcastHistoryItem>,
    onPlayEpisode: (PodcastHistoryItem) -> Unit,
    onOpenShow: (PodcastShow) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveItem: (PodcastHistoryItem) -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_nav_custom_recent),
                    contentDescription = null,
                    tint = colors.secondaryText.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "暂无播客播放历史",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "收听过的播客单集会记录在这里，方便继续收听或访问专辑",
                    fontSize = 12.sp,
                    color = colors.secondaryText.copy(alpha = 0.7f),
                    fontFamily = appFontFamily(),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 顶栏提示与清空按钮
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "共收听 ${history.size} 期单集",
                        fontSize = 12.sp,
                        color = colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                    Text(
                        text = "清空历史",
                        fontSize = 12.sp,
                        color = Color(0xFFFA5252),
                        fontWeight = FontWeight.Medium,
                        fontFamily = appFontFamily(),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            items(history, key = { "${it.episode.id}_${it.playedAt}" }) { item ->
                PodcastHistoryRow(
                    item = item,
                    onPlay = { onPlayEpisode(item) },
                    onOpenShow = { onOpenShow(item.show) },
                )
            }

            item {
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
}

/**
 * 单条播客历史记录项组件。
 */
@Composable
private fun PodcastHistoryRow(
    item: PodcastHistoryItem,
    onPlay: () -> Unit,
    onOpenShow: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val podcastGreen = if (isDark) Color(0xFF1DB954) else Color(0xFF109D43)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.52f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.12f else 0.18f), RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面（点击可直接起播）
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
        ) {
            BitmapImage(
                key = item.episode.coverUrl.ifBlank { item.show.coverUrl },
                contentDescription = item.episode.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 128,
                targetHeight = 128,
                showDefaultArtwork = false,
            )
            // 播放浮层小图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 中间文本区：单集标题 + 可跳转播客专辑
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.episode.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )

            Spacer(Modifier.height(3.dp))

            // 所属专辑胶囊/链接（点击跳转到具体的播客专辑，关键需求 4）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenShow)
                    .padding(vertical = 1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(podcastGreen.copy(alpha = 0.16f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "专辑",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = podcastGreen,
                        fontFamily = appFontFamily(),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = item.show.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = podcastGreen,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
            }

            Spacer(Modifier.height(3.dp))

            // 播放时间与时长
            val relativeTime = remember(item.playedAt) { formatRelativeTime(item.playedAt) }
            val durationStr = remember(item.episode.durationMs) { formatDuration(item.episode.durationMs) }
            Text(
                text = if (durationStr.isNotBlank()) "$relativeTime · 时长 $durationStr" else relativeTime,
                fontSize = 11.sp,
                color = colors.secondaryText,
                fontFamily = appFontFamily(),
            )
        }

        Spacer(Modifier.width(8.dp))

        // 右侧操作：跳转具体播客专辑按钮
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface.copy(alpha = if (isDark) 0.6f else 0.8f))
                .border(1.dp, colors.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                .clickable(onClick = onOpenShow)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "查看专辑",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                fontFamily = appFontFamily(),
            )
        }
    }
}

/**
 * 单个播客节目大卡片。
 */
@Composable
private fun PodcastShowCard(
    show: PodcastShow,
    onClick: () -> Unit,
) {
    val colors = themeColors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        // 正方形节目封面
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Color.Black.copy(alpha = 0.25f),
                )
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface),
        ) {
            BitmapImage(
                key = show.coverUrl,
                contentDescription = show.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 320,
                targetHeight = 320,
                showDefaultArtwork = false,
            )

            // 来源角标
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1DB954).copy(alpha = 0.90f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "RSS / iTunes",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = appFontFamily(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 节目标题
        Text(
            text = show.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
        )

        // 主播名称
        Text(
            text = show.publisher,
            fontSize = 12.sp,
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Spotify Funkis Home 风格的播客聚合流首页视图。
 */
@Composable
private fun SpotifyPodcastHomeContent(
    featuredShows: List<PodcastShow>,
    recommendedVideos: List<PodcastShow>,
    favorites: List<PodcastShow>,
    history: List<PodcastHistoryItem>,
    isLoading: Boolean,
    onOpenShow: (PodcastShow) -> Unit,
    onPlayEpisode: (PodcastHistoryItem) -> Unit,
    onBrowseMoreVideos: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (isLoading && featuredShows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "正在探索播客世界...",
                fontSize = 14.sp,
                color = colors.secondaryText,
                fontFamily = appFontFamily(),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // 1. 【Shortcuts 快速访问网格】（展示常听与收藏的 4~6 个播客）
        val shortcutShows = (favorites + history.map { it.show }).distinctBy { it.id }.take(6)
        if (shortcutShows.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.podcast_section_shortcuts),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // 2 列网格呈现
                    val chunked = shortcutShows.chunked(2)
                    chunked.forEach { rowShows ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowShows.forEach { show ->
                                Box(modifier = Modifier.weight(1f)) {
                                    PodcastShortcutCard(
                                        show = show,
                                        onClick = { onOpenShow(show) },
                                    )
                                }
                            }
                            if (rowShows.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 2. 【为你精选视频播客】横向货架（Spotify 视频推荐特色）
        if (recommendedVideos.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.podcast_section_recommended_videos),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                fontFamily = appFontFamily(),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1DB954).copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.podcast_video_badge),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                                    fontFamily = appFontFamily(),
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        // 标题右侧的【浏览更多 >】操作按钮（紧贴标题右边，平板与小屏设备均清晰可见，直达视频专区）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1DB954).copy(alpha = if (isDark) 0.18f else 0.12f))
                                .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .clickable(onClick = onBrowseMoreVideos)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.podcast_browse_more),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                                fontFamily = appFontFamily(),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.podcast_browse_more),
                                tint = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(recommendedVideos, key = { it.id }) { vShow ->
                            PodcastVideoCard(
                                show = vShow,
                                onClick = { onOpenShow(vShow) },
                                onPlayVideo = {
                                    scope.launch {
                                        PodcastRepository.playLatestEpisode(context, vShow)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 3. 【继续收听】横向货架（读取 PodcastStore.history）
        val uncompletedHistory = history.take(8)
        if (uncompletedHistory.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.podcast_section_continue_listening),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(10.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uncompletedHistory, key = { it.episode.id }) { item ->
                            PodcastContinueListeningCard(
                                item = item,
                                onClick = { onPlayEpisode(item) },
                            )
                        }
                    }
                }
            }
        }

        // 4. 【热门播客专栏】推荐探索卡片组
        if (featuredShows.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.podcast_section_explore),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(10.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(featuredShows.take(10), key = { it.id }) { show ->
                            Box(modifier = Modifier.width(140.dp)) {
                                PodcastShowCard(
                                    show = show,
                                    onClick = { onOpenShow(show) },
                                )
                            }
                        }
                    }
                }
            }

            // 5. 更多精选单曲与播客垂直流
            val remainingShows = featuredShows.drop(10)
            if (remainingShows.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "大家都在听",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                items(remainingShows) { show ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface.copy(alpha = if (isDark) 0.22f else 0.38f))
                            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.08f else 0.14f), RoundedCornerShape(12.dp))
                            .clickable { onOpenShow(show) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface),
                        ) {
                            BitmapImage(
                                key = show.coverUrl,
                                contentDescription = show.title,
                                modifier = Modifier.fillMaxSize(),
                                targetWidth = 120,
                                targetHeight = 120,
                                showDefaultArtwork = false,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = show.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = appFontFamily(),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = show.description.ifBlank { show.publisher },
                                fontSize = 12.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Spotify 风格快捷访问卡片（Shortcuts Card，56dp 高度，2 列小卡片）。
 */
@Composable
private fun PodcastShortcutCard(
    show: PodcastShow,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.08f else 0.15f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(colors.surface),
        ) {
            BitmapImage(
                key = show.coverUrl,
                contentDescription = show.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 112,
                targetHeight = 112,
                showDefaultArtwork = false,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = show.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
    }
}

/**
 * Spotify 风格【视频播客（Video Podcast）】横向卡片。
 */
@Composable
private fun PodcastVideoCard(
    show: PodcastShow,
    onClick: () -> Unit,
    onPlayVideo: (() -> Unit)? = null,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.28f else 0.45f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.1f else 0.18f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // 16:9 比例视频预览槽位
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            BitmapImage(
                key = show.coverUrl,
                contentDescription = show.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 440,
                targetHeight = 248,
                showDefaultArtwork = false,
            )

            // 半透明暗色渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )

            // 绿色播放按钮（支持单独点击起播视频）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1DB954).copy(alpha = 0.92f))
                    .clickable {
                        onPlayVideo?.invoke() ?: onClick()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            // 视频徽章
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "VIDEO",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1DB954),
                    fontFamily = appFontFamily(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = show.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = show.description.ifBlank { show.publisher },
            fontSize = 11.sp,
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
        )
    }
}

/**
 * Spotify 风格【继续收听（Continue Listening）】卡片（展示单集进度与剩余分钟）。
 */
@Composable
private fun PodcastContinueListeningCard(
    item: PodcastHistoryItem,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val savedProgress = remember(item.episode.id) {
        PodcastStore.getProgress(item.episode.id)
    }

    val progressFraction = if (item.episode.durationMs > 0 && savedProgress > 0) {
        (savedProgress.toFloat() / item.episode.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0.35f // 模拟默认已收听进度
    }

    val remainingMinutes = if (item.episode.durationMs > 0) {
        ((item.episode.durationMs - savedProgress).coerceAtLeast(0L) / 60000L).coerceAtLeast(1L)
    } else {
        15L
    }

    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.25f else 0.42f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.08f else 0.15f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface),
            ) {
                BitmapImage(
                    key = item.episode.coverUrl.ifBlank { item.show.coverUrl },
                    contentDescription = item.episode.title,
                    modifier = Modifier.fillMaxSize(),
                    targetWidth = 100,
                    targetHeight = 100,
                    showDefaultArtwork = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.episode.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.podcast_time_remaining, remainingMinutes),
                    fontSize = 10.sp,
                    color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Spotify 标志性绿色细进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(colors.outline.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressFraction)
                    .clip(CircleShape)
                    .background(Color(0xFF1DB954))
            )
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)
    val minutes = diff / (60 * 1000L)
    val hours = diff / (3600 * 1000L)
    val days = diff / (86400 * 1000L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        hours < 24 -> "${hours}小时前"
        days < 7 -> "${days}天前"
        else -> {
            val sdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/**
 * 视频专属网格卡片列数计算（专为 16:9 横幅视频卡片优化，大屏展示 3~4 列，视野极其开阔饱满）。
 */
@Composable
private fun resolveVideoGridColumns(): Int {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 1200 -> 4
        widthDp >= 800 -> 3
        widthDp >= 550 -> 3
        else -> 2
    }
}

/**
 * 视频播客专属探索页面（支持搜索特定视频播客，多端自适应网格展示）。
 */
@Composable
private fun PodcastVideoExploreContent(
    recommendedVideos: List<PodcastShow>,
    onOpenShow: (PodcastShow) -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridColumns = resolveVideoGridColumns()

    val quickTags = listOf("全部", "TED演讲", "科技数码", "深度访谈", "宇宙自然", "时尚生活", "文化艺术")
    var selectedTag by remember { mutableStateOf("全部") }

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var displayedVideos by remember { mutableStateOf<List<PodcastShow>>(recommendedVideos) }
    var searchResults by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var isSearchingLoading by remember { mutableStateOf(false) }

    fun executeVideoSearch(queryOverride: String? = null) {
        val trimmed = (queryOverride ?: searchQuery).trim()
        if (trimmed.isEmpty() || trimmed == "全部") {
            isSearching = false
            searchResults = emptyList()
            selectedTag = "全部"
            return
        }
        isSearching = true
        isSearchingLoading = true
        scope.launch {
            val list = PodcastRepository.searchVideoShows(trimmed)
            searchResults = list
            isSearchingLoading = false
        }
    }

    LaunchedEffect(recommendedVideos) {
        if (!isSearching && recommendedVideos.isNotEmpty()) {
            displayedVideos = recommendedVideos
        }
    }

    LaunchedEffect(Unit) {
        if (displayedVideos.isEmpty()) {
            val list = PodcastRepository.getRecommendedVideoShows()
            if (list.isNotEmpty()) {
                displayedVideos = list
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // 搜索栏与过滤提示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.52f))
                    .border(1.dp, colors.outline.copy(alpha = 0.2f), RoundedCornerShape(19.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Search,
                        contentDescription = "搜索",
                        tint = colors.secondaryText,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = colors.onSurface,
                            fontFamily = appFontFamily(),
                        ),
                        cursorBrush = SolidColor(if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { executeVideoSearch() }),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.podcast_video_search_hint),
                                    fontSize = 12.sp,
                                    color = colors.secondaryText.copy(alpha = 0.6f),
                                    fontFamily = appFontFamily(),
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清空",
                            tint = colors.secondaryText,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    searchQuery = ""
                                    selectedTag = "全部"
                                    isSearching = false
                                    searchResults = emptyList()
                                }
                        )
                    }
                }
            }

            // 搜索按钮
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Color(0xFF1DB954).copy(alpha = if (isDark) 0.22f else 0.14f))
                    .clickable { executeVideoSearch() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "搜索",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                    fontFamily = appFontFamily(),
                )
            }
        }

        // 快捷分类胶囊行（使用 LazyRow，支持小屏/宽屏平滑惯性滚动，绝不贴边裁切）
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            contentPadding = PaddingValues(end = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(quickTags) { tag ->
                val isSelected = selectedTag == tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) {
                                Color(0xFF1DB954).copy(alpha = if (isDark) 0.28f else 0.18f)
                            } else {
                                colors.surface.copy(alpha = if (isDark) 0.25f else 0.40f)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) {
                                Color(0xFF1DB954).copy(alpha = 0.6f)
                            } else {
                                colors.outline.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable {
                            selectedTag = tag
                            if (tag == "全部") {
                                searchQuery = ""
                                isSearching = false
                                searchResults = emptyList()
                            } else {
                                isSearching = true
                                isSearchingLoading = false
                                val filterWord = when (tag) {
                                    "TED演讲" -> "TED"
                                    "科技数码" -> "科技"
                                    "深度访谈" -> "访谈"
                                    "宇宙自然" -> "宇宙"
                                    "时尚生活" -> "时尚"
                                    "文化艺术" -> "文化"
                                    else -> tag
                                }
                                val localMatched = displayedVideos.filter {
                                    it.category.contains(tag) || it.category.contains(filterWord) ||
                                    it.title.contains(filterWord, ignoreCase = true) ||
                                    it.description.contains(filterWord, ignoreCase = true)
                                }
                                searchResults = localMatched

                                // 异步全网补充检索该分类的更多超清视频播客
                                scope.launch {
                                    val queryWord = when (tag) {
                                        "TED演讲" -> "TED talks video"
                                        "科技数码" -> "Tech video podcast"
                                        "深度访谈" -> "Interview video podcast"
                                        "宇宙自然" -> "NASA video"
                                        "时尚生活" -> "Fashion video podcast"
                                        "文化艺术" -> "Art video podcast"
                                        else -> "$tag video"
                                    }
                                    val netShows = PodcastRepository.searchVideoShows(queryWord)
                                    if (selectedTag == tag && netShows.isNotEmpty()) {
                                        val seen = localMatched.map { it.id }.toMutableSet()
                                        val combined = localMatched.toMutableList()
                                        netShows.forEach { ns ->
                                            if (seen.add(ns.id)) combined.add(ns)
                                        }
                                        searchResults = combined
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tag,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43)
                        } else {
                            colors.secondaryText
                        },
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }

        val currentShows = if (isSearching) searchResults else displayedVideos

        if (isSearchingLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在全网检索视频播客...",
                    fontSize = 14.sp,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                )
            }
        } else if (currentShows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isSearching) stringResource(R.string.podcast_video_search_empty) else "暂无视频播客",
                    fontSize = 14.sp,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(currentShows, key = { it.id }) { show ->
                    PodcastVideoGridCard(
                        show = show,
                        onClick = { onOpenShow(show) },
                        onPlayVideo = {
                            scope.launch {
                                PodcastRepository.playLatestEpisode(context, show)
                            }
                        },
                    )
                }

                item(span = { GridItemSpan(gridColumns) }) {
                    Spacer(modifier = Modifier.height(180.dp))
                }
            }
        }
    }
}

/**
 * 视频专属探索网格卡片（16:9 高清横幅比例 + 视频徽章 + 播放快捷预览）。
 */
@Composable
private fun PodcastVideoGridCard(
    show: PodcastShow,
    onClick: () -> Unit,
    onPlayVideo: (() -> Unit)? = null,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (isDark) 0.28f else 0.45f))
            .border(1.dp, colors.outline.copy(alpha = if (isDark) 0.1f else 0.18f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            BitmapImage(
                key = show.coverUrl,
                contentDescription = show.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 440,
                targetHeight = 248,
                showDefaultArtwork = false,
            )

            // 半透明暗色渐变遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )

            // 绿色播放按钮（支持单独点击直接起播最新视频）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1DB954).copy(alpha = 0.92f))
                    .clickable {
                        onPlayVideo?.invoke() ?: onClick()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            // 视频徽章
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.70f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "VIDEO",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1DB954),
                    fontFamily = appFontFamily(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = show.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = show.description.ifBlank { show.publisher },
            fontSize = 11.sp,
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = appFontFamily(),
        )
    }
}
