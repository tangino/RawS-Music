package com.rawsmusic.module.scanner.webdav

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * WebDAV 媒体扫描器。
 *
 * 负责递归深度遍历远端 WebDAV 服务器，筛选所有支持的音频文件，
 * 解析层级元数据（歌手/专辑/音轨/歌名/内嵌封面），并批量导入至本地曲库 (MusicRepository / Room 数据库)。
 */
object WebDavMediaScanner {
    private const val TAG = "WebDavMediaScanner"

    data class ScanResult(
        val totalScanned: Int,
        val imported: Int,
        val elapsedMs: Long,
    )

    /**
     * 递归扫描 WebDAV 目录，提取音频文件并保存至本地 MusicRepository。
     *
     * @param client WebDavClient 客户端实例
     * @param config WebDavConfig 鉴权及服务器配置
     * @param targetDirectoryUrl 目标目录（为空时默认从 config.url 开始全量扫描）
     * @param context 可选上下文，用于缓存抽取的内嵌封面
     * @param onProgress 进度回调 (已发现音频数量, 当前扫描状态/文件名)
     */
    suspend fun scanAndImport(
        client: WebDavClient,
        config: WebDavConfig,
        targetDirectoryUrl: String = "",
        context: Context? = null,
        onProgress: ((count: Int, message: String) -> Unit)? = null
    ): ScanResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val startUrl = targetDirectoryUrl.ifBlank { config.url }.trim()
        if (startUrl.isBlank()) return@withContext ScanResult(0, 0, 0L)

        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        val collectedAudioFiles = mutableListOf<AudioFile>()

        queue.add(startUrl)
        visited.add(WebDavUrlTools.identity(startUrl))

        AppLogger.i(TAG, "Starting WebDAV scan from: $startUrl")
        onProgress?.invoke(0, "正在连接 WebDAV 目录...")

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val currentDir = queue.removeFirst()
            val dirResult = runCatching {
                client.listDirectoryResult(config, currentDir)
            }.getOrElse { error ->
                AppLogger.w(TAG, "Failed to list directory: $currentDir, ${error.message}")
                null
            } ?: continue

            for (item in dirResult.items) {
                coroutineContext.ensureActive()
                val resolved = item.resolvedUrl.ifBlank {
                    client.buildFileUrl(dirResult.canonicalUrl, item.href, isDirectory = item.isDirectory)
                }

                if (item.isDirectory) {
                    val dirIdentity = WebDavUrlTools.identity(resolved)
                    if (visited.add(dirIdentity)) {
                        queue.add(resolved)
                    }
                } else if (AudioUtils.isAudioFile(item.fileName)) {
                    val audioFile = parseWebDavAudioFile(client, config, item, resolved, context)
                    collectedAudioFiles.add(audioFile)
                    onProgress?.invoke(collectedAudioFiles.size, "已发现 ${collectedAudioFiles.size} 首：${audioFile.title}")
                }
            }
        }

        val totalFound = collectedAudioFiles.size
        AppLogger.i(TAG, "WebDAV scan completed, found $totalFound audio files. Persisting to repository...")
        onProgress?.invoke(totalFound, "正在写入音乐库...")

        val importedCount = if (collectedAudioFiles.isNotEmpty()) {
            MusicRepository.unfenceRemotePaths(startUrl)
            val count = MusicRepository.upsertSongsSuspend(collectedAudioFiles, refreshLibrary = true)
            MusicRepository.refreshAllSuspend()
            count
        } else {
            0
        }

        val elapsed = System.currentTimeMillis() - t0
        AppLogger.i(TAG, "WebDAV scan finished: found=$totalFound, imported=$importedCount, time=${elapsed}ms")
        ScanResult(totalFound, importedCount, elapsed)
    }

    /**
     * 清除音乐库中属于特定 WebDAV URL 根地址的歌曲。
     */
    suspend fun purgeWebDavSongs(webDavBaseUrl: String): Int = withContext(Dispatchers.IO) {
        if (webDavBaseUrl.isBlank()) return@withContext 0
        val cleanPrefix = WebDavUrlTools.stripUserInfo(webDavBaseUrl).trimEnd('/')
        val currentSongs = MusicRepository.getAllSongs()
        val webDavSongs = currentSongs.filter { song ->
            val p = song.path
            (p.startsWith("http://", true) || p.startsWith("https://", true)) &&
                p.startsWith(cleanPrefix, ignoreCase = true)
        }
        if (webDavSongs.isNotEmpty()) {
            AppLogger.i(TAG, "Purging ${webDavSongs.size} WebDAV songs with prefix $cleanPrefix")
            MusicRepository.deleteSongsSuspend(webDavSongs, refreshLibrary = true)
        }
        webDavSongs.size
    }

    /**
     * 获取当前音乐库中已导入的 WebDAV 歌曲数量。
     */
    fun getImportedWebDavSongCount(webDavBaseUrl: String): Int {
        if (webDavBaseUrl.isBlank()) return 0
        val cleanPrefix = WebDavUrlTools.stripUserInfo(webDavBaseUrl).trimEnd('/')
        return MusicRepository.getAllSongs().count { song ->
            val p = song.path
            (p.startsWith("http://", true) || p.startsWith("https://", true)) &&
                p.startsWith(cleanPrefix, ignoreCase = true)
        }
    }

    /**
     * 智能解析 WebDAV 音频文件项为 AudioFile。
     * 优先通过远端 HTTP Range 读取内嵌标签（ID3 / FLAC Vorbis / MP4 / etc.），
     * 自动提取内嵌封面至本地缓存，并在标签缺失时智能拆解文件名与目录层级。
     */
    private fun parseWebDavAudioFile(
        client: WebDavClient,
        config: WebDavConfig,
        item: WebDavItem,
        resolvedUrl: String,
        context: Context?
    ): AudioFile {
        val fileName = item.fileName
        val rawBaseName = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "").uppercase()

        // 规范化为 ASCII URL 传给底层网络和 FFmpeg
        val safeUrl = WebDavUrlTools.toSafeHttpUrl(resolvedUrl)?.toString() ?: resolvedUrl

        var artist = ""
        var title = rawBaseName
        var album = ""
        var trackNumber = 0
        var durationMs = 0L
        var sampleRate = 0
        var bitRate = 0
        var bitsPerSample = 0
        var channelCount = 0
        var coverPath = ""

        // 1. 注册远端流鉴权信息（纯内存注册，零网络请求）
        try {
            val req = client.buildPlaybackRequestForUrl(config, safeUrl, refreshAuthentication = false)
            val headerProvider: (String) -> Map<String, String> = { target ->
                client.buildPlaybackHeaders(config, target, refreshAuthentication = false)
            }
            listOf(safeUrl, resolvedUrl, req.url).distinct().forEach { u ->
                RemoteHttpStreamRegistry.register(
                    url = u,
                    headers = req.headers,
                    userAgent = req.userAgent,
                    owner = "webdav-scanner",
                    headerProvider = headerProvider,
                    urlProvider = { safeUrl },
                )
            }

            // 检查本地已有缓存封面（若之前播放富集过，直接秒级挂接，0 额外网络开销）
            if (context != null) {
                val coverDir = File(context.cacheDir, "webdav_covers")
                val coverFile = File(coverDir, "art_${(safeUrl.hashCode().toLong() and 0xFFFFFFFFL)}.jpg")
                if (coverFile.exists() && coverFile.length() > 0) {
                    coverPath = "file://${coverFile.absolutePath}"
                } else {
                    val embedDir = File(context.cacheDir, "albumart/webdav_embedded")
                    val embedKey = runCatching {
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest("${safeUrl}\u0000${item.size}".toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                            .take(32)
                    }.getOrNull()
                    if (!embedKey.isNullOrBlank()) {
                        val embedFile = File(embedDir, "$embedKey.jpg")
                        if (embedFile.exists() && embedFile.length() > 1024L) {
                            coverPath = "file://${embedFile.absolutePath}"
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            AppLogger.d(TAG, "WebDAV register credentials skipped: ${e.message}")
        }

        // 2. 纯本地零流量智能拆解：去除音轨序号并精确分离歌手与歌名
        var cleanName = rawBaseName.trim()
        val trackPrefixRegex = Regex("""^(\d{1,3})[\s.\-_]+(.+)$""")
        val trackMatch = trackPrefixRegex.find(cleanName)
        if (trackMatch != null) {
            trackNumber = trackMatch.groupValues[1].toIntOrNull() ?: 0
            cleanName = trackMatch.groupValues[2].trim()
        }

        // 智能分离歌手与歌名，支持 " - ", " — ", " -", "- ", "-", "_"
        val splitDelimiters = listOf(" - ", " — ", " -", "- ", "-", "_")
        var splitDone = false
        for (delimiter in splitDelimiters) {
            if (cleanName.contains(delimiter)) {
                val parts = cleanName.split(delimiter, limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    artist = parts[0].trim()
                    title = parts[1].trim()
                    splitDone = true
                    break
                }
            }
        }
        if (!splitDone) {
            title = cleanName
        }

        // 3. 从 WebDAV URL 目录层级推导专辑名与歌手名（完全在本地推导，零网络开销）
        val decodedSegments = runCatching {
            val parsedUrl = WebDavUrlTools.toSafeHttpUrl(resolvedUrl)
            parsedUrl?.pathSegments?.map {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            } ?: emptyList()
        }.getOrDefault(emptyList())

        if (decodedSegments.size >= 2) {
            val parentFolder = decodedSegments[decodedSegments.size - 2].trim()
            if (parentFolder.isNotBlank() && !parentFolder.equals("music", ignoreCase = true) && !parentFolder.equals("webdav", ignoreCase = true)) {
                album = parentFolder
            }
            if (artist.isBlank() && decodedSegments.size >= 3) {
                val grandParentFolder = decodedSegments[decodedSegments.size - 3].trim()
                if (grandParentFolder.isNotBlank() && !grandParentFolder.equals("music", ignoreCase = true) && !grandParentFolder.equals("webdav", ignoreCase = true)) {
                    artist = grandParentFolder
                }
            }
        }

        if (artist.isBlank()) {
            artist = if (album.isNotBlank() && album != "WebDAV") album else "WebDAV"
        }
        if (album.isBlank()) {
            album = "WebDAV"
        }

        val stableId = (safeUrl.hashCode().toLong() and 0x7FFFFFFF)

        return AudioFile(
            id = stableId,
            path = resolvedUrl,
            title = title,
            artist = artist,
            album = album,
            albumId = 0,
            duration = durationMs,
            sampleRate = sampleRate,
            bitRate = bitRate,
            bitsPerSample = bitsPerSample,
            channelCount = channelCount,
            format = ext,
            fileSize = item.size,
            trackNumber = trackNumber,
            dateModified = System.currentTimeMillis(),
            dateAdded = System.currentTimeMillis(),
            albumArtPath = coverPath,
        )
    }
}

