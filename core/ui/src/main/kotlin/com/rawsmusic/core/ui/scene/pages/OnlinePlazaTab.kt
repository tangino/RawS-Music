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
import androidx.compose.runtime.setValue
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
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.repository.OnlineMusicRepository
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
    val keyboard = LocalSoftwareKeyboardController.current
    val gridColumns = resolveOnlineGridColumns()

    var section by remember { mutableStateOf(OnlinePlazaSection.Recommend) }
    var tags by remember(platform) { mutableStateOf<List<OnlinePlaylistTag>>(emptyList()) }
    var selectedTagId by remember(platform) { mutableStateOf("全部") }
    var playlists by remember(platform) { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var leaderboards by remember(platform) { mutableStateOf<List<OnlineLeaderboard>>(emptyList()) }
    var page by remember(platform) { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endReached by remember(platform) { mutableStateOf(false) }
    // 重试令牌：自增即触发下面的 LaunchedEffect 重新拉取（不引入 ViewModel，AGENTS §4.4）。
    var reloadToken by remember { mutableIntStateOf(0) }

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

    // 平台、分类段或重试令牌变化都要重新拉第一页，避免把上一个平台的数据留在列表里。
    LaunchedEffect(platform, selectedTagId, section, reloadToken) {
        if (section == OnlinePlazaSection.Leaderboards) {
            loading = true
            errorText = null
            repository.getLeaderboards(platform)
                .onSuccess { leaderboards = it; errorText = null }
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
            repository.getTags(platform).onSuccess { tags = it }
        }

        val queryTagId = if (section == OnlinePlazaSection.Recommend) "全部" else selectedTagId
        repository.getPlaylists(tagId = queryTagId, page = 1, pageSize = PAGE_SIZE, platform = platform)
            .onSuccess {
                playlists = it
                endReached = it.size < PAGE_SIZE
                errorText = null
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
        scope.launch {
            val next = page + 1
            val queryTagId = if (section == OnlinePlazaSection.Recommend) "全部" else selectedTagId
            repository.getPlaylists(tagId = queryTagId, page = next, pageSize = PAGE_SIZE, platform = platform)
                .onSuccess {
                    playlists = playlists + it
                    page = next
                    endReached = it.size < PAGE_SIZE
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
                OnlineTagRow(
                    tags = tags,
                    selectedTagId = selectedTagId,
                    onSelect = { selectedTagId = it },
                )
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
                        modifier = Modifier.fillMaxSize(),
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(leaderboards, key = { "${it.platform.id}_${it.id}" }) { board ->
                            OnlineLeaderboardRow(board)
                        }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
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
private fun OnlineLeaderboardRow(board: OnlineLeaderboard) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
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
