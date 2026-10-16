package com.rawsmusic.ui.playlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.R
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.SongAttitudeRepository
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import com.rawsmusic.module.online.sync.CloudOnlineFavoriteDto
import com.rawsmusic.module.online.sync.CloudPlaylistBackup
import com.rawsmusic.module.online.sync.CloudPlaylistDto
import com.rawsmusic.module.online.sync.CloudPlaylistJson
import com.rawsmusic.module.online.sync.CloudPlaylistSyncService
import com.rawsmusic.module.online.sync.CloudSyncClient
import com.rawsmusic.module.online.sync.CloudSyncResult
import com.rawsmusic.ui.settings.themeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Playlist

private const val TAG = "CloudPlaylistSyncDialog"

private enum class SyncTab(val title: String) {
    UPLOAD("⬆️ 上传备份"),
    DOWNLOAD("⬇️ 从云端下载"),
}

enum class PlaylistSyncMode(val title: String) {
    MERGE("增量合并"),
    OVERWRITE("全量覆盖"),
}

/**
 * 歌单多端云同步弹窗（遵循 OrBitPlayer 布局体系，UI 样式全面遵循 RawS-Music 规范）。
 */
@Composable
fun CloudPlaylistSyncDialog(
    onDismiss: () -> Unit,
    onSyncCompleted: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = themeColors()

    val playlistStore = remember { PlaylistStore.getInstance(context) }
    val allPlaylists by playlistStore.playlists.collectAsState()
    val localRegularPlaylists = remember(allPlaylists) { allPlaylists.filterNot { it.isFavorites } }
    val onlineFavorites by OnlinePlaylistFavoriteStore.favorites.collectAsState()
    val dislikedKeys by SongAttitudeRepository.dislikedKeys.collectAsState()
    val playlistGroups by PlaylistGroupStore.playlistGroups.collectAsState()

    // 收藏歌曲数（本地红心 + 在线红心）
    val favoriteKeys = remember(allPlaylists) { playlistStore.favoriteSongKeys() }
    val onlineFavoriteKeys by SongAttitudeRepository.onlineFavoriteKeys.collectAsState()
    val totalFavoriteCount = remember(favoriteKeys, onlineFavoriteKeys) {
        (favoriteKeys + onlineFavoriteKeys).distinct().size
    }

    var currentTab by remember { mutableStateOf(SyncTab.UPLOAD) }

    // 账号登录状态与云端节点配置
    var serverUrlInput by remember { mutableStateOf(AppPreferences.CloudSync.serverUrl) }
    var showServerSettings by remember { mutableStateOf(AppPreferences.CloudSync.serverUrl.isBlank()) }
    var usernameInput by remember { mutableStateOf(AppPreferences.CloudSync.username) }
    var passwordInput by remember { mutableStateOf("") }
    var isConfigured by remember { mutableStateOf(CloudSyncClient.isConfigured()) }
    var isAuthExpanded by remember { mutableStateOf(!isConfigured) }
    var isLoggingIn by remember { mutableStateOf(false) }

    // 同步模式状态 (增量合并 / 全量覆盖)
    var uploadSyncMode by remember {
        mutableStateOf(
            if (AppPreferences.CloudSync.uploadMode == CloudSyncClient.MODE_OVERWRITE) {
                PlaylistSyncMode.OVERWRITE
            } else {
                PlaylistSyncMode.MERGE
            },
        )
    }
    var downloadSyncMode by remember { mutableStateOf(PlaylistSyncMode.MERGE) }

    // 上传状态：选中的本地歌单 ID 集合、选中的在线歌单 key 集合、系统喜好与态度
    val selectedLocalUploadIds = remember { mutableStateListOf<String>() }
    val selectedOnlineUploadKeys = remember { mutableStateListOf<String>() }
    var uploadFavoritesChecked by remember { mutableStateOf(true) }
    var uploadDislikedChecked by remember { mutableStateOf(true) }

    // 初始默认全选所有歌单
    LaunchedEffect(localRegularPlaylists, onlineFavorites) {
        if (selectedLocalUploadIds.isEmpty() && localRegularPlaylists.isNotEmpty()) {
            selectedLocalUploadIds.addAll(localRegularPlaylists.map { it.id })
        }
        if (selectedOnlineUploadKeys.isEmpty() && onlineFavorites.isNotEmpty()) {
            selectedOnlineUploadKeys.addAll(onlineFavorites.map { it.playlistKey })
        }
    }

    var isUploading by remember { mutableStateOf(false) }

    // 下载状态：从云端拉取的数据
    var cloudBackupData by remember { mutableStateOf<CloudPlaylistBackup?>(null) }
    var isLoadingCloudData by remember { mutableStateOf(false) }
    var cloudDataError by remember { mutableStateOf<String?>(null) }

    val selectedCloudLocalNames = remember { mutableStateListOf<String>() }
    val selectedCloudOnlineIds = remember { mutableStateListOf<String>() }
    var restoreFavoritesChecked by remember { mutableStateOf(true) }
    var restoreDislikedChecked by remember { mutableStateOf(true) }

    var isRestoring by remember { mutableStateOf(false) }
    var isDeletingCloudPlaylists by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var showClearAllCloudDialog by remember { mutableStateOf(false) }

    // 解析云端本地与在线歌单列表
    val parsedCloudPlaylists = remember(cloudBackupData) {
        cloudBackupData?.let { CloudPlaylistJson.parsePlaylists(it.playlistsJson) } ?: emptyList()
    }
    val cloudSystemPlaylists = remember(parsedCloudPlaylists) {
        parsedCloudPlaylists.filter { CloudPlaylistJson.systemKindOf(it) != null }
    }
    val cloudNormalPlaylists = remember(parsedCloudPlaylists) {
        parsedCloudPlaylists.filter { CloudPlaylistJson.systemKindOf(it) == null && it.name.isNotBlank() }
    }
    val parsedCloudOnlineFavorites = remember(cloudBackupData) {
        cloudBackupData?.let { CloudPlaylistJson.parseOnlineFavorites(it.onlineFavoritesJson) } ?: emptyList()
    }

    // 自动拉取云端数据
    val loadCloudData: () -> Unit = {
        if (isConfigured) {
            isLoadingCloudData = true
            cloudDataError = null
            coroutineScope.launch {
                when (val res = CloudPlaylistSyncService.fetchCloudPlaylists(context)) {
                    is CloudSyncResult.Success -> {
                        cloudBackupData = res.data
                        selectedCloudLocalNames.clear()
                        selectedCloudOnlineIds.clear()
                        val normal = CloudPlaylistJson.parsePlaylists(res.data.playlistsJson)
                            .filter { CloudPlaylistJson.systemKindOf(it) == null && it.name.isNotBlank() }
                        selectedCloudLocalNames.addAll(normal.map { it.name })
                        val onlineDtos = CloudPlaylistJson.parseOnlineFavorites(res.data.onlineFavoritesJson)
                        selectedCloudOnlineIds.addAll(onlineDtos.map { it.id })
                        restoreFavoritesChecked = true
                        restoreDislikedChecked = true
                    }
                    is CloudSyncResult.Failure -> {
                        if (res.code == 404) {
                            cloudBackupData = null
                            cloudDataError = "云端暂无歌单备份"
                        } else {
                            cloudDataError = res.message.ifBlank { "拉取云端歌单失败" }
                        }
                    }
                }
                isLoadingCloudData = false
            }
        }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == SyncTab.DOWNLOAD && cloudBackupData == null && isConfigured) {
            loadCloudData()
        }
    }

    // 确认从云端删除所选歌单弹窗
    if (showDeleteSelectedDialog) {
        val totalSelected = selectedCloudLocalNames.size + selectedCloudOnlineIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "确认从云端删除所选歌单？",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                }
            },
            text = {
                Text(
                    text = "即将从云端账号 [${AppPreferences.CloudSync.username}] 中永久删除勾选的 $totalSelected 个歌单。\n本地设备上的歌单不受任何影响。",
                    fontSize = 12.5.sp,
                    color = colors.secondaryText,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteSelectedDialog = false
                        isDeletingCloudPlaylists = true
                        coroutineScope.launch {
                            val res = CloudPlaylistSyncService.deleteSelectedCloudPlaylists(
                                context = context,
                                localPlaylistNames = selectedCloudLocalNames.toList(),
                                onlinePlaylistIds = selectedCloudOnlineIds.toList(),
                            )
                            when (res) {
                                is CloudSyncResult.Success -> {
                                    AppNoticeBus.post("已从云端删除 ${res.data} 个歌单", icon = AppNoticeIcon.PLAYLIST)
                                    loadCloudData()
                                }
                                is CloudSyncResult.Failure -> {
                                    AppNoticeBus.error("删除失败: ${res.message}")
                                }
                            }
                            isDeletingCloudPlaylists = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("确认删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text("取消", fontSize = 12.sp, color = colors.secondaryText)
                }
            },
            containerColor = colors.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    // 确认清空所有云端歌单弹窗
    if (showClearAllCloudDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllCloudDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "确认清空所有云端歌单？",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                }
            },
            text = {
                Text(
                    text = "此操作将彻底清空账号 [${AppPreferences.CloudSync.username}] 在云端存储的所有歌单备份！\n本地现有歌单不受影响。",
                    fontSize = 12.5.sp,
                    color = colors.secondaryText,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllCloudDialog = false
                        isDeletingCloudPlaylists = true
                        coroutineScope.launch {
                            val res = CloudPlaylistSyncService.clearAllCloudPlaylists(context)
                            when (res) {
                                is CloudSyncResult.Success -> {
                                    AppNoticeBus.post("已清空云端所有歌单", icon = AppNoticeIcon.PLAYLIST)
                                    loadCloudData()
                                }
                                is CloudSyncResult.Failure -> {
                                    AppNoticeBus.error("清空失败: ${res.message}")
                                }
                            }
                            isDeletingCloudPlaylists = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("彻底清空", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllCloudDialog = false }) {
                    Text("取消", fontSize = 12.sp, color = colors.secondaryText)
                }
            },
            containerColor = colors.surface,
            shape = RoundedCornerShape(16.dp),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.25f)),
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 1. 顶部 Header ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Regular.CloudFill,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column {
                            Text(
                                text = "歌单多端云同步",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                            val currentUser = AppPreferences.CloudSync.username
                            Text(
                                text = if (currentUser.isNotBlank()) "账号: $currentUser" else "未登录云同步账号",
                                fontSize = 12.sp,
                                fontWeight = if (currentUser.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                                color = if (currentUser.isNotBlank()) colors.primary else colors.secondaryText,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { showServerSettings = !showServerSettings },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "服务器设置",
                                tint = if (showServerSettings || AppPreferences.CloudSync.serverUrl.isBlank()) {
                                    colors.primary
                                } else {
                                    colors.secondaryText
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = colors.secondaryText,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // ── 1.5 服务器节点设置卡片 (展开/折叠) ──
                if (showServerSettings || AppPreferences.CloudSync.serverUrl.isBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface.copy(alpha = 0.65f),
                        border = BorderStroke(
                            1.dp,
                            if (AppPreferences.CloudSync.serverUrl.isBlank()) {
                                Color(0xFFFFB74D)
                            } else {
                                colors.primary.copy(alpha = 0.35f)
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Regular.CloudFill,
                                        contentDescription = null,
                                        tint = if (AppPreferences.CloudSync.serverUrl.isBlank()) Color(0xFFFFB74D) else colors.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Cloudflare Worker 同步节点",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface,
                                    )
                                }
                                if (AppPreferences.CloudSync.serverUrl.isNotBlank()) {
                                    Text(
                                        text = "已配置",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                } else {
                                    Text(
                                        text = "未配置地址",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFFFFB74D),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Text(
                                text = "请输入部署的 Cloudflare Worker 地址（如 https://xxxx.workers.dev 或自定义域名）",
                                fontSize = 10.5.sp,
                                color = colors.secondaryText,
                                lineHeight = 14.sp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextField(
                                    value = serverUrlInput,
                                    onValueChange = { serverUrlInput = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        val saveRes = CloudSyncClient.saveCredentials(
                                            serverUrl = serverUrlInput,
                                            username = usernameInput,
                                            password = passwordInput,
                                        )
                                        when (saveRes) {
                                            is CloudSyncResult.Success -> {
                                                isConfigured = CloudSyncClient.isConfigured()
                                                AppNoticeBus.post("同步节点地址已保存", icon = AppNoticeIcon.PLAYLIST)
                                                if (serverUrlInput.isNotBlank()) {
                                                    showServerSettings = false
                                                }
                                            }
                                            is CloudSyncResult.Failure -> {
                                                AppNoticeBus.error(saveRes.message)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("保存", fontSize = 12.sp, color = colors.onPrimary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // ── 2. 账号快捷切换 / 登录栏 ──
                if (!isConfigured || isAuthExpanded) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "🔑 登录云同步账号（手机与车机输入相同账号即可互通）",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextField(
                                    value = usernameInput,
                                    onValueChange = { usernameInput = it },
                                    label = "账号",
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                TextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = "密码",
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = {
                                        if (usernameInput.trim().isNotBlank() && passwordInput.trim().isNotBlank()) {
                                            isLoggingIn = true
                                            coroutineScope.launch {
                                                val saveRes = withContext(Dispatchers.IO) {
                                                    CloudSyncClient.saveCredentials(
                                                        serverUrl = serverUrlInput,
                                                        username = usernameInput,
                                                        password = passwordInput,
                                                    )
                                                }
                                                if (saveRes is CloudSyncResult.Failure) {
                                                    AppNoticeBus.error(saveRes.message)
                                                    isLoggingIn = false
                                                    return@launch
                                                }
                                                when (val authRes = CloudSyncClient.authenticate(context)) {
                                                    is CloudSyncResult.Success -> {
                                                        isConfigured = CloudSyncClient.isConfigured()
                                                        isAuthExpanded = false
                                                        AppNoticeBus.post(authRes.data, icon = AppNoticeIcon.PLAYLIST)
                                                        if (currentTab == SyncTab.DOWNLOAD) {
                                                            loadCloudData()
                                                        }
                                                    }
                                                    is CloudSyncResult.Failure -> {
                                                        AppNoticeBus.error("登录失败: ${authRes.message}")
                                                    }
                                                }
                                                isLoggingIn = false
                                            }
                                        } else {
                                            AppNoticeBus.error("请输入账号和密码")
                                        }
                                    },
                                    enabled = !isLoggingIn,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    if (isLoggingIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(
                                            text = "登录 / 自动注册",
                                            fontSize = 12.sp,
                                            color = colors.onPrimary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 3. Tab 切换 (上传 / 下载) ──
                TabRow(
                    selectedTabIndex = currentTab.ordinal,
                    containerColor = colors.surface.copy(alpha = 0.5f),
                    contentColor = colors.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[currentTab.ordinal]),
                            color = colors.primary,
                            height = 3.dp,
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    SyncTab.entries.forEach { tab ->
                        val isSelected = currentTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            text = {
                                Text(
                                    text = tab.title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.5.sp,
                                    color = if (isSelected) colors.primary else colors.secondaryText,
                                )
                            },
                        )
                    }
                }

                // ── 4. Tab 内容区域 ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    when (currentTab) {
                        SyncTab.UPLOAD -> {
                            // ── 上传备份面板 ──
                            val totalLocal = localRegularPlaylists.size
                            val totalOnline = onlineFavorites.size
                            val hasFav = totalFavoriteCount > 0
                            val hasDislike = dislikedKeys.isNotEmpty()
                            val favSelected = (!hasFav || uploadFavoritesChecked)
                            val dislikeSelected = (!hasDislike || uploadDislikedChecked)
                            val isAllSelected = (selectedLocalUploadIds.size == totalLocal) &&
                                (selectedOnlineUploadKeys.size == totalOnline) &&
                                favSelected && dislikeSelected

                            Column(modifier = Modifier.fillMaxSize()) {
                                // 提示与全选栏
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "请勾选需要备份到云端的歌单与喜好：",
                                        fontSize = 12.5.sp,
                                        color = colors.secondaryText,
                                    )
                                    TextButton(
                                        onClick = {
                                            if (isAllSelected) {
                                                selectedLocalUploadIds.clear()
                                                selectedOnlineUploadKeys.clear()
                                                uploadFavoritesChecked = false
                                                uploadDislikedChecked = false
                                            } else {
                                                selectedLocalUploadIds.clear()
                                                selectedLocalUploadIds.addAll(localRegularPlaylists.map { it.id })
                                                selectedOnlineUploadKeys.clear()
                                                selectedOnlineUploadKeys.addAll(onlineFavorites.map { it.playlistKey })
                                                uploadFavoritesChecked = true
                                                uploadDislikedChecked = true
                                            }
                                        },
                                    ) {
                                        Text(
                                            text = if (isAllSelected) "取消全选" else "全选",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.primary,
                                        )
                                    }
                                }

                                if (localRegularPlaylists.isEmpty() && onlineFavorites.isEmpty() && totalFavoriteCount == 0 && dislikedKeys.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("本机暂无可备份的歌单或喜好数据", color = colors.secondaryText, fontSize = 13.sp)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = 12.dp),
                                    ) {
                                        // 0. 系统喜好与态度歌单 (红心与踩)
                                        if (totalFavoriteCount > 0 || dislikedKeys.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "❤️ 系统喜好与态度",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFF3366),
                                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                                )
                                            }

                                            if (totalFavoriteCount > 0) {
                                                item {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (uploadFavoritesChecked) Color(0xFFFF3366).copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                        border = BorderStroke(
                                                            1.dp,
                                                            if (uploadFavoritesChecked) Color(0xFFFF3366).copy(alpha = 0.45f) else colors.primary.copy(alpha = 0.1f),
                                                        ),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { uploadFavoritesChecked = !uploadFavoritesChecked },
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Checkbox(
                                                                checked = uploadFavoritesChecked,
                                                                onCheckedChange = { uploadFavoritesChecked = it },
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(42.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFFF3366).copy(alpha = 0.18f)),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Favorite,
                                                                    contentDescription = null,
                                                                    tint = Color(0xFFFF3366),
                                                                    modifier = Modifier.size(24.dp),
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = "我喜欢的音乐 (红心)",
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = colors.onSurface,
                                                                )
                                                                Text(
                                                                    text = "共 $totalFavoriteCount 首已收藏歌曲 · 云端同步",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            if (dislikedKeys.isNotEmpty()) {
                                                item {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (uploadDislikedChecked) Color(0xFFE57373).copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                        border = BorderStroke(
                                                            1.dp,
                                                            if (uploadDislikedChecked) Color(0xFFE57373).copy(alpha = 0.45f) else colors.primary.copy(alpha = 0.1f),
                                                        ),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { uploadDislikedChecked = !uploadDislikedChecked },
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Checkbox(
                                                                checked = uploadDislikedChecked,
                                                                onCheckedChange = { uploadDislikedChecked = it },
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(42.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFE57373).copy(alpha = 0.18f)),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Close,
                                                                    contentDescription = null,
                                                                    tint = Color(0xFFE57373),
                                                                    modifier = Modifier.size(22.dp),
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(10.dp))
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = "不喜欢的音乐",
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = colors.onSurface,
                                                                )
                                                                Text(
                                                                    text = "共 ${dislikedKeys.size} 首已标记不喜欢歌曲 · 云端同步",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 本地自建歌单列表
                                        if (localRegularPlaylists.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "📁 本地自建歌单 (${localRegularPlaylists.size})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                                )
                                            }
                                            items(localRegularPlaylists, key = { "local_up_${it.id}" }) { p ->
                                                val isChecked = selectedLocalUploadIds.contains(p.id)
                                                val groupName = playlistGroups[p.id]
                                                val hasOnline = p.songs.any { it.isOnlineEntry() }
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isChecked) colors.primary.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isChecked) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.1f),
                                                    ),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isChecked) selectedLocalUploadIds.remove(p.id) else selectedLocalUploadIds.add(p.id)
                                                        },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = {
                                                                if (it) selectedLocalUploadIds.add(p.id) else selectedLocalUploadIds.remove(p.id)
                                                            },
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(42.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(colors.surface),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            val cover = p.coverUrl
                                                            if (!cover.isNullOrBlank()) {
                                                                BitmapImage(
                                                                    key = cover,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            } else {
                                                                Icon(
                                                                    imageVector = MiuixIcons.Regular.Playlist,
                                                                    contentDescription = null,
                                                                    tint = colors.primary,
                                                                    modifier = Modifier.size(24.dp),
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = p.name,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = colors.onSurface,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            ) {
                                                                if (hasOnline) {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp),
                                                                        color = colors.primary.copy(alpha = 0.15f),
                                                                    ) {
                                                                        Text(
                                                                            text = "网络列表",
                                                                            color = colors.primary,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                        )
                                                                    }
                                                                }
                                                                if (!groupName.isNullOrBlank() && groupName != "默认") {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp),
                                                                        color = colors.primary.copy(alpha = 0.15f),
                                                                    ) {
                                                                        Text(
                                                                            text = groupName,
                                                                            color = colors.primary,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                        )
                                                                    }
                                                                }
                                                                Text(
                                                                    text = "${p.songs.size} 首歌曲",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 在线收藏歌单列表
                                        if (onlineFavorites.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "🌐 在线收藏歌单 (${onlineFavorites.size})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                                )
                                            }
                                            items(onlineFavorites, key = { "online_up_${it.playlistKey}" }) { op ->
                                                val opKey = op.playlistKey
                                                val isChecked = selectedOnlineUploadKeys.contains(opKey)
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isChecked) colors.primary.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isChecked) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.1f),
                                                    ),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isChecked) selectedOnlineUploadKeys.remove(opKey) else selectedOnlineUploadKeys.add(opKey)
                                                        },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = {
                                                                if (it) selectedOnlineUploadKeys.add(opKey) else selectedOnlineUploadKeys.remove(opKey)
                                                            },
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(42.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(colors.surface),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (op.coverUrl.isNotBlank()) {
                                                                BitmapImage(
                                                                    key = op.coverUrl,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = op.title,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = colors.onSurface,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            ) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = colors.primary.copy(alpha = 0.15f),
                                                                ) {
                                                                    Text(
                                                                        text = OnlinePlatform.fromId(op.platform)?.displayName ?: op.platform,
                                                                        color = colors.primary,
                                                                        fontSize = 9.sp,
                                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                    )
                                                                }
                                                                Text(
                                                                    text = "${op.trackCount} 首",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 上传同步模式选择卡片 (增量合并 / 全量覆盖)
                                SyncModeSelector(
                                    selectedMode = uploadSyncMode,
                                    onModeSelect = { uploadSyncMode = it },
                                    isUpload = true,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }

                        SyncTab.DOWNLOAD -> {
                            // ── 从云端下载恢复面板 ──
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    val timeStr = cloudBackupData?.let {
                                        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                                        sdf.format(Date(it.updatedAt))
                                    } ?: ""
                                    Text(
                                        text = if (timeStr.isNotBlank()) "备份时间: $timeStr" else "请选择云端歌单：",
                                        fontSize = 11.5.sp,
                                        color = colors.secondaryText,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val hasData = cloudNormalPlaylists.isNotEmpty() || parsedCloudOnlineFavorites.isNotEmpty()
                                        val totalSelected = selectedCloudLocalNames.size + selectedCloudOnlineIds.size

                                        if (cloudBackupData != null && hasData) {
                                            // 全选/反选
                                            TextButton(
                                                onClick = {
                                                    val allLocalCount = cloudNormalPlaylists.size
                                                    val allOnlineCount = parsedCloudOnlineFavorites.size
                                                    if (selectedCloudLocalNames.size == allLocalCount && selectedCloudOnlineIds.size == allOnlineCount) {
                                                        selectedCloudLocalNames.clear()
                                                        selectedCloudOnlineIds.clear()
                                                        restoreFavoritesChecked = false
                                                        restoreDislikedChecked = false
                                                    } else {
                                                        selectedCloudLocalNames.clear()
                                                        selectedCloudOnlineIds.clear()
                                                        selectedCloudLocalNames.addAll(cloudNormalPlaylists.map { it.name })
                                                        selectedCloudOnlineIds.addAll(parsedCloudOnlineFavorites.map { it.id })
                                                        restoreFavoritesChecked = true
                                                        restoreDislikedChecked = true
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp),
                                            ) {
                                                val allSelected = selectedCloudLocalNames.size == cloudNormalPlaylists.size &&
                                                    selectedCloudOnlineIds.size == parsedCloudOnlineFavorites.size
                                                Text(if (allSelected) "取消全选" else "全选", fontSize = 11.sp, color = colors.primary)
                                            }

                                            // 删除勾选的云端歌单
                                            if (totalSelected > 0) {
                                                TextButton(
                                                    onClick = { showDeleteSelectedDialog = true },
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp),
                                                    enabled = !isDeletingCloudPlaylists && !isRestoring,
                                                ) {
                                                    Text("🗑️ 删除已选 ($totalSelected)", fontSize = 11.sp, color = Color(0xFFFF5252))
                                                }
                                            }

                                            // 清空全部云端歌单备份
                                            TextButton(
                                                onClick = { showClearAllCloudDialog = true },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(28.dp),
                                                enabled = !isDeletingCloudPlaylists && !isRestoring,
                                            ) {
                                                Text("⚠️ 清空云端", fontSize = 11.sp, color = Color(0xFFFF5252).copy(alpha = 0.85f))
                                            }
                                        }

                                        IconButton(
                                            onClick = loadCloudData,
                                            modifier = Modifier.size(28.dp),
                                            enabled = !isLoadingCloudData,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "刷新",
                                                tint = colors.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }

                                if (isLoadingCloudData) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(color = colors.primary)
                                    }
                                } else if (cloudDataError != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(cloudDataError ?: "拉取失败", color = colors.secondaryText, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = loadCloudData,
                                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                            ) {
                                                Text("重新加载", color = colors.onPrimary)
                                            }
                                        }
                                    }
                                } else if (cloudBackupData == null || (cloudNormalPlaylists.isEmpty() && parsedCloudOnlineFavorites.isEmpty() && cloudSystemPlaylists.isEmpty())) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("云端暂无可恢复的歌单数据", color = colors.secondaryText, fontSize = 13.sp)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = 12.dp),
                                    ) {
                                        // 云端系统喜好与态度
                                        if (cloudSystemPlaylists.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "❤️ 系统喜好与态度 (${cloudSystemPlaylists.size})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFFF3366),
                                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                                )
                                            }
                                            items(cloudSystemPlaylists, key = { "cloud_sys_${it.name}" }) { p ->
                                                val isFav = CloudPlaylistJson.systemKindOf(p) == CloudPlaylistJson.SystemPlaylistKind.FAVORITE
                                                val isChecked = if (isFav) restoreFavoritesChecked else restoreDislikedChecked
                                                val accentColor = if (isFav) Color(0xFFFF3366) else Color(0xFFE57373)
                                                val icon = if (isFav) Icons.Default.Favorite else Icons.Default.Close
                                                val title = if (isFav) "我喜欢的音乐" else "不喜欢的音乐"
                                                val desc = if (isFav) "恢复后将自动标记为红心喜爱" else "恢复后将自动标记为不喜欢屏蔽"
                                                val badge = if (isFav) "系统红心" else "系统屏蔽"

                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isChecked) accentColor.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isChecked) accentColor.copy(alpha = 0.45f) else colors.primary.copy(alpha = 0.1f),
                                                    ),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isFav) restoreFavoritesChecked = !restoreFavoritesChecked
                                                            else restoreDislikedChecked = !restoreDislikedChecked
                                                        },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = {
                                                                if (isFav) restoreFavoritesChecked = it else restoreDislikedChecked = it
                                                            },
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(36.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(accentColor.copy(alpha = 0.15f)),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            Icon(
                                                                imageVector = icon,
                                                                contentDescription = null,
                                                                tint = accentColor,
                                                                modifier = Modifier.size(20.dp),
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            ) {
                                                                Text(
                                                                    text = title,
                                                                    fontSize = 14.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = colors.onSurface,
                                                                )
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = accentColor.copy(alpha = 0.15f),
                                                                ) {
                                                                    Text(
                                                                        text = badge,
                                                                        color = accentColor,
                                                                        fontSize = 9.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                    )
                                                                }
                                                            }
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            ) {
                                                                Text(
                                                                    text = "${p.songs.size} 首歌曲",
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = colors.secondaryText,
                                                                )
                                                                Text(
                                                                    text = "· $desc",
                                                                    fontSize = 11.sp,
                                                                    color = colors.secondaryText.copy(alpha = 0.8f),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 云端本地自建歌单
                                        if (cloudNormalPlaylists.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "📁 云端自建歌单 (${cloudNormalPlaylists.size})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(top = if (cloudSystemPlaylists.isNotEmpty()) 10.dp else 4.dp, bottom = 2.dp),
                                                )
                                            }
                                            items(cloudNormalPlaylists, key = { "cloud_local_${it.name}" }) { p ->
                                                val isChecked = selectedCloudLocalNames.contains(p.name)
                                                val hasOnline = p.songs.any { CloudPlaylistJson.isOnlineSong(it) }
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isChecked) colors.primary.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isChecked) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.1f),
                                                    ),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isChecked) selectedCloudLocalNames.remove(p.name) else selectedCloudLocalNames.add(p.name)
                                                        },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = {
                                                                if (it) selectedCloudLocalNames.add(p.name) else selectedCloudLocalNames.remove(p.name)
                                                            },
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = p.name,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = colors.onSurface,
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            ) {
                                                                if (hasOnline) {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp),
                                                                        color = colors.primary.copy(alpha = 0.15f),
                                                                    ) {
                                                                        Text(
                                                                            text = "网络列表",
                                                                            color = colors.primary,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                        )
                                                                    }
                                                                }
                                                                if (p.groupName.isNotBlank() && p.groupName != "默认") {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp),
                                                                        color = colors.primary.copy(alpha = 0.15f),
                                                                    ) {
                                                                        Text(
                                                                            text = p.groupName,
                                                                            color = colors.primary,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                        )
                                                                    }
                                                                }
                                                                Text(
                                                                    text = "${p.songs.size} 首歌曲",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // 云端在线歌单
                                        if (parsedCloudOnlineFavorites.isNotEmpty()) {
                                            item {
                                                Text(
                                                    text = "🌐 云端在线歌单 (${parsedCloudOnlineFavorites.size})",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.primary,
                                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                                )
                                            }
                                            items(parsedCloudOnlineFavorites, key = { "cloud_online_${it.id}" }) { op ->
                                                val isChecked = selectedCloudOnlineIds.contains(op.id)
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isChecked) colors.primary.copy(alpha = 0.12f) else colors.surface.copy(alpha = 0.45f),
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isChecked) colors.primary.copy(alpha = 0.4f) else colors.primary.copy(alpha = 0.1f),
                                                    ),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            if (isChecked) selectedCloudOnlineIds.remove(op.id) else selectedCloudOnlineIds.add(op.id)
                                                        },
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = {
                                                                if (it) selectedCloudOnlineIds.add(op.id) else selectedCloudOnlineIds.remove(op.id)
                                                            },
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Box(
                                                            modifier = Modifier
                                                                .size(42.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(colors.surface),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (op.coverUrl.isNotBlank()) {
                                                                BitmapImage(
                                                                    key = op.coverUrl,
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = op.title,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = colors.onSurface,
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            ) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = colors.primary.copy(alpha = 0.15f),
                                                                ) {
                                                                    Text(
                                                                        text = op.platform,
                                                                        color = colors.primary,
                                                                        fontSize = 9.sp,
                                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                    )
                                                                }
                                                                if (op.customGroup.isNotBlank() && op.customGroup != "默认") {
                                                                    Surface(
                                                                        shape = RoundedCornerShape(4.dp),
                                                                        color = colors.primary.copy(alpha = 0.15f),
                                                                    ) {
                                                                        Text(
                                                                            text = op.customGroup,
                                                                            color = colors.primary,
                                                                            fontSize = 9.sp,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                        )
                                                                    }
                                                                }
                                                                Text(
                                                                    text = "${op.trackCount} 首",
                                                                    fontSize = 11.5.sp,
                                                                    color = colors.secondaryText,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 本机恢复同步模式选择卡片 (增量合并 / 全量覆盖)
                                SyncModeSelector(
                                    selectedMode = downloadSyncMode,
                                    onModeSelect = { downloadSyncMode = it },
                                    isUpload = false,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }

                // ── 5. 底部固定操作栏 ──
                Surface(
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    color = colors.surface.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        when (currentTab) {
                            SyncTab.UPLOAD -> {
                                val favCount = if (uploadFavoritesChecked && totalFavoriteCount > 0) 1 else 0
                                val dislikeCount = if (uploadDislikedChecked && dislikedKeys.isNotEmpty()) 1 else 0
                                val totalSel = selectedLocalUploadIds.size + selectedOnlineUploadKeys.size + favCount + dislikeCount
                                Column {
                                    Text(
                                        text = "已选 $totalSel 项备份 · ${uploadSyncMode.title}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                    )
                                    Text(
                                        text = if (uploadSyncMode == PlaylistSyncMode.OVERWRITE) "将全量替换云端备份" else "增量去重追加至云端",
                                        fontSize = 10.5.sp,
                                        color = colors.secondaryText,
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (totalSel == 0) {
                                            AppNoticeBus.error("请至少勾选一个要上传的歌单或喜好项")
                                            return@Button
                                        }
                                        isUploading = true
                                        coroutineScope.launch {
                                            val res = CloudPlaylistSyncService.upload(
                                                context = context,
                                                mode = if (uploadSyncMode == PlaylistSyncMode.OVERWRITE) CloudSyncClient.MODE_OVERWRITE else CloudSyncClient.MODE_MERGE,
                                                selectedLocalIds = selectedLocalUploadIds.toSet(),
                                                selectedOnlineKeys = selectedOnlineUploadKeys.toSet(),
                                                includeFavorites = uploadFavoritesChecked && totalFavoriteCount > 0,
                                                includeDisliked = uploadDislikedChecked && dislikedKeys.isNotEmpty(),
                                            )
                                            when (res) {
                                                is CloudSyncResult.Success -> {
                                                    AppNoticeBus.post("增量备份上传成功 (共 ${res.data} 项)", icon = AppNoticeIcon.PLAYLIST)
                                                    onSyncCompleted?.invoke()
                                                }
                                                is CloudSyncResult.Failure -> {
                                                    AppNoticeBus.error("上传失败: ${res.message}")
                                                }
                                            }
                                            isUploading = false
                                        }
                                    },
                                    enabled = !isUploading && totalSel > 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                                ) {
                                    if (isUploading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("正在上传...", color = colors.onPrimary, fontSize = 13.sp)
                                    } else {
                                        Icon(
                                            imageVector = MiuixIcons.Regular.CloudFill,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = colors.onPrimary,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val btnText = if (uploadSyncMode == PlaylistSyncMode.OVERWRITE) "全量覆盖上传" else "增量备份上传"
                                        Text(btnText, color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            SyncTab.DOWNLOAD -> {
                                val favCount = if (restoreFavoritesChecked && cloudSystemPlaylists.any { CloudPlaylistJson.systemKindOf(it) == CloudPlaylistJson.SystemPlaylistKind.FAVORITE }) 1 else 0
                                val dislikeCount = if (restoreDislikedChecked && cloudSystemPlaylists.any { CloudPlaylistJson.systemKindOf(it) == CloudPlaylistJson.SystemPlaylistKind.DISLIKED }) 1 else 0
                                val totalSel = selectedCloudLocalNames.size + selectedCloudOnlineIds.size + favCount + dislikeCount
                                Column {
                                    Text(
                                        text = "已选 $totalSel 项备份 · ${downloadSyncMode.title}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                    )
                                    Text(
                                        text = if (downloadSyncMode == PlaylistSyncMode.OVERWRITE) "将全量覆盖本地同名歌单" else "增量去重追加至本地",
                                        fontSize = 10.5.sp,
                                        color = colors.secondaryText,
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (cloudBackupData == null || totalSel == 0) {
                                            AppNoticeBus.error("请至少勾选一个要恢复的歌单")
                                            return@Button
                                        }
                                        isRestoring = true
                                        coroutineScope.launch {
                                            val res = CloudPlaylistSyncService.restore(
                                                context = context,
                                                selectedLocalNames = selectedCloudLocalNames.toSet(),
                                                selectedOnlineFavoriteIds = selectedCloudOnlineIds.toSet(),
                                                restoreFavorites = restoreFavoritesChecked,
                                                restoreDisliked = restoreDislikedChecked,
                                                backup = cloudBackupData,
                                                mode = if (downloadSyncMode == PlaylistSyncMode.OVERWRITE) CloudSyncClient.MODE_OVERWRITE else CloudSyncClient.MODE_MERGE,
                                            )
                                            when (res) {
                                                is CloudSyncResult.Success -> {
                                                    val r = res.data
                                                    AppNoticeBus.post(
                                                        "恢复完成：歌单 ${r.playlistsWritten}、喜欢 ${r.favoritesApplied}、收藏 ${r.onlineFavoritesApplied}",
                                                        icon = AppNoticeIcon.PLAYLIST,
                                                    )
                                                    onSyncCompleted?.invoke()
                                                    onDismiss()
                                                }
                                                is CloudSyncResult.Failure -> {
                                                    AppNoticeBus.error("恢复失败: ${res.message}")
                                                }
                                            }
                                            isRestoring = false
                                        }
                                    },
                                    enabled = !isRestoring && totalSel > 0,
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                                ) {
                                    if (isRestoring) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("正在恢复...", color = colors.onPrimary, fontSize = 13.sp)
                                    } else {
                                        Icon(
                                            imageVector = MiuixIcons.Regular.CloudFill,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = colors.onPrimary,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val btnText = if (downloadSyncMode == PlaylistSyncMode.OVERWRITE) "全量覆盖恢复" else "增量合并恢复"
                                        Text(btnText, color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 同步模式选择器 (增量合并 / 全量覆盖 双卡片并排)
 */
@Composable
private fun SyncModeSelector(
    selectedMode: PlaylistSyncMode,
    onModeSelect: (PlaylistSyncMode) -> Unit,
    isUpload: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = if (isUpload) "云端同步模式：" else "本机恢复模式：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. 增量合并
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedMode == PlaylistSyncMode.MERGE) colors.primary.copy(alpha = 0.16f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (selectedMode == PlaylistSyncMode.MERGE) colors.primary else colors.primary.copy(alpha = 0.12f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onModeSelect(PlaylistSyncMode.MERGE) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMode == PlaylistSyncMode.MERGE,
                            onClick = { onModeSelect(PlaylistSyncMode.MERGE) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "增量合并 (推荐)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedMode == PlaylistSyncMode.MERGE) colors.primary else colors.onSurface,
                            )
                            Text(
                                text = if (isUpload) "去重追加，保留云端已有" else "去重追加，保留本地已有",
                                fontSize = 9.5.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 2. 全量覆盖
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedMode == PlaylistSyncMode.OVERWRITE) colors.primary.copy(alpha = 0.16f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (selectedMode == PlaylistSyncMode.OVERWRITE) colors.primary else colors.primary.copy(alpha = 0.12f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onModeSelect(PlaylistSyncMode.OVERWRITE) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMode == PlaylistSyncMode.OVERWRITE,
                            onClick = { onModeSelect(PlaylistSyncMode.OVERWRITE) },
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "全量覆盖",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedMode == PlaylistSyncMode.OVERWRITE) colors.primary else colors.onSurface,
                            )
                            Text(
                                text = if (isUpload) "直接替代云端全部歌单" else "清空同名歌单原有歌曲",
                                fontSize = 9.5.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
