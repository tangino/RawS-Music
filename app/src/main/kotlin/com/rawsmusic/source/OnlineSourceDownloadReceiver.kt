package com.rawsmusic.source

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.scanner.MetadataParser
import com.rawsmusic.module.scanner.ScanScheduler
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges public online downloads into RawSMusic's local library.
 *
 * DownloadManager owns the transfer. Once it reports completion, the data controller publishes the
 * file to MediaStore and sends [MusicSourceDownloadController.ACTION_DOWNLOAD_MEDIA_READY]. This
 * receiver then starts the same persistent-cache scan used by manual library refreshes.
 */
class OnlineSourceDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val systemId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (systemId < 0L) return
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        MusicSourceDownloadController.handleSystemDownloadCompleted(context, systemId)
                    } catch (error: Throwable) {
                        AppLogger.e(TAG, "download completion reconciliation failed id=$systemId", error)
                    } finally {
                        pending.finish()
                    }
                }
            }

            MusicSourceDownloadController.ACTION_DOWNLOAD_MEDIA_READY -> {
                val path = intent.getStringExtra(MusicSourceDownloadController.EXTRA_DOWNLOAD_PATH).orEmpty()
                val appContext = context.applicationContext
                AppLogger.i(TAG, "online download ready, parse metadata & upsert directly path=$path")
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val parsed = path.takeIf { it.isNotBlank() && File(it).isFile }
                            ?.let { MetadataParser.parseFromFile(it) }
                        if (parsed != null) {
                            // 续期内部写入抑制标记，防止延迟到达的 MediaStore 回调误触发整库扫盘
                            MusicSourceDownloadController.recordInternalMediaWrite(path)
                            // 直接将单曲完整标签（歌名、歌手、专辑、采样率、位深等）精扫入库，
                            // 避免全目录两阶段扫描的第一阶段用“文件名占位”覆盖真实标签
                            MusicRepository.init(appContext)
                            MusicRepository.upsertSongsSuspend(
                                songs = listOf(parsed),
                                refreshLibrary = true,
                            )
                            // 同步更新父目录的 scan_directory 时间戳基线，避免前台巡检或 MediaStore 误判目录变更
                            val parentDir = File(path).parentFile
                            if (parentDir != null && parentDir.isDirectory) {
                                val modifiedAt = parentDir.lastModified()
                                if (modifiedAt > 0L) {
                                    MusicRepository.commitScanDirectories(
                                        mapOf(fileSystemDirKey(parentDir.path) to modifiedAt)
                                    )
                                }
                            }
                            PlaylistStore.getInstance(appContext).invalidateMatchCache()
                            AppLogger.i(
                                TAG,
                                "online download upserted with full tags: title=${parsed.title}, artist=${parsed.artist}, album=${parsed.album}"
                            )
                        } else {
                            PlaylistStore.getInstance(appContext).invalidateMatchCache()
                            ScanScheduler.requestDirScan(appContext, "在线下载完成")
                        }
                    } catch (error: Throwable) {
                        AppLogger.e(TAG, "direct metadata upsert failed path=$path, fallback to dir scan", error)
                        PlaylistStore.getInstance(appContext).invalidateMatchCache()
                        ScanScheduler.requestDirScan(appContext, "在线下载完成")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun fileSystemDirKey(path: String): String {
        val normalized = runCatching { File(path).canonicalPath }
            .getOrElse { File(path).absolutePath }
            .trim()
            .replace('\\', '/')
            .trimEnd('/')
            .lowercase(Locale.ROOT)
        return "fs:$normalized"
    }

    private companion object {
        const val TAG = "OnlineSourceDownload"
    }
}
