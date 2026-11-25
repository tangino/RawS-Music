package com.rawsmusic.module.data.source.playback

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.util.Log
import android.net.Uri
import android.os.Environment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.source.kuwo.KuwoCarAudioEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Status for downloads owned by the online-source portal. */
enum class MusicSourceDownloadStatus {
    Resolving,
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

data class MusicSourceDownloadTask(
    val id: String,
    val item: RawSourceMediaItem,
    val requestedQuality: RawSourceQuality,
    val resolvedQuality: RawSourceQuality = requestedQuality,
    val status: MusicSourceDownloadStatus = MusicSourceDownloadStatus.Resolving,
    val systemDownloadId: Long = -1L,
    val fileName: String = "",
    val mimeType: String = "audio/*",
    val localUri: String = "",
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val libraryScanRequested: Boolean = false,
    val mediaMetadataFinalized: Boolean = false,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isActive: Boolean
        get() = status == MusicSourceDownloadStatus.Resolving ||
            status == MusicSourceDownloadStatus.Queued ||
            status == MusicSourceDownloadStatus.Downloading ||
            status == MusicSourceDownloadStatus.Paused
}

/**
 * Resolves online audio in RawSMusic's isolated source runtime, then delegates the byte transfer
 * to Android DownloadManager so the task can continue after the Compose page leaves the screen.
 */
object MusicSourceDownloadController {
    private val directDownloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private const val STORAGE_KEY = "music_source_download_tasks_v1"
    private const val MAX_TASKS = 80
    private const val MAX_PAYLOAD_CHARS = 64 * 1024
    private const val INTERNAL_WRITE_SUPPRESS_TTL_MS = 30_000L
    const val ACTION_DOWNLOAD_MEDIA_READY = "com.rawsmusic.action.ONLINE_DOWNLOAD_MEDIA_READY"
    const val EXTRA_DOWNLOAD_PATH = "download_path"
    const val EXTRA_DOWNLOAD_ID = "download_id"
    const val PUBLIC_DOWNLOAD_DIRECTORY = "RawSMusic"

    private val recentlyWrittenPaths = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastInternalWriteAtMs: Long = 0L

    /** 登记由本应用自身写入的下载文件路径，用于短期抑制 MediaStore 回调引发的重复全盘扫描。 */
    fun recordInternalMediaWrite(path: String) {
        val normalized = normalizePathForSuppression(path)
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        recentlyWrittenPaths[normalized] = now
        lastInternalWriteAtMs = now
        // 顺手清理已过期记录，避免长期运行内存增长
        if (recentlyWrittenPaths.size > 32) {
            val cutoff = now - INTERNAL_WRITE_SUPPRESS_TTL_MS
            recentlyWrittenPaths.entries.removeIf { it.value < cutoff }
        }
    }

    /** 判断指定物理路径是否为本应用近期自身写入并已单曲入库的下载文件。 */
    fun isRecentlyWrittenPath(path: String, ttlMs: Long = INTERNAL_WRITE_SUPPRESS_TTL_MS): Boolean {
        val normalized = normalizePathForSuppression(path)
        if (normalized.isBlank()) return false
        val recordedAt = recentlyWrittenPaths[normalized] ?: return false
        val age = System.currentTimeMillis() - recordedAt
        if (age in 0..ttlMs) return true
        recentlyWrittenPaths.remove(normalized, recordedAt)
        return false
    }

    /** 判断最近短窗口内是否刚发生过本应用内部下载写盘，用于抑制泛 MediaStore 通知的竞态扫盘。 */
    fun hasRecentInternalWrite(windowMs: Long = 5_000L): Boolean {
        val last = lastInternalWriteAtMs
        return last > 0L && (System.currentTimeMillis() - last) in 0..windowMs
    }

    private fun normalizePathForSuppression(path: String): String =
        runCatching { File(path).canonicalPath }
            .getOrElse { File(path).absolutePath }
            .trim()
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 限制同时进行的直连下载并发数，避免批量下载时抢占带宽导致连接被系统或 CDN 中断
    private val directDownloadSemaphore = Semaphore(2)
    private val lock = Any()
    private val resolveJobs = linkedMapOf<String, Job>()
    private val finalizingTaskIds = linkedSetOf<String>()
    private val mutableTasks = MutableStateFlow(loadTasks())
    val tasks = mutableTasks.asStateFlow()

    @Volatile
    private var applicationContext: Context? = null
    @Volatile
    private var initialized = false
    private var monitorJob: Job? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        val firstInitialization = synchronized(lock) {
            if (initialized) false else {
                initialized = true
                true
            }
        }
        if (!firstInitialization) {
            ensureMonitor()
            return
        }
        val interruptedIds = mutableTasks.value
            .filter { it.status == MusicSourceDownloadStatus.Resolving }
            .map { it.id }
            .toSet()
        if (interruptedIds.isNotEmpty()) {
            updateTasks { current ->
                current.map { task ->
                    if (task.id in interruptedIds) {
                        task.copy(
                            status = MusicSourceDownloadStatus.Failed,
                            error = "地址解析被应用重启中断，可点击重试",
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    } else task
                }
            }
        }
        val unfinishedLibraryImports = mutableTasks.value.filter {
            it.status == MusicSourceDownloadStatus.Completed && !it.mediaMetadataFinalized
        }
        unfinishedLibraryImports.forEach(::publishCompletedDownload)
        ensureMonitor()
    }

    fun enqueue(
        context: Context,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): String {
        initialize(context)
        require(item.mediaType == RawSourceMediaType.Music) { "仅歌曲结果可以下载" }
        val activeDuplicate = mutableTasks.value.firstOrNull {
            it.item.stableIdentity == item.stableIdentity &&
                it.requestedQuality == quality &&
                it.isActive
        }
        if (activeDuplicate != null) return activeDuplicate.id

        val task = MusicSourceDownloadTask(
            id = UUID.randomUUID().toString(),
            item = item,
            requestedQuality = quality,
        )
        updateTasks { current -> (listOf(task) + current).take(MAX_TASKS) }
        launchResolution(task.id)
        return task.id
    }

    /**
     * 歌曲播放完自动保存入口：目录、音质、元数据嵌入与曲库扫描完全复用手动下载管线 [enqueue]，
     * 并在入队前检查是否已有活跃任务或目标下载目录中已存在有效同名音频文件，避免重复下载。
     *
     * @return 新建/已存在的活跃任务 ID；若已保存于本地下载目录则跳过并返回 null。
     */
    fun enqueueAutoSaveIfNeeded(
        context: Context,
        item: RawSourceMediaItem,
        quality: RawSourceQuality = RawSourceQuality.fromKey(AppPreferences.Download.defaultQuality),
    ): String? {
        if (item.mediaType != RawSourceMediaType.Music) return null
        initialize(context)

        val existingTasks = mutableTasks.value
        val activeTask = existingTasks.firstOrNull {
            it.item.stableIdentity == item.stableIdentity && it.isActive
        }
        if (activeTask != null) {
            AppLogger.d("MusicSourceDownload", "AutoSave skipped (already active): ${item.title}")
            return null
        }

        val dir = publicDownloadDirectory()
        val completedTask = existingTasks.firstOrNull {
            it.item.stableIdentity == item.stableIdentity && it.status == MusicSourceDownloadStatus.Completed
        }
        if (completedTask != null) {
            val completedFile = completedTask.localUri
                .takeIf(String::isNotBlank)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                ?.path
                ?.let(::File)
                ?: completedTask.fileName.takeIf(String::isNotBlank)?.let { File(dir, it) }
            if (completedFile != null && isValidLocalAudioFile(completedFile)) {
                AppLogger.d("MusicSourceDownload", "AutoSave skipped (completed file exists): ${completedFile.name}")
                return null
            }
        }

        if (hasExistingDownloadedFile(dir, item)) {
            AppLogger.d("MusicSourceDownload", "AutoSave skipped (matching file exists in download dir): ${item.title}")
            return null
        }

        AppLogger.i("MusicSourceDownload", "AutoSave on complete: enqueue '${item.title}' quality=$quality dir=${dir.absolutePath}")
        return enqueue(context, item, quality)
    }

    private fun hasExistingDownloadedFile(directory: File, item: RawSourceMediaItem): Boolean {
        if (!directory.isDirectory) return false
        val artist = item.artists.joinToString(" & ").ifBlank { "" }
        val title = item.title.ifBlank { "未知曲目" }
        val rawBase = if (artist.isNotBlank()) "$artist - $title" else title
        val base = sanitizeFileName(rawBase)
        val extensions = listOf("flac", "mp3", "m4a", "mp4", "aac", "ogg", "opus", "wav")
        return extensions.any { ext ->
            val candidate = File(directory, "$base.$ext")
            candidate.isFile && isValidLocalAudioFile(candidate)
        }
    }

    fun retry(context: Context, taskId: String) {
        initialize(context)
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        task.systemDownloadId.takeIf { it >= 0L }?.let { systemId ->
            downloadManager()?.remove(systemId)
        }
        updateTask(taskId) {
            it.copy(
                status = MusicSourceDownloadStatus.Resolving,
                systemDownloadId = -1L,
                localUri = "",
                bytesDownloaded = 0L,
                totalBytes = -1L,
                error = "",
                updatedAtMs = System.currentTimeMillis(),
                libraryScanRequested = false,
                mediaMetadataFinalized = false,
            )
        }
        launchResolution(taskId)
    }

    fun cancel(taskId: String) {
        resolveJobs.remove(taskId)?.cancel()
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        task.systemDownloadId.takeIf { it >= 0L }?.let { downloadManager()?.remove(it) }
        updateTask(taskId) {
            it.copy(
                status = MusicSourceDownloadStatus.Cancelled,
                error = "已取消",
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Removes only the task record. A completed public music file is intentionally preserved. */
    fun removeRecord(taskId: String) {
        val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.isActive) cancel(taskId)
        updateTasks { current -> current.filterNot { it.id == taskId } }
    }

    fun clearFinished() {
        updateTasks { current -> current.filter { it.isActive } }
    }

    private fun launchResolution(taskId: String) {
        resolveJobs.remove(taskId)?.cancel()
        resolveJobs[taskId] = scope.launch {
            try {
                val context = applicationContext ?: error("下载服务尚未初始化")
                val task = mutableTasks.value.firstOrNull { it.id == taskId } ?: return@launch

                // 1. 优先尝试【酷我车载母带引擎】（192kHz/24bit母带或FLAC无损直链 + QMC流式解密）
                val kuwoSuccess = runCatching {
                    tryDownloadViaKuwoEngine(context, task)
                }.getOrElse { error ->
                    AppLogger.w("MusicSourceDownload", "Kuwo car engine download failed, fallback to default resolver: ${error.message}")
                    false
                }

                if (kuwoSuccess) {
                    AppLogger.i("MusicSourceDownload", "Kuwo car engine download completed successfully for taskId=$taskId")
                    return@launch
                }

                // 2. 酷我匹配失败或异常，降级唤起【洛雪源 / 网易源 / 咪咕源】等默认解析通道兜底
                AppLogger.i("MusicSourceDownload", "Falling back to default MusicSourceAudioResolver for taskId=$taskId")
                val resolved = MusicSourceAudioResolver.resolve(
                    context = context,
                    item = task.item,
                    requestedQuality = task.requestedQuality,
                )
                val systemId = enqueueSystemDownload(context, task, resolved)
                updateTask(taskId) {
                    val descriptor = buildFileDescriptor(task.item, resolved)
                    it.copy(
                        resolvedQuality = resolved.quality,
                        status = MusicSourceDownloadStatus.Queued,
                        systemDownloadId = systemId,
                        fileName = descriptor.fileName,
                        mimeType = descriptor.mimeType,
                        error = "",
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                ensureMonitor()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateTask(taskId) {
                    it.copy(
                        status = MusicSourceDownloadStatus.Failed,
                        error = error.message.orEmpty().ifBlank { "下载任务创建失败" }.take(1_024),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            } finally {
                val runningJob = kotlin.coroutines.coroutineContext[Job]
                synchronized(lock) {
                    if (resolveJobs[taskId] === runningJob) resolveJobs.remove(taskId)
                }
            }
        }
    }

    /**
     * 优先通过酷我车载母带引擎匹配高规格音源并进行流式解密下载。
     */
    private suspend fun tryDownloadViaKuwoEngine(context: Context, task: MusicSourceDownloadTask): Boolean {
        val title = task.item.title
        val artist = task.item.artists.joinToString(" & ")
        val durationMs = task.item.durationMs

        AppLogger.i("MusicSourceDownload", "KW_ENGINE: Matching best song for '$title' - '$artist' (duration=${durationMs}ms)")
        val match = KuwoCarAudioEngine.matchBestSong(title, artist, durationMs) ?: run {
            AppLogger.w("MusicSourceDownload", "KW_ENGINE: No match found for '$title' - '$artist'")
            return false
        }

        val primaryResource = KuwoCarAudioEngine.resolveDownloadResource(
            songMatch = match,
            requestedQuality = task.requestedQuality,
            allowEncrypted = true,
        ) ?: run {
            AppLogger.w("MusicSourceDownload", "KW_ENGINE: Failed to resolve download resource for rid=${match.rid}")
            return false
        }

        if (downloadKuwoResource(context, task, artist, title, primaryResource)) {
            return true
        }

        // 若加密母带解密校验失败或断流失败，自动降级尝试未加密无损 FLAC / 320K MP3，绝不落盘损坏文件
        if (primaryResource.isEncrypted) {
            AppLogger.w("MusicSourceDownload", "KW_ENGINE: Encrypted stream failed, retrying unencrypted lossless for rid=${match.rid}")
            val fallbackResource = KuwoCarAudioEngine.resolveDownloadResource(
                songMatch = match,
                requestedQuality = task.requestedQuality,
                allowEncrypted = false,
            ) ?: return false
            return downloadKuwoResource(context, task, artist, title, fallbackResource)
        }
        return false
    }

    private suspend fun downloadKuwoResource(
        context: Context,
        task: MusicSourceDownloadTask,
        artist: String,
        title: String,
        resource: KuwoCarAudioEngine.KuwoDownloadResource,
    ): Boolean {
        // 先标为排队中，等待获取并发下载槽位
        updateTask(task.id) {
            it.copy(
                status = MusicSourceDownloadStatus.Queued,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        return directDownloadSemaphore.withPermit {
            downloadKuwoResourceInternal(context, task, artist, title, resource)
        }
    }

    private suspend fun downloadKuwoResourceInternal(
        context: Context,
        task: MusicSourceDownloadTask,
        artist: String,
        title: String,
        resource: KuwoCarAudioEngine.KuwoDownloadResource,
    ): Boolean {
        AppLogger.i("MusicSourceDownload", "KW_ENGINE: Downloading targetExt=${resource.targetExtension}, bitrate=${resource.bitrate}k, encrypted=${resource.isEncrypted}")

        val publicDir = publicDownloadDirectory().apply { mkdirs() }
        ensurePublicDownloadFolderSelected(publicDir)

        val ext = resource.targetExtension
        val mimeType = when (ext.lowercase(Locale.ROOT)) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }

        val rawBase = if (artist.isNotBlank()) "$artist - $title" else title
        val base = sanitizeFileName(rawBase)
        var candidateName = "$base.$ext"
        val defaultExisting = File(publicDir, candidateName)
        // 如果同名旧文件存在且完整有效，避免覆盖另存编号；若同名旧文件已损坏（如旧版未解密残余），直接覆盖修复
        if (defaultExisting.exists() && isValidLocalAudioFile(defaultExisting)) {
            var index = 1
            while (File(publicDir, "$base ($index).$ext").let { it.exists() && isValidLocalAudioFile(it) }) {
                index++
            }
            candidateName = "$base ($index).$ext"
        }

        val outputFile = File(publicDir, candidateName)
        val tempFile = File(publicDir, "$candidateName.downloading")
        runCatching { if (tempFile.exists()) tempFile.delete() }

        val resolvedQuality = when {
            resource.bitrate >= 10000 -> RawSourceQuality.HiRes
            resource.bitrate >= 2000 -> RawSourceQuality.Lossless
            resource.bitrate >= 320 -> RawSourceQuality.High
            else -> RawSourceQuality.Standard
        }

        updateTask(task.id) {
            it.copy(
                resolvedQuality = resolvedQuality,
                status = MusicSourceDownloadStatus.Downloading,
                fileName = candidateName,
                mimeType = mimeType,
                localUri = Uri.fromFile(outputFile).toString(),
                bytesDownloaded = 0L,
                totalBytes = -1L,
                error = "",
                updatedAtMs = System.currentTimeMillis(),
            )
        }

        // 发起流式下载（支持 HTTP Range 断点续传重试，QMCv2 流式解密天然支持任意偏移随机续传）
        val cipher = if (resource.isEncrypted) resource.qmcCipher else null
        var totalRead = 0L
        var expectedTotalBytes = -1L
        var headerChecked = false
        var headerValid = true
        var downloadSucceeded = false
        val maxAttempts = 5

        try {
            for (attempt in 1..maxAttempts) {
                coroutineContext.ensureActive()
                val reqBuilder = Request.Builder()
                    .url(resource.downloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                if (totalRead > 0L) {
                    reqBuilder.header("Range", "bytes=$totalRead-")
                }

                try {
                    directDownloadClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            AppLogger.w("MusicSourceDownload", "KW_ENGINE: Direct download HTTP ${response.code} (attempt=$attempt)")
                            if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                                return false
                            }
                            throw IOException("HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty response body")
                        val isResumed = response.code == 206 && totalRead > 0L
                        if (!isResumed) {
                            // 服务端不支持 Range 或首次请求，从头写入
                            totalRead = 0L
                            headerChecked = false
                            expectedTotalBytes = body.contentLength()
                        } else if (expectedTotalBytes <= 0L) {
                            val remaining = body.contentLength()
                            if (remaining > 0L) expectedTotalBytes = totalRead + remaining
                        }

                        if (expectedTotalBytes > 0L) {
                            updateTask(task.id) {
                                it.copy(totalBytes = expectedTotalBytes, updatedAtMs = System.currentTimeMillis())
                            }
                        }

                        var lastUpdate = System.currentTimeMillis()
                        body.byteStream().use { input ->
                            FileOutputStream(tempFile, isResumed).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    coroutineContext.ensureActive()
                                    cipher?.decrypt(buffer, totalRead, bytesRead)
                                    if (!headerChecked && totalRead == 0L && bytesRead >= 4) {
                                        headerChecked = true
                                        if (!isValidAudioHeader(buffer, bytesRead, ext)) {
                                            headerValid = false
                                            AppLogger.w(
                                                "MusicSourceDownload",
                                                "KW_ENGINE: Invalid audio header after decrypt for $candidateName " +
                                                    "(magic=${buffer.take(4).joinToString("") { "%02x".format(it) }})"
                                            )
                                            break
                                        }
                                    }
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate >= 200L) {
                                        lastUpdate = now
                                        updateTask(task.id) {
                                            it.copy(bytesDownloaded = totalRead, updatedAtMs = now)
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    }

                    if (!headerValid) {
                        runCatching { tempFile.delete() }
                        return false
                    }

                    // 校验是否完整接收了全部字节（防止服务端提前关闭流导致半截文件）
                    if (expectedTotalBytes > 0L && totalRead < expectedTotalBytes) {
                        throw IOException("Incomplete download: $totalRead/$expectedTotalBytes")
                    }
                    downloadSucceeded = true
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (io: IOException) {
                    AppLogger.w(
                        "MusicSourceDownload",
                        "KW_ENGINE: Stream interrupted for $candidateName at ${totalRead}/${expectedTotalBytes} (attempt=$attempt/$maxAttempts): ${io.message}"
                    )
                    if (attempt >= maxAttempts) {
                        runCatching { tempFile.delete() }
                        return false
                    }
                    delay(1500L * attempt)
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { tempFile.delete() }
            throw cancelled
        }

        if (!downloadSucceeded || !headerValid) {
            runCatching { tempFile.delete() }
            return false
        }

        if (tempFile.exists()) {
            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                runCatching {
                    tempFile.copyTo(outputFile, overwrite = true)
                    tempFile.delete()
                }
            }
        }

        if (!outputFile.exists() || outputFile.length() == 0L || !isValidLocalAudioFile(outputFile)) {
            AppLogger.w("MusicSourceDownload", "KW_ENGINE: Output file missing, empty, or invalid header")
            runCatching { outputFile.delete() }
            return false
        }

        // 写入正确的 ID3/FLAC 歌曲标签与内嵌高清封面
        val completedTask = mutableTasks.value.firstOrNull { it.id == task.id } ?: task.copy(
            fileName = candidateName,
            mimeType = mimeType,
            localUri = Uri.fromFile(outputFile).toString(),
            resolvedQuality = resolvedQuality,
        )
        val warnings = runCatching {
            finalizeDownloadedMetadata(context, completedTask, outputFile)
        }.getOrElse { err ->
            listOf("标签处理异常: ${err.message}")
        }

        updateTask(task.id) {
            it.copy(
                status = MusicSourceDownloadStatus.Completed,
                bytesDownloaded = outputFile.length(),
                totalBytes = outputFile.length(),
                mediaMetadataFinalized = true,
                libraryScanRequested = true,
                error = warnings.joinToString("；").take(1_024),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        PlaylistStore.getInstance(context.applicationContext).invalidateMatchCache()

        // 登记内部自写路径，避免随后的 MediaScannerConnection 回调再次触发全盘扫描提示
        recordInternalMediaWrite(outputFile.absolutePath)
        // 触发 Android 媒体扫描并广播完成事件
        MediaScannerConnection.scanFile(
            context,
            arrayOf(outputFile.absolutePath),
            arrayOf(mimeType),
        ) { path, _ ->
            context.sendBroadcast(
                Intent(ACTION_DOWNLOAD_MEDIA_READY)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_DOWNLOAD_PATH, path ?: outputFile.absolutePath)
                    .putExtra(EXTRA_DOWNLOAD_ID, -1L)
            )
        }

        return true
    }

    private fun enqueueSystemDownload(
        context: Context,
        task: MusicSourceDownloadTask,
        resolved: RawResolvedAudioSource,
    ): Long {
        val uri = Uri.parse(resolved.url)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "下载地址不是 HTTP/HTTPS"
        }
        require(!uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")) {
            "暂不支持 HLS 分片流下载"
        }
        val descriptor = buildFileDescriptor(task.item, resolved)
        val request = DownloadManager.Request(uri)
            .setTitle(task.item.title.ifBlank { descriptor.fileName })
            .setDescription(
                listOf(
                    task.item.artists.joinToString(" / "),
                    descriptor.quality.name,
                ).filter(String::isNotBlank).joinToString(" · ")
            )
            .setMimeType(descriptor.mimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val headers = linkedMapOf<String, String>()
        headers.putAll(resolved.headers)
        resolved.userAgent?.takeIf(String::isNotBlank)?.let { userAgent ->
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = userAgent
            }
        }
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) request.addRequestHeader(name, value)
        }

        val publicDirectory = publicDownloadDirectory()
        runCatching { publicDirectory.mkdirs() }
        ensurePublicDownloadFolderSelected(publicDirectory)
        val defaultMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (publicDirectory.absolutePath.startsWith(defaultMusicDir.absolutePath)) {
            val subPath = publicDirectory.absolutePath.removePrefix(defaultMusicDir.absolutePath).trimStart('/', '\\')
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MUSIC,
                if (subPath.isNotBlank()) "$subPath/${descriptor.fileName}" else descriptor.fileName,
            )
        } else {
            request.setDestinationUri(Uri.fromFile(File(publicDirectory, descriptor.fileName)))
        }
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun ensureMonitor() {
        if (monitorJob?.isActive == true) return
        if (mutableTasks.value.none { it.systemDownloadId >= 0L && it.isActive }) return
        monitorJob = scope.launch {
            while (isActive) {
                val active = mutableTasks.value.filter { it.systemDownloadId >= 0L && it.isActive }
                if (active.isEmpty()) break
                refreshSystemDownloads(active)
                delay(750L)
            }
        }
    }

    /** Called by the app-level DownloadManager receiver, including after process recreation. */
    suspend fun handleSystemDownloadCompleted(context: Context, systemDownloadId: Long) {
        if (systemDownloadId < 0L) return
        initialize(context)
        withContext(Dispatchers.IO) {
            val task = mutableTasks.value.firstOrNull { it.systemDownloadId == systemDownloadId } ?: return@withContext
            refreshSystemDownloads(listOf(task))
        }
    }

    private fun refreshSystemDownloads(active: List<MusicSourceDownloadTask>) {
        val manager = downloadManager() ?: return
        val bySystemId = active.associateBy { it.systemDownloadId }
        val query = DownloadManager.Query().setFilterById(*bySystemId.keys.toLongArray())
        val updates = linkedMapOf<String, MusicSourceDownloadTask>()
        val completed = mutableListOf<MusicSourceDownloadTask>()
        runCatching {
            manager.query(query)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val bytesIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIndex = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (cursor.moveToNext()) {
                    val systemId = cursor.getLong(idIndex)
                    val current = bySystemId[systemId] ?: continue
                    val status = cursor.getInt(statusIndex)
                    val mapped = when (status) {
                        DownloadManager.STATUS_PENDING -> MusicSourceDownloadStatus.Queued
                        DownloadManager.STATUS_RUNNING -> MusicSourceDownloadStatus.Downloading
                        DownloadManager.STATUS_PAUSED -> MusicSourceDownloadStatus.Paused
                        DownloadManager.STATUS_SUCCESSFUL -> MusicSourceDownloadStatus.Completed
                        DownloadManager.STATUS_FAILED -> MusicSourceDownloadStatus.Failed
                        else -> current.status
                    }
                    val reason = cursor.getInt(reasonIndex)
                    val error = if (mapped == MusicSourceDownloadStatus.Failed) {
                        "系统下载失败（$reason）"
                    } else {
                        ""
                    }
                    val shouldRequestScan = mapped == MusicSourceDownloadStatus.Completed && !current.mediaMetadataFinalized
                    val updated = current.copy(
                        status = mapped,
                        bytesDownloaded = cursor.getLong(bytesIndex).coerceAtLeast(0L),
                        totalBytes = cursor.getLong(totalIndex),
                        localUri = if (uriIndex >= 0) cursor.getString(uriIndex).orEmpty() else current.localUri,
                        error = error,
                        updatedAtMs = System.currentTimeMillis(),
                        libraryScanRequested = current.libraryScanRequested,
                    )
                    updates[current.id] = updated
                    if (shouldRequestScan) completed += updated
                }
            }
        }
        if (updates.isNotEmpty()) {
            updateTasks { current -> current.map { updates[it.id] ?: it } }
        }
        completed.forEach(::publishCompletedDownload)
    }

    private fun publishCompletedDownload(task: MusicSourceDownloadTask) {
        val context = applicationContext ?: return
        val accepted = synchronized(lock) { finalizingTaskIds.add(task.id) }
        if (!accepted) return
        scope.launch {
            try {
                val directory = publicDownloadDirectory().apply { mkdirs() }
                ensurePublicDownloadFolderSelected(directory)
                val expectedOutput = File(directory, task.fileName)
                val reportedOutput = task.localUri
                    .takeIf(String::isNotBlank)
                    ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
                    ?.takeIf { uri -> uri.scheme.equals("file", ignoreCase = true) }
                    ?.path
                    ?.let(::File)
                    ?.takeIf(File::exists)
                var output = when {
                    reportedOutput == null -> expectedOutput
                    reportedOutput.isDirectChildOf(directory) -> reportedOutput
                    expectedOutput.exists() -> expectedOutput
                    migrateDownloadFile(reportedOutput, expectedOutput) -> expectedOutput
                    else -> reportedOutput
                }
                if (!output.exists()) {
                    val legacy = File(
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                            "RawSMusic/Online",
                        ),
                        task.fileName,
                    )
                    if (legacy.exists()) {
                        output = if (migrateDownloadFile(legacy, expectedOutput)) expectedOutput else legacy
                    }
                }
                val notifyReady: (String) -> Unit = { path ->
                    context.sendBroadcast(
                        Intent(ACTION_DOWNLOAD_MEDIA_READY)
                            .setPackage(context.packageName)
                            .putExtra(EXTRA_DOWNLOAD_PATH, path)
                            .putExtra(EXTRA_DOWNLOAD_ID, task.systemDownloadId)
                    )
                }
                if (!output.exists()) {
                    updateTask(task.id) { current ->
                        current.copy(
                            error = "下载完成，但没有找到输出文件",
                            libraryScanRequested = true,
                            mediaMetadataFinalized = true,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                    notifyReady(output.absolutePath)
                    return@launch
                }

                val warnings = runCatching {
                    finalizeDownloadedMetadata(context, task, output)
                }.getOrElse { error ->
                    listOf("标签处理失败：${error.message.orEmpty().ifBlank { "未知错误" }}")
                }
                updateTask(task.id) { current ->
                    current.copy(
                        error = warnings.joinToString("；").take(1_024),
                        libraryScanRequested = true,
                        mediaMetadataFinalized = true,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                PlaylistStore.getInstance(context.applicationContext).invalidateMatchCache()
                recordInternalMediaWrite(output.absolutePath)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(output.absolutePath),
                    arrayOf(task.mimeType),
                ) { path, _ -> notifyReady(path ?: output.absolutePath) }
            } finally {
                synchronized(lock) { finalizingTaskIds.remove(task.id) }
            }
        }
    }

    private suspend fun finalizeDownloadedMetadata(
        context: Context,
        task: MusicSourceDownloadTask,
        output: File,
    ): List<String> = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        if (!TagLibBridge.isLoaded() || !TagLibBridge.isSupported(output.absolutePath)) {
            warnings += "当前格式不支持写入标签和内嵌封面"
            return@withContext warnings
        }

        val metadata = linkedMapOf(
            "title" to task.item.title,
            "artist" to task.item.artists.joinToString(" / "),
            "album" to task.item.album,
            "album_artist" to task.item.artists.joinToString(" / "),
        ).filterValues(String::isNotBlank)
        if (metadata.isNotEmpty() && !TagLibBridge.writeMetadata(output.absolutePath, metadata)) {
            warnings += "歌曲标签写入失败"
        }

        val resolvedArtworkUrl = task.item.artworkUrl.ifBlank {
            val songKey = com.rawsmusic.core.common.model.onlineSongKey(task.item.sourceId, task.item.remoteId)
            PlaylistStore.getInstance(context.applicationContext)
                .artworkFallbackDelegate
                ?.getCachedArtworkUrl(task.item.title, task.item.artists.joinToString(" / "), songKey)
                .orEmpty()
        }
        if (resolvedArtworkUrl.isNotBlank()) {
            val artworkItem = if (resolvedArtworkUrl == task.item.artworkUrl) {
                task.item
            } else {
                task.item.copy(artworkUrl = resolvedArtworkUrl)
            }
            val artwork = runCatching {
                MusicSourceArtworkRepository.ensureLocalFile(context, artworkItem)
            }.getOrNull()
            if (artwork == null) {
                warnings += "专辑图下载失败"
            } else {
                val mime = detectArtworkMime(artwork)
                if (!TagLibBridge.writeEmbeddedArtwork(output.absolutePath, artwork.absolutePath, mime)) {
                    warnings += "当前音频格式不支持内嵌专辑图"
                }
            }
        }
        if (warnings.isNotEmpty()) {
            Log.w("MusicSourceDownload", "${task.fileName}: ${warnings.joinToString()}")
        }
        warnings
    }

    private fun detectArtworkMime(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outMimeType
            ?.takeIf { it.startsWith("image/") }
            ?: when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
    }

    /**
     * 校验字节流头部是否符合目标音频格式的合法魔数，防止将未解密密文或错误页面落盘为假音频文件。
     */
    fun isValidAudioHeader(header: ByteArray, length: Int, extension: String): Boolean {
        if (length < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        val hasId3 = b0 == 0x49 && b1 == 0x44 && b2 == 0x33 // "ID3"
        return when (extension.lowercase(Locale.ROOT)) {
            "flac" -> (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) || hasId3 // "fLaC"
            "ogg" -> b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53 // "OggS"
            "wav" -> b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 // "RIFF"
            "mp3" -> hasId3 || (b0 == 0xFF && (b1 and 0xE0) == 0xE0)
            "m4a", "mp4" -> (length >= 8 &&
                (header[4].toInt() and 0xFF) == 0x66 &&
                (header[5].toInt() and 0xFF) == 0x74 &&
                (header[6].toInt() and 0xFF) == 0x79 &&
                (header[7].toInt() and 0xFF) == 0x70) // "ftyp"
            "aac" -> hasId3 || (b0 == 0xFF && (b1 and 0xF0) == 0xF0) ||
                (length >= 8 &&
                    (header[4].toInt() and 0xFF) == 0x66 &&
                    (header[5].toInt() and 0xFF) == 0x74 &&
                    (header[6].toInt() and 0xFF) == 0x79 &&
                    (header[7].toInt() and 0xFF) == 0x70)
            else -> true
        }
    }

    /**
     * 快速读取本地音频文件前 12 字节，判断其是否为可解码的合法音频文件。
     */
    fun isValidLocalAudioFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < 12L) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext.isBlank()) return true
        return runCatching {
            val header = ByteArray(12)
            val read = file.inputStream().use { it.read(header) }
            isValidAudioHeader(header, read, ext)
        }.getOrDefault(false)
    }

    fun publicDownloadDirectory(): File {
        val custom = AppPreferences.Download.customDirectory.trim()
        if (custom.isNotBlank()) {
            val dir = File(custom)
            if (dir.exists() || dir.mkdirs()) {
                return dir
            }
        }
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            PUBLIC_DOWNLOAD_DIRECTORY,
        )
    }

    private fun migrateDownloadFile(source: File, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        if (!destination.exists()) {
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = false)
                if (destination.exists()) source.delete()
            }
        }
        destination.exists()
    }.getOrDefault(false)

    private fun File.isDirectChildOf(directory: File): Boolean =
        normalizeFolderPath(parentFile?.absolutePath.orEmpty()) == normalizeFolderPath(directory.absolutePath)

    private fun ensurePublicDownloadFolderSelected(directory: File) {
        val normalized = normalizeFolderPath(directory.absolutePath)
        if (normalized.isBlank()) return
        val selected = AppPreferences.UI.scanPaths
            .map(::normalizeFolderPath)
            .filter(String::isNotBlank)
            .distinct()
        AppPreferences.UI.scanPaths = when {
            selected.any { path -> normalized == path || normalized.startsWith("$path/") } -> selected
            else -> selected.filterNot { path -> path.startsWith("$normalized/") } + normalized
        }
        val musicRoot = normalizeFolderPath(directory.parentFile?.absolutePath.orEmpty())
        if (musicRoot.isNotBlank()) {
            AppPreferences.UI.rootScanPaths = (AppPreferences.UI.rootScanPaths
                .map(::normalizeFolderPath)
                .filter(String::isNotBlank) + musicRoot)
                .distinct()
        }
    }

    private fun normalizeFolderPath(path: String): String = path
        .replace('\\', '/')
        .trim()
        .trimEnd('/')

    private fun downloadManager(): DownloadManager? = applicationContext
        ?.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private fun updateTask(id: String, transform: (MusicSourceDownloadTask) -> MusicSourceDownloadTask) {
        updateTasks { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    private fun updateTasks(transform: (List<MusicSourceDownloadTask>) -> List<MusicSourceDownloadTask>) {
        synchronized(lock) {
            mutableTasks.value = transform(mutableTasks.value)
            persist(mutableTasks.value)
        }
    }

    private data class FileDescriptor(
        val fileName: String,
        val mimeType: String,
        val quality: RawSourceQuality,
    )

    private fun buildFileDescriptor(
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
    ): FileDescriptor {
        val extension = inferExtension(source.url, source.quality)
        val mime = when (extension) {
            "flac" -> "audio/flac"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
        val artist = item.artists.joinToString(" & ").ifBlank { "" }
        val title = item.title.ifBlank { "未知曲目" }
        val rawBase = if (artist.isNotBlank()) "$artist - $title" else title
        val base = sanitizeFileName(rawBase)
        val dir = publicDownloadDirectory()
        var candidateName = "$base.$extension"
        if (File(dir, candidateName).exists()) {
            var index = 1
            while (File(dir, "$base ($index).$extension").exists()) {
                index++
            }
            candidateName = "$base ($index).$extension"
        }
        return FileDescriptor(candidateName, mime, source.quality)
    }

    private fun inferExtension(url: String, quality: RawSourceQuality): String {
        val path = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
            .substringBefore('?')
            .lowercase(Locale.ROOT)
        val candidate = path.substringAfterLast('.', "")
        if (candidate in setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav")) {
            return candidate
        }
        return when (quality) {
            RawSourceQuality.Lossless, RawSourceQuality.HiRes -> "flac"
            else -> "mp3"
        }
    }

    internal fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(160)
        .ifBlank { "RawSMusic-online" }

    private fun qualityFileLabel(quality: RawSourceQuality): String = when (quality) {
        RawSourceQuality.Standard -> "标准"
        RawSourceQuality.High -> "高品质"
        RawSourceQuality.Super -> "超高品质"
        RawSourceQuality.Lossless -> "无损"
        RawSourceQuality.HiRes -> "Hi-Res"
    }

    private fun loadTasks(): List<MusicSourceDownloadTask> = runCatching {
        val raw = AppPreferences.storage.decodeString(STORAGE_KEY, "").orEmpty()
        if (raw.isBlank()) return@runCatching emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull(::parseTask).take(MAX_TASKS)
    }.getOrDefault(emptyList())

    private fun parseTask(element: com.google.gson.JsonElement): MusicSourceDownloadTask? = runCatching {
        val obj = element.asJsonObject
        val itemObj = obj.getAsJsonObject("item") ?: return null
        MusicSourceDownloadTask(
            id = obj.string("id"),
            item = RawSourceMediaItem(
                sourceId = itemObj.string("sourceId"),
                remoteId = itemObj.string("remoteId"),
                mediaType = runCatching { RawSourceMediaType.valueOf(itemObj.string("mediaType")) }
                    .getOrDefault(RawSourceMediaType.Music),
                title = itemObj.string("title"),
                artists = itemObj.getAsJsonArray("artists")?.mapNotNull { it.asString }.orEmpty(),
                album = itemObj.string("album"),
                durationMs = itemObj.long("durationMs"),
                artworkUrl = itemObj.string("artworkUrl"),
                availableQualities = itemObj.getAsJsonArray("availableQualities")
                    ?.mapNotNull { value -> runCatching { RawSourceQuality.valueOf(value.asString) }.getOrNull() }
                    ?.toSet()
                    ?.ifEmpty { setOf(RawSourceQuality.Standard) }
                    ?: setOf(RawSourceQuality.Standard),
                sourcePayload = itemObj.string("sourcePayload").let { payload ->
                    payload.takeIf { it.length <= MAX_PAYLOAD_CHARS } ?: "{}"
                },
            ),
            requestedQuality = obj.quality("requestedQuality"),
            resolvedQuality = obj.quality("resolvedQuality"),
            status = runCatching { MusicSourceDownloadStatus.valueOf(obj.string("status")) }
                .getOrDefault(MusicSourceDownloadStatus.Failed),
            systemDownloadId = obj.long("systemDownloadId", -1L),
            fileName = obj.string("fileName"),
            mimeType = obj.string("mimeType").ifBlank { "audio/*" },
            localUri = obj.string("localUri"),
            bytesDownloaded = obj.long("bytesDownloaded"),
            totalBytes = obj.long("totalBytes", -1L),
            error = obj.string("error"),
            createdAtMs = obj.long("createdAtMs"),
            updatedAtMs = obj.long("updatedAtMs"),
            libraryScanRequested = obj.bool("libraryScanRequested"),
            mediaMetadataFinalized = obj.bool("mediaMetadataFinalized"),
        )
    }.getOrNull()

    private fun persist(tasks: List<MusicSourceDownloadTask>) {
        AppPreferences.storage.encode(STORAGE_KEY, JsonArray().apply {
            tasks.take(MAX_TASKS).forEach { add(it.toJson()) }
        }.toString())
    }

    private fun MusicSourceDownloadTask.toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("requestedQuality", requestedQuality.name)
        addProperty("resolvedQuality", resolvedQuality.name)
        addProperty("status", status.name)
        addProperty("systemDownloadId", systemDownloadId)
        addProperty("fileName", fileName)
        addProperty("mimeType", mimeType)
        addProperty("localUri", localUri)
        addProperty("bytesDownloaded", bytesDownloaded)
        addProperty("totalBytes", totalBytes)
        addProperty("error", error)
        addProperty("createdAtMs", createdAtMs)
        addProperty("updatedAtMs", updatedAtMs)
        addProperty("libraryScanRequested", libraryScanRequested)
        addProperty("mediaMetadataFinalized", mediaMetadataFinalized)
        add("item", JsonObject().apply {
            addProperty("sourceId", item.sourceId)
            addProperty("remoteId", item.remoteId)
            addProperty("mediaType", item.mediaType.name)
            addProperty("title", item.title)
            add("artists", JsonArray().apply { item.artists.forEach { add(it) } })
            addProperty("album", item.album)
            addProperty("durationMs", item.durationMs)
            addProperty("artworkUrl", item.artworkUrl)
            add("availableQualities", JsonArray().apply { item.availableQualities.forEach { add(it.name) } })
            addProperty(
                "sourcePayload",
                item.sourcePayload.takeIf { it.length <= MAX_PAYLOAD_CHARS } ?: "{}",
            )
        })
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.long(name: String, fallback: Long = 0L): Long =
        runCatching { get(name)?.asLong ?: fallback }.getOrDefault(fallback)

    private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean =
        runCatching { get(name)?.asBoolean ?: fallback }.getOrDefault(fallback)

    private fun JsonObject.quality(name: String): RawSourceQuality =
        runCatching { RawSourceQuality.valueOf(string(name)) }.getOrDefault(RawSourceQuality.Standard)
}
