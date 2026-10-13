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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 我的收藏（在线歌单）。
 *
 * 数据来自 `OnlinePlaylistFavoriteStore` —— 它把 Room 的 `suspend` DAO 包成了 `StateFlow`。
 * **不要直接调 DAO**：DAO 写入后不会通知 UI，界面会停在旧数据上（见 `AGENTS.MD` §5.3）。
 */
@Composable
internal fun OnlineFavoritesTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()

    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf(false) }

    val loadFailedMessage = stringResource(R.string.online_favorites_load_failed)
    val removedMessage = stringResource(R.string.online_favorites_removed)

    // 进入本 tab 时同步一次：其它地方（广场收藏按钮 / 云同步还原）也可能改过收藏表。
    LaunchedEffect(Unit) {
        runCatching { OnlinePlaylistFavoriteStore.refresh(context) }
            .onFailure {
                loadError = true
                AppNoticeBus.error(loadFailedMessage)
            }
    }

    val groups = OnlinePlaylistFavoriteStore.groupNames()
    val visible = favorites.filter { selectedGroup == null || it.groupName == selectedGroup }

    Column(modifier = modifier.fillMaxSize()) {
        if (favorites.isNotEmpty()) {
            OnlineFavoriteGroupRow(
                groups = groups,
                counts = favorites.groupingBy { it.groupName }.eachCount(),
                selectedGroup = selectedGroup,
                total = favorites.size,
                onSelect = { selectedGroup = it },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loadError -> OnlineFavoritesMessage(stringResource(R.string.online_favorites_load_failed))
                favorites.isEmpty() -> OnlineFavoritesMessage(stringResource(R.string.online_favorites_empty))
                visible.isEmpty() -> OnlineFavoritesMessage(stringResource(R.string.online_favorites_empty))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.playlistKey }) { favorite ->
                        OnlineFavoriteRow(
                            favorite = favorite,
                            onRemove = {
                                scope.launch {
                                    runCatching {
                                        OnlinePlaylistFavoriteStore.remove(context, favorite.playlistKey)
                                        AppNoticeBus.post(removedMessage)
                                    }.onFailure { AppNoticeBus.error(loadFailedMessage) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineFavoriteGroupRow(
    groups: List<String>,
    counts: Map<String, Int>,
    selectedGroup: String?,
    total: Int,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OnlineFavoriteChip(
                label = "${stringResource(R.string.online_favorites_group_all)} ($total)",
                selected = selectedGroup == null,
                onClick = { onSelect(null) },
            )
        }
        items(groups, key = { it }) { group ->
            OnlineFavoriteChip(
                label = "$group (${counts[group] ?: 0})",
                selected = selectedGroup == group,
                onClick = { onSelect(group) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun OnlineFavoriteChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun OnlineFavoriteRow(
    favorite: OnlinePlaylistFavoriteEntity,
    onRemove: () -> Unit,
) {
    val colors = themeColors()
    // stringResource 是 @Composable，不能放进 buildString 的 lambda 里，所以先算好。
    val trackCountText = if (favorite.trackCount > 0) {
        stringResource(R.string.online_favorites_track_count, favorite.trackCount)
    } else {
        ""
    }
    val creatorText = if (favorite.creatorName.isNotBlank()) {
        stringResource(R.string.online_favorites_creator, favorite.creatorName)
    } else {
        ""
    }
    val metaText = listOf(trackCountText, creatorText).filter { it.isNotEmpty() }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BitmapImage(
            key = favorite.coverUrl,
            contentDescription = favorite.title,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp)),
            targetWidth = 128,
            targetHeight = 128,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = favorite.title,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(OnlinePlatform.fromId(favorite.platform)?.displayName ?: favorite.platform)
                    append(" · ")
                    append(favorite.groupName)
                },
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaText.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metaText,
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            text = stringResource(R.string.online_favorites_remove),
            onClick = onRemove,
        )
    }
}

@Composable
private fun OnlineFavoritesMessage(message: String) {
    val colors = themeColors()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = colors.secondaryText, fontSize = 15.sp)
    }
}
