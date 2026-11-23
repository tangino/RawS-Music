package com.rawsmusic.helper

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.os.SystemClock
import com.rawsmusic.module.scanner.AutomaticScanScheduler
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.locale.localizedErrorMessage
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.scanner.AudioLibraryRepository
import com.rawsmusic.module.scanner.LibraryScanCoordinator
import com.rawsmusic.module.scanner.LibraryScanSummary
import com.rawsmusic.module.scanner.LibraryScanToast
import com.rawsmusic.module.scanner.LibraryScannerDependencies
import com.rawsmusic.module.scanner.MediaStoreChangeObserver
import com.rawsmusic.module.scanner.MediaStoreObserver
import com.rawsmusic.module.scanner.MediaStoreScanner
import com.rawsmusic.module.scanner.ScanScheduler
import com.rawsmusic.module.scanner.ScanUiState
import com.rawsmusic.module.scanner.TwoStageMediaScanner
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一扫描调度器。
 * 手动扫描和自动监听共用一个入口，互不冲突。
 */
class StartupScanHelper(
    private val context: Context,
    private val audioRepository: AudioLibraryRepository? = null
) {
    private var mediaStoreObserver: MediaStoreObserver? = null
    private var autoObserverJob: Job? = null
    private var foregroundCheckJob: Job? = null
    @Volatile
    private var lastForegroundCheckAtMs: Long = 0L
    private var mountReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestScheduler = AutomaticScanScheduler(
        scope = scope,
        automaticEnabled = {
            AppPreferences.Scanner.automaticScanEnabled &&
                (AppPreferences.UI.scanPaths.isNotEmpty() || AppPreferences.Scanner.musicFolderUris.isNotEmpty())
        },
        nowMs = { System.currentTimeMillis() },
        uptimeMs = { SystemClock.uptimeMillis() },
        immediate = { AppPreferences.Scanner.immediateAutoScanEnabled },
        startupToken = coldStartScanRequested,
        scan = { request -> runScanLoop(context, ScanRequest(request.reason, request.automatic)) },
        externallyScanning = { LibraryScanCoordinator.isScanning }
    )
    private var scanCoordinator: LibraryScanCoordinator? = null
    private val resolvedRepository: AudioLibraryRepository? by lazy {
        audioRepository ?: LibraryScannerDependencies.repositoryOrNull(context)
    }

    private val _scanUiState = MutableStateFlow(ScanUiState.idle())
    val scanUiState: StateFlow<ScanUiState> = _scanUiState.asStateFlow()

    private val _songs = MutableStateFlow<List<AudioFile>>(emptyList())
    val songs: StateFlow<List<AudioFile>> = _songs.asStateFlow()

    fun start() {
        if (autoObserverJob != null || mediaStoreObserver != null) return
        AppLogger.d(TAG, "start")
        if (resolvedRepository != null) {
            startAutoMediaStoreScan()
            registerMountReceiver()
            scope.launch {
                val existingCount = runCatching {
                    resolvedRepository?.getAllSongs()?.size ?: 0
                }.getOrDefault(0)
                val hasSelectedFolders = AppPreferences.UI.scanPaths.isNotEmpty() ||
                    AppPreferences.Scanner.musicFolderUris.isNotEmpty()
                val shouldAutoScan = AppPreferences.Scanner.automaticScanEnabled &&
                    AppPreferences.Scanner.coldStartAutoScanEnabled && hasSelectedFolders

                if (shouldAutoScan) {
                    AppLogger.d(TAG, "request cold-start incremental scan, existing songs=$existingCount")
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), true)
                } else if (existingCount <= 0) {
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), false)
                    // 没有可扫描来源时保持空态，不从冷启动流程主动拉起系统授权。
                    AppLogger.d(TAG, "library empty, waiting for user to select folders")
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_empty)
                    )
                } else {
                    requestScheduler.startup(context.getString(R.string.scan_reason_cold_start), false)
                    AppLogger.d(
                        TAG,
                        "cold-start scan skipped: enabled=${AppPreferences.Scanner.coldStartAutoScanEnabled}, " +
                            "selectedFolders=$hasSelectedFolders, existing songs=$existingCount"
                    )
                    _scanUiState.value = ScanUiState.idle().copy(
                        message = context.getString(R.string.scan_library_ready),
                        found = existingCount
                    )
                }
            }
        } else {
            if (AppPreferences.Scanner.automaticScanEnabled) registerMediaStoreObserver()
            AppLogger.w(TAG, "LibraryScannerDependencies not installed, fallback to legacy ScanScheduler")
            if (AppPreferences.Scanner.automaticScanEnabled && AppPreferences.Scanner.coldStartAutoScanEnabled) {
                ScanScheduler.scheduleInitialScan(context)
            }
        }
    }

    fun scanLibrary(restartIfRunning: Boolean = true) {
        val reason = context.getString(R.string.scan_settings_rescan_reason_manual)
        if (resolvedRepository != null) requestScheduler.manual(reason)
        else ScanScheduler.requestDirScan(context, reason)
    }

    fun cancelScan() {
        requestScheduler.cancel()
        ScanScheduler.cancelCurrentScan()
        _scanUiState.value = ScanUiState.cancelled(
            stage = context.getString(R.string.scan_stage_cancelled),
            message = context.getString(R.string.scan_cancelled),
        )
        AppLogger.d(TAG, "scan cancelled by user")
    }

    private fun registerMediaStoreObserver() {
        try {
            val observer = MediaStoreObserver(context)
            observer.register()
            mediaStoreObserver = observer
            AppLogger.d(TAG, "MediaStoreObserver registered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register MediaStoreObserver", e)
        }
    }

    private fun registerMountReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_MEDIA_MOUNTED) {
                    requestScheduler.mounted(this@StartupScanHelper.context.getString(R.string.scan_media_changed))
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        mountReceiver = receiver
    }

    private fun startAutoMediaStoreScan() {
        val repository = resolvedRepository
        if (repository == null) {
            AppLogger.d(TAG, "No AudioLibraryRepository, skipping auto scan")
            return
        }
        scanCoordinator = LibraryScanCoordinator(repository)

        autoObserverJob = scope.launch {
            MediaStoreChangeObserver.observeAudio(context, debounceMs = MEDIA_STORE_DEBOUNCE_MS)
                .collectLatest { change ->
                    if (!AppPreferences.Scanner.automaticScanEnabled) return@collectLatest
                    if (LibraryScanCoordinator.isScanning) return@collectLatest
                    if (!shouldTriggerAutoScanForMediaStoreChange(change.uri)) {
                        AppLogger.d(TAG, "MediaStore change ignored (outside selected folders or no delta): uri=${change.uri}")
                        return@collectLatest
                    }
                    AppLogger.d(TAG, "MediaStore changed inside selected folders: uri=${change.uri}")
                    _scanUiState.value = _scanUiState.value.copy(
                        pendingScan = true,
                        message = context.getString(R.string.scan_media_changed)
                    )
                    requestScheduler.systemChanged(context.getString(R.string.scan_media_changed))
                }
        }
        AppLogger.d(TAG, "Auto MediaStore scan started")
    }

    /**
     * 应用回到前台时执行极轻量目录变化巡检（带节流）。
     * 解决用户在文件管理器/电脑端删除或新增音频后，系统未通知 MediaStore 导致曲库长期不更新的问题。
     */
    fun onAppForegrounded() {
        if (autoObserverJob == null && mediaStoreObserver == null) return
        if (!AppPreferences.Scanner.automaticScanEnabled) return
        if (LibraryScanCoordinator.isScanning) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundCheckAtMs < FOREGROUND_CHECK_INTERVAL_MS) return
        lastForegroundCheckAtMs = now

        if (foregroundCheckJob?.isActive == true) return
        foregroundCheckJob = scope.launch {
            val changed = runCatching { hasSelectedDirectoriesChanged() }.getOrDefault(false)
            if (changed && AppPreferences.Scanner.automaticScanEnabled && !LibraryScanCoordinator.isScanning) {
                AppLogger.i(TAG, "Foreground directory check detected added/deleted files, scheduling incremental scan")
                requestScheduler.systemChanged(context.getString(R.string.scan_media_changed))
            }
        }
    }

    private suspend fun shouldTriggerAutoScanForMediaStoreChange(uri: Uri?): Boolean {
        val scanPaths = AppPreferences.UI.scanPaths.map { normalizeDirPath(it) }.filter { it.isNotBlank() }
        val safUris = AppPreferences.Scanner.musicFolderUris
        if (scanPaths.isEmpty() && safUris.isEmpty()) return false

        // 1. 若变更 URI 指向具体音频记录，先核对其物理路径是否落在已选扫描目录内，
        //    同时屏蔽本应用近期自身下载/播完自动保存写入的文件（已由单曲精扫入库），
        //    彻底避免微信语音、录音、第三方缓存以及自身下载引发的“频繁扫盘提示”。
        if (uri != null && uri.lastPathSegment?.toLongOrNull() != null && safUris.isEmpty()) {
            val changedPath = queryMediaStorePath(uri)
            if (changedPath != null) {
                if (MusicSourceDownloadController.isRecentlyWrittenPath(changedPath)) {
                    AppLogger.d(TAG, "MediaStore change ignored (app-managed online download): path=$changedPath")
                    return false
                }
                val normalized = normalizeDirPath(changedPath)
                val inSelected = scanPaths.any { root ->
                    normalized == root || normalized.startsWith("$root/")
                }
                if (!inSelected) return false
            }
        } else if (MusicSourceDownloadController.hasRecentInternalWrite()) {
            // 泛 MediaStore 通知在内部刚写盘的短窗口内直接忽略，交由单曲精扫与目录基线同步处理
            AppLogger.d(TAG, "Generic MediaStore change ignored during internal download write window: uri=$uri")
            return false
        }

        // 2. 若仅配置了文件系统路径，进一步快速核对所选目录是否真的发生了文件增删或时间戳变更
        if (safUris.isEmpty() && scanPaths.isNotEmpty()) {
            return hasSelectedDirectoriesChanged()
        }
        return true
    }

    private fun queryMediaStorePath(uri: Uri): String? = runCatching {
        context.applicationContext.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    /**
     * 轻量巡检所选文件系统目录：
     * 1. 检查基线中任意本地歌曲物理文件是否已被删除；
     * 2. 检查所选目录及其子目录的 lastModified 时间戳是否相对上次扫描发生了变化。
     */
    private suspend fun hasSelectedDirectoriesChanged(): Boolean {
        val repo = resolvedRepository ?: return false
        val scanPaths = AppPreferences.UI.scanPaths.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (scanPaths.isEmpty()) return false

        val normalizedRoots = scanPaths.map { normalizeDirPath(it) }
        val baseline = repo.getScanBaseline()

        // 1. 检查曲库中位于所选目录下的本地物理文件是否有任意一首已被删除
        for (song in baseline.songs) {
            val path = song.path
            if (path.isBlank() || path.startsWith("online://") || path.startsWith("content://", ignoreCase = true) ||
                path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)
            ) {
                continue
            }
            val normPath = normalizeDirPath(path)
            val underSelected = normalizedRoots.any { root -> normPath == root || normPath.startsWith("$root/") }
            if (underSelected && !File(path).isFile) {
                return true
            }
        }

        // 2. 检查目录及子目录的 lastModified 是否相对基线变化（捕获外部新增/改名/删文件）
        val baselineDirs = baseline.directories
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>()
        scanPaths.forEach { stack.addLast(File(it)) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            if (!dir.exists() || !dir.isDirectory) continue
            val normDir = normalizeDirPath(dir.path)
            if (!visited.add(normDir)) continue
            val dirKey = "fs:$normDir"
            val recordedModified = baselineDirs[dirKey]
            val currentModified = dir.lastModified()
            if (recordedModified == null || (currentModified > 0L && recordedModified != currentModified)) {
                return true
            }
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith(".") && !child.name.startsWith("_")) {
                    stack.addLast(child)
                }
            }
        }
        return false
    }

    private fun normalizeDirPath(path: String): String =
        runCatching { File(path).canonicalPath }
            .getOrElse { File(path).absolutePath }
            .trim()
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)

    private suspend fun runScanLoop(context: Context, request: ScanRequest) {
        try {
            runSingleScan(context, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _scanUiState.value = _scanUiState.value.copy(
                isScanning = false, canCancel = false,
                stage = context.getString(R.string.scan_stage_error), error = e.message,
                message = context.getString(
                    R.string.scan_failed,
                    context.localizedErrorMessage(e, R.string.ui_unknown_error),
                )
            )
            AppLogger.e(TAG, "scan failed", e)
        }
    }

    private suspend fun runSingleScan(context: Context, request: ScanRequest) {
        val reason = request.reason
        // 检查用户是否已选择扫描文件夹
        val scanPaths = com.rawsmusic.module.data.prefs.AppPreferences.UI.scanPaths
        val safFolders = com.rawsmusic.module.data.prefs.AppPreferences.Scanner.musicFolderUris
        if (scanPaths.isEmpty() && safFolders.isEmpty()) {
            AppLogger.w(TAG, "runSingleScan: no filesystem path or SAF folder selected")
            _scanUiState.value = ScanUiState.idle().copy(
                message = context.getString(R.string.scan_select_folder)
            )
            // 触发文件夹选择需求
            com.rawsmusic.module.scanner.ScanStateBus.notifyFolderSelectionNeeded()
            return
        }

        _scanUiState.value = ScanUiState.starting(
            reason = reason,
            stage = context.getString(R.string.scan_stage_start),
            message = context.getString(R.string.scan_preparing, reason),
        )
        val coordinator = scanCoordinator ?: resolvedRepository?.let { LibraryScanCoordinator(it).also { c -> scanCoordinator = c } } ?: return
        var finalSync: LibraryScanCoordinator.Event.DatabaseSyncCompleted? = null

        coordinator.scanAndSync(
            context = context,
            options = TwoStageMediaScanner.Options(
                scannerOptions = MediaStoreScanner.ScanOptions.fromPreferences(),
                customPaths = scanPaths,
                sourceMode = if (request.coldStart) {
                    TwoStageMediaScanner.SourceMode.SELECTED_FOLDERS
                } else {
                    TwoStageMediaScanner.SourceMode.fromPreferences()
                },
                expandCueTracks = true,
                emitEachSong = false,
                usePersistentCache = !request.coldStart,
                saveCacheAtEnd = !request.coldStart,
                incrementalMetadataOnly = request.coldStart,
                publishQuickVisible = !request.coldStart
            )
        ).collect { event ->
            when (event) {
                is LibraryScanCoordinator.Event.ScannerEvent -> handleScannerEvent(reason, event.event)
                is LibraryScanCoordinator.Event.DatabaseSyncStarted -> {
                    val stageText = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> context.getString(R.string.scan_stage_quick_write)
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> context.getString(R.string.scan_stage_background_enrich)
                        LibraryScanCoordinator.SyncPhase.FINAL -> context.getString(R.string.scan_stage_final_sync)
                    }
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, stage = stageText,
                        message = context.getString(R.string.scan_sync_progress, reason, stageText, event.newCount)
                    )
                }
                is LibraryScanCoordinator.Event.DatabaseSyncCompleted -> {
                    if (event.phase == LibraryScanCoordinator.SyncPhase.FINAL) finalSync = event
                    val message = when (event.phase) {
                        LibraryScanCoordinator.SyncPhase.QUICK_VISIBLE -> context.getString(R.string.scan_quick_visible, reason, event.upserted)
                        LibraryScanCoordinator.SyncPhase.ENRICHED_BATCH -> context.getString(R.string.scan_background_enriched, reason, event.upserted)
                        LibraryScanCoordinator.SyncPhase.FINAL -> context.getString(R.string.scan_database_synced, reason, event.upserted, event.deleted, event.unchanged)
                    }
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_database_complete),
                        dbUpserted = event.upserted, dbDeleted = event.deleted, dbUnchanged = event.unchanged,
                        message = message
                    )
                }
                is LibraryScanCoordinator.Event.VisibleCompleted -> {
                    _songs.value = event.songs
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = true, canCancel = true, pendingScan = false,
                        stage = context.getString(R.string.scan_stage_visible), progress = _scanUiState.value.progress.coerceAtLeast(0.40f),
                        found = event.found, timeMs = event.timeMs,
                        message = context.getString(R.string.scan_visible_progress, reason, event.found)
                    )
                }
                is LibraryScanCoordinator.Event.Completed -> {
                    _songs.value = event.songs
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = false, canCancel = false, pendingScan = false,
                        stage = context.getString(R.string.scan_stage_complete), progress = 1f, found = event.songs.size, timeMs = event.timeMs,
                        message = context.getString(R.string.scan_completed, reason, event.songs.size, event.timeMs)
                    )
                    val stats = finalSync
                    // 仅手动触发扫描时弹出结果 Toast；后台自动增量扫描静默完成，避免听歌过程中频繁弹窗打扰
                    if (!request.coldStart) {
                        LibraryScanToast.show(
                            context,
                            LibraryScanSummary(
                                scanned = event.songs.size,
                                added = stats?.added ?: 0,
                                updated = stats?.updated ?: 0,
                                removed = stats?.deleted ?: 0,
                                elapsedMs = event.timeMs,
                                mode = if (event.incremental) {
                                    LibraryScanSummary.Mode.INCREMENTAL
                                } else {
                                    LibraryScanSummary.Mode.FULL
                                },
                                skippedDirectories = event.skippedDirectoryCount,
                                reused = event.baselineHits,
                                metadataParsed = event.metadataParsed
                            )
                        )
                    }
                }
                is LibraryScanCoordinator.Event.Error -> {
                    _scanUiState.value = _scanUiState.value.copy(
                        isScanning = false, canCancel = false, stage = context.getString(R.string.scan_stage_error),
                        error = event.message, message = context.getString(R.string.scan_error_with_reason, reason, event.message)
                    )
                }
            }
        }
    }

    private fun handleScannerEvent(reason: String, event: TwoStageMediaScanner.Event) {
        when (event) {
            is TwoStageMediaScanner.Event.Started -> {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, reason = reason, stage = context.getString(R.string.scan_stage_start),
                    total = event.totalEstimated, scanned = 0, progress = 0f,
                    message = context.getString(R.string.scan_started, reason, event.totalEstimated)
                )
            }
            is TwoStageMediaScanner.Event.CacheLoaded -> {
                _scanUiState.value = _scanUiState.value.copy(
                    stage = context.getString(R.string.scan_stage_cache), message = context.getString(R.string.scan_cache_loaded, reason, event.cachedCount)
                )
            }
            is TwoStageMediaScanner.Event.QuickProgress -> {
                val p = if (event.total > 0) event.scanned.toFloat() / event.total * 0.35f else 0f
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_quick),
                    scanned = event.scanned, total = event.total, progress = p.coerceIn(0f, 0.35f),
                    message = context.getString(R.string.scan_progress, reason, event.message, event.scanned, event.total)
                )
            }
            is TwoStageMediaScanner.Event.QuickCompleted -> {
                // QuickCompleted still contains one physical row per media file and has not
                // expanded embedded/external CUE yet. Publishing it here can temporarily erase
                // already-persisted CUE virtual tracks that share the same path. The coordinator
                // publishes VisibleCompleted immediately after the quick Room sync; only that
                // repository snapshot is safe to expose to the library UI.
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_quick_complete),
                    found = event.found, progress = 0.35f,
                    message = context.getString(R.string.scan_quick_found, reason, event.found)
                )
            }
            is TwoStageMediaScanner.Event.EnrichProgress -> {
                val ep = if (event.total > 0) event.processed.toFloat() / event.total else 0f
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_enrich),
                    scanned = event.processed, total = event.total,
                    progress = (0.35f + ep * 0.55f).coerceIn(0.35f, 0.90f),
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = context.getString(R.string.scan_enrich_progress, reason, event.message, event.processed, event.total, event.cacheHits, event.enrichedCount)
                )
            }
            is TwoStageMediaScanner.Event.SongEnriched -> {
                // Progress only: library rows become visible through committed repository state.
            }
            is TwoStageMediaScanner.Event.EnrichBatchCompleted -> {
                // 后台懒同步批次，UI 进度由 EnrichProgress 统一更新。
            }
            is TwoStageMediaScanner.Event.FullyCompleted -> {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = true, canCancel = true, stage = context.getString(R.string.scan_stage_prepare_sync),
                    progress = 0.90f, found = event.found,
                    cacheHits = event.cacheHits, enrichedCount = event.enrichedCount,
                    message = context.getString(R.string.scan_enrich_completed, reason, event.found, event.cacheHits, event.enrichedCount)
                )
            }
            is TwoStageMediaScanner.Event.Error -> {
                _scanUiState.value = _scanUiState.value.copy(
                    isScanning = false, canCancel = false, stage = context.getString(R.string.scan_stage_error),
                    error = event.message, message = context.getString(R.string.scan_error_with_reason, reason, event.message)
                )
            }
        }
    }

    fun destroy() {
        requestScheduler.destroy()
        mountReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        mountReceiver = null
        mediaStoreObserver?.unregister()
        mediaStoreObserver = null
        autoObserverJob?.cancel()
        foregroundCheckJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "StartupScanHelper"
        private const val MEDIA_STORE_DEBOUNCE_MS = 3_500L
        private const val FOREGROUND_CHECK_INTERVAL_MS = 15_000L
        private val coldStartScanRequested = AtomicBoolean(false)
    }

    private data class ScanRequest(val reason: String, val coldStart: Boolean)
}
