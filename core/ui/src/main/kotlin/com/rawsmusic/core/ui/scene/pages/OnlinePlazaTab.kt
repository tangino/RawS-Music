package com.rawsmusic.core.ui.scene.pages

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

    var section by remember { mutableStateOf(OnlinePlazaSection.Recommend) }
    var tags by remember { mutableStateOf<List<OnlinePlaylistTag>>(emptyList()) }
    var selectedTagId by remember { mutableStateOf("全部") }
    var playlists by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var leaderboards by remember { mutableStateOf<List<OnlineLeaderboard>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var endReached by remember { mutableStateOf(false) }
    // 重试令牌：自增即触发下面的 LaunchedEffect 重新拉取（不引入 ViewModel，AGENTS §4.4）。
    var reloadToken by remember { mutableIntStateOf(0) }

    val loadFailedMessage = stringResource(R.string.online_plaza_load_failed)

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

        repository.getPlaylists(tagId = selectedTagId, page = 1, pageSize = PAGE_SIZE, platform = platform)
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
            repository.getPlaylists(tagId = selectedTagId, page = next, pageSize = PAGE_SIZE, platform = platform)
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
        OnlinePlazaSectionRow(selected = section, onSelect = { section = it })

        if (section == OnlinePlazaSection.Categories && tags.isNotEmpty()) {
            OnlineTagRow(
                tags = tags,
                selectedTagId = selectedTagId,
                onSelect = { selectedTagId = it },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(playlists.chunked(GRID_COLUMNS)) { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { playlist ->
                                OnlinePlaylistCard(
                                    playlist = playlist,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenPlaylist(playlist.platform, playlist.id) },
                                )
                            }
                            // 奇数个时补一个等宽空位，避免最后一张卡被拉成整行宽。
                            if (rowItems.size < GRID_COLUMNS) {
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

@Composable
private fun OnlinePlazaSectionRow(
    selected: OnlinePlazaSection,
    onSelect: (OnlinePlazaSection) -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnlinePlazaSection.entries.forEach { item ->
            val isSelected = item == selected
            Text(
                text = stringResource(item.labelRes),
                color = if (isSelected) colors.onPrimaryContainer else colors.secondaryText,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) colors.primaryContainer else colors.surface)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
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
    val colors = themeColors()
    val allLabel = stringResource(R.string.online_plaza_tag_all)
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OnlineChip(
                label = allLabel,
                selected = selectedTagId == "全部",
                onClick = { onSelect("全部") },
            )
        }
        items(tags, key = { it.id }) { tag ->
            OnlineChip(
                label = tag.name,
                selected = selectedTagId == tag.id,
                onClick = { onSelect(tag.id) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun OnlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = themeColors()
    Text(
        text = label,
        color = if (selected) colors.onPrimaryContainer else colors.secondaryText,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.primaryContainer else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        BitmapImage(
            key = playlist.coverUrl,
            contentDescription = playlist.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            targetWidth = 256,
            targetHeight = 256,
        )
        Text(
            text = playlist.title,
            color = colors.onSurface,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            text = stringResource(R.string.online_plaza_play_count, formatPlayCount(playlist.playCount)),
            color = colors.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
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
        BitmapImage(
            key = board.coverUrl,
            contentDescription = board.title,
            modifier = Modifier
                .width(64.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp)),
            targetWidth = 128,
            targetHeight = 128,
        )
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

/** 播放量按中文习惯用「万 / 亿」压缩，否则 7 位数会撑破卡片。 */
private fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000L -> "${count / 100_000_000L}亿"
    count >= 10_000L -> "${count / 10_000L}万"
    else -> count.toString()
}
