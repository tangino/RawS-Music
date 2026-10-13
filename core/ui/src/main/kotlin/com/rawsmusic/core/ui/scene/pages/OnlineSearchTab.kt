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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
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
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** 单次请求条数。与仓库 `searchSongs` / `searchSongsAllPlatforms` 的默认值保持一致。 */
private const val PAGE_SIZE = 20

/** 搜索历史上限。只做内存态（`rememberSaveable`），持久化由后续按 `SearchHistoryEntity` 统一做。 */
private const val HISTORY_LIMIT = 10

/**
 * 搜索历史上限用 `listSaver` 而不是 autoSaver。
 *
 * 原因：`SnapshotStateList` 不是 `Parcelable`，autoSaver 会把它整体丢给 Bundle，
 * 在不同 Compose 版本上对「可保存类型」的判定并不稳定；显式声明成 `List<String>` 才是确定可保存的。
 */
private val SEARCH_HISTORY_SAVER = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * 一个平台的搜索结果分组。
 *
 * 为什么要有这个结构：需求要求「选全部时按平台分组展示，且单个平台失败不能影响其它平台」。
 * 把「该平台的结果 + 该平台的失败原因」绑在一起，渲染层就不需要再去猜某个平台是「没搜到」
 * 还是「请求炸了」——这是仓库 `searchSongsAllPlatforms` 返回 `Map<Platform, Result<...>>` 的直接映射。
 */
private data class PlatformSearchGroup(
    val platform: OnlinePlatform,
    val songs: List<OnlineSongItem>,
    val failure: String? = null,
)

/**
 * 全网搜歌。
 *
 * 四态（初始态 / 加载 / 空 / 错误 / 内容）按 RawS 既有做法**页面内联**实现 —— 全仓没有共享的
 * Loading/Empty/Error 组件（见根 `AGENTS.MD` §4.4），这里与 `OnlinePlazaTab` 保持一致。
 *
 * 状态与副作用全部走 `remember` + `rememberCoroutineScope()` + Repository 单例，**不引入 ViewModel**（§4.4）。
 * 刻意**不做 debounce**：本工程没有既有 debounce 工具，只支持显式点按钮 / 键盘回车触发，
 * 这样可以保证「一次点击 = 一次搜索 = 一条历史」的因果关系，不会被定时器打断。
 */
@Composable
internal fun OnlineSearchTab(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val repository = OnlineMusicRepository.getInstance()
    // 只读仓库当前平台：平台切换由门户顶部的 chip 行驱动。这里**绝不**把它当作 LaunchedEffect 的 key，
    // 否则切平台就会清空已有结果（需求 §7 明确要求不清空，下次搜索才用新平台）。
    val currentPlatform by repository.currentPlatform.collectAsState()

    val loadFailedMessage = stringResource(R.string.online_search_load_failed)
    val emptyQueryMessage = stringResource(R.string.online_search_empty_query)
    val platformFailedMessage = stringResource(R.string.online_search_platform_failed)
    val platformEmptyMessage = stringResource(R.string.online_search_platform_empty)

    var titleInput by rememberSaveable { mutableStateOf("") }
    var artistInput by rememberSaveable { mutableStateOf("") }
    // 「全部」是本 tab 自己的聚合开关：true 时忽略 currentPlatform 走 searchSongsAllPlatforms（分组展示），
    // false 时走 searchSongs(platform = 仓库当前平台)。
    var searchAllPlatforms by rememberSaveable { mutableStateOf(true) }
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    val history = rememberSaveable(saver = SEARCH_HISTORY_SAVER) { mutableStateListOf<String>() }

    var groups by remember { mutableStateOf<List<PlatformSearchGroup>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 实际发起搜索的关键词。分页与重试都以它为准，而不是以输入框的当前内容为准 ——
    // 用户可能在结果出来之后又改了输入框但没点搜索。
    var activeKeyword by remember { mutableStateOf("") }
    // 重试令牌：自增即触发下面的 LaunchedEffect 重新搜索（照 `OnlinePlazaTab` 的 reloadToken 设计，不引入 ViewModel）。
    var reloadToken by remember { mutableIntStateOf(0) }

    /** 歌名 + 歌手拼成一个查询串；`searchSongsAggregated` 内部也是这么组合的，保持一致。 */
    fun buildKeyword(rawTitle: String, rawArtist: String): String {
        val t = rawTitle.trim()
        val a = rawArtist.trim()
        return when {
            t.isNotBlank() && a.isNotBlank() -> "$t $a"
            t.isNotBlank() -> t
            else -> a
        }
    }

    /** 记录历史：去重后置顶，并裁到上限（最近 10 条）。 */
    fun rememberKeyword(keyword: String) {
        history.remove(keyword)
        history.add(0, keyword)
        while (history.size > HISTORY_LIMIT) {
            history.removeAt(history.lastIndex)
        }
    }

    /**
     * 分平台合并（仅用于「加载更多」）。
     *
     * 为什么要合并而不是替换：分页要保留前面几页，同时某一页里某个平台失败时，
     * **不能**把该平台已拿到的歌曲丢掉，否则用户会觉得「加载更多把结果弄没了」。
     */
    fun mergeGroups(
        previous: List<PlatformSearchGroup>,
        fresh: List<PlatformSearchGroup>,
    ): List<PlatformSearchGroup> = fresh.map { incoming ->
        val old = previous.firstOrNull { it.platform == incoming.platform }
        PlatformSearchGroup(
            platform = incoming.platform,
            // 按 id 去重：分页边界上平台接口可能重复返回同一首。
            songs = (old?.songs.orEmpty() + incoming.songs).distinctBy { it.id },
            failure = incoming.failure,
        )
    }

    /**
     * 真正的取数逻辑。挂起函数，由按钮 / 回车 / 历史项 / 重试 / 加载更多共用，
     * 避免把同一段四态转换写五遍。
     */
    suspend fun fetch(keyword: String, reset: Boolean) {
        val targetPage = if (reset) 1 else page + 1
        if (reset) {
            searching = true
            errorText = null
            page = 1
            endReached = false
        } else {
            loadingMore = true
        }
        try {
            if (searchAllPlatforms) {
                // 仓库内部已经对每个平台各包一层 runCatching，所以这里拿到的是「每个平台各自成败」，
                // 天然满足错误隔离：一个平台挂掉不影响其它四个。
                val results = repository.searchSongsAllPlatforms(
                    keyword = keyword,
                    page = targetPage,
                    pageSize = PAGE_SIZE,
                )
                val fresh = OnlinePlatform.entries.map { platform ->
                    val result = results[platform]
                    when {
                        // 仓库理论上不会漏平台，但真漏了也要给用户一个明确原因而不是静默少一行。
                        result == null -> PlatformSearchGroup(platform, emptyList(), platformFailedMessage)
                        result.isSuccess -> PlatformSearchGroup(
                            platform = platform,
                            songs = result.getOrNull().orEmpty().filter { it.title.isNotBlank() },
                            failure = null,
                        )
                        else -> PlatformSearchGroup(platform, emptyList(), platformFailedMessage)
                    }
                }
                // 部分平台失败时给一次总提示；逐平台原因由列表里的分组行呈现，页面不整屏切错误态。
                if (fresh.any { it.failure != null }) {
                    AppNoticeBus.error(loadFailedMessage)
                }
                groups = if (reset) fresh else mergeGroups(groups, fresh)
                // 只有当**所有**平台都不足一页时才算到底，否则最后一个满页的平台会让其它平台永远拉不到后续页。
                endReached = fresh.all { it.songs.size < PAGE_SIZE }
            } else {
                val platform = currentPlatform
                val result = repository.searchSongs(
                    keyword = keyword,
                    page = targetPage,
                    pageSize = PAGE_SIZE,
                    platform = platform,
                )
                val songs = result.getOrNull().orEmpty().filter { it.title.isNotBlank() }
                val fresh = listOf(
                    PlatformSearchGroup(
                        platform = platform,
                        songs = songs,
                        failure = if (result.isFailure) platformFailedMessage else null,
                    ),
                )
                groups = if (reset) fresh else mergeGroups(groups, fresh)
                endReached = songs.isEmpty() || songs.size < PAGE_SIZE
                if (result.isFailure) {
                    // 单平台模式下失败就是「这一页什么都没有」，直接整屏错误态 + 重试。
                    errorText = loadFailedMessage
                    AppNoticeBus.error(loadFailedMessage)
                }
            }
            if (!reset) page = targetPage
        } catch (e: CancellationException) {
            // 协程取消不是错误，必须原样抛出，否则会被下面的 catch 误报成搜索失败。
            throw e
        } catch (e: Exception) {
            errorText = loadFailedMessage
            AppNoticeBus.error(loadFailedMessage)
        } finally {
            searching = false
            loadingMore = false
        }
    }

    /** 发起一次全新搜索（重置分页）。两个输入都为空时不搜，只提示。 */
    fun startSearch(rawTitle: String, rawArtist: String) {
        val keyword = buildKeyword(rawTitle, rawArtist)
        if (keyword.isBlank()) {
            AppNoticeBus.error(emptyQueryMessage)
            return
        }
        keyboard?.hide()
        // 顺手把输入框规范化（去首尾空格），避免下次 buildKeyword 结果与历史里那条不一致。
        titleInput = rawTitle.trim()
        artistInput = rawArtist.trim()
        activeKeyword = keyword
        hasSearched = true
        rememberKeyword(keyword)
        scope.launch { fetch(keyword, reset = true) }
    }

    /** 点历史项：回填并立刻搜索。历史只存了一个查询串，无法可靠拆回歌名/歌手，整体放进歌名框。 */
    fun repeatSearch(keyword: String) {
        titleInput = keyword
        artistInput = ""
        activeKeyword = keyword
        hasSearched = true
        rememberKeyword(keyword)
        scope.launch { fetch(keyword, reset = true) }
    }

    fun loadMore() {
        if (searching || loadingMore || endReached || activeKeyword.isBlank()) return
        scope.launch { fetch(activeKeyword, reset = false) }
    }

    // 重试：走令牌自增，保证「重试」用的仍然是上次真正执行过的关键词。
    LaunchedEffect(reloadToken) {
        if (reloadToken > 0 && activeKeyword.isNotBlank()) {
            hasSearched = true
            fetch(activeKeyword, reset = true)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OnlineSearchInputPanel(
            titleInput = titleInput,
            artistInput = artistInput,
            onTitleChange = { titleInput = it },
            onArtistChange = { artistInput = it },
            onSearch = { startSearch(titleInput, artistInput) },
            onClearInputs = {
                titleInput = ""
                artistInput = ""
            },
        )

        OnlineSearchPlatformRow(
            searchAllPlatforms = searchAllPlatforms,
            currentPlatform = currentPlatform,
            onSelectAll = { searchAllPlatforms = true },
            onSelectPlatform = { platform ->
                searchAllPlatforms = false
                // 同步给仓库：门户顶部 chip 行、以及「单平台搜索」都以它为当前平台。
                // 注意这里**只**切平台，不清空 groups —— 结果保留到下一次搜索（需求 §7）。
                repository.switchPlatform(platform)
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> OnlineSearchLoading()
                errorText != null -> OnlineSearchError(
                    message = errorText.orEmpty(),
                    onRetry = { reloadToken++ },
                )
                !hasSearched -> OnlineSearchInitialState(
                    history = history,
                    onHistoryClick = { repeatSearch(it) },
                    onClearHistory = { history.clear() },
                )
                // 单平台且一首都没有 → 空态；全部平台模式即使全空也走列表，
                // 因为逐平台分组行要负责说明「每个平台到底是失败还是无结果」。
                !searchAllPlatforms && groups.all { it.songs.isEmpty() } -> OnlineSearchEmpty()
                groups.isEmpty() -> OnlineSearchEmpty()
                else -> OnlineSearchResultList(
                    groups = groups,
                    platformEmptyMessage = platformEmptyMessage,
                    loadingMore = loadingMore,
                    endReached = endReached,
                    onLoadMore = { loadMore() },
                )
            }
        }
    }
}

/** 双输入 + 搜索按钮。输入框写法照抄 `SourcePortalChrome.kt` 的 `SourceSearchTopBar`（`BasicTextField` + `SolidColor`）。 */
@Composable
private fun OnlineSearchInputPanel(
    titleInput: String,
    artistInput: String,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearInputs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        OnlineSearchField(
            value = titleInput,
            hint = stringResource(R.string.online_search_title_hint),
            imeAction = ImeAction.Next,
            onValueChange = onTitleChange,
            // 歌名框按「下一项」跳到歌手框，所以这里不触发搜索。
            onSubmit = {},
        )
        Spacer(Modifier.height(8.dp))
        OnlineSearchField(
            value = artistInput,
            hint = stringResource(R.string.online_search_artist_hint),
            imeAction = ImeAction.Search,
            onValueChange = onArtistChange,
            onSubmit = onSearch,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (titleInput.isNotBlank() || artistInput.isNotBlank()) {
                TextButton(
                    text = stringResource(R.string.online_search_clear_input),
                    onClick = onClearInputs,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.online_search_action),
                onClick = onSearch,
            )
        }
    }
}

@Composable
private fun OnlineSearchField(
    value: String,
    hint: String,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = themeColors()
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboard?.hide()
                onSubmit()
            },
            onDone = {
                keyboard?.hide()
                onSubmit()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isBlank()) {
                    Text(text = hint, color = colors.secondaryText, fontSize = 14.sp)
                }
                inner()
            }
        },
    )
}

/** 平台过滤 chip 行：「全部」+ 5 个平台。选中态与 `OnlinePlazaTab` 的 chip 保持一致。 */
@Composable
private fun OnlineSearchPlatformRow(
    searchAllPlatforms: Boolean,
    currentPlatform: OnlinePlatform,
    onSelectAll: () -> Unit,
    onSelectPlatform: (OnlinePlatform) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnlineSearchChip(
            label = stringResource(R.string.online_search_platform_all),
            selected = searchAllPlatforms,
            onClick = onSelectAll,
        )
        OnlinePlatform.entries.forEach { platform ->
            OnlineSearchChip(
                label = platform.displayName,
                selected = !searchAllPlatforms && platform == currentPlatform,
                onClick = { onSelectPlatform(platform) },
            )
        }
    }
}

@Composable
private fun OnlineSearchChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
 * 初始态：提示语 + 搜索历史（含清空）。历史只做内存态，不碰数据库。
 */
@Composable
private fun OnlineSearchInitialState(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val colors = themeColors()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(R.string.online_search_initial_hint),
                color = colors.secondaryText,
                fontSize = 13.sp,
            )
        }
        item(key = "history_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallTitle(text = stringResource(R.string.online_search_history_title))
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TextButton(
                        text = stringResource(R.string.online_search_history_clear),
                        onClick = onClearHistory,
                    )
                }
            }
        }
        if (history.isEmpty()) {
            item(key = "history_empty") {
                Text(
                    text = stringResource(R.string.online_search_history_empty),
                    color = colors.secondaryText,
                    fontSize = 13.sp,
                )
            }
        } else {
            items(history, key = { "history_$it" }) { keyword ->
                Text(
                    text = keyword,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .clickable { onHistoryClick(keyword) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 结果列表：每个平台一个组头 + 该平台的歌曲。
 *
 * 分组头用 `SmallTitle`；没有歌曲的平台**一定**留一行说明（失败给失败原因、成功给「无结果」），
 * 这样用户不会把「这个平台没数据」误判成「应用少渲染了」。
 */
@Composable
private fun OnlineSearchResultList(
    groups: List<PlatformSearchGroup>,
    platformEmptyMessage: String,
    loadingMore: Boolean,
    endReached: Boolean,
    onLoadMore: () -> Unit,
) {
    val colors = themeColors()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            item(key = "header_${group.platform.id}") {
                SmallTitle(text = group.platform.displayName)
            }
            if (group.songs.isEmpty()) {
                item(key = "note_${group.platform.id}") {
                    Text(
                        text = group.failure ?: platformEmptyMessage,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                    )
                }
            } else {
                // key 带上平台 id：同一首歌在不同平台 id 可能相同，只用 id 会撞 key 崩溃。
                items(group.songs, key = { "${it.platform.id}_${it.id}" }) { song ->
                    OnlineSearchSongRow(song = song)
                }
            }
        }
        item(key = "footer") {
            OnlineSearchLoadMoreFooter(
                loadingMore = loadingMore,
                endReached = endReached,
                onLoadMore = onLoadMore,
            )
        }
    }
}

/** 结果项：歌名 + 「歌手 · 专辑」+ 平台标签 + 时长。 */
@Composable
private fun OnlineSearchSongRow(song: OnlineSongItem) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = song.coverUrl
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.background),
        ) {
            // 搜索接口偶尔不给封面；只有非空时才真的发图片请求，避免用空 key 去查缓存。
            if (!cover.isNullOrBlank()) {
                BitmapImage(
                    key = cover,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    targetWidth = 96,
                    targetHeight = 96,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = when {
                song.artist.isNotBlank() && song.album.isNotBlank() ->
                    stringResource(R.string.online_search_artist_album, song.artist, song.album)
                song.artist.isNotBlank() -> song.artist
                else -> song.album
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = meta,
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = song.platform.displayName,
                color = colors.primary,
                fontSize = 11.sp,
                maxLines = 1,
            )
            val duration = formatDuration(song.durationMs)
            if (duration.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(text = duration, color = colors.secondaryText, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** 页脚三态：加载更多 / 没有更多了 / 正在加载。照 `OnlinePlazaTab` 的 `LoadMoreFooter`。 */
@Composable
private fun OnlineSearchLoadMoreFooter(
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
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.online_search_loading_more),
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                )
            }
            endReached -> Text(
                text = stringResource(R.string.online_search_no_more),
                color = colors.secondaryText,
                fontSize = 12.sp,
            )
            else -> TextButton(
                text = stringResource(R.string.online_search_load_more),
                onClick = onLoadMore,
            )
        }
    }
}

@Composable
private fun OnlineSearchLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.online_search_loading),
                color = themeColors().secondaryText,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun OnlineSearchEmpty() {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.online_search_empty),
            color = colors.secondaryText,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun OnlineSearchError(message: String, onRetry: () -> Unit) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.online_search_retry),
                onClick = onRetry,
            )
        }
    }
}

/**
 * 时长格式化为 `mm:ss`。
 *
 * 为什么未知时长（<=0）返回空串而不是 `00:00`：0 在这批接口里表示「不知道」，
 * 显示成 00:00 会被当成「真的只有 0 秒」；返回空串让调用方整块隐藏。
 * 不用 `String.format` / `%02d`：那会跟随系统 locale，阿拉伯语等区域会输出非 ASCII 数字。
 */
private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
