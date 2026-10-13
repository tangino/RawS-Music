package com.rawsmusic.ui.playlist

import android.widget.Toast
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.helper.rememberSystemFileAccessGate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.ui.AppNoticeAnimation
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.repository.SongAttitudeRepository
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton

@Composable
fun PlaylistScreen(
    playlistStore: PlaylistStore,
    onBack: () -> Unit,
    virtualListState: ComposeVirtualListState? = null,
    onPlaylistClick: (String, String) -> Unit
) {
    val context = LocalContext.current
    val withSystemFileAccess = rememberSystemFileAccessGate()
    val playlists by playlistStore.playlists.collectAsState()
    val librarySongs by MusicRepository.songs.collectAsState()
    // 「不喜欢」不在任何歌单里，状态只在 song_attitude 表；进入页面刷一次即可，
    // 之后每次写入由 SongAttitudeRepository 自己 refresh（见其类注释）。
    val dislikedKeys by SongAttitudeRepository.dislikedKeys.collectAsState()
    // 分组与「歌单 → 分组」归属都在单例 Store 的 StateFlow 里（UserPlaylist 模型没有 groupName
    // 字段，见 AGENTS.MD 任务说明），页面进入时刷一次，之后每次分组写入由 Store 自己 refresh。
    val playlistGroups by PlaylistGroupStore.groups.collectAsState()
    val playlistGroupMap by PlaylistGroupStore.playlistGroups.collectAsState()
    LaunchedEffect(Unit) {
        SongAttitudeRepository.refresh(context)
    }
    LaunchedEffect(Unit) {
        PlaylistGroupStore.refresh(context)
    }
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    var createDialog by remember { mutableStateOf(false) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var clearDislikesConfirm by remember { mutableStateOf(false) }
    // 分组管理 + 歌单卡动作菜单的弹窗状态；弹窗装配在 PlaylistGroupDialogHost 里。
    val groupChrome = rememberPlaylistGroupChromeState()
    val listState = virtualListState ?: rememberComposeVirtualListState("playlists")
    val windowLayoutProfile = LocalRawWindowLayoutProfile.current
    val playlistColumns = windowLayoutProfile.homeCardColumns

    // 两张伪歌单卡：本地曲的「喜欢」沿用既有系统歌单（PlaylistStore），「不喜欢」走
    // SongAttitudeRepository（决策见 AGENTS.MD §9 D14）。它们排在真实歌单之前，
    // 并复用同一套网格 / 行高 / 卡片写法，不另起样式。
    val favoriteKeyCount = remember(playlists) { playlistStore.favoriteSongKeys().size }
    val attitudePlaylists = remember(playlists, dislikedKeys, librarySongs) {
        listOfNotNull(
            attitudePlaylistView(context, playlistStore, librarySongs, ATTITUDE_FAVORITES_PLAYLIST_ID, dislikedKeys),
            attitudePlaylistView(context, playlistStore, librarySongs, ATTITUDE_DISLIKED_PLAYLIST_ID, dislikedKeys),
        )
    }
    val displayedPlaylists = remember(attitudePlaylists, playlists) { attitudePlaylists + playlists }
    val attitudeSongCounts = remember(favoriteKeyCount, dislikedKeys) {
        mapOf(
            ATTITUDE_FAVORITES_PLAYLIST_ID to favoriteKeyCount,
            ATTITUDE_DISLIKED_PLAYLIST_ID to dislikedKeys.size,
        )
    }

    val resolvedSongsByPlaylistId = remember(displayedPlaylists, librarySongs) {
        displayedPlaylists.associate { playlist ->
            playlist.id to playlistStore.resolveSongs(playlist, librarySongs)
        }
    }
    val collageSongsByPlaylistId = remember(resolvedSongsByPlaylistId) {
        resolvedSongsByPlaylistId.mapValues { (_, songs) -> buildPlaylistCollageSongs(songs) }
    }
    // 分组只影响真实歌单：伪卡是系统入口，不属于任何分组，固定排在所有分区之上（默认选择，
    // 理由见汇报）；同一分组内保持既有「先 chunked、再交给虚拟列表」的网格排布。
    // Store 首帧还没刷回来时按「只有默认」处理，否则真实歌单会整个消失一帧。
    val orderedGroupNames = remember(playlistGroups) {
        playlistGroups.map { it.name }.ifEmpty { listOf(DEFAULT_PLAYLIST_GROUP) }
    }
    val playlistGridPlan = remember(
        attitudePlaylists,
        playlists,
        orderedGroupNames,
        playlistGroupMap,
        playlistColumns,
    ) {
        buildPlaylistGridPlan(
            attitudePlaylists = attitudePlaylists,
            playlists = playlists,
            groupNames = orderedGroupNames,
            playlistGroups = playlistGroupMap,
            columns = playlistColumns,
        )
    }
    val playlistRowHeight = remember(windowLayoutProfile.contentMaxWidth, playlistColumns) {
        playlistGridRowHeight(windowLayoutProfile.contentMaxWidth, playlistColumns)
    }
    val playlistCustomProvider = rememberPlaylistGridProvider(
        plan = playlistGridPlan,
        columns = playlistColumns,
        rowHeight = playlistRowHeight,
        collageSongsByPlaylistId = collageSongsByPlaylistId,
        attitudeSongCounts = attitudeSongCounts,
        onPlaylistClick = onPlaylistClick,
        onPlaylistActions = { groupChrome.playlistActionsTarget = it },
        onGroupLongPress = { groupChrome.groupMenuTarget = it },
    )

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val results = uris.map { playlistStore.importTextPlaylist(it, librarySongs) }
            val imported = results.sumOf { it.importedCount }
            val missing = results.sumOf { it.missingCount }
            val message = if (imported > 0) {
                context.getString(R.string.playlist_import_success, imported, missing)
            } else {
                context.getString(R.string.playlist_import_empty)
            }
            if (imported > 0) {
                AppNoticeBus.post(
                    message = message,
                    icon = AppNoticeIcon.PLAYLIST,
                )
            } else {
                AppNoticeBus.error(message)
            }
        }
    }

    // 「清空所有不喜欢」只在确实有不喜欢时出现，避免常驻一个无意义的入口。
    // 没有更好的归属：歌单页没有「更多」菜单槽位，只有顶栏尾部这个插槽。
    val clearDislikesHeaderAction: (@Composable () -> Unit)? = if (dislikedKeys.isEmpty()) {
        null
    } else {
        {
            IconButton(
                onClick = { clearDislikesConfirm = true },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_bin_6_fill),
                    contentDescription = stringResource(R.string.playlist_attitude_clear_dislikes),
                    tint = colors.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_playlists),
        sceneId = NavScene.PLAYLISTS.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_playlists, playlists.size, playlists.sumOf { it.songs.size }),
        onBack = onBack,
        onCreatePlaylist = { createDialog = true },
        onImportPlaylist = {
            withSystemFileAccess {
                importLauncher.launch(
                    arrayOf(
                        "text/plain",
                        "audio/x-mpegurl",
                        "audio/mpegurl",
                        "application/vnd.apple.mpegurl",
                        "application/octet-stream"
                    )
                )
            }
        },
        headerTrailingContent = clearDislikesHeaderAction,
        virtualListState = listState,
        contentOverlap = 0.dp
    ) { topPadding, backdropSource ->
        CompositionLocalProvider(LocalVirtualListCustomProvider provides playlistCustomProvider) {
            ComposeVirtualList(
                songs = playlistGridPlan.virtualItems,
                state = listState,
                pinchEnabled = false,
                contentTopPadding = topPadding + 12.dp,
                contentBottomPadding = 150.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .then(backdropSource),
            )
        }
    }

    // 分组长按菜单、新建/改名/删除分组、歌单卡动作菜单的装配都在这里（见 PlaylistGroupDialogs.kt）。
    PlaylistGroupDialogHost(
        state = groupChrome,
        groupNames = orderedGroupNames,
        playlistGroupMap = playlistGroupMap,
        // 改名与删除歌单沿用页面既有的确认弹窗，行为不变。
        onRenamePlaylist = { renameTarget = it },
        onDeletePlaylist = { deleteTarget = it },
    )

    if (createDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_create),
            initialName = "",
            submitting = creatingPlaylist,
            onDismiss = { createDialog = false },
            onConfirm = { name ->
                scope.launch {
                    creatingPlaylist = true
                    val result = runCatching { playlistStore.createPlaylist(name) }
                    creatingPlaylist = false
                    when {
                        result.getOrNull() != null -> createDialog = false
                        result.isFailure -> AppNoticeBus.error(
                            result.exceptionOrNull()?.localizedMessage
                                ?: context.getString(R.string.playlist_create_failed)
                        )
                        else -> AppNoticeBus.error(context.getString(R.string.playlist_name_exists))
                    }
                }
            }
        )
    }

    renameTarget?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initialName = playlist.name,
            submitting = false,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    if (!playlistStore.renamePlaylist(playlist.id, name)) {
                        AppNoticeBus.error(context.getString(R.string.playlist_name_exists))
                    }
                }
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.playlist_delete_title), fontFamily = appFontFamily()) },
            text = { Text(stringResource(R.string.playlist_delete_message), fontFamily = appFontFamily()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { playlistStore.deletePlaylist(playlist.id) }
                            .onSuccess { deleted ->
                                if (deleted) AppNoticeBus.post(
                                    context.getString(R.string.notice_playlist_deleted, playlist.name),
                                    com.rawsmusic.core.common.ui.AppNoticeIcon.PLAYLIST,
                                    com.rawsmusic.core.common.ui.AppNoticeAnimation.DELETE,
                                )
                            }
                            .onFailure { failure ->
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                AppNoticeBus.error(context.getString(R.string.notice_playlist_delete_failed))
                            }
                    }
                    deleteTarget = null
                }) { Text(stringResource(R.string.playlist_delete_action), color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.playlist_cancel_action))
                }
            }
        )
    }

    // 危险操作：清空所有「不喜欢」。按规范用 RawMiuixOverlayDialog 做二次确认。
    if (clearDislikesConfirm) {
        RawMiuixOverlayDialog(
            show = true,
            title = stringResource(R.string.playlist_attitude_clear_dislikes_title),
            summary = stringResource(R.string.playlist_attitude_clear_dislikes_summary),
            backgroundColor = colors.surface,
            onDismissRequest = { clearDislikesConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiuixTextButton(
                    text = stringResource(R.string.playlist_cancel_action),
                    onClick = { clearDislikesConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                MiuixButton(
                    onClick = {
                        clearDislikesConfirm = false
                        scope.launch {
                            try {
                                SongAttitudeRepository.clearAllDislikes(context)
                                AppNoticeBus.post(
                                    message = context.getString(R.string.playlist_attitude_cleared),
                                    icon = AppNoticeIcon.PLAYLIST,
                                    animation = AppNoticeAnimation.DELETE,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                AppNoticeBus.error(context.getString(R.string.playlist_attitude_clear_failed))
                            }
                        }
                    },
                    colors = MiuixButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.playlist_attitude_clear_dislikes_confirm))
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

private data class PlaylistArtworkSlot(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val cornerRadius: Dp,
    val z: Float,
)

private val playlistArtworkSlots = listOf(
    PlaylistArtworkSlot(0.025f, 0.035f, 0.60f, 0.79f, -1.8f, 20.dp, 6f),
    PlaylistArtworkSlot(0.63f, 0.035f, 0.29f, 0.35f, 2.1f, 17.dp, 4f),
    PlaylistArtworkSlot(0.815f, 0.22f, 0.16f, 0.30f, -1.2f, 14.dp, 5f),
    PlaylistArtworkSlot(0.61f, 0.40f, 0.35f, 0.27f, 1.0f, 16.dp, 3f),
    PlaylistArtworkSlot(0.67f, 0.66f, 0.28f, 0.25f, -1.8f, 15.dp, 4f),
    PlaylistArtworkSlot(0.49f, 0.72f, 0.19f, 0.20f, 1.8f, 13.dp, 5f),
)

private object PlaylistOrganicFrameShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.10f, 0f)
            cubicTo(w * 0.27f, h * 0.012f, w * 0.33f, 0f, w * 0.50f, h * 0.01f)
            cubicTo(w * 0.68f, h * 0.022f, w * 0.73f, 0f, w * 0.88f, h * 0.018f)
            cubicTo(w * 0.965f, h * 0.03f, w, h * 0.09f, w * 0.99f, h * 0.18f)
            cubicTo(w * 0.98f, h * 0.34f, w, h * 0.43f, w * 0.985f, h * 0.58f)
            cubicTo(w * 0.97f, h * 0.72f, w, h * 0.81f, w * 0.955f, h * 0.91f)
            cubicTo(w * 0.91f, h * 0.995f, w * 0.81f, h, w * 0.69f, h * 0.985f)
            cubicTo(w * 0.53f, h * 0.97f, w * 0.44f, h, w * 0.29f, h * 0.985f)
            cubicTo(w * 0.15f, h * 0.97f, w * 0.055f, h * 0.985f, w * 0.022f, h * 0.89f)
            cubicTo(-w * 0.005f, h * 0.79f, w * 0.018f, h * 0.69f, w * 0.008f, h * 0.55f)
            cubicTo(0f, h * 0.42f, w * 0.018f, h * 0.31f, w * 0.008f, h * 0.19f)
            cubicTo(0f, h * 0.09f, w * 0.03f, h * 0.025f, w * 0.10f, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

private fun buildPlaylistCollageSongs(songs: List<AudioFile>): List<AudioFile> {
    if (songs.isEmpty()) return emptyList()
    val result = ArrayList<AudioFile>(6)
    val artworkKeys = HashSet<String>(6)
    songs.forEach { song ->
        val artworkKey = song.coverKey
        if (artworkKeys.add(artworkKey)) {
            result += song
        }
        if (result.size == 6) return result
    }
    return result
}

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
    val frameShape = PlaylistOrganicFrameShape

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(0.86f)
            .shadow(
                elevation = 10.dp,
                shape = frameShape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.18f),
                spotColor = accent.copy(alpha = 0.24f),
            )
            .clip(frameShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.26f),
                        colors.surface.copy(alpha = 0.96f),
                        Color.Black.copy(alpha = 0.78f),
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.42f), frameShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (actionsEnabled) onActions else null,
            )
    ) {
        val collageHeight = maxHeight - 64.dp

        Box(
            modifier = Modifier
                .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                .fillMaxWidth()
                .height(collageHeight.coerceAtLeast(96.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(Color.Black.copy(alpha = 0.26f))
        ) {
            if (collageSongs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = accent.copy(alpha = 0.92f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    collageSongs.forEachIndexed { index, song ->
                        val slot = playlistArtworkSlots[index]
                        BitmapImage(
                            key = song.coverKey,
                            contentDescription = if (index == 0) playlist.name else null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .offset(
                                    x = maxWidth * slot.x,
                                    y = maxHeight * slot.y,
                                )
                                .size(
                                    width = maxWidth * slot.width,
                                    height = maxHeight * slot.height,
                                )
                                .zIndex(slot.z)
                                .graphicsLayer { rotationZ = slot.rotation }
                                .clip(RoundedCornerShape(slot.cornerRadius))
                                .border(
                                    width = if (index == 0) 1.2.dp else 0.8.dp,
                                    color = Color.White.copy(alpha = if (index == 0) 0.24f else 0.16f),
                                    shape = RoundedCornerShape(slot.cornerRadius),
                                ),
                            targetWidth = if (index == 0) 512 else 256,
                            targetHeight = if (index == 0) 512 else 256,
                            priority = BitmapRequest.Priority.LOADING_LIST,
                            surface = ArtworkSurface.List,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.20f),
                            colors.surface.copy(alpha = 0.82f),
                            Color.Black.copy(alpha = 0.52f),
                        )
                    )
                )
                .border(0.8.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = playlist.name,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Text(
                    text = stringResource(R.string.playlist_song_count, songCountOverride ?: playlist.songs.size),
                    color = colors.onSurface.copy(alpha = 0.50f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    fontFamily = appFontFamily(),
                )
            }

            if (actionsEnabled) {
                IconButton(
                    onClick = onActions,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.playlist_group_playlist_actions),
                        tint = colors.onSurface.copy(alpha = 0.44f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Spacer(Modifier.width(32.dp))
            }
        }
    }
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
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = themeColors()
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title, fontFamily = appFontFamily()) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name_hint)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    cursorColor = colors.primary
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !submitting,
                onClick = { onConfirm(name.trim()) },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) { Text(stringResource(R.string.playlist_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel_action)) }
        }
    )
}
