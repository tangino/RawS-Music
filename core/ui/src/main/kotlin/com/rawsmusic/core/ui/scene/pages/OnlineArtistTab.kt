package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.OnlineArtist
import com.rawsmusic.module.online.model.OnlineArtistCategory
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

// 分页与网格参数：30 是各平台源推荐的页大小，与仓库 getArtists 的默认值一致。
private const val PAGE_SIZE = 30
private const val GRID_COLUMNS = 2

/**
 * 仓库约定的「不分类」哨兵值。
 *
 * 这是**数据契约**而不是界面文案（`OnlineMusicRepository.getArtists(category = "全部")` 是默认值，
 * QQ 源会把 `"全部"` 翻译成 `all_all_all`，网易源因不含 `_` 而退化为 `area=-1&type=-1`）。
 * 界面上的「全部」标签走 [R.string.online_artist_category_all]，不要用这个常量去渲染。
 */
private const val ALL_CATEGORY = "全部"

/**
 * 在线音乐门户 · 歌手库。
 *
 * 结构照 `OnlinePlazaTab`（唯一模板）：四态**页面内联**（全仓没有共享的 Loading/Empty/Error 组件，
 * 见根 `AGENTS.MD` §4.4）；网格用 `chunked(2)` + `LazyColumn` 内嵌 `Row`，**不用 `LazyVerticalGrid`**
 * （全仓 0 使用，决策 D11）；重试用自增 `reloadToken` 驱动 `LaunchedEffect`，不引入 ViewModel。
 *
 * 与广场的区别只有一处：歌手库多了「分类」这一维度，且**多数平台并不支持歌手能力**
 * （酷狗 / 酷我 / 咪咕沿用 `OnlinePlatformSource` 的默认空实现），所以分类为空时必须能降级，
 * 而不是停在白屏或空 chip 行。
 */
@Composable
internal fun OnlineArtistTab(
    onOpenArtist: (OnlinePlatform, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repository = OnlineMusicRepository.getInstance()
    val platform by repository.currentPlatform.collectAsState()

    var categories by remember { mutableStateOf<List<OnlineArtistCategory>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(ALL_CATEGORY) }
    var artists by remember { mutableStateOf<List<OnlineArtist>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endReached by remember { mutableStateOf(false) }
    // 重试令牌：自增即触发下面的 LaunchedEffect 重新拉取（不引入 ViewModel，AGENTS §4.4）。
    var reloadToken by remember { mutableIntStateOf(0) }

    val loadFailedMessage = stringResource(R.string.online_artist_load_failed)

    // 平台切换必须把「分类」和「选中项」一起重置：分类 id 是各平台私有的
    // （网易 "7_1" / QQ "cn_man_all"），沿用上一个平台的 id 只会拉到空结果。
    // 分类请求失败**不报错、不阻塞**：降级成只剩「全部」一个 chip，歌手列表照常走下面的 effect。
    LaunchedEffect(platform, reloadToken) {
        selectedCategory = ALL_CATEGORY
        categories = repository.getArtistCategories(platform).getOrNull().orEmpty()
    }

    // 平台、选中分类或重试令牌变化都要重新拉第一页，避免把上一个平台/分类的数据留在列表里。
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

        // 仓库层用 runCatching 包住了平台调用，会把 CancellationException 也当成 Failure 返回；
        // 这里必须再查一次 isActive，否则切平台/切分类时被取消的旧请求会闪一下错误态并把 loading 关掉。
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
        ArtistCategoryRow(
            categories = categories,
            selectedCategory = selectedCategory,
            onSelect = { selectedCategory = it },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                        items = artists.chunked(GRID_COLUMNS),
                        // 平台内 id 唯一，带上平台前缀可避免换平台后 key 撞车导致复用错卡片。
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
                            // 奇数个时补一个等宽空位，避免最后一张卡被拉成整行宽。
                            if (rowItems.size < GRID_COLUMNS) {
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

/**
 * 分类 chip 行。
 *
 * 「全部」是固定项：分类拉取失败或平台不支持歌手能力时，这一行仍然只剩「全部」，
 * 配合空态文案给出明确反馈，而不是留一片空白。
 */
@Composable
private fun ArtistCategoryRow(
    categories: List<OnlineArtistCategory>,
    selectedCategory: String,
    onSelect: (String) -> Unit,
) {
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
 *
 * 头像走 [BitmapImage]，它底层已支持 http(s) 远程源与本地缓存；[BitmapImage] 的 `key` 用
 * `avatarUrl`，与广场用 `coverUrl` 同理——同一个歌手在不同页面复用同一份缓存。
 * 名字固定 `maxLines = 1`，保证同一行两张卡高度一致，不会被长名字顶高。
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
        Text(
            text = stringResource(R.string.online_artist_song_count, artist.songCount),
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** 底部三态页脚：正在加载 / 没有更多了 / 加载更多。写法与 `OnlinePlazaTab.LoadMoreFooter` 一致。 */
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
