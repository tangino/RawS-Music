package com.rawsmusic.ui.playlist

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.scene.pages.SectionHeader
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListCustomProvider
import com.rawsmusic.module.data.db.entity.PlaylistGroupEntity
import com.rawsmusic.module.data.prefs.PlaylistGroupOpResult
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.model.OnlinePlatform

/**
 * 歌单分组 UI 的「无状态」部分：网格排布、分组筛选 Chip 栏、`PlaylistGroupOpResult` 文案映射。
 *
 * 为什么独立成文件：`PlaylistScreen.kt` 已经 700+ 行，分组只是它的一个维度；
 * 页面里只保留状态与弹窗装配，排布与文案映射放这里，避免主文件继续膨胀。
 */

private const val TAG = "PlaylistGroup"

/** 保留分组名。取自数据层的常量，UI 侧不要再抄一份字面量。 */
internal const val DEFAULT_PLAYLIST_GROUP = PlaylistGroupEntity.DEFAULT_GROUP_NAME

/**
 * 虚拟列表里每一条「网格项」的编码前缀。
 *
 * 这一页的曲库虚拟列表被当作通用行容器使用（既有写法），只有带本前缀的条目才交给
 * [rememberPlaylistGridProvider] 绘制；后缀是 [PlaylistGridPlan.entries] 的下标，
 * 于是标题行与卡片行可以共用一个下标空间，不必再 encode 两次内容。
 */
private const val PLAYLIST_GRID_ENCODING_PREFIX = "raws_playlist_grid:"

/**
 * 歌单项抽象模型：本地歌单 (Local) 与 在线收藏歌单 (Online) 均实现该接口。
 */
sealed interface PlaylistItemCardModel {
    val id: String
    val name: String
    val groupName: String

    data class Local(val playlist: UserPlaylist, val group: String) : PlaylistItemCardModel {
        override val id: String get() = playlist.id
        override val name: String get() = playlist.name
        override val groupName: String get() = group
    }

    data class Online(val favorite: OnlinePlaylistFavoriteEntity) : PlaylistItemCardModel {
        override val id: String get() = favorite.playlistKey
        override val name: String get() = favorite.title
        override val groupName: String get() = favorite.groupName
    }
}

/** 分组标题行高。`SectionHeader` 自带 12/4 的上下内边距，这里给足余量避免文字被裁。 */
private val PLAYLIST_GRID_HEADER_HEIGHT = 44.dp

/** 网格里的一行：要么是分组标题，要么是一排歌单卡（本地/在线/伪歌单共用）。 */
internal sealed interface PlaylistGridEntry {
    data class GroupHeader(val groupName: String) : PlaylistGridEntry

    data class Row(val items: List<PlaylistItemCardModel>) : PlaylistGridEntry
}

/** 排布结果：逻辑条目 + 直接喂给 `ComposeVirtualList` 的虚拟条目。 */
internal data class PlaylistGridPlan(
    val entries: List<PlaylistGridEntry>,
    val virtualItems: List<AudioFile>,
)

/**
 * 把「伪歌单 + 本地歌单 + 在线收藏歌单」排成规整的网格。
 *
 * 分组查看统一由顶部的横向 Chip 筛选栏承载，网格卡片永远规整对齐，没有任何孤立文本挤占打断。
 */
internal fun buildPlaylistGridPlan(
    attitudePlaylists: List<UserPlaylist>,
    items: List<PlaylistItemCardModel>,
    columns: Int,
): PlaylistGridPlan {
    val safeColumns = columns.coerceAtLeast(1)
    val entries = buildList {
        if (attitudePlaylists.isNotEmpty()) {
            val attitudeItems = attitudePlaylists.map { PlaylistItemCardModel.Local(it, DEFAULT_PLAYLIST_GROUP) }
            attitudeItems.chunked(safeColumns).forEach { add(PlaylistGridEntry.Row(it)) }
        }
        items.chunked(safeColumns).forEach { add(PlaylistGridEntry.Row(it)) }
    }
    return PlaylistGridPlan(
        entries = entries,
        virtualItems = entries.mapIndexed(::playlistGridVirtualItem),
    )
}

/**
 * 行高公式：与既有网格一致（卡片宽高比 0.86，行内额外 14.dp 留给歌单名）。
 * 抄成独立函数只是为了让页面侧不再内联这段算术。
 */
internal fun playlistGridRowHeight(contentMaxWidth: Dp, columns: Int): Dp {
    val safeColumns = columns.coerceAtLeast(1)
    val horizontalPadding = 24.dp
    val totalGap = 10.dp * (safeColumns - 1).coerceAtLeast(0)
    val cardWidth = ((contentMaxWidth - horizontalPadding - totalGap) /
        safeColumns.toFloat()).coerceAtLeast(120.dp)
    return cardWidth / 0.86f + 14.dp
}

/**
 * 画这一页的网格：分组标题 + 卡排行。
 *
 * 所有条目由本 provider 接管（`handles` 恒真），所以页面侧不需要为标题行另建列表。
 */
@Composable
internal fun rememberPlaylistGridProvider(
    plan: PlaylistGridPlan,
    columns: Int,
    rowHeight: Dp,
    collageSongsByPlaylistId: Map<String, List<AudioFile>>,
    attitudeSongCounts: Map<String, Int>,
    onPlaylistClick: (String, String) -> Unit,
    onPlaylistActions: (UserPlaylist) -> Unit,
    onOnlinePlaylistClick: (OnlinePlatform, String) -> Unit,
    onOnlinePlaylistActions: (OnlinePlaylistFavoriteEntity) -> Unit,
    onGroupLongPress: (String) -> Unit,
): VirtualListCustomProvider = remember(
    plan,
    columns,
    rowHeight,
    collageSongsByPlaylistId,
    attitudeSongCounts,
    onPlaylistClick,
    onPlaylistActions,
    onOnlinePlaylistClick,
    onOnlinePlaylistActions,
    onGroupLongPress,
) {
    VirtualListCustomProvider(
        itemHeight = { song, _ ->
            if (!song.encodingFormat.startsWith(PLAYLIST_GRID_ENCODING_PREFIX)) {
                null
            } else if (plan.entries.getOrNull(playlistGridIndex(song)) is PlaylistGridEntry.GroupHeader) {
                PLAYLIST_GRID_HEADER_HEIGHT
            } else {
                rowHeight
            }
        },
        handles = { song, _ -> song.encodingFormat.startsWith(PLAYLIST_GRID_ENCODING_PREFIX) },
        content = { song, _, modifier ->
            when (val entry = plan.entries.getOrNull(playlistGridIndex(song))) {
                is PlaylistGridEntry.GroupHeader -> PlaylistGroupHeaderRow(
                    groupName = entry.groupName,
                    onLongPress = onGroupLongPress,
                    modifier = modifier,
                )

                is PlaylistGridEntry.Row -> PlaylistGridRow(
                    items = entry.items,
                    columns = columns,
                    collageSongsByPlaylistId = collageSongsByPlaylistId,
                    attitudeSongCounts = attitudeSongCounts,
                    onPlaylistClick = onPlaylistClick,
                    onPlaylistActions = onPlaylistActions,
                    onOnlinePlaylistClick = onOnlinePlaylistClick,
                    onOnlinePlaylistActions = onOnlinePlaylistActions,
                    modifier = modifier,
                )

                null -> Unit
            }
        },
    )
}

/** 分组标题的显示名：「默认」走资源（数据层存的是中文字面量，需要本地化显示）。 */
@Composable
internal fun playlistGroupDisplayName(groupName: String): String =
    if (groupName == DEFAULT_PLAYLIST_GROUP) {
        stringResource(R.string.playlist_group_default_name)
    } else {
        groupName
    }

/**
 * 数据层只产出枚举，用户可见文案在 UI 层映射（见 `PlaylistGroupStore` 的类注释）。
 * [successMessage] 由调用方按操作给出，成功时原样使用。
 */
internal fun playlistGroupOpMessage(
    context: Context,
    result: PlaylistGroupOpResult,
    successMessage: String,
): String = when (result) {
    PlaylistGroupOpResult.Success -> successMessage
    PlaylistGroupOpResult.BlankName -> context.getString(R.string.playlist_group_name_blank)
    PlaylistGroupOpResult.ReservedName -> context.getString(R.string.playlist_group_name_reserved)
    PlaylistGroupOpResult.DuplicateName -> context.getString(R.string.playlist_group_name_duplicate)
    PlaylistGroupOpResult.NotFound -> context.getString(R.string.playlist_group_not_found)
}

/**
 * 执行一次分组写操作并把结果翻译成提示。
 *
 * [block] 返回 `null` 表示「这个操作没有枚举结果」（例如重排序），按成功处理。
 * 这里显式 rethrow `CancellationException`：`runCatching` 会把它吞掉，页面销毁后的
 * 协程取消会被误报成「操作失败」。
 */
internal fun CoroutineScope.launchPlaylistGroupOp(
    context: Context,
    successMessage: String,
    block: suspend () -> PlaylistGroupOpResult?,
) {
    launch {
        try {
            val result = block()
            if (result == null || result == PlaylistGroupOpResult.Success) {
                AppNoticeBus.post(message = successMessage, icon = AppNoticeIcon.PLAYLIST)
            } else {
                AppNoticeBus.error(playlistGroupOpMessage(context, result, successMessage))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.e(TAG, "group operation failed", error)
            AppNoticeBus.error(context.getString(R.string.playlist_group_op_failed))
        }
    }
}

@Composable
private fun PlaylistGroupHeaderRow(
    groupName: String,
    onLongPress: (String) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // 长按标题才能进入分组的新建/改名/删除/排序菜单；单击刻意留空，
            // 免得误触把用户从「浏览」拽进「管理」。
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(groupName) },
            )
            .padding(start = 12.dp, end = 12.dp),
    ) {
        SectionHeader(playlistGroupDisplayName(groupName))
    }
}

@Composable
private fun PlaylistGridRow(
    items: List<PlaylistItemCardModel>,
    columns: Int,
    collageSongsByPlaylistId: Map<String, List<AudioFile>>,
    attitudeSongCounts: Map<String, Int>,
    onPlaylistClick: (String, String) -> Unit,
    onPlaylistActions: (UserPlaylist) -> Unit,
    onOnlinePlaylistClick: (OnlinePlatform, String) -> Unit,
    onOnlinePlaylistActions: (OnlinePlaylistFavoriteEntity) -> Unit,
    modifier: Modifier,
) {
    val rowColors = themeColors()
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            when (item) {
                is PlaylistItemCardModel.Local -> {
                    val playlist = item.playlist
                    // 伪歌单沿用同一张卡片，只是锁住改名/删除/移动（它们不落 Room）。
                    val attitudeCard = isAttitudePlaylistId(playlist.id)
                    val actionsEnabled = !attitudeCard && !playlist.isFavorites
                    PlaylistFrameCard(
                        playlist = playlist,
                        collageSongs = collageSongsByPlaylistId[playlist.id].orEmpty(),
                        modifier = Modifier.weight(1f),
                        iconRes = playlistCardIconRes(playlist),
                        accentOverride = when (playlist.id) {
                            ATTITUDE_FAVORITES_PLAYLIST_ID -> rowColors.primary
                            ATTITUDE_DISLIKED_PLAYLIST_ID -> rowColors.outline
                            else -> null
                        },
                        songCountOverride = attitudeSongCounts[playlist.id],
                        actionsEnabled = actionsEnabled,
                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                        onActions = { if (actionsEnabled) onPlaylistActions(playlist) },
                    )
                }

                is PlaylistItemCardModel.Online -> {
                    val favorite = item.favorite
                    OnlinePlaylistFrameCard(
                        favorite = favorite,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val platform = OnlinePlatform.fromId(favorite.platform) ?: OnlinePlatform.NETEASE
                            onOnlinePlaylistClick(platform, favorite.remoteId)
                        },
                        onActions = { onOnlinePlaylistActions(favorite) },
                    )
                }
            }
        }
        repeat((columns - items.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun playlistGridIndex(song: AudioFile): Int =
    song.encodingFormat.removePrefix(PLAYLIST_GRID_ENCODING_PREFIX).toIntOrNull() ?: -1

private fun playlistGridVirtualItem(index: Int, entry: PlaylistGridEntry): AudioFile {
    // 持有者身份属于这一行本身（内容变了就换身份），复用既有虚拟列表的稳定性约定：
    // id 落在私有负数区间，不会与真实曲目或伪歌单行撞号。
    val identity = when (entry) {
        is PlaylistGridEntry.GroupHeader -> "header:${entry.groupName}"
        is PlaylistGridEntry.Row -> "row:" + entry.items.joinToString(separator = "|") { it.id }
    }.fold(17L) { acc, char -> acc * 31L + char.code }
    return AudioFile(
        id = Long.MIN_VALUE / 4L + identity,
        title = "Playlist grid item $index",
        encodingFormat = "$PLAYLIST_GRID_ENCODING_PREFIX$index",
    )
}

/**
 * 现代轻量化二级分组筛选胶囊栏 (深度融合 Miuix 极简圆角药丸规范)。
 * 移除了突兀死板的实体遮挡背景条，采用通透沉浸的无感平滑滑动的流线药丸设计；
 * 仅在存在多个包含歌单的分组时展示；空分组自动隐形，不打扰用户。
 */
@Composable
internal fun PlaylistGroupFilterBar(
    groups: List<PlaylistGroupEntity>,
    totalPlaylistCount: Int,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit,
    groupCounts: Map<String, Int>,
    onGroupLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isAllSelected = selectedGroup == null
        PlaylistFilterChip(
            name = stringResource(R.string.playlist_group_all),
            count = totalPlaylistCount,
            isSelected = isAllSelected,
            onClick = { onSelectGroup(null) },
            onLongClick = {},
        )

        groups.forEach { group ->
            val isSelected = selectedGroup == group.name
            val count = groupCounts[group.name] ?: 0
            val displayName = playlistGroupDisplayName(group.name)
            PlaylistFilterChip(
                name = displayName,
                count = count,
                isSelected = isSelected,
                onClick = { onSelectGroup(group.name) },
                onLongClick = {
                    if (group.name != DEFAULT_PLAYLIST_GROUP) {
                        onGroupLongPress(group.name)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistFilterChip(
    name: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = themeColors()
    val shape = RoundedCornerShape(999.dp)
    val pillBgColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.65f),
        label = "playlistChipBg",
    )
    val pillBorderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary.copy(alpha = 0.45f) else colors.outline.copy(alpha = 0.12f),
        label = "playlistChipBorder",
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.secondaryText,
        label = "playlistChipText",
    )

    Box(
        modifier = Modifier
            .clip(shape)
            .background(pillBgColor)
            .border(BorderStroke(if (isSelected) 1.dp else 0.5.dp, pillBorderColor), shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = name,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                fontFamily = appFontFamily(),
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (isSelected) colors.primary.copy(alpha = 0.20f)
                            else colors.onSurface.copy(alpha = 0.08f)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$count",
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) colors.primary else colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }
    }
}

