package com.rawsmusic.core.ui.scene.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import com.rawsmusic.core.ui.scene.RawWindowWidthClass
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.OnlineArtist
import com.rawsmusic.module.online.model.OnlineArtistCategory
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Search

// 分页与网格参数：30 是各平台源推荐的页大小，与仓库 getArtists 的默认值一致。
private const val PAGE_SIZE = 30

/**
 * 仓库约定的「不分类」哨兵值。
 */
private const val ALL_CATEGORY = "全部"

/**
 * 在线音乐门户 · 歌手库。
 */
@Composable
internal fun OnlineArtistTab(
    onOpenArtist: (OnlinePlatform, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repository = OnlineMusicRepository.getInstance()
    val platform by repository.currentPlatform.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val gridColumns = resolveOnlineGridColumns()

    var categories by remember { mutableStateOf<List<OnlineArtistCategory>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(ALL_CATEGORY) }
    var artists by remember { mutableStateOf<List<OnlineArtist>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endReached by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }

    // 歌手搜索状态
    var isSearchMode by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var activeSearchKeyword by remember { mutableStateOf("") }
    var searchResults by remember(platform) { mutableStateOf<List<OnlineArtist>>(emptyList()) }
    var searchPage by remember(platform) { mutableIntStateOf(1) }
    var searching by remember { mutableStateOf(false) }
    var searchLoadingMore by remember { mutableStateOf(false) }
    var searchEndReached by remember(platform) { mutableStateOf(false) }
    var searchErrorText by remember { mutableStateOf<String?>(null) }

    val loadFailedMessage = stringResource(R.string.online_artist_load_failed)

    val sceneMotionFrozen = LocalSceneBackgroundFrozen.current
    BackHandler(enabled = isSearchMode && !sceneMotionFrozen) {
        isSearchMode = false
    }

    fun startSearchArtists(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        activeSearchKeyword = q
        searching = true
        searchErrorText = null
        searchPage = 1
        searchEndReached = false
        scope.launch {
            repository.searchArtists(keyword = q, page = 1, pageSize = PAGE_SIZE, platform = platform)
                .onSuccess {
                    searchResults = it
                    searchEndReached = it.size < PAGE_SIZE
                    searchErrorText = null
                }
                .onFailure {
                    searchResults = emptyList()
                    searchErrorText = loadFailedMessage
                    AppNoticeBus.error(loadFailedMessage)
                }
            searching = false
        }
    }

    fun loadMoreSearchResults() {
        if (searching || searchLoadingMore || searchEndReached || activeSearchKeyword.isBlank()) return
        searchLoadingMore = true
        scope.launch {
            val next = searchPage + 1
            repository.searchArtists(keyword = activeSearchKeyword, page = next, pageSize = PAGE_SIZE, platform = platform)
                .onSuccess {
                    searchResults = searchResults + it
                    searchPage = next
                    searchEndReached = it.size < PAGE_SIZE
                }
                .onFailure { AppNoticeBus.error(loadFailedMessage) }
            searchLoadingMore = false
        }
    }

    // 搜索模式下若切换了平台，自动使用新平台搜索当前关键词
    LaunchedEffect(platform) {
        if (isSearchMode && activeSearchKeyword.isNotBlank()) {
            startSearchArtists(activeSearchKeyword)
        }
    }

    // 平台切换重置分类
    LaunchedEffect(platform, reloadToken) {
        selectedCategory = ALL_CATEGORY
        categories = repository.getArtistCategories(platform).getOrNull().orEmpty()
    }

    // 平台、选中分类或重试令牌变化拉取第一页
    LaunchedEffect(platform, selectedCategory, reloadToken) {
        loading = true
        errorText = null
        page = 1
        endReached = false

        val result = repository.getArtists(
            category = selectedCategory,
            page = 1,
            pageSize = PAGE_SIZE,
            platform = platform,
        )

        if (!isActive) return@LaunchedEffect

        result
            .onSuccess {
                artists = it
                endReached = it.size < PAGE_SIZE
                errorText = null
            }
            .onFailure {
                artists = emptyList()
                errorText = loadFailedMessage
                AppNoticeBus.error(loadFailedMessage)
            }
        loading = false
    }

    fun loadNextPage() {
        if (loading || loadingMore || endReached) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            repository.getArtists(
                category = selectedCategory,
                page = next,
                pageSize = PAGE_SIZE,
                platform = platform,
            )
                .onSuccess {
                    artists = artists + it
                    page = next
                    endReached = it.size < PAGE_SIZE
                }
                .onFailure { AppNoticeBus.error(loadFailedMessage) }
            loadingMore = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isSearchMode) {
            OnlineArtistSearchBar(
                query = searchKeyword,
                onQueryChange = { searchKeyword = it },
                onSearch = { startSearchArtists(searchKeyword) },
                onCancel = { isSearchMode = false },
            )
        } else {
            ArtistTopBar(onOpenSearch = { isSearchMode = true })
            ArtistCategoryRow(
                categories = categories,
                selectedCategory = selectedCategory,
                onSelect = { selectedCategory = it },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (isSearchMode) {
                when {
                    searching -> ArtistLoadingState()
                    searchErrorText != null -> ArtistErrorState(
                        message = searchErrorText.orEmpty(),
                        onRetry = { startSearchArtists(activeSearchKeyword) },
                    )
                    activeSearchKeyword.isBlank() -> ArtistEmptyState(stringResource(R.string.online_artist_search_initial))
                    searchResults.isEmpty() -> ArtistEmptyState(stringResource(R.string.online_artist_search_empty))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = searchResults.chunked(gridColumns),
                            key = { row -> "search_${platform.id}_" + row.joinToString("_") { it.id } },
                        ) { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowItems.forEach { artist ->
                                    OnlineArtistCard(
                                        artist = artist,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenArtist(artist.platform, artist.id, artist.name, artist.avatarUrl) },
                                    )
                                }
                                repeat((gridColumns - rowItems.size).coerceAtLeast(0)) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            ArtistLoadMoreFooter(
                                loadingMore = searchLoadingMore,
                                endReached = searchEndReached,
                                onLoadMore = ::loadMoreSearchResults,
                            )
                        }
                    }
                }
            } else {
                when {
                    loading -> ArtistLoadingState()
                    errorText != null -> ArtistErrorState(
                        message = errorText.orEmpty(),
                        onRetry = { reloadToken++ },
                    )
                    artists.isEmpty() -> ArtistEmptyState(stringResource(R.string.online_artist_empty))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = artists.chunked(gridColumns),
                            key = { row -> "${platform.id}_${row.first().id}" },
                        ) { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowItems.forEach { artist ->
                                    OnlineArtistCard(
                                        artist = artist,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenArtist(artist.platform, artist.id, artist.name, artist.avatarUrl) },
                                    )
                                }
                                repeat((gridColumns - rowItems.size).coerceAtLeast(0)) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            ArtistLoadMoreFooter(
                                loadingMore = loadingMore,
                                endReached = endReached,
                                onLoadMore = ::loadNextPage,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistTopBar(onOpenSearch: () -> Unit) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface)
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Search,
                contentDescription = stringResource(R.string.online_artist_search_action),
                tint = colors.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.online_artist_search_action),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun OnlineArtistSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = themeColors()
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearch()
                }
            ),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Search,
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(16.dp),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.online_artist_search_hint),
                                color = colors.secondaryText,
                                fontSize = 13.sp,
                            )
                        }
                        inner()
                    }
                    if (query.isNotBlank()) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.online_artist_search_cancel),
                            tint = colors.secondaryText,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onQueryChange("") },
                        )
                    }
                }
            }
        )
        TextButton(
            text = stringResource(R.string.online_artist_search_action),
            onClick = {
                keyboard?.hide()
                onSearch()
            },
        )
        TextButton(
            text = stringResource(R.string.online_artist_search_cancel),
            onClick = {
                keyboard?.hide()
                onCancel()
            },
        )
    }
}

/**
 * 分类 chip 行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistCategoryRow(
    categories: List<OnlineArtistCategory>,
    selectedCategory: String,
    onSelect: (String) -> Unit,
) {
    val compact = LocalRawWindowLayoutProfile.current.widthClass == RawWindowWidthClass.Compact
    if (compact) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArtistCategoryChip(
                label = stringResource(R.string.online_artist_category_all),
                selected = selectedCategory == ALL_CATEGORY,
                onClick = { onSelect(ALL_CATEGORY) },
            )
            categories.forEach { category ->
                ArtistCategoryChip(
                    label = category.name,
                    selected = selectedCategory == category.id,
                    onClick = { onSelect(category.id) },
                )
            }
        }
    } else {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ArtistCategoryChip(
                    label = stringResource(R.string.online_artist_category_all),
                    selected = selectedCategory == ALL_CATEGORY,
                    onClick = { onSelect(ALL_CATEGORY) },
                )
            }
            items(categories, key = { it.id }) { category ->
                ArtistCategoryChip(
                    label = category.name,
                    selected = selectedCategory == category.id,
                    onClick = { onSelect(category.id) },
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ArtistCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = themeColors()
    Text(
        text = label,
        color = if (selected) colors.onPrimaryContainer else colors.secondaryText,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.primaryContainer else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 歌手卡片。
 * 头像下副文本：若有歌曲数则显示歌曲数；无歌曲数则显示简介或“歌手”标识，避免全部显示“0 首”。
 */
@Composable
private fun OnlineArtistCard(
    artist: OnlineArtist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BitmapImage(
            key = artist.avatarUrl,
            contentDescription = artist.name,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(999.dp)),
            targetWidth = 168,
            targetHeight = 168,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            color = colors.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(3.dp))
        val subtitle = when {
            artist.songCount > 0 -> stringResource(R.string.online_artist_song_count, artist.songCount)
            !artist.description.isNullOrBlank() -> artist.description.orEmpty()
            else -> stringResource(R.string.online_artist_title)
        }
        Text(
            text = subtitle,
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** 底部三态页脚：正在加载 / 没有更多了 / 加载更多。 */
@Composable
private fun ArtistLoadMoreFooter(
    loadingMore: Boolean,
    endReached: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.width(22.dp).height(22.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.online_artist_loading),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                )
            }
            endReached -> Text(
                text = stringResource(R.string.online_artist_no_more),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
            else -> TextButton(
                text = stringResource(R.string.online_artist_load_more),
                onClick = onLoadMore,
            )
        }
    }
}

@Composable
private fun ArtistLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun ArtistEmptyState(message: String) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
    }
}

@Composable
private fun ArtistErrorState(message: String, onRetry: () -> Unit) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.online_artist_retry),
                onClick = onRetry,
            )
        }
    }
}
