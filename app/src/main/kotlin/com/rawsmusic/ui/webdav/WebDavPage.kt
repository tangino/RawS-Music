package com.rawsmusic.ui.webdav

import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.module.scanner.webdav.WebDavMediaScanner
import com.rawsmusic.module.scanner.webdav.WebDavUrlTools
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.locale.localizedErrorMessage
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.webdav.AuthMode
import com.rawsmusic.module.scanner.webdav.WebDavClient
import com.rawsmusic.module.scanner.webdav.WebDavConfig
import com.rawsmusic.module.scanner.webdav.WebDavHeaderCodec
import com.rawsmusic.module.scanner.webdav.WebDavItem
import com.rawsmusic.module.scanner.webdav.WebDavPlaybackCache
import com.rawsmusic.module.scanner.webdav.WebDavPlaybackCredentialResolver
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 页面。
 * 一级视图：已连接的 WebDAV 目录管理（展示所有目录、右侧编辑/移除、添加目录）。
 * 二级视图：点击目录后下钻展示真实内容（子目录浏览、音频流式播放、封面解析与曲库扫描导入）。
 */
@Composable
fun WebDavPageCompose(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    val webDavClient = remember { WebDavClient() }
    val items = remember { mutableStateListOf<WebDavItem>() }
    val artworkKeyByUrl = remember { mutableStateMapOf<String, String>() }
    var currentUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var emptyMessage by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var editingDirectory by remember { mutableStateOf<AppPreferences.WebDavDirectory?>(null) }
    var pendingRemoveDir by remember { mutableStateOf<AppPreferences.WebDavDirectory?>(null) }
    var loadGeneration by remember { mutableStateOf(0L) }

    val directories = remember { mutableStateListOf<AppPreferences.WebDavDirectory>() }
    var selectedDirectory by remember { mutableStateOf<AppPreferences.WebDavDirectory?>(null) }

    var config by remember {
        mutableStateOf(
            WebDavConfig(
                url = AppPreferences.WebDav.url,
                username = AppPreferences.WebDav.username,
                password = AppPreferences.WebDav.password,
                authMode = when (AppPreferences.WebDav.authMode) {
                    1 -> AuthMode.BASIC
                    2 -> AuthMode.DIGEST
                    else -> AuthMode.AUTO
                },
                extraHeaders = WebDavHeaderCodec.parseOrEmpty(AppPreferences.WebDav.extraHeadersText),
            )
        )
    }

    var isScanningToLibrary by remember { mutableStateOf(false) }
    var importedCount by remember { mutableStateOf(0) }

    fun refreshImportedCount() {
        importedCount = if (config.url.isNotBlank()) {
            WebDavMediaScanner.getImportedWebDavSongCount(config.url)
        } else 0
    }

    fun refreshDirectories() {
        val list = AppPreferences.WebDav.getDirectories()
        directories.clear()
        directories.addAll(list)
    }

    LaunchedEffect(config.url) {
        refreshImportedCount()
    }

    fun loadDirectory(url: String, configOverride: WebDavConfig? = null) {
        val configSnapshot = configOverride ?: config
        if (configSnapshot.url.isBlank()) {
            items.clear()
            currentUrl = ""
            emptyMessage = context.getString(R.string.webdav_configure_hint)
            return
        }
        loadGeneration += 1L
        val requestGeneration = loadGeneration
        isLoading = true
        emptyMessage = ""
        scope.launch {
            val loadResult = withContext(Dispatchers.IO) {
                runCatching { webDavClient.listDirectoryResult(configSnapshot, url) }
            }
            loadResult.fold(
                onSuccess = { result ->
                    if (requestGeneration != loadGeneration) return@fold
                    isLoading = false
                    currentUrl = result.canonicalUrl
                    AppPreferences.WebDav.lastUrl = result.canonicalUrl
                    selectedDirectory?.let { dir ->
                        if (dir.url.isNotBlank()) {
                            AppPreferences.WebDav.addOrUpdateDirectory(dir.copy(lastUrl = result.canonicalUrl))
                        }
                    }
                    items.clear()
                    items.addAll(result.items.sortedWith(compareByDescending<WebDavItem> { it.isDirectory }.thenBy { it.fileName }))
                    artworkKeyByUrl.clear()
                    scope.launch {
                        val cachedArtwork = withContext(Dispatchers.IO) {
                            val map = mutableMapOf<String, String>()
                            // 1. 查找目录中的图片文件，对小于 5MB 的图片轻量缓存作为封面
                            val imageItems = result.items.filter { 
                                !it.isDirectory && it.fileName.substringAfterLast(".", "").lowercase() in setOf("jpg", "jpeg", "png", "webp") 
                            }
                            var directoryCoverKey: String? = null
                            val primaryImage = imageItems.firstOrNull { 
                                val lower = it.fileName.lowercase()
                                lower.startsWith("cover") || lower.startsWith("folder") || lower.startsWith("albumart") || lower.contains("front")
                            } ?: imageItems.firstOrNull()

                            if (primaryImage != null && primaryImage.size in 1..5_000_000L) {
                                val imagePath = runCatching {
                                    WebDavPlaybackCache.resolve(
                                        context = context,
                                        client = webDavClient,
                                        config = configSnapshot,
                                        remoteUrl = primaryImage.resolvedUrl,
                                        knownSize = primaryImage.size,
                                    )
                                }.getOrNull()
                                if (!imagePath.isNullOrBlank()) {
                                    directoryCoverKey = AudioFile(
                                        path = imagePath,
                                        fileSize = primaryImage.size,
                                        dateModified = primaryImage.lastModified.hashCode().toLong(),
                                    ).coverKey
                                    map[primaryImage.resolvedUrl] = directoryCoverKey
                                }
                            }

                            // 2. 为所有文件生成或关联封面 key
                            result.items.forEach { item ->
                                if (item.isDirectory) return@forEach
                                val remoteUrl = item.resolvedUrl.takeIf { it.isNotBlank() } ?: return@forEach
                                val ext = item.fileName.substringAfterLast(".", "").lowercase()
                                val isAudio = AudioUtils.isAudioFile(item.fileName)
                                val isImage = ext in setOf("jpg", "jpeg", "png", "webp")
                                if (!isAudio && !isImage) return@forEach

                                val cachedPath = WebDavPlaybackCache.findCachedPath(
                                    context = context,
                                    config = configSnapshot,
                                    remoteUrl = remoteUrl,
                                    knownSize = item.size,
                                )
                                if (!cachedPath.isNullOrBlank()) {
                                    val key = AudioFile(
                                        path = cachedPath,
                                        fileSize = item.size,
                                        dateModified = item.lastModified.hashCode().toLong(),
                                    ).coverKey
                                    map[remoteUrl] = key
                                    return@forEach
                                }

                                // 检查已缓存的封面文件（播放或扫描时提取并留存的封面）
                                val safeUrl = WebDavUrlTools.toSafeHttpUrl(remoteUrl)?.toString() ?: remoteUrl
                                val coverDir = java.io.File(context.cacheDir, "webdav_covers")
                                val coverFile = java.io.File(coverDir, "art_${(safeUrl.hashCode().toLong() and 0xFFFFFFFFL)}.jpg")
                                if (coverFile.exists() && coverFile.length() > 0) {
                                    val key = AudioFile(
                                        path = coverFile.absolutePath,
                                        fileSize = coverFile.length(),
                                        dateModified = coverFile.lastModified(),
                                    ).coverKey
                                    map[remoteUrl] = key
                                    return@forEach
                                }

                                if (isAudio && directoryCoverKey != null) {
                                    map[remoteUrl] = directoryCoverKey
                                }
                            }
                            map
                        }
                        if (requestGeneration == loadGeneration) {
                            cachedArtwork.forEach { (url, key) -> artworkKeyByUrl[url] = key }
                        }
                    }
                    if (items.isEmpty()) emptyMessage = context.getString(R.string.webdav_empty_directory)
                },
                onFailure = { error ->
                    if (requestGeneration != loadGeneration) return@fold
                    isLoading = false
                    emptyMessage = context.getString(
                        R.string.webdav_load_failed,
                        context.localizedErrorMessage(error, R.string.ui_unknown_error),
                    )
                },
            )
        }
    }

    fun switchToDirectory(dir: AppPreferences.WebDavDirectory, autoLoad: Boolean = true) {
        AppPreferences.WebDav.setActiveDirectory(dir.id)
        val newConfig = WebDavConfig(
            url = dir.url,
            username = dir.username,
            password = dir.password,
            authMode = when (dir.authMode) {
                1 -> AuthMode.BASIC
                2 -> AuthMode.DIGEST
                else -> AuthMode.AUTO
            },
            extraHeaders = WebDavHeaderCodec.parseOrEmpty(dir.extraHeadersText),
        )
        config = newConfig
        selectedDirectory = dir
        if (autoLoad) {
            val startUrl = dir.lastUrl.ifBlank { dir.url }
            if (startUrl.isNotBlank()) {
                loadDirectory(startUrl, newConfig)
            } else {
                items.clear()
                currentUrl = ""
                emptyMessage = context.getString(R.string.webdav_configure_hint)
            }
        }
    }

    fun evictWebDavSongsFromQueue(targetUrl: String) {
        if (targetUrl.isBlank()) return
        val controller = PlayerHolder.controller ?: return
        val normalized = targetUrl.trimEnd('/')
        val queue = controller.queue.value
        val toRemove = queue.songs.filter { song ->
            val p = song.path.trim()
            p.startsWith(normalized, ignoreCase = true) || p.startsWith(targetUrl, ignoreCase = true)
        }
        if (toRemove.isNotEmpty()) {
            controller.removeSongsFromQueue(toRemove)
        }
        val current = controller.currentSong.value
        if (current != null) {
            val cp = current.path.trim()
            if (cp.startsWith(normalized, ignoreCase = true) || cp.startsWith(targetUrl, ignoreCase = true)) {
                controller.pause()
            }
        }
    }

    fun removeDirectory(dir: AppPreferences.WebDavDirectory, purgeSongs: Boolean) {
        if (purgeSongs && dir.url.isNotBlank()) {
            evictWebDavSongsFromQueue(dir.url)
            scope.launch {
                val purged = withContext(Dispatchers.IO) {
                    WebDavMediaScanner.purgeWebDavSongs(dir.url)
                }
                if (purged > 0) {
                    AppNoticeBus.post(
                        message = context.getString(R.string.webdav_purge_success, purged),
                        icon = AppNoticeIcon.INFO,
                    )
                }
            }
        }
        AppPreferences.WebDav.removeDirectory(dir.id)
        refreshDirectories()
        if (selectedDirectory?.id == dir.id) {
            selectedDirectory = null
            items.clear()
            currentUrl = ""
            config = WebDavConfig(url = "")
        }
    }

    fun scanToLibrary(targetUrl: String = currentUrl, configOverride: WebDavConfig? = null) {
        if (isScanningToLibrary) return
        val configSnapshot = configOverride ?: config
        if (configSnapshot.url.isBlank()) {
            AppNoticeBus.error(context.getString(R.string.webdav_configure_hint))
            return
        }
        isScanningToLibrary = true
        AppNoticeBus.post(
            message = context.getString(R.string.webdav_scanning_library),
            icon = AppNoticeIcon.SCAN,
        )
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WebDavMediaScanner.scanAndImport(
                        client = webDavClient,
                        config = configSnapshot,
                        targetDirectoryUrl = targetUrl,
                        context = context,
                    )
                }
            }
            isScanningToLibrary = false
            result.fold(
                onSuccess = { scanResult ->
                    refreshImportedCount()
                    val count = if (scanResult.imported > 0) scanResult.imported else scanResult.totalScanned
                    if (scanResult.totalScanned > 0) {
                        AppNoticeBus.post(
                            message = context.getString(R.string.webdav_scan_success, count),
                            icon = AppNoticeIcon.SCAN,
                        )
                    } else {
                        AppNoticeBus.post(
                            message = context.getString(R.string.webdav_scan_empty),
                            icon = AppNoticeIcon.INFO,
                        )
                    }
                },
                onFailure = { error ->
                    AppNoticeBus.error(
                        context.localizedErrorMessage(error, R.string.ui_unknown_error)
                    )
                }
            )
        }
    }

    fun saveDirectory(
        id: String?,
        name: String,
        url: String,
        username: String,
        password: String,
        authMode: Int,
        extraHeadersText: String
    ) {
        val isNew = (id == null)
        val targetId = id ?: java.util.UUID.randomUUID().toString()
        val dir = AppPreferences.WebDavDirectory(
            id = targetId,
            name = name.ifBlank { url.substringAfter("://").substringBefore("/").ifBlank { "WebDAV" } },
            url = url,
            username = username,
            password = password,
            authMode = authMode,
            extraHeadersText = extraHeadersText,
        )
        AppPreferences.WebDav.addOrUpdateDirectory(dir)
        refreshDirectories()
        // 自动下钻进入该目录
        switchToDirectory(dir, autoLoad = true)
        // 若为新添加目录，自动触发一次曲库扫描导入
        if (isNew) {
            val newConfig = WebDavConfig(
                url = dir.url,
                username = dir.username,
                password = dir.password,
                authMode = when (dir.authMode) {
                    1 -> AuthMode.BASIC
                    2 -> AuthMode.DIGEST
                    else -> AuthMode.AUTO
                },
                extraHeaders = WebDavHeaderCodec.parseOrEmpty(dir.extraHeadersText),
            )
            scanToLibrary(dir.url, newConfig)
        }
    }

    fun purgeLibrarySongs() {
        evictWebDavSongsFromQueue(config.url)
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                WebDavMediaScanner.purgeWebDavSongs(config.url)
            }
            refreshImportedCount()
            AppNoticeBus.post(
                message = context.getString(R.string.webdav_purge_success, count),
                icon = AppNoticeIcon.INFO,
            )
        }
    }

    fun playItem(item: WebDavItem) {
        val configSnapshot = config
        val playback = webDavClient.buildPlaybackRequest(configSnapshot, item)
        Log.i(
            "WebDavPlayback",
            "PLAY_ITEM file=${item.fileName} url=${playback.url} headers=${playback.headers.keys.sorted()} ua=${playback.userAgent.ifBlank { "-" }}"
        )
        RemoteHttpStreamRegistry.register(
            url = playback.url,
            headers = playback.headers,
            userAgent = playback.userAgent,
            owner = "webdav",
            headerProvider = { targetUrl ->
                webDavClient.buildPlaybackHeaders(
                    config = configSnapshot,
                    targetUrl = targetUrl,
                    refreshAuthentication = true,
                )
            },
        )
        val audioItems = items.filter { !it.isDirectory && AudioUtils.isAudioFile(it.fileName) }
        val audioQueue = audioItems.map { itItem ->
            val p = webDavClient.buildPlaybackRequest(configSnapshot, itItem)
            RemoteHttpStreamRegistry.register(
                url = p.url,
                headers = p.headers,
                userAgent = p.userAgent,
                owner = "webdav",
                headerProvider = { targetUrl ->
                    webDavClient.buildPlaybackHeaders(
                        config = configSnapshot,
                        targetUrl = targetUrl,
                        refreshAuthentication = true,
                    )
                },
            )
            AudioFile(
                id = (itItem.resolvedUrl.hashCode().toLong() and 0x7FFFFFFF),
                path = p.url,
                title = itItem.fileName.substringBeforeLast("."),
                artist = selectedDirectory?.name?.ifBlank { "WebDAV" } ?: "WebDAV",
                album = context.getString(R.string.webdav_remote_album),
                albumArtPath = "",
                duration = 0,
                format = itItem.fileName.substringAfterLast(".", "").uppercase(),
                fileSize = itItem.size,
            )
        }
        val curIndex = audioItems.indexOfFirst { it.resolvedUrl == item.resolvedUrl }.coerceAtLeast(0)
        val audioFile = audioQueue.getOrNull(curIndex) ?: AudioFile(
            id = (item.resolvedUrl.hashCode().toLong() and 0x7FFFFFFF),
            path = playback.url,
            title = item.fileName.substringBeforeLast("."),
            artist = selectedDirectory?.name?.ifBlank { "WebDAV" } ?: "WebDAV",
            album = context.getString(R.string.webdav_remote_album),
            albumArtPath = "",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size,
        )

        val isCached = WebDavPlaybackCredentialResolver.findCachedPath(context, playback.url) != null
        if (!isCached) {
            AppNoticeBus.post(context.getString(R.string.webdav_buffering_song), AppNoticeIcon.DOWNLOAD)
        }

        PlayerHolder.controller?.play(audioFile, queue = audioQueue, index = curIndex)

        if (artworkKeyByUrl[playback.url].isNullOrBlank()) {
            scope.launch {
                repeat(180) {
                    val cachedPath = withContext(Dispatchers.IO) {
                        WebDavPlaybackCache.findCachedPath(
                            context = context,
                            config = configSnapshot,
                            remoteUrl = playback.url,
                            knownSize = item.size,
                        )
                    }
                    if (!cachedPath.isNullOrBlank()) {
                        artworkKeyByUrl[playback.url] = AudioFile(
                            path = cachedPath,
                            fileSize = item.size,
                            dateModified = item.lastModified.hashCode().toLong(),
                        ).coverKey
                        return@launch
                    }
                    delay(1_000L)
                }
            }
        }
    }

    fun addToQueue(item: WebDavItem) {
        val configSnapshot = config
        val playback = webDavClient.buildPlaybackRequest(configSnapshot, item)
        RemoteHttpStreamRegistry.register(
            url = playback.url,
            headers = playback.headers,
            userAgent = playback.userAgent,
            owner = "webdav",
            headerProvider = { targetUrl ->
                webDavClient.buildPlaybackHeaders(
                    config = configSnapshot,
                    targetUrl = targetUrl,
                    refreshAuthentication = true,
                )
            },
        )
        val audioFile = AudioFile(
            id = (item.resolvedUrl.hashCode().toLong() and 0x7FFFFFFF),
            path = playback.url,
            title = item.fileName.substringBeforeLast("."),
            artist = selectedDirectory?.name?.ifBlank { "WebDAV" } ?: "WebDAV",
            album = context.getString(R.string.webdav_remote_album),
            albumArtPath = "",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size
        )
        PlayerHolder.controller?.addToQueue(audioFile)
        AppNoticeBus.post(
            message = context.getString(R.string.webdav_added_to_queue),
            icon = AppNoticeIcon.QUEUE,
        )
    }

    LaunchedEffect(Unit) {
        refreshDirectories()
    }

    // 处理系统返回手势/物理按键
    androidx.activity.compose.BackHandler {
        if (selectedDirectory != null) {
            val parentUrl = if (config.url.isNotBlank() && currentUrl.isNotBlank()) {
                webDavClient.getParentUrl(currentUrl, config)
            } else null
            if (parentUrl != null) {
                loadDirectory(parentUrl)
            } else {
                selectedDirectory = null
            }
        } else {
            onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        val currentDir = selectedDirectory
        if (currentDir == null) {
            // ================== 一级视图：已连接的 WebDAV 目录管理 ==================
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.webdav_back), color = colors.primary, fontSize = 14.sp)
                }
                Text(
                    text = stringResource(R.string.webdav_directory_manager_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 4.dp)
                )
                TextButton(onClick = {
                    editingDirectory = null
                    showSettings = true
                }) {
                    Text("+ " + stringResource(R.string.webdav_add_directory), color = colors.primary, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (directories.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.webdav_no_directories),
                            fontSize = 14.sp,
                            color = colors.secondaryText
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = {
                            editingDirectory = null
                            showSettings = true
                        }) {
                            Text(stringResource(R.string.webdav_add_directory), color = colors.primary, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(directories, key = { it.id }) { dir ->
                        WebDavDirectoryCard(
                            directory = dir,
                            onClick = {
                                switchToDirectory(dir, autoLoad = true)
                            },
                            onEdit = {
                                editingDirectory = dir
                                showSettings = true
                            },
                            onRemove = {
                                pendingRemoveDir = dir
                            }
                        )
                    }
                }
            }
        } else {
            // ================== 二级视图：下钻目录内容浏览与播放 ==================
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val parentUrl = if (config.url.isNotBlank() && currentUrl.isNotBlank()) {
                        webDavClient.getParentUrl(currentUrl, config)
                    } else null
                    if (parentUrl != null) {
                        loadDirectory(parentUrl)
                    } else {
                        selectedDirectory = null
                    }
                }) {
                    Text(stringResource(R.string.webdav_back), color = colors.primary, fontSize = 14.sp)
                }
                Text(
                    text = currentDir.name.ifBlank { stringResource(R.string.webdav_title) },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 4.dp)
                )
                TextButton(onClick = {
                    editingDirectory = currentDir
                    showSettings = true
                }) {
                    Text(stringResource(R.string.settings), color = colors.primary, fontSize = 13.sp)
                }
            }

            // 当前路径面包屑
            if (currentUrl.isNotBlank()) {
                Text(
                    currentUrl,
                    fontSize = 11.sp,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            // 操作栏：扫描至曲库与清理
            if (config.url.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { scanToLibrary(currentUrl) },
                        enabled = !isScanningToLibrary && !isLoading
                    ) {
                        if (isScanningToLibrary) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = colors.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isScanningToLibrary) {
                                stringResource(R.string.webdav_scanning_library)
                            } else {
                                stringResource(R.string.webdav_scan_to_library)
                            },
                            fontSize = 13.sp,
                            color = if (isScanningToLibrary) colors.secondaryText else colors.primary
                        )
                    }

                    if (importedCount > 0) {
                        TextButton(
                            onClick = { purgeLibrarySongs() },
                            enabled = !isScanningToLibrary
                        ) {
                            Text(
                                text = "${stringResource(R.string.webdav_purge_songs)} ($importedCount)",
                                fontSize = 12.sp,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 目录与文件列表内容
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.primary)
                    }
                }
                emptyMessage.isNotBlank() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyMessage, fontSize = 14.sp, color = colors.secondaryText)
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(items) { item ->
                            WebDavItemRow(
                                item = item,
                                artworkKey = artworkKeyByUrl[item.resolvedUrl],
                                onClick = {
                                    if (item.isDirectory) {
                                        val dirUrl = item.resolvedUrl.ifBlank {
                                            webDavClient.buildFileUrl(currentUrl, item.href, isDirectory = true)
                                        }
                                        loadDirectory(dirUrl)
                                    } else if (AudioUtils.isAudioFile(item.fileName)) {
                                        playItem(item)
                                    }
                                },
                                onMore = {
                                    if (!item.isDirectory && AudioUtils.isAudioFile(item.fileName)) {
                                        addToQueue(item)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 移除目录二次确认弹窗
    if (pendingRemoveDir != null) {
        val target = pendingRemoveDir!!
        WebDavRemoveConfirmDialog(
            directory = target,
            onDismiss = { pendingRemoveDir = null },
            onConfirm = { purgeSongs ->
                removeDirectory(target, purgeSongs)
                pendingRemoveDir = null
            }
        )
    }

    // 设置/添加/编辑对话框
    if (showSettings) {
        WebDavSettingsDialog(
            initialDirectory = editingDirectory,
            onDismiss = {
                showSettings = false
                editingDirectory = null
            },
            webDavClient = webDavClient,
            onSave = { name, url, username, password, authMode, extraHeadersText ->
                runCatching {
                    val normalizedUrl = webDavClient.canonicalizeCollectionUrl(url)
                    val dirId = editingDirectory?.id
                    saveDirectory(
                        id = dirId,
                        name = name,
                        url = normalizedUrl,
                        username = username,
                        password = password,
                        authMode = authMode,
                        extraHeadersText = extraHeadersText
                    )
                }.onSuccess {
                    showSettings = false
                    editingDirectory = null
                }.onFailure { error ->
                    AppNoticeBus.error(
                        context.localizedErrorMessage(error, R.string.webdav_enter_address)
                    )
                }
            }
        )
    }
}

/**
 * 已连接目录卡片，右侧带编辑与移除按钮，整卡点击下钻。
 */
@Composable
private fun WebDavDirectoryCard(
    directory: AppPreferences.WebDavDirectory,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = themeColors()
    val importedCount = remember(directory.url) {
        if (directory.url.isNotBlank()) WebDavMediaScanner.getImportedWebDavSongCount(directory.url) else 0
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = directory.name.ifBlank { stringResource(R.string.webdav_title) },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = directory.url,
                fontSize = 11.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (importedCount > 0) "已导入音乐库 · $importedCount 首" else "未导入音乐库 (点击进入可扫描)",
                fontSize = 11.sp,
                color = if (importedCount > 0) colors.primary else colors.secondaryText,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.webdav_edit_directory), fontSize = 12.sp, color = colors.primary)
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.webdav_remove_directory), fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFC62828))
            }
        }
    }
}

/**
 * 移除目录的二次确认对话框
 */
@Composable
private fun WebDavRemoveConfirmDialog(
    directory: AppPreferences.WebDavDirectory,
    onDismiss: () -> Unit,
    onConfirm: (purgeSongs: Boolean) -> Unit,
) {
    val colors = themeColors()
    var purgeSongsOnRemove by remember { mutableStateOf(true) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(20.dp)
        ) {
            Text(
                stringResource(R.string.webdav_remove_confirm_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.webdav_remove_confirm_msg, directory.name.ifBlank { directory.url }),
                fontSize = 13.sp,
                color = colors.secondaryText
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { purgeSongsOnRemove = !purgeSongsOnRemove }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = purgeSongsOnRemove,
                    onCheckedChange = { purgeSongsOnRemove = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.webdav_remove_purge_songs),
                    fontSize = 13.sp,
                    color = colors.onSurface
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel), color = colors.secondaryText)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    onConfirm(purgeSongsOnRemove)
                }) {
                    Text(stringResource(R.string.webdav_remove_directory), color = androidx.compose.ui.graphics.Color(0xFFC62828))
                }
            }
        }
    }
}



@Composable
private fun WebDavItemRow(
    item: WebDavItem,
    artworkKey: String?,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.isDirectory) {
            Text(
                "📁",
                fontSize = 20.sp,
                modifier = Modifier.size(48.dp)
            )
        } else {
            val ext = item.fileName.substringAfterLast(".", "").lowercase()
            val isAudio = AudioUtils.isAudioFile(item.fileName)
            val isImage = ext in setOf("jpg", "jpeg", "png", "webp", "bmp")
            val isLrc = ext in setOf("lrc", "txt")
            val defaultIcon = when {
                isAudio -> "🎵"
                isImage -> "🖼️"
                isLrc -> "📝"
                else -> "📄"
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(defaultIcon, fontSize = 20.sp)
                if (!artworkKey.isNullOrBlank()) {
                    BitmapImage(
                        key = artworkKey,
                        contentDescription = item.fileName,
                        modifier = Modifier.fillMaxSize(),
                        targetWidth = 256,
                        targetHeight = 256,
                        showDefaultArtwork = false,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val info = if (item.isDirectory) stringResource(R.string.webdav_folder)
            else {
                val ext = item.fileName.substringAfterLast(".", "").uppercase()
                "$ext · ${AudioUtils.formatFileSize(item.size)}"
            }
            Text(info, fontSize = 12.sp, color = colors.secondaryText)
        }
        if (!item.isDirectory && AudioUtils.isAudioFile(item.fileName)) {
            TextButton(onClick = onMore) {
                Text(stringResource(R.string.webdav_add), color = colors.primary, fontSize = 12.sp)
            }
        }
    }
}



@Composable
private fun WebDavSettingsDialog(
    initialDirectory: AppPreferences.WebDavDirectory?,
    onDismiss: () -> Unit,
    webDavClient: WebDavClient,
    onSave: (name: String, url: String, username: String, password: String, authMode: Int, extraHeadersText: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    var name by remember { mutableStateOf(initialDirectory?.name.orEmpty()) }
    var url by remember { mutableStateOf(initialDirectory?.url ?: AppPreferences.WebDav.url) }
    var username by remember { mutableStateOf(initialDirectory?.username ?: AppPreferences.WebDav.username) }
    var password by remember { mutableStateOf(initialDirectory?.password ?: AppPreferences.WebDav.password) }
    var authMode by remember { mutableStateOf(initialDirectory?.authMode ?: AppPreferences.WebDav.authMode) }
    var extraHeadersText by remember { mutableStateOf(initialDirectory?.extraHeadersText ?: AppPreferences.WebDav.extraHeadersText) }
    var isTesting by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var testSuccess by remember { mutableStateOf(false) }
    val authModes = arrayOf(
        stringResource(R.string.webdav_auth_auto),
        stringResource(R.string.webdav_auth_basic),
        stringResource(R.string.webdav_auth_digest),
    )
    val dismissProgress = rememberPredictiveDialogProgress(enabled = true, onDismissRequest = onDismiss)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .predictiveDialogMotion(dismissProgress)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                if (initialDirectory == null) stringResource(R.string.webdav_add_directory) else stringResource(R.string.webdav_edit_directory),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.webdav_dir_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.webdav_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.webdav_username_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.webdav_password_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.webdav_authentication_method), fontSize = 13.sp, color = colors.secondaryText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                authModes.forEachIndexed { index, mode ->
                    TextButton(onClick = { authMode = index }) {
                        Text(
                            mode,
                            fontSize = 12.sp,
                            color = if (authMode == index) colors.primary else colors.secondaryText
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = extraHeadersText,
                onValueChange = {
                    extraHeadersText = it
                    testMessage = ""
                },
                label = { Text(stringResource(R.string.webdav_extra_headers_label)) },
                placeholder = { Text(stringResource(R.string.webdav_extra_headers_hint)) },
                supportingText = { Text(stringResource(R.string.webdav_extra_headers_help)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            if (testMessage.isNotBlank()) {
                Text(
                    testMessage,
                    fontSize = 12.sp,
                    color = if (testSuccess) colors.primary else androidx.compose.ui.graphics.Color(0xFFC62828),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = !isTesting,
                    onClick = {
                        if (url.isBlank()) {
                            AppNoticeBus.error(context.getString(R.string.webdav_enter_address))
                            return@TextButton
                        }
                        isTesting = true
                        testMessage = ""
                        val extraHeaders = runCatching { WebDavHeaderCodec.parse(extraHeadersText) }
                            .getOrElse { error ->
                                isTesting = false
                                testSuccess = false
                                testMessage = context.getString(
                                    R.string.webdav_extra_headers_invalid,
                                    context.localizedErrorMessage(error, R.string.ui_unknown_error),
                                )
                                return@TextButton
                            }
                        val testConfig = WebDavConfig(
                            url = url.trim(),
                            username = username.trim(),
                            password = password,
                            authMode = when (authMode) {
                                1 -> AuthMode.BASIC
                                2 -> AuthMode.DIGEST
                                else -> AuthMode.AUTO
                            },
                            extraHeaders = extraHeaders,
                        )
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { webDavClient.testConnection(testConfig) }
                            isTesting = false
                            testSuccess = result.success
                            testMessage = if (result.success) {
                                context.getString(R.string.webdav_test_success)
                            } else {
                                context.localizedErrorMessage(
                                    result.message,
                                    R.string.webdav_test_failed,
                                )
                            }
                            if (result.success && result.canonicalUrl.isNotBlank()) {
                                url = result.canonicalUrl
                            }
                        }
                    },
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary,
                        )
                    } else {
                        Text(stringResource(R.string.webdav_test_connection), color = colors.primary)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel), color = colors.secondaryText) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    if (url.isBlank()) {
                        AppNoticeBus.error(context.getString(R.string.webdav_enter_address))
                        return@TextButton
                    }
                    val headerError = runCatching { WebDavHeaderCodec.parse(extraHeadersText) }.exceptionOrNull()
                    if (headerError != null) {
                        AppNoticeBus.error(
                            context.getString(
                                R.string.webdav_extra_headers_invalid,
                                context.localizedErrorMessage(headerError, R.string.ui_unknown_error),
                            )
                        )
                        return@TextButton
                    }
                    onSave(name.trim(), url.trim(), username.trim(), password, authMode, extraHeadersText)
                }) {
                    Text(stringResource(R.string.webdav_save), color = colors.primary)
                }
            }
        }
    }
}
