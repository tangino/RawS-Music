package com.rawsmusic.ui.playlist

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.isOnlineSongKey
import com.rawsmusic.core.common.prefs.PlayedPlaylistEntry
import com.rawsmusic.core.common.prefs.PlayedPlaylistManager
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.repository.SongAttitudeRepository
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.icon.extended.CloudFill

/**
 * 歌单列表主界面，采用 OrBitPlayer 的高质感歌单布局风格：
 * 1. 顶部二级分类 Chip 导航轨道（全部、自定义分组、本地、网易云、QQ音乐、酷狗、酷我、咪咕，带平台彩色圆点、数量 Badge，右侧新建分组与管理微按钮）；
 * 2. 当前播放歌单专属置顶卡片（CurrentPlayedPlaylistCard）与上次播放置顶卡片（PreviousPlayedPlaylistCard）；
 * 3. 本地歌单与各平台收藏歌单分组折叠卡片列表（对应主题色 4dp 竖条、数量 Badge、右侧“导入歌单/新建歌单/去广场”快捷操作、平滑旋转折叠箭头）；
 * 4. 精致横向条形卡片（左侧封面 54~60dp，中间标题与 [本地]/[网络]/[混合]/[网易云音乐] 标签徽标、首数统计，右侧快捷播放圆形按钮与独立 ⋮ 更多菜单）。
 */
@Composable
fun PlaylistScreen(
    playlistStore: PlaylistStore,
    librarySongs: List<AudioFile> = emptyList(),
    isPlaying: Boolean = false,
    onTogglePlayEngine: () -> Unit = {},
    onBack: () -> Unit,
    virtualListState: ComposeVirtualListState? = null,
    onPlaylistClick: (String, String) -> Unit,
    onOnlinePlaylistClick: (OnlinePlatform, String) -> Unit = { _, _ -> },
    onNavigateToOnlineSquare: (OnlinePlatform) -> Unit = {},
    onPlayLocalPlaylist: (UserPlaylist) -> Unit = {},
    onPlayOnlinePlaylist: (OnlinePlaylistFavoriteEntity) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    val playlists by playlistStore.playlists.collectAsState()
    val onlineFavorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()
    val allLibrarySongs = if (librarySongs.isNotEmpty()) librarySongs else MusicRepository.songs.collectAsState().value
    val dislikedKeys by SongAttitudeRepository.dislikedKeys.collectAsState()
    val onlineFavoriteKeys by SongAttitudeRepository.onlineFavoriteKeys.collectAsState()
    val playlistGroups by PlaylistGroupStore.groups.collectAsState()
    val playlistGroupMap by PlaylistGroupStore.playlistGroups.collectAsState()

    // 当前播放歌单 & 上次播放歌单
    val currentPlayedPlaylist by PlayedPlaylistManager.currentPlaylist.collectAsState()
    val previousPlayedPlaylist by PlayedPlaylistManager.previousPlaylist.collectAsState()

    LaunchedEffect(Unit) {
        SongAttitudeRepository.refresh(context)
        PlaylistGroupStore.refresh(context)
        OnlinePlaylistFavoriteStore.refresh(context)
    }

    // 弹窗状态
    var createDialog by remember { mutableStateOf(false) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var exportTargetPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var clearDislikesConfirm by remember { mutableStateOf(false) }
    var showCloudPlaylistSyncDialog by remember { mutableStateOf(false) }
    val groupChrome = rememberPlaylistGroupChromeState()

    // 布局模式：仅在非 Compact 紧凑宽度（屏幕宽度 >= 600dp 的大屏/平板/展开态）才采用双列布局，手机单列
    val windowLayoutProfile = LocalRawWindowLayoutProfile.current
    val useTabletLayout = windowLayoutProfile.widthClass != com.rawsmusic.core.ui.scene.RawWindowWidthClass.Compact

    // 喜欢与踩伪歌单
    val favoriteKeyCount = remember(playlists, onlineFavoriteKeys) {
        val favPlaylistSongs = playlists.firstOrNull { it.isFavorites }?.songs?.size ?: 0
        maxOf(favPlaylistSongs, (playlistStore.favoriteSongKeys() + onlineFavoriteKeys).size)
    }
    val attitudePlaylists = remember(playlists, dislikedKeys, allLibrarySongs) {
        listOfNotNull(
            attitudePlaylistView(context, playlistStore, allLibrarySongs, ATTITUDE_FAVORITES_PLAYLIST_ID, dislikedKeys),
            attitudePlaylistView(context, playlistStore, allLibrarySongs, ATTITUDE_DISLIKED_PLAYLIST_ID, dislikedKeys),
        )
    }
    val filteredLocalPlaylists = remember(playlists) { playlists.filterNot { it.isFavorites || it.isDisliked } }
    val allGroupNames = remember(playlistGroups) {
        playlistGroups.map { it.name }.ifEmpty { listOf(DEFAULT_PLAYLIST_GROUP) }
    }

    // 各平台在线歌单拆分
    val neteaseFavorites = remember(onlineFavorites) { onlineFavorites.filter { it.platform.equals("NETEASE", ignoreCase = true) } }
    val qqFavorites = remember(onlineFavorites) { onlineFavorites.filter { it.platform.equals("QQ", ignoreCase = true) } }
    val kugouFavorites = remember(onlineFavorites) { onlineFavorites.filter { it.platform.equals("KUGOU", ignoreCase = true) } }
    val kuwoFavorites = remember(onlineFavorites) { onlineFavorites.filter { it.platform.equals("KUWO", ignoreCase = true) } }
    val miguFavorites = remember(onlineFavorites) { onlineFavorites.filter { it.platform.equals("MIGU", ignoreCase = true) } }

    // 二级分类导航 Chips 构造
    var selectedPlaylistGroup by remember { mutableStateOf("ALL") }
    val localTotalCount = filteredLocalPlaylists.size + attitudePlaylists.size
    val totalPlaylistsCount = localTotalCount + onlineFavorites.size
    val totalSongsCount = playlists.sumOf { it.songs.size } + onlineFavorites.sumOf { it.trackCount }

    val filterCategories = remember(playlistGroups, filteredLocalPlaylists, onlineFavorites, localTotalCount, neteaseFavorites, qqFavorites, kugouFavorites, kuwoFavorites, miguFavorites) {
        buildList {
            add(Triple("ALL", "全部", totalPlaylistsCount))
            // 用户自定义分组
            playlistGroups.forEach { grp ->
                val count = filteredLocalPlaylists.count { playlistGroupMap[it.id] == grp.name } +
                        onlineFavorites.count { it.groupName == grp.name }
                if (count > 0) {
                    add(Triple("GRP:${grp.name}", grp.name, count))
                }
            }
            if (localTotalCount > 0) {
                add(Triple("LOCAL", "本地", localTotalCount))
            }
            if (neteaseFavorites.isNotEmpty()) {
                add(Triple("NETEASE", "网易云", neteaseFavorites.size))
            }
            if (qqFavorites.isNotEmpty()) {
                add(Triple("QQ", "QQ音乐", qqFavorites.size))
            }
            if (kugouFavorites.isNotEmpty()) {
                add(Triple("KUGOU", "酷狗", kugouFavorites.size))
            }
            if (kuwoFavorites.isNotEmpty()) {
                add(Triple("KUWO", "酷我", kuwoFavorites.size))
            }
            if (miguFavorites.isNotEmpty()) {
                add(Triple("MIGU", "咪咕", miguFavorites.size))
            }
        }
    }

    // 分组折叠状态：存储已折叠的 groupKey，如 "LOCAL", "NETEASE", "QQ" 等
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }

    // 顶栏右侧快捷操作
    val headerTrailingAction: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { showCloudPlaylistSyncDialog = true },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Regular.CloudFill,
                    contentDescription = "歌单多端云同步",
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (dislikedKeys.isNotEmpty()) {
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
    }

    // 处理在线歌单导入为本地
    val handleImportOnlineToLocal: (OnlinePlaylistFavoriteEntity) -> Unit = { op ->
        scope.launch {
            AppNoticeBus.post("正在导入为本地歌单...", AppNoticeIcon.PLAYLIST)
            val platform = OnlinePlatform.fromId(op.platform)
            if (platform != null) {
                val repo = OnlineMusicRepository.getInstance()
                repo.getPlaylistDetail(op.remoteId, platform).onSuccess { (detail, songs) ->
                    val pl = playlistStore.createPlaylist(detail.title.ifBlank { op.title })
                    if (pl != null) {
                        val playlistSongs = songs.map { OnlineSourceMediaMapper.toUserPlaylistSong(it) }
                        playlistStore.addOnlineSongsToPlaylistAndReportAddedKeys(pl.id, playlistSongs)
                        AppNoticeBus.post("已成功导入为本地歌单「${pl.name}」", AppNoticeIcon.PLAYLIST)
                    } else {
                        AppNoticeBus.error("创建本地歌单失败")
                    }
                }.onFailure {
                    AppNoticeBus.error("导入歌单失败: ${it.localizedMessage}")
                }
            }
        }
    }

    val listState = rememberLazyListState()

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_playlists),
        sceneId = NavScene.PLAYLISTS.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_playlists, totalPlaylistsCount, totalSongsCount),
        onBack = onBack,
        onCreatePlaylist = { createDialog = true },
        onImportPlaylist = { importDialog = true },
        headerTrailingContent = headerTrailingAction,
        virtualListState = virtualListState,
        contentOverlap = 0.dp
    ) { topPadding, backdropSource ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropSource)
                .padding(top = topPadding)
        ) {
            // ==========================================
            // 优雅二级分类过滤导航条 (Chip 轨道)
            // ==========================================
            PlaylistCategoryChipsBar(
                categories = filterCategories,
                selectedCategory = selectedPlaylistGroup,
                onSelectCategory = { selectedPlaylistGroup = it },
                onCreateGroup = { groupChrome.createGroupDialog = true },
                onManageGroups = { groupChrome.groupMenuTarget = allGroupNames.firstOrNull() ?: DEFAULT_PLAYLIST_GROUP },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // ==========================================
            // 计算当前筛选下的本地与在线歌单集合
            // ==========================================
            val targetCustomGroupName = if (selectedPlaylistGroup.startsWith("GRP:")) selectedPlaylistGroup.removePrefix("GRP:") else null
            val currentLocalList = remember(filteredLocalPlaylists, selectedPlaylistGroup, targetCustomGroupName, playlistGroupMap) {
                when {
                    targetCustomGroupName != null -> filteredLocalPlaylists.filter { (playlistGroupMap[it.id] ?: DEFAULT_PLAYLIST_GROUP) == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "LOCAL") -> filteredLocalPlaylists
                    else -> emptyList()
                }
            }
            val showFavInCurrent = (selectedPlaylistGroup in listOf("ALL", "LOCAL") || targetCustomGroupName == DEFAULT_PLAYLIST_GROUP)
            val showDislikedInCurrent = (selectedPlaylistGroup in listOf("ALL", "LOCAL") || targetCustomGroupName == DEFAULT_PLAYLIST_GROUP) && dislikedKeys.isNotEmpty()
            val activeLocalCount = currentLocalList.size + (if (showFavInCurrent) 1 else 0) + (if (showDislikedInCurrent) 1 else 0)

            val currentNeteaseList = remember(neteaseFavorites, selectedPlaylistGroup, targetCustomGroupName) {
                when {
                    targetCustomGroupName != null -> neteaseFavorites.filter { it.groupName == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "NETEASE") -> neteaseFavorites
                    else -> emptyList()
                }
            }
            val currentQqList = remember(qqFavorites, selectedPlaylistGroup, targetCustomGroupName) {
                when {
                    targetCustomGroupName != null -> qqFavorites.filter { it.groupName == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "QQ") -> qqFavorites
                    else -> emptyList()
                }
            }
            val currentKugouList = remember(kugouFavorites, selectedPlaylistGroup, targetCustomGroupName) {
                when {
                    targetCustomGroupName != null -> kugouFavorites.filter { it.groupName == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "KUGOU") -> kugouFavorites
                    else -> emptyList()
                }
            }
            val currentKuwoList = remember(kuwoFavorites, selectedPlaylistGroup, targetCustomGroupName) {
                when {
                    targetCustomGroupName != null -> kuwoFavorites.filter { it.groupName == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "KUWO") -> kuwoFavorites
                    else -> emptyList()
                }
            }
            val currentMiguList = remember(miguFavorites, selectedPlaylistGroup, targetCustomGroupName) {
                when {
                    targetCustomGroupName != null -> miguFavorites.filter { it.groupName == targetCustomGroupName }
                    selectedPlaylistGroup in listOf("ALL", "MIGU") -> miguFavorites
                    else -> emptyList()
                }
            }

            val currentTotalVisible = activeLocalCount + currentNeteaseList.size + currentQqList.size + currentKugouList.size + currentKuwoList.size + currentMiguList.size

            if (currentTotalVisible == 0) {
                // 空状态
                PlaylistEmptyView(
                    selectedGroup = selectedPlaylistGroup,
                    customGroupName = targetCustomGroupName,
                    onCreatePlaylist = { createDialog = true },
                    onImportPlaylist = { importDialog = true },
                    onNavigateToSquare = { onNavigateToOnlineSquare(OnlinePlatform.NETEASE) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // ==========================================
                    // 1. 本地歌单列表 (LOCAL PLAYLISTS)
                    // ==========================================
                    if (activeLocalCount > 0) {
                        val isLocalCollapsed = collapsedGroups.contains("LOCAL")

                        item(key = "header_local") {
                            PlaylistSectionHeader(
                                title = "本地歌单",
                                count = activeLocalCount,
                                accentColor = colors.primary,
                                isCollapsed = isLocalCollapsed,
                                onToggleCollapse = {
                                    collapsedGroups = if (isLocalCollapsed) collapsedGroups - "LOCAL" else collapsedGroups + "LOCAL"
                                },
                                actionContent = {
                                    TextButton(
                                        onClick = { importDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(painter = painterResource(R.drawable.ic_play_list_add_fill), contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("导入歌单", fontSize = 11.5.sp, color = colors.primary, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
                                    }
                                    TextButton(
                                        onClick = { createDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("新建歌单", fontSize = 11.5.sp, color = colors.primary, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
                                    }
                                }
                            )
                        }

                        if (!isLocalCollapsed) {
                            val favPlaylist = attitudePlaylists.firstOrNull { it.id == ATTITUDE_FAVORITES_PLAYLIST_ID }
                            val disPlaylist = attitudePlaylists.firstOrNull { it.id == ATTITUDE_DISLIKED_PLAYLIST_ID }

                            val favFirstCover = favPlaylist?.songs?.firstOrNull()?.let { s ->
                                s.albumArtPath.ifBlank { allLibrarySongs.find { it.id == s.id }?.coverKey }
                            }
                            val disFirstCover = disPlaylist?.songs?.firstOrNull()?.let { s ->
                                s.albumArtPath.ifBlank { allLibrarySongs.find { it.id == s.id }?.coverKey }
                            }

                            if (useTabletLayout) {
                                // 平板模式：伪歌单并排，自建歌单 chunked(2)
                                if (showFavInCurrent && showDislikedInCurrent && favPlaylist != null && disPlaylist != null) {
                                    item(key = "attitude_dual") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            FavoritePlaylistItemCard(
                                                title = favPlaylist.name,
                                                songCount = favoriteKeyCount,
                                                coverKey = favFirstCover,
                                                onClick = { onPlaylistClick(favPlaylist.id, favPlaylist.name) },
                                                onPlayAll = { onPlayLocalPlaylist(favPlaylist) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            DislikedPlaylistItemCard(
                                                title = disPlaylist.name,
                                                songCount = dislikedKeys.size,
                                                coverKey = disFirstCover,
                                                onClick = { onPlaylistClick(disPlaylist.id, disPlaylist.name) },
                                                onPlayAll = { onPlayLocalPlaylist(disPlaylist) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                } else {
                                    if (showFavInCurrent && favPlaylist != null) {
                                        item(key = "attitude_fav") {
                                            FavoritePlaylistItemCard(
                                                title = favPlaylist.name,
                                                songCount = favoriteKeyCount,
                                                coverKey = favFirstCover,
                                                onClick = { onPlaylistClick(favPlaylist.id, favPlaylist.name) },
                                                onPlayAll = { onPlayLocalPlaylist(favPlaylist) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                    if (showDislikedInCurrent && disPlaylist != null) {
                                        item(key = "attitude_dis") {
                                            DislikedPlaylistItemCard(
                                                title = disPlaylist.name,
                                                songCount = dislikedKeys.size,
                                                coverKey = disFirstCover,
                                                onClick = { onPlaylistClick(disPlaylist.id, disPlaylist.name) },
                                                onPlayAll = { onPlayLocalPlaylist(disPlaylist) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                val chunks = currentLocalList.chunked(2)
                                items(chunks, key = { chunk -> "local_chunk_${chunk.joinToString("_") { it.id }}" }) { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val p1 = pair[0]
                                        val g1 = playlistGroupMap[p1.id] ?: DEFAULT_PLAYLIST_GROUP
                                        LocalPlaylistItemCard(
                                            playlist = p1,
                                            groupName = g1,
                                            librarySongs = allLibrarySongs,
                                            onClick = { onPlaylistClick(p1.id, p1.name) },
                                            onPlay = { onPlayLocalPlaylist(p1) },
                                            onRename = { renameTarget = p1 },
                                            onDelete = { deleteTarget = p1 },
                                            onExport = { exportTargetPlaylist = p1 },
                                            onSetGroup = { groupChrome.moveToGroupTarget = p1 },
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (pair.size > 1) {
                                            val p2 = pair[1]
                                            val g2 = playlistGroupMap[p2.id] ?: DEFAULT_PLAYLIST_GROUP
                                            LocalPlaylistItemCard(
                                                playlist = p2,
                                                groupName = g2,
                                                librarySongs = allLibrarySongs,
                                                onClick = { onPlaylistClick(p2.id, p2.name) },
                                                onPlay = { onPlayLocalPlaylist(p2) },
                                                onRename = { renameTarget = p2 },
                                                onDelete = { deleteTarget = p2 },
                                                onExport = { exportTargetPlaylist = p2 },
                                                onSetGroup = { groupChrome.moveToGroupTarget = p2 },
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                // 手机模式：单列纵向排列
                                if (showFavInCurrent && favPlaylist != null) {
                                    item(key = "local_fav") {
                                        FavoritePlaylistItemCard(
                                            title = favPlaylist.name,
                                            songCount = favoriteKeyCount,
                                            coverKey = favFirstCover,
                                            onClick = { onPlaylistClick(favPlaylist.id, favPlaylist.name) },
                                            onPlayAll = { onPlayLocalPlaylist(favPlaylist) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                if (showDislikedInCurrent && disPlaylist != null) {
                                    item(key = "local_dis") {
                                        DislikedPlaylistItemCard(
                                            title = disPlaylist.name,
                                            songCount = dislikedKeys.size,
                                            coverKey = disFirstCover,
                                            onClick = { onPlaylistClick(disPlaylist.id, disPlaylist.name) },
                                            onPlayAll = { onPlayLocalPlaylist(disPlaylist) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                items(currentLocalList, key = { "local_${it.id}" }) { playlist ->
                                    val group = playlistGroupMap[playlist.id] ?: DEFAULT_PLAYLIST_GROUP
                                    LocalPlaylistItemCard(
                                        playlist = playlist,
                                        groupName = group,
                                        librarySongs = allLibrarySongs,
                                        onClick = { onPlaylistClick(playlist.id, playlist.name) },
                                        onPlay = { onPlayLocalPlaylist(playlist) },
                                        onRename = { renameTarget = playlist },
                                        onDelete = { deleteTarget = playlist },
                                        onExport = { exportTargetPlaylist = playlist },
                                        onSetGroup = { groupChrome.moveToGroupTarget = playlist },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // ==========================================
                    // 2. 各平台在线收藏歌单列表 (ONLINE PLATFORMS)
                    // ==========================================
                    val platformsWithFavorites = listOf(
                        Triple(OnlinePlatform.NETEASE, currentNeteaseList, Color(0xFFE60026)),
                        Triple(OnlinePlatform.QQ, currentQqList, Color(0xFF1ECF96)),
                        Triple(OnlinePlatform.KUGOU, currentKugouList, Color(0xFF0088FF)),
                        Triple(OnlinePlatform.KUWO, currentKuwoList, Color(0xFFFF9500)),
                        Triple(OnlinePlatform.MIGU, currentMiguList, Color(0xFFE91E63))
                    )

                    platformsWithFavorites.forEach { (platform, favList, brandColor) ->
                        val groupKey = platform.name.uppercase()
                        val shouldRenderPlatform = (selectedPlaylistGroup == "ALL" && favList.isNotEmpty()) ||
                                (selectedPlaylistGroup == groupKey) ||
                                (selectedPlaylistGroup.startsWith("GRP:") && favList.isNotEmpty())

                        if (shouldRenderPlatform && favList.isNotEmpty()) {
                            val isPlatformCollapsed = collapsedGroups.contains(groupKey)
                            val platformDisplayName = when (platform) {
                                OnlinePlatform.NETEASE -> "网易云音乐"
                                OnlinePlatform.QQ -> "QQ 音乐"
                                OnlinePlatform.KUGOU -> "酷狗音乐"
                                OnlinePlatform.KUWO -> "酷我音乐"
                                OnlinePlatform.MIGU -> "咪咕音乐"
                            }

                            item(key = "header_online_${platform.name}") {
                                PlaylistSectionHeader(
                                    title = "$platformDisplayName 收藏歌单",
                                    count = favList.size,
                                    accentColor = brandColor,
                                    isCollapsed = isPlatformCollapsed,
                                    onToggleCollapse = {
                                        collapsedGroups = if (isPlatformCollapsed) collapsedGroups - groupKey else collapsedGroups + groupKey
                                    },
                                    actionContent = {
                                        TextButton(
                                            onClick = { onNavigateToOnlineSquare(platform) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("去广场", fontSize = 11.5.sp, color = brandColor, fontWeight = FontWeight.Medium, fontFamily = appFontFamily())
                                        }
                                    }
                                )
                            }

                            if (!isPlatformCollapsed) {
                                if (useTabletLayout) {
                                    val chunks = favList.chunked(2)
                                    items(chunks, key = { chunk -> "online_chunk_${platform.name}_${chunk.joinToString("_") { it.remoteId }}" }) { pair ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            val op1 = pair[0]
                                            OnlinePlaylistItemCard(
                                                entity = op1,
                                                brandColor = brandColor,
                                                onClick = { onOnlinePlaylistClick(platform, op1.remoteId) },
                                                onPlay = { onPlayOnlinePlaylist(op1) },
                                                onShowDetail = { onOnlinePlaylistClick(platform, op1.remoteId) },
                                                onRemoveFavorite = {
                                                    scope.launch {
                                                        OnlinePlaylistFavoriteStore.remove(context, op1.playlistKey)
                                                        AppNoticeBus.post("已取消收藏「${op1.title}」", AppNoticeIcon.PLAYLIST)
                                                    }
                                                },
                                                onImportToLocal = { handleImportOnlineToLocal(op1) },
                                                onSetGroup = { groupChrome.moveOnlineToGroupTarget = op1 },
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (pair.size > 1) {
                                                val op2 = pair[1]
                                                OnlinePlaylistItemCard(
                                                    entity = op2,
                                                    brandColor = brandColor,
                                                    onClick = { onOnlinePlaylistClick(platform, op2.remoteId) },
                                                    onPlay = { onPlayOnlinePlaylist(op2) },
                                                    onShowDetail = { onOnlinePlaylistClick(platform, op2.remoteId) },
                                                    onRemoveFavorite = {
                                                        scope.launch {
                                                            OnlinePlaylistFavoriteStore.remove(context, op2.playlistKey)
                                                            AppNoticeBus.post("已取消收藏「${op2.title}」", AppNoticeIcon.PLAYLIST)
                                                        }
                                                    },
                                                    onImportToLocal = { handleImportOnlineToLocal(op2) },
                                                    onSetGroup = { groupChrome.moveOnlineToGroupTarget = op2 },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            } else {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                } else {
                                    items(favList, key = { "online_${it.playlistKey}" }) { entity ->
                                        OnlinePlaylistItemCard(
                                            entity = entity,
                                            brandColor = brandColor,
                                            onClick = { onOnlinePlaylistClick(platform, entity.remoteId) },
                                            onPlay = { onPlayOnlinePlaylist(entity) },
                                            onShowDetail = { onOnlinePlaylistClick(platform, entity.remoteId) },
                                            onRemoveFavorite = {
                                                scope.launch {
                                                    OnlinePlaylistFavoriteStore.remove(context, entity.playlistKey)
                                                    AppNoticeBus.post("已取消收藏「${entity.title}」", AppNoticeIcon.PLAYLIST)
                                                }
                                            },
                                            onImportToLocal = { handleImportOnlineToLocal(entity) },
                                            onSetGroup = { groupChrome.moveOnlineToGroupTarget = entity },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(110.dp))
                    }
                }
            }
        }
    }

    // 分组管理与操作弹窗 Host
    PlaylistGroupDialogHost(
        state = groupChrome,
        groupNames = allGroupNames,
        playlistGroupMap = playlistGroupMap,
        onRenamePlaylist = { renameTarget = it },
        onDeletePlaylist = { deleteTarget = it },
    )

    // 新建歌单弹窗
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
                        result.isSuccess -> {
                            createDialog = false
                            AppNoticeBus.post(
                                message = "已创建歌单「$name」",
                                icon = AppNoticeIcon.PLAYLIST,
                            )
                        }
                        else -> {
                            val msg = result.exceptionOrNull()?.message.orEmpty()
                            AppNoticeBus.error("创建歌单失败: $msg")
                        }
                    }
                }
            }
        )
    }

    // 重命名歌单弹窗
    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initialName = target.name,
            submitting = false,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                scope.launch {
                    playlistStore.renamePlaylist(target.id, newName)
                    renameTarget = null
                    AppNoticeBus.post(
                        message = "已重命名歌单",
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                }
            }
        )
    }

    // 删除歌单确认弹窗
    deleteTarget?.let { target ->
        PlaylistConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            message = stringResource(R.string.playlist_delete_message, target.name),
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    playlistStore.deletePlaylist(target.id)
                    deleteTarget = null
                    AppNoticeBus.post(
                        message = "已删除歌单",
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                }
            }
        )
    }

    // 清空不喜欢确认弹窗
    if (clearDislikesConfirm) {
        PlaylistConfirmDialog(
            title = stringResource(R.string.playlist_attitude_clear_dislikes),
            message = stringResource(R.string.playlist_attitude_clear_dislikes_summary),
            onDismiss = { clearDislikesConfirm = false },
            onConfirm = {
                scope.launch {
                    SongAttitudeRepository.clearAllDislikes(context)
                    clearDislikesConfirm = false
                    AppNoticeBus.post(
                        message = context.getString(R.string.playlist_attitude_cleared),
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                }
            }
        )
    }

    // 导出歌单弹窗
    exportTargetPlaylist?.let { target ->
        ExportPlaylistDialog(
            playlist = target,
            onDismiss = { exportTargetPlaylist = null }
        )
    }

    // 导入歌单弹窗
    if (importDialog) {
        ImportPlaylistDialog(
            librarySongs = allLibrarySongs,
            onDismiss = { importDialog = false },
            onImportSuccess = { pl ->
                importDialog = false
                AppNoticeBus.post("已成功导入歌单「${pl.name}」", AppNoticeIcon.PLAYLIST)
            }
        )
    }

    // 多端歌单云同步弹窗
    if (showCloudPlaylistSyncDialog) {
        CloudPlaylistSyncDialog(
            onDismiss = { showCloudPlaylistSyncDialog = false }
        )
    }
}

/**
 * 优雅二级分类过滤导航条 (Chip 轨道)
 */
@Composable
private fun PlaylistCategoryChipsBar(
    categories: List<Triple<String, String, Int>>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onManageGroups: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = 0.30f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { (groupId, groupName, count) ->
                val isSelected = selectedCategory == groupId

                val badgeColor = when (groupId) {
                    "QQ" -> Color(0xFF1ECF96)
                    "NETEASE" -> Color(0xFFE60026)
                    "KUGOU" -> Color(0xFF0088FF)
                    "KUWO" -> Color(0xFFFF9500)
                    "MIGU" -> Color(0xFFE91E63)
                    "LOCAL" -> colors.primary
                    else -> null
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) colors.primary.copy(alpha = 0.18f) else Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectCategory(groupId) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (badgeColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(badgeColor, CircleShape)
                            )
                        }
                        Text(
                            text = groupName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.primary else colors.onSurface.copy(alpha = 0.7f),
                            fontFamily = appFontFamily()
                        )
                        if (count > 0) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) colors.primary.copy(alpha = 0.22f) else colors.onSurface.copy(alpha = 0.10f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) colors.primary else colors.onSurface.copy(alpha = 0.85f),
                                    fontFamily = appFontFamily()
                                )
                            }
                        }
                    }
                }
            }

            // 垂直细分割线
            Box(
                modifier = Modifier
                    .height(14.dp)
                    .width(1.dp)
                    .background(colors.onSurface.copy(alpha = 0.15f))
            )

            // 新建分组微按钮
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = colors.surface.copy(alpha = 0.40f),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onCreateGroup() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "新建分组",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        fontFamily = appFontFamily()
                    )
                }
            }

            // 分组管理微按钮
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = colors.surface.copy(alpha = 0.40f),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { onManageGroups() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = null,
                        tint = colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "管理",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface.copy(alpha = 0.7f),
                        fontFamily = appFontFamily()
                    )
                }
            }
        }
    }
}

/**
 * 分组 Header 条（带平台品牌色竖条、标题、数量 Badge、操作按钮与折叠动画箭头）
 */
@Composable
private fun PlaylistSectionHeader(
    title: String,
    count: Int,
    accentColor: Color,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    actionContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val rotationAngle by animateFloatAsState(
        targetValue = if (isCollapsed) -90f else 0f,
        label = "collapse_anim"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggleCollapse() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontFamily = appFontFamily()
            )
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "$count",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            actionContent()
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (isCollapsed) "展开" else "折叠",
                tint = colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotationAngle)
            )
        }
    }
}

/**
 * 当前播放歌单专属置顶卡片
 */
@Composable
private fun CurrentPlayedPlaylistCard(
    curPlay: PlayedPlaylistEntry,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val accent = colors.primary

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.6f)),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.14f),
                            Color.Transparent
                        )
                    )
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧封面
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface),
                contentAlignment = Alignment.Center
            ) {
                val cover = curPlay.coverUrl
                if (!cover.isNullOrBlank()) {
                    BitmapImage(
                        key = cover,
                        contentDescription = curPlay.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 160,
                        targetHeight = 160,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_list_fill),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 类别徽章
                val badgeText = if (curPlay.isOnline) {
                    when (curPlay.platformId?.uppercase()) {
                        "NETEASE" -> "网易"
                        "QQ" -> "QQ"
                        "KUGOU" -> "酷狗"
                        "KUWO" -> "酷我"
                        "MIGU" -> "咪咕"
                        else -> "在线"
                    }
                } else {
                    "本地"
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .background(accent, RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = Color.Black,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = appFontFamily()
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // 中间文字
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = accent.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "当前播放歌单",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            fontFamily = appFontFamily(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (isPlaying) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_equalizer_bars),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = curPlay.name,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.height(2.dp))
                val trackText = curPlay.lastPlayedSongTitle?.let {
                    "$it${if (!curPlay.lastPlayedSongArtist.isNullOrBlank()) " - ${curPlay.lastPlayedSongArtist}" else ""}"
                } ?: "${curPlay.trackCount} 首歌曲"
                Text(
                    text = trackText,
                    color = colors.onSurface.copy(alpha = 0.65f),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }

            // 播放/暂停控制按钮
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = "播放/暂停",
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * 上次播放歌单专属置顶卡片
 */
@Composable
private fun PreviousPlayedPlaylistCard(
    prevPlay: PlayedPlaylistEntry,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surface.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface),
                contentAlignment = Alignment.Center
            ) {
                val cover = prevPlay.coverUrl
                if (!cover.isNullOrBlank()) {
                    BitmapImage(
                        key = cover,
                        contentDescription = prevPlay.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 160,
                        targetHeight = 160,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_speed_fill),
                        contentDescription = null,
                        tint = colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.onSurface.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "上次播放",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface.copy(alpha = 0.7f),
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = prevPlay.name,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
                Spacer(Modifier.height(2.dp))
                val prevDesc = prevPlay.lastPlayedSongTitle?.let {
                    "上次播放: $it${if (!prevPlay.lastPlayedSongArtist.isNullOrBlank()) " - ${prevPlay.lastPlayedSongArtist}" else ""}"
                } ?: "${prevPlay.trackCount} 首歌曲"
                Text(
                    text = prevDesc,
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = "继续播放",
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 我喜欢的音乐条形卡片
 */
@Composable
private fun FavoritePlaylistItemCard(
    title: String,
    songCount: Int,
    coverKey: String? = null,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFF3366).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (!coverKey.isNullOrBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFFF3366),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFFF3366),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.playlist_song_count, songCount),
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontFamily = appFontFamily()
            )
        }

        if (songCount > 0) {
            IconButton(
                onClick = onPlayAll,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = "播放全部",
                    tint = Color(0xFFFF3366),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * 不喜欢的音乐条形卡片
 */
@Composable
private fun DislikedPlaylistItemCard(
    title: String,
    songCount: Int,
    coverKey: String? = null,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE57373).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            if (!coverKey.isNullOrBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_thumb_down_fill),
                                contentDescription = null,
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_thumb_down_fill),
                    contentDescription = null,
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.playlist_song_count, songCount),
                color = colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontFamily = appFontFamily()
            )
        }

        if (songCount > 0) {
            IconButton(
                onClick = onPlayAll,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = "播放全部",
                    tint = Color(0xFFE57373),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * 自建本地歌单精致横条卡片
 */
@Composable
private fun LocalPlaylistItemCard(
    playlist: UserPlaylist,
    groupName: String,
    librarySongs: List<AudioFile>,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onSetGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()
    var showMenu by remember { mutableStateOf(false) }

    val firstCover = remember(playlist, librarySongs) {
        val customCover = playlist.coverUrl?.takeIf { it.isNotBlank() }
        if (customCover != null) return@remember customCover
        val firstSong = playlist.songs.firstOrNull()
        if (firstSong != null) {
            firstSong.albumArtPath.ifBlank { librarySongs.find { it.id == firstSong.id }?.coverKey }
        } else null
    }

    val totalCount = playlist.songs.size
    val onlineCount = playlist.songs.count { isOnlineSongKey(it.path) }
    val localCount = (totalCount - onlineCount).coerceAtLeast(0)
    val isHybrid = onlineCount > 0 && localCount > 0
    val isPureOnline = onlineCount > 0 && onlineCount == totalCount

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            if (!firstCover.isNullOrBlank()) {
                BitmapImage(
                    key = firstCover,
                    contentDescription = playlist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play_list_fill),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // 文字
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 智能类型 Badge (本地 / 网络 / 混合)
                val (typeColor, typeText) = when {
                    isHybrid -> Color(0xFFFF9100) to "混合"
                    isPureOnline -> Color(0xFF00E5FF) to "网络"
                    else -> Color(0xFF3B82F6) to "本地"
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = typeColor.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = typeText,
                        color = typeColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                if (groupName.isNotBlank() && groupName != DEFAULT_PLAYLIST_GROUP) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.primary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = groupName,
                            color = colors.primary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = appFontFamily(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                val countText = if (isHybrid) {
                    "${totalCount} 首 (网 $onlineCount · 本 $localCount)"
                } else {
                    stringResource(R.string.playlist_song_count, totalCount)
                }
                Text(
                    text = countText,
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }
        }

        // 快捷播放按钮
        IconButton(
            onClick = onPlay,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = "播放",
                tint = colors.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        // 更多操作菜单
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(colors.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("设置分组", color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onSetGroup()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_folder_2_fill), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text("导出歌单", color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onExport()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_playlist_export), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename), color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = colors.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete_title), color = Color(0xFFEF4444), fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_delete_bin_6_fill), contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
    }
}

/**
 * 在线收藏歌单精致横条卡片
 */
@Composable
private fun OnlinePlaylistItemCard(
    entity: OnlinePlaylistFavoriteEntity,
    brandColor: Color,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShowDetail: () -> Unit,
    onRemoveFavorite: () -> Unit,
    onImportToLocal: () -> Unit,
    onSetGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            val cover = entity.coverUrl
            if (!cover.isNullOrBlank()) {
                BitmapImage(
                    key = cover,
                    contentDescription = entity.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 180,
                    targetHeight = 180,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_music_2_fill),
                    contentDescription = null,
                    tint = brandColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // 文字
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.title,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val platformName = when (entity.platform.uppercase()) {
                    "NETEASE" -> "网易云音乐"
                    "QQ" -> "QQ音乐"
                    "KUGOU" -> "酷狗音乐"
                    "KUWO" -> "酷我音乐"
                    "MIGU" -> "咪咕音乐"
                    else -> entity.platform
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = brandColor.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = platformName,
                        color = brandColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = appFontFamily(),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                if (entity.groupName.isNotBlank() && entity.groupName != DEFAULT_PLAYLIST_GROUP) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.primary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = entity.groupName,
                            color = colors.primary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = appFontFamily(),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                val subtitle = buildString {
                    if (!entity.creatorName.isNullOrBlank()) {
                        append(entity.creatorName)
                        append(" · ")
                    }
                    if (entity.trackCount > 0) {
                        append("${entity.trackCount} 首")
                    } else {
                        append("在线歌单")
                    }
                }
                Text(
                    text = subtitle,
                    color = colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily()
                )
            }
        }

        // 快捷播放按钮
        IconButton(
            onClick = onPlay,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = "播放全部",
                tint = colors.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        // 更多操作菜单
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "更多",
                    tint = colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(colors.surface)
            ) {
                DropdownMenuItem(
                    text = { Text("设置分组", color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onSetGroup()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_folder_2_fill), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text("查看歌单详情", color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onShowDetail()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_play_list_fill), contentDescription = null, tint = colors.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text("导入为本地歌单", color = colors.onSurface, fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onImportToLocal()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_play_list_add_fill), contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                )
                DropdownMenuItem(
                    text = { Text("取消收藏", color = Color(0xFFEF4444), fontFamily = appFontFamily()) },
                    onClick = {
                        showMenu = false
                        onRemoveFavorite()
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.FavoriteBorder, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    }
                )
            }
        }
    }
}

/**
 * 筛选为空状态展示
 */
@Composable
private fun PlaylistEmptyView(
    selectedGroup: String,
    customGroupName: String?,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onNavigateToSquare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = themeColors()

    val (title, desc) = when {
        customGroupName != null -> "分组「$customGroupName」暂无歌单" to "可点击歌单右侧「⋮」菜单选择「设置分组」将其归入此分组"
        selectedGroup == "LOCAL" -> "暂无本地自建歌单" to "点击下方按钮即可创建或导入专属本地歌单"
        selectedGroup == "NETEASE" -> "暂无收藏的网易云音乐歌单" to "前往在线音乐广场点击收藏即可在此汇聚"
        selectedGroup == "QQ" -> "暂无收藏的QQ音乐歌单" to "前往在线音乐广场点击收藏即可在此汇聚"
        selectedGroup == "KUGOU" -> "暂无收藏的酷狗音乐歌单" to "前往在线音乐广场点击收藏即可在此汇聚"
        selectedGroup == "KUWO" -> "暂无收藏的酷我音乐歌单" to "前往在线音乐广场点击收藏即可在此汇聚"
        selectedGroup == "MIGU" -> "暂无收藏的咪咕音乐歌单" to "前往在线音乐广场点击收藏即可在此汇聚"
        else -> "暂无任何歌单" to "您可以创建本地歌单或在在线广场收藏歌单"
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_play_list_fill),
                contentDescription = null,
                tint = colors.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedGroup in listOf("LOCAL", "ALL") || customGroupName != null) {
                    TextButton(
                        onClick = onCreatePlaylist,
                        colors = ButtonDefaults.textButtonColors(containerColor = colors.primary.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建歌单", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = appFontFamily())
                    }
                    TextButton(
                        onClick = onImportPlaylist,
                        colors = ButtonDefaults.textButtonColors(containerColor = colors.primary.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_play_list_add_fill), contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入歌单", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = appFontFamily())
                    }
                } else {
                    TextButton(
                        onClick = onNavigateToSquare,
                        colors = ButtonDefaults.textButtonColors(containerColor = colors.primary.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("前往在线广场", color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = appFontFamily())
                    }
                }
            }
        }
    }
}

/**
 * 歌单名称输入弹窗 (新建 / 重命名)
 */
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
                label = { Text(stringResource(R.string.playlist_name_hint), fontFamily = appFontFamily()) },
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
            ) {
                Text(stringResource(R.string.playlist_confirm_action), fontFamily = appFontFamily())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.playlist_cancel_action), fontFamily = appFontFamily())
            }
        }
    )
}

/**
 * 通用确认弹窗 (删除 / 清空踩)
 */
@Composable
private fun PlaylistConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = appFontFamily()) },
        text = { Text(message, fontFamily = appFontFamily()) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
            ) {
                Text(stringResource(R.string.playlist_confirm_action), fontFamily = appFontFamily())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.playlist_cancel_action), fontFamily = appFontFamily())
            }
        }
    )
}
