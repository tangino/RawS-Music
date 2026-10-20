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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.toAudioFile

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

/** 伪歌单卡的图标：喜欢沿用既有心形，「不喜欢」用新加的拇指向下图形。 */
internal fun playlistCardIconRes(playlist: UserPlaylist): Int = when {
    playlist.id == ATTITUDE_DISLIKED_PLAYLIST_ID -> R.drawable.ic_thumb_down_fill
    playlist.isFavorites || playlist.id == ATTITUDE_FAVORITES_PLAYLIST_ID -> R.drawable.ic_heart_fill
    else -> R.drawable.ic_music_note
}

private fun playlistFrameAccent(playlist: UserPlaylist): Color {
    if (playlist.isFavorites) return Color(0xFFD58A9D)
    val palette = listOf(
        Color(0xFF8997C6),
        Color(0xFF91A889),
        Color(0xFFC39575),
        Color(0xFF7899AF),
        Color(0xFFA38CB4),
        Color(0xFFB59D72),
    )
    val index = (playlist.id.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[index]
}

@Composable
private fun PlaylistCollageCover(
    coverUrl: String?,
    collageSongs: List<AudioFile>,
    iconRes: Int,
    accent: Color,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!coverUrl.isNullOrBlank()) {
            BitmapImage(
                key = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetWidth = 320,
                targetHeight = 320,
            )
        } else if (collageSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = 0.40f),
                                Color.Black.copy(alpha = 0.65f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.95f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        } else {
            val song = collageSongs.firstOrNull()
            if (song != null && song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 320,
                    targetHeight = 320,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.92f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaylistFrameCard(
    playlist: UserPlaylist,
    collageSongs: List<AudioFile>,
    modifier: Modifier = Modifier,
    iconRes: Int,
    accentOverride: Color? = null,
    songCountOverride: Int? = null,
    actionsEnabled: Boolean = !playlist.isFavorites,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val colors = themeColors()
    val accent = remember(playlist.id, playlist.isFavorites, accentOverride) {
        accentOverride ?: playlistFrameAccent(playlist)
    }
    val cardShape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .aspectRatio(0.86f)
            .shadow(
                elevation = 8.dp,
                shape = cardShape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.15f),
                spotColor = accent.copy(alpha = 0.20f),
            )
            .clip(cardShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f),
                        colors.surface.copy(alpha = 0.94f),
                        Color.Black.copy(alpha = 0.72f),
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.35f), cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (actionsEnabled) onActions else null,
            )
            .padding(8.dp),
    ) {
        PlaylistCollageCover(
            coverUrl = playlist.coverUrl,
            collageSongs = collageSongs,
            iconRes = iconRes,
            accent = accent,
            title = playlist.name,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.22f),
                            colors.surface.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.55f),
                        )
                    )
                )
                .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = playlist.name,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                val totalCount = songCountOverride ?: playlist.songs.size
                val onlineCount = playlist.songs.count { it.isOnlineEntry() }
                val localCount = (playlist.songs.size - onlineCount).coerceAtLeast(0)
                val isHybrid = onlineCount > 0 && localCount > 0
                val countText = when {
                    totalCount == 0 -> stringResource(R.string.playlist_song_count, 0)
                    isHybrid -> "${totalCount} 首 (网 $onlineCount · 本 $localCount)"
                    onlineCount > 0 && onlineCount == playlist.songs.size -> "${totalCount} 首 (网络)"
                    else -> stringResource(R.string.playlist_song_count, totalCount)
                }

                Text(
                    text = countText,
                    color = colors.onSurface.copy(alpha = 0.50f),
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    fontFamily = appFontFamily(),
                )
            }

            if (actionsEnabled) {
                IconButton(
                    onClick = onActions,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "更多操作",
                        tint = colors.onSurface.copy(alpha = 0.60f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnlinePlaylistFrameCard(
    favorite: OnlinePlaylistFavoriteEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onActions: () -> Unit,
) {
    val colors = themeColors()
    val platform = remember(favorite.platform) { OnlinePlatform.fromId(favorite.platform) }
    val accent = remember(favorite.platform, favorite.playlistKey) {
        when (favorite.platform.lowercase()) {
            "netease" -> Color(0xFFE53935)
            "qq" -> Color(0xFF00C853)
            "kugou" -> Color(0xFF2979FF)
            "kuwo" -> Color(0xFFFF9800)
            "migu" -> Color(0xFFE040FB)
            else -> {
                val palette = listOf(
                    Color(0xFF8997C6),
                    Color(0xFF8BAE97),
                    Color(0xFFD4A574),
                    Color(0xFF9E8DB5),
                    Color(0xFF6B9EA8),
                    Color(0xFFC78B8B),
                )
                val idx = (favorite.playlistKey.hashCode() and Int.MAX_VALUE) % palette.size
                palette[idx]
            }
        }
    }
    val cardShape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .aspectRatio(0.86f)
            .shadow(
                elevation = 8.dp,
                shape = cardShape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.15f),
                spotColor = accent.copy(alpha = 0.20f),
            )
            .clip(cardShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.22f),
                        colors.surface.copy(alpha = 0.94f),
                        Color.Black.copy(alpha = 0.72f),
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.35f), cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onActions,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            val coverUrl = favorite.coverUrl
            if (coverUrl.isNotBlank()) {
                BitmapImage(
                    key = coverUrl,
                    contentDescription = favorite.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 320,
                    targetHeight = 320,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_list_fill),
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.92f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.22f),
                            colors.surface.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.55f),
                        )
                    )
                )
                .border(0.8.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val platformLabel = platform?.displayName ?: favorite.platform.uppercase()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = platformLabel,
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(6.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = favorite.title,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Text(
                    text = stringResource(R.string.playlist_song_count, favorite.trackCount),
                    color = colors.onSurface.copy(alpha = 0.50f),
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    fontFamily = appFontFamily(),
                )
            }

            IconButton(
                onClick = onActions,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = "更多操作",
                    tint = colors.onSurface.copy(alpha = 0.60f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
