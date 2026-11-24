package com.rawsmusic.core.ui.scene.pages

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import com.rawsmusic.module.online.source.qq.QQMusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 歌单广场的三段式切换。
 *
 * 与「平台」是**正交**的两个维度：平台决定数据来源，section 决定看什么内容。
 */
internal enum class OnlinePlazaSection(@StringRes val labelRes: Int) {
    Recommend(R.string.online_plaza_section_recommend),
    Categories(R.string.online_plaza_section_categories),
    Leaderboards(R.string.online_plaza_section_leaderboards),
}

private const val PAGE_SIZE = 30
private const val GRID_COLUMNS = 2

/** 某个「平台 + 分段 + 分类」组合已加载的歌单分页快照。 */
private data class OnlinePlazaPageCache(
    val playlists: List<OnlinePlaylist>,
    val page: Int,
    val endReached: Boolean,
)

/**
 * 歌单广场的进程级内存缓存。
 *
 * 「在线音乐」页离开组合后 `remember` 状态会全部丢失，再次进入时只能重新请求，
 * 转场动画期间就会出现「加载圈 → 整屏网格」的突变重组。缓存上次结果后，再次进入的
 * 隐藏预热帧里即可直接组合出完整网格。仅在主线程读写。
 */
private object OnlinePlazaMemoryCache {
    val pages = HashMap<String, OnlinePlazaPageCache>()
    val leaderboards = HashMap<String, List<OnlineLeaderboard>>()
    val tags = HashMap<String, List<OnlinePlaylistTag>>()
    /** QQ 音乐最近访问的分类 ID（对照 QTMusic `RECENT_ASSORTMENT`，最多保留 6 项）。 */
    val recentQQTagIds = ArrayList<String>()

    fun recordRecentQQTag(tagId: String) {
        val id = tagId.trim()
        if (id.isEmpty() || id == "全部" || id == "10000000" || id == "0") return
        recentQQTagIds.remove(id)
        recentQQTagIds.add(0, id)
        while (recentQQTagIds.size > 6) {
            recentQQTagIds.removeAt(recentQQTagIds.lastIndex)
        }
    }
}

/** 推荐段固定查「全部」，QQ 音乐分类段同时区分排序维度（推荐=5 / 最新=2）。 */
private fun plazaQueryKey(
    platform: OnlinePlatform,
    section: OnlinePlazaSection,
    tagId: String,
    sortId: String = "",
): String {
    val effectiveTagId = if (section == OnlinePlazaSection.Recommend) "全部" else tagId
    val effectiveSort = if (platform == OnlinePlatform.QQ && section == OnlinePlazaSection.Categories) sortId else ""
    return "${platform.id}:${section.name}:$effectiveTagId:$effectiveSort"
}

/**
 * 歌单广场。
 *
 * 四态（加载 / 空 / 错误 / 内容）按 RawS 的既有做法**页面内联**实现（全仓没有共享的
 * Loading/Empty/Error 组件，见根 `AGENTS.MD` §4.4）。
 *
 * 网格用 `chunked(2)` + `LazyColumn` 内嵌行，**不用 `LazyVerticalGrid`**（决策 D11）。
 */
@Composable
internal fun OnlinePlazaTab(
    onOpenPlaylist: (OnlinePlatform, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repository = OnlineMusicRepository.getInstance()
    val platform by repository.currentPlatform.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridColumns = resolveOnlineGridColumns()

    var section by remember { mutableStateOf(OnlinePlazaSection.Recommend) }
    var tags by remember(platform) { mutableStateOf(OnlinePlazaMemoryCache.tags[platform.id].orEmpty()) }
    var selectedTagId by remember(platform) { mutableStateOf("全部") }
    // QQ 音乐歌单分类排序方式（对齐 QTMusic `3g_get_diss_by_tag`：5=推荐、2=最新）
    var selectedSortId by remember(platform) { mutableStateOf(QQMusicSource.SORT_RECOMMEND) }
    var showQQCategoryPicker by remember(platform) { mutableStateOf(false) }
    var recentQQTagIds by remember { mutableStateOf(OnlinePlazaMemoryCache.recentQQTagIds.toList()) }
    // 首帧直接从进程级缓存取上次的结果：再次进入「在线音乐」时，隐藏预热帧里就已是完整网格，转场期间不再出现「加载圈 → 整屏网格」的突变重组。
    val initialPage = remember(platform) { OnlinePlazaMemoryCache.pages[plazaQueryKey(platform, OnlinePlazaSection.Recommend, "全部")] }
    var playlists by remember(platform) { mutableStateOf(initialPage?.playlists.orEmpty()) }
    var leaderboards by remember(platform) { mutableStateOf(OnlinePlazaMemoryCache.leaderboards[platform.id].orEmpty()) }
    var page by remember(platform) { mutableIntStateOf(initialPage?.page ?: 1) }
    // 无缓存时首帧即为加载态，避免先闪一帧「暂无内容」。
    var loading by remember { mutableStateOf(initialPage == null) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endReached by remember(platform) { mutableStateOf(initialPage?.endReached ?: false) }
    // 重试令牌：自增即触发下面的 LaunchedEffect 重新拉取（不引入 ViewModel，AGENTS §4.4）。
    var reloadToken by remember { mutableIntStateOf(0) }
    // 已加载缓存见 OnlinePlazaMemoryCache

    fun selectTag(tagId: String) {
        selectedTagId = tagId
        if (platform == OnlinePlatform.QQ) {
            OnlinePlazaMemoryCache.recordRecentQQTag(tagId)
            recentQQTagIds = OnlinePlazaMemoryCache.recentQQTagIds.toList()
        }
    }

    // 歌单搜索状态
    var isSearchMode by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var activeSearchKeyword by remember { mutableStateOf("") }
    var searchResults by remember(platform) { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var searchPage by remember(platform) { mutableIntStateOf(1) }
    var searching by remember { mutableStateOf(false) }
    var searchLoadingMore by remember { mutableStateOf(false) }
    var searchEndReached by remember(platform) { mutableStateOf(false) }
    var searchErrorText by remember { mutableStateOf<String?>(null) }

    val loadFailedMessage = stringResource(R.string.online_plaza_load_failed)

    val sceneMotionFrozen = LocalSceneBackgroundFrozen.current
    val latestSceneMotionFrozen by rememberUpdatedState(sceneMotionFrozen)
    BackHandler(enabled = isSearchMode && !sceneMotionFrozen) {
        isSearchMode = false
    }

    fun startSearchPlaylists(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        activeSearchKeyword = q
        searching = true
        searchErrorText = null
        searchPage = 1
        searchEndReached = false
        scope.launch {
            repository.searchPlaylists(keyword = q, page = 1, pageSize = PAGE_SIZE, platform = platform)
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
            repository.searchPlaylists(keyword = activeSearchKeyword, page = next, pageSize = PAGE_SIZE, platform = platform)
                .onSuccess {
                    searchResults = searchResults + it
                    searchPage = next
                    searchEndReached = it.size < PAGE_SIZE
                }
                .onFailure { AppNoticeBus.error(loadFailedMessage) }
            searchLoadingMore = false
        }
    }

    // 当处于搜索模式并切换了平台时，自动使用新平台搜索当前关键词
    LaunchedEffect(platform) {
        if (isSearchMode && activeSearchKeyword.isNotBlank()) {
            startSearchPlaylists(activeSearchKeyword)
        }
    }

    // 平台、分类、排序或重试令牌变化都要重新拉第一页，避免把上一个平台的数据留在列表里。
    LaunchedEffect(platform, selectedTagId, selectedSortId, section, reloadToken) {
        val effectiveSortId = if (platform == OnlinePlatform.QQ && section == OnlinePlazaSection.Categories) selectedSortId else ""
        val queryKey = plazaQueryKey(platform, section, selectedTagId, effectiveSortId)
        // 命中进程级缓存直接复用：不发请求、不切加载态（重试只会在无缓存的错误态下触发）。
        if (section == OnlinePlazaSection.Leaderboards) {
            val cached = OnlinePlazaMemoryCache.leaderboards[platform.id]
            if (!cached.isNullOrEmpty()) {
                if (leaderboards !== cached) leaderboards = cached
                errorText = null
                loading = false
                return@LaunchedEffect
            }
        } else {
            val cached = OnlinePlazaMemoryCache.pages[queryKey]
            if (cached != null && cached.playlists.isNotEmpty()) {
                if (playlists !== cached.playlists) playlists = cached.playlists
                page = cached.page
                endReached = cached.endReached
                errorText = null
                loading = false
                // 即便命中歌单缓存，切到分类段时若 tags 尚未加载也补拉一次分类树
                if (section == OnlinePlazaSection.Categories && tags.isEmpty()) {
                    repository.getTags(platform).onSuccess {
                        tags = it
                        OnlinePlazaMemoryCache.tags[platform.id] = it
                    }
                }
                return@LaunchedEffect
            }
        }
        if (section == OnlinePlazaSection.Leaderboards) {
            loading = true
            errorText = null
            val res = repository.getLeaderboards(platform)
            if (latestSceneMotionFrozen) {
                snapshotFlow { latestSceneMotionFrozen }.first { !it }
            }
            res
                .onSuccess {
                    leaderboards = it
                    OnlinePlazaMemoryCache.leaderboards[platform.id] = it
                    errorText = null
                }
                .onFailure {
                    errorText = loadFailedMessage
                    AppNoticeBus.error(loadFailedMessage)
                }
            loading = false
            return@LaunchedEffect
        }

        loading = true
        errorText = null
        page = 1
        endReached = false

        if (section == OnlinePlazaSection.Categories && tags.isEmpty()) {
            repository.getTags(platform).onSuccess {
                tags = it
                OnlinePlazaMemoryCache.tags[platform.id] = it
            }
        }

        val queryTagId = if (section == OnlinePlazaSection.Recommend) "全部" else selectedTagId
        val res = repository.getPlaylists(
            tagId = queryTagId,
            page = 1,
            pageSize = PAGE_SIZE,
            platform = platform,
            sortId = effectiveSortId,
        )
        if (latestSceneMotionFrozen) {
            snapshotFlow { latestSceneMotionFrozen }.first { !it }
        }
        res
            .onSuccess {
                playlists = it
                endReached = it.size < PAGE_SIZE
                errorText = null
                OnlinePlazaMemoryCache.pages[queryKey] =
                    OnlinePlazaPageCache(playlists = it, page = 1, endReached = endReached)
            }
            .onFailure {
                playlists = emptyList()
                errorText = loadFailedMessage
                AppNoticeBus.error(loadFailedMessage)
            }
        loading = false
    }

    fun loadNextPage() {
        if (loading || loadingMore || endReached) return
        loadingMore = true
        // 固定发起请求时的查询条件，防止回包时用户已切换分段/分类而写错缓存。
        val requestPlatform = platform
        val requestSection = section
        val requestTagId = selectedTagId
        val requestSortId = if (requestPlatform == OnlinePlatform.QQ && requestSection == OnlinePlazaSection.Categories) selectedSortId else ""
        scope.launch {
            val next = page + 1
            val queryTagId = if (requestSection == OnlinePlazaSection.Recommend) "全部" else requestTagId
            repository.getPlaylists(
                tagId = queryTagId,
                page = next,
                pageSize = PAGE_SIZE,
                platform = requestPlatform,
                sortId = requestSortId,
            )
                .onSuccess {
                    playlists = playlists + it
                    page = next
                    endReached = it.size < PAGE_SIZE
                    OnlinePlazaMemoryCache.pages[plazaQueryKey(requestPlatform, requestSection, requestTagId, requestSortId)] =
                        OnlinePlazaPageCache(playlists = playlists, page = page, endReached = endReached)
                }
                .onFailure { AppNoticeBus.error(loadFailedMessage) }
            loadingMore = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isSearchMode) {
            OnlinePlaylistSearchBar(
                query = searchKeyword,
                onQueryChange = { searchKeyword = it },
                onSearch = { startSearchPlaylists(searchKeyword) },
                onCancel = { isSearchMode = false },
            )
        } else {
            OnlinePlazaSectionRow(
                selected = section,
                onSelect = { section = it },
                onOpenSearch = { isSearchMode = true },
            )

            if (section == OnlinePlazaSection.Categories && tags.isNotEmpty()) {
                if (platform == OnlinePlatform.QQ) {
                    QQMusicCategoryBar(
                        tags = tags,
                        selectedTagId = selectedTagId,
                        selectedSortId = selectedSortId,
                        onSelectTag = ::selectTag,
                        onSelectSort = { selectedSortId = it },
                        onOpenAllCategories = { showQQCategoryPicker = true },
                    )
                } else {
                    OnlineTagRow(
                        tags = tags,
                        selectedTagId = selectedTagId,
                        onSelect = ::selectTag,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (isSearchMode) {
                when {
                    searching -> LoadingState()
                    searchErrorText != null -> ErrorState(
                        message = searchErrorText.orEmpty(),
                        onRetry = { startSearchPlaylists(activeSearchKeyword) },
                    )
                    activeSearchKeyword.isBlank() -> EmptyState(stringResource(R.string.online_plaza_search_initial))
                    searchResults.isEmpty() -> EmptyState(stringResource(R.string.online_plaza_search_empty))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {

                        items(
                            searchResults.chunked(gridColumns),
                            key = { row -> "search_" + row.joinToString("_") { "${it.platform.id}_${it.id}" } }
                        ) { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { playlist ->
                                    OnlinePlaylistCard(
                                        playlist = playlist,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenPlaylist(playlist.platform, playlist.id) },
                                    )
                                }
                                repeat((gridColumns - rowItems.size).coerceAtLeast(0)) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            LoadMoreFooter(
                                loadingMore = searchLoadingMore,
                                endReached = searchEndReached,
                                onLoadMore = ::loadMoreSearchResults,
                            )
                        }
                    }
                }
            } else {
                when {
                    loading -> LoadingState()
                    errorText != null -> ErrorState(
                        message = errorText.orEmpty(),
                        onRetry = { reloadToken++ },
                    )
                    section == OnlinePlazaSection.Leaderboards && leaderboards.isEmpty() ->
                        EmptyState(stringResource(R.string.online_plaza_empty))
                    section != OnlinePlazaSection.Leaderboards && playlists.isEmpty() ->
                        EmptyState(stringResource(R.string.online_plaza_empty))
                    section == OnlinePlazaSection.Leaderboards -> LazyColumn(
                        modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(leaderboards, key = { "${it.platform.id}_${it.id}" }) { board ->
                            OnlineLeaderboardRow(
                                board = board,
                                onClick = { onOpenPlaylist(board.platform, board.id) },
                            )
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().bottomChromeNestedScroll(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {

                        items(
                            playlists.chunked(gridColumns),
                            key = { row -> row.joinToString("_") { "${it.platform.id}_${it.id}" } }
                        ) { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { playlist ->
                                    OnlinePlaylistCard(
                                        playlist = playlist,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenPlaylist(playlist.platform, playlist.id) },
                                    )
                                }
                                // 不足 columns 个时补齐等宽空位，保持列宽一致与对齐。
                                repeat((gridColumns - rowItems.size).coerceAtLeast(0)) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            LoadMoreFooter(
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

    if (showQQCategoryPicker && platform == OnlinePlatform.QQ && tags.isNotEmpty()) {
        QQMusicCategoryPickerDialog(
            tags = tags,
            selectedTagId = selectedTagId,
            recentTagIds = recentQQTagIds,
            onSelect = { tagId ->
                selectTag(tagId)
                showQQCategoryPicker = false
            },
            onDismiss = { showQQCategoryPicker = false },
        )
    }
}

@Composable
private fun OnlinePlaylistSearchBar(
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
                                text = stringResource(R.string.online_plaza_search_hint),
                                color = colors.secondaryText,
                                fontSize = 13.sp,
                            )
                        }
                        inner()
                    }
                    if (query.isNotBlank()) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.online_plaza_search_cancel),
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
            text = stringResource(R.string.online_plaza_search_action),
            onClick = {
                keyboard?.hide()
                onSearch()
            },
        )
        TextButton(
            text = stringResource(R.string.online_plaza_search_cancel),
            onClick = {
                keyboard?.hide()
                onCancel()
            },
        )
    }
}

@Composable
private fun OnlinePlazaSectionRow(
    selected: OnlinePlazaSection,
    onSelect: (OnlinePlazaSection) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlinePlazaSection.entries.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(item.labelRes),
                    color = if (isSelected) colors.primary else colors.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.weight(1f))
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
                contentDescription = stringResource(R.string.online_plaza_search_action),
                tint = colors.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.online_plaza_search_action),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun OnlineTagRow(
    tags: List<OnlinePlaylistTag>,
    selectedTagId: String,
    onSelect: (String) -> Unit,
) {
    val allLabel = stringResource(R.string.online_plaza_tag_all)
    val filteredTags = remember(tags) {
        tags.filter {
            val name = it.name.trim()
            val id = it.id.trim()
            name != "全部" && name != "推荐" && id != "全部" && id != "10000000" && id != "0"
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            val isAllSelected = selectedTagId == "全部" || selectedTagId == "10000000" || selectedTagId == "0"
            OnlineChip(
                label = allLabel,
                selected = isAllSelected,
                onClick = { onSelect("全部") },
            )
        }
        items(filteredTags, key = { it.id }) { tag ->
            OnlineChip(
                label = tag.name,
                selected = selectedTagId == tag.id || selectedTagId == tag.name,
                onClick = { onSelect(tag.id) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * QQ 音乐官方分组顺序（对齐 QTMusic `3g_get_diss_tag_conf`）。
 */
private val QQ_CATEGORY_GROUP_ORDER = listOf("语种", "流派", "主题", "心情", "场景")

/**
 * QQ 音乐快捷分类精选 ID（从 65 个全量官方分类中提炼高频标签，其余收进「全部分类」分组弹窗）。
 */
private val QQ_QUICK_CATEGORY_IDS = listOf(
    "165", // 国语
    "166", // 粤语
    "167", // 英语
    "6",   // 流行
    "11",  // 摇滚
    "28",  // 民谣
    "15",  // 轻音乐
    "24",  // 电子
    "116", // 治愈
    "52",  // 伤感
    "122", // 安静
    "78",  // 睡前
    "99",  // 运动
    "39",  // ACG
    "194", // 古风
    "133", // 影视
)

private fun isAllPlaylistTag(tagId: String): Boolean {
    val id = tagId.trim()
    return id.isEmpty() || id == "全部" || id == "10000000" || id == "0"
}

/**
 * QQ 音乐歌单分类栏（对齐 QTMusic 乐馆-歌单模块）：
 * 1. 第一行：左侧快捷精选分类横向滚动 + 右侧「全部分类」分组弹窗入口；
 * 2. 第二行：左侧当前分类上下文（如「语种 · 国语」）+ 右侧「推荐 / 最新」排序切换（sortId = 5 / 2）。
 */
@Composable
private fun QQMusicCategoryBar(
    tags: List<OnlinePlaylistTag>,
    selectedTagId: String,
    selectedSortId: String,
    onSelectTag: (String) -> Unit,
    onSelectSort: (String) -> Unit,
    onOpenAllCategories: () -> Unit,
) {
    val colors = themeColors()
    val allLabel = stringResource(R.string.online_plaza_tag_all)
    val allPlaylistsLabel = stringResource(R.string.online_plaza_all_playlists)
    val allCategoriesLabel = stringResource(R.string.online_plaza_all_categories)
    val sortRecommendLabel = stringResource(R.string.online_plaza_sort_recommend)
    val sortLatestLabel = stringResource(R.string.online_plaza_sort_latest)

    val tagMap = remember(tags) { tags.associateBy { it.id } }
    val selectedTag = remember(tags, selectedTagId) {
        if (isAllPlaylistTag(selectedTagId)) {
            null
        } else {
            tagMap[selectedTagId] ?: tags.firstOrNull { it.name == selectedTagId }
        }
    }

    val quickTags = remember(tags, selectedTag) {
        val result = ArrayList<OnlinePlaylistTag>()
        // 若用户从全部分类弹窗选了一个非精选标签，自动置前展示并高亮，保证当前选中状态立即可见
        if (selectedTag != null && !isAllPlaylistTag(selectedTag.id) && selectedTag.id !in QQ_QUICK_CATEGORY_IDS) {
            result.add(selectedTag)
        }
        QQ_QUICK_CATEGORY_IDS.forEach { id ->
            tagMap[id]?.let { result.add(it) }
        }
        // 兜底：若服务端返回了全新 ID 集合，补齐前 12 个有效标签
        if (result.isEmpty()) {
            tags.filterNot { isAllPlaylistTag(it.id) || it.name == "全部" }
                .take(12)
                .forEach { result.add(it) }
        }
        result
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── 第一行：快捷精选分类 + 「全部分类」按钮 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "qq_tag_all") {
                    OnlineChip(
                        label = allLabel,
                        selected = isAllPlaylistTag(selectedTagId),
                        onClick = { onSelectTag("全部") },
                    )
                }
                items(quickTags, key = { "qq_quick_${it.id}" }) { tag ->
                    OnlineChip(
                        label = tag.name,
                        selected = selectedTagId == tag.id || selectedTagId == tag.name,
                        onClick = { onSelectTag(tag.id) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary.copy(alpha = 0.12f))
                    .border(
                        width = 0.5.dp,
                        color = colors.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onOpenAllCategories)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = allCategoriesLabel,
                    color = colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = "▾",
                    color = colors.primary,
                    fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 第二行：当前分类路径 + 推荐/最新排序切换（对应 QTMusic sortId = 5 / 2） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentTitle = if (selectedTag == null) {
                allPlaylistsLabel
            } else if (selectedTag.category.isNotBlank() && selectedTag.category != "默认" && selectedTag.category != "热门") {
                "${selectedTag.category} · ${selectedTag.name}"
            } else {
                selectedTag.name
            }
            Text(
                text = currentTitle,
                color = colors.secondaryText,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surface.copy(alpha = 0.8f))
                    .border(
                        width = 0.5.dp,
                        color = colors.outline.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val isRecommend = selectedSortId != QQMusicSource.SORT_LATEST
                QQSortPill(
                    label = sortRecommendLabel,
                    selected = isRecommend,
                    onClick = { onSelectSort(QQMusicSource.SORT_RECOMMEND) },
                )
                QQSortPill(
                    label = sortLatestLabel,
                    selected = !isRecommend,
                    onClick = { onSelectSort(QQMusicSource.SORT_LATEST) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun QQSortPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.primary else colors.secondaryText,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * QQ 音乐「全部分类」分组选择弹窗（对照 QTMusic 乐馆-歌单分类页与 `RECENT_ASSORTMENT`）。
 * 采用 Miuix `RawMiuixOverlayDialog` + `LazyColumn` 内嵌 3 列等宽网格（遵循决策 D11）。
 */
@Composable
private fun QQMusicCategoryPickerDialog(
    tags: List<OnlinePlaylistTag>,
    selectedTagId: String,
    recentTagIds: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = themeColors()
    val allPlaylistsLabel = stringResource(R.string.online_plaza_all_playlists)
    val recentTitle = stringResource(R.string.online_plaza_recent_categories)
    val tagMap = remember(tags) { tags.associateBy { it.id } }

    val recentTags = remember(recentTagIds, tagMap) {
        recentTagIds.mapNotNull { tagMap[it] }
    }

    val groupedTags = remember(tags) {
        val buckets = LinkedHashMap<String, MutableList<OnlinePlaylistTag>>()
        QQ_CATEGORY_GROUP_ORDER.forEach { buckets[it] = mutableListOf() }
        tags.forEach { tag ->
            if (isAllPlaylistTag(tag.id) || tag.name == "全部") return@forEach
            val group = tag.category.trim().ifEmpty { "其他" }
            if (group == "热门" || group == "默认") return@forEach
            buckets.getOrPut(group) { mutableListOf() }.add(tag)
        }
        buckets.entries
            .filter { it.value.isNotEmpty() }
            .map { it.key to it.value.toList() }
    }

    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.online_plaza_all_categories),
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            // ── 全部歌单通栏按钮 ──
            item(key = "qq_picker_all") {
                val isAllSelected = isAllPlaylistTag(selectedTagId)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isAllSelected) colors.primary.copy(alpha = 0.14f)
                            else colors.background.copy(alpha = 0.6f),
                        )
                        .border(
                            width = 0.75.dp,
                            color = if (isAllSelected) colors.primary.copy(alpha = 0.5f) else colors.outline.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect("全部") }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = allPlaylistsLabel,
                        color = if (isAllSelected) colors.primary else colors.onSurface,
                        fontSize = 13.5.sp,
                        fontWeight = if (isAllSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                }
            }

            // ── 最近访问分组（对齐 QTMusic `RECENT_ASSORTMENT`） ──
            if (recentTags.isNotEmpty()) {
                item(key = "qq_picker_recent_header") {
                    Text(
                        text = recentTitle,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                    )
                }
                items(
                    items = recentTags.chunked(3),
                    key = { row -> "qq_recent_" + row.joinToString("_") { it.id } },
                ) { rowTags ->
                    QQCategoryPickerGridRow(
                        rowTags = rowTags,
                        selectedTagId = selectedTagId,
                        onSelect = onSelect,
                    )
                }
            }

            // ── 官方全量分组（语种 / 流派 / 主题 / 心情 / 场景） ──
            groupedTags.forEach { (groupName, groupItems) ->
                item(key = "qq_group_header_$groupName") {
                    Text(
                        text = groupName,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(
                    items = groupItems.chunked(3),
                    key = { row -> "qq_group_${groupName}_" + row.joinToString("_") { it.id } },
                ) { rowTags ->
                    QQCategoryPickerGridRow(
                        rowTags = rowTags,
                        selectedTagId = selectedTagId,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun QQCategoryPickerGridRow(
    rowTags: List<OnlinePlaylistTag>,
    selectedTagId: String,
    onSelect: (String) -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rowTags.forEach { tag ->
            val selected = selectedTagId == tag.id || selectedTagId == tag.name
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) colors.primary.copy(alpha = 0.14f)
                        else colors.background.copy(alpha = 0.6f),
                    )
                    .border(
                        width = 0.5.dp,
                        color = if (selected) colors.primary.copy(alpha = 0.5f) else colors.outline.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(tag.id) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tag.name,
                    color = if (selected) colors.primary else colors.onSurface,
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        repeat((3 - rowTags.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun OnlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = themeColors()
    Text(
        text = label,
        color = if (selected) colors.primary else colors.secondaryText,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.7f))
            .border(
                width = 0.5.dp,
                color = if (selected) colors.primary.copy(alpha = 0.4f) else colors.outline.copy(alpha = 0.1f),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/** 歌单卡片。封面走 [BitmapImage]，它底层已支持 http(s) 远程源与本地缓存。 */
@Composable
private fun OnlinePlaylistCard(
    playlist: OnlinePlaylist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface.copy(alpha = 0.5f)),
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Music,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.25f),
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
            )
            BitmapImage(
                key = playlist.coverUrl,
                contentDescription = playlist.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 256,
                targetHeight = 256,
                showDefaultArtwork = false,
            )
            if (playlist.playCount > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "▶",
                        fontSize = 8.sp,
                        color = Color.White,
                    )
                    Text(
                        text = formatPlayCount(playlist.playCount),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = playlist.title,
            color = colors.onSurface,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
        playlist.creatorName?.takeIf { it.isNotBlank() }?.let { creator ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = creator,
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OnlineLeaderboardRow(
    board: OnlineLeaderboard,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Folder,
                contentDescription = null,
                tint = colors.secondaryText.copy(alpha = 0.25f),
                modifier = Modifier.size(24.dp),
            )
            BitmapImage(
                key = board.coverUrl,
                contentDescription = board.title,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 128,
                targetHeight = 128,
                showDefaultArtwork = false,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = board.title,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            board.updateFrequency?.takeIf { it.isNotBlank() }?.let { frequency ->
                Spacer(Modifier.height(4.dp))
                Text(text = frequency, color = colors.secondaryText, fontSize = 11.sp, maxLines = 1)
            }
            if (board.topSongsPreview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = board.topSongsPreview.take(3).joinToString(" / "),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(
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
            loadingMore -> CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            endReached -> Text(
                text = stringResource(R.string.online_plaza_no_more),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
            else -> TextButton(
                text = stringResource(R.string.online_plaza_load_more),
                onClick = onLoadMore,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun EmptyState(message: String) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.online_plaza_retry),
                onClick = onRetry,
            )
        }
    }
}

/** 播放量按中文习惯用「万 / 亿」压缩并保留一位小数，与主流平台一致。 */
private fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000L -> {
        val v = count / 100_000_000.0
        if (v >= 10.0 || (count % 100_000_000L < 10_000_000L)) "${count / 100_000_000L}亿" else String.format(java.util.Locale.CHINA, "%.1f亿", v)
    }
    count >= 10_000L -> {
        val v = count / 10_000.0
        if (v >= 100.0 || (count % 10_000L < 1_000L)) "${count / 10_000L}万" else String.format(java.util.Locale.CHINA, "%.1f万", v)
    }
    else -> count.toString()
}
