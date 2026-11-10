package com.rawsmusic.module.scanner.webdav

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.FfmpegMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * WebDAV 媒体扫描器。
 *
 * 负责递归深度遍历远端 WebDAV 服务器，筛选所有支持的音频文件，
 * 解析层级元数据（歌手/专辑/音轨/歌名），并批量导入至本地曲库 (MusicRepository / Room 数据库)。
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
     * @param onProgress 进度回调 (已发现音频数量, 当前扫描状态/文件名)
     */
    suspend fun scanAndImport(
        client: WebDavClient,
        config: WebDavConfig,
        targetDirectoryUrl: String = "",
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
                    val audioFile = parseWebDavAudioFile(client, config, item, resolved)
                    collectedAudioFiles.add(audioFile)
                    onProgress?.invoke(collectedAudioFiles.size, "已发现 ${collectedAudioFiles.size} 首：${audioFile.title}")
                }
            }
        }

        val totalFound = collectedAudioFiles.size
        AppLogger.i(TAG, "WebDAV scan completed, found $totalFound audio files. Persisting to repository...")
        onProgress?.invoke(totalFound, "正在写入音乐库...")

        val importedCount = if (collectedAudioFiles.isNotEmpty()) {
            MusicRepository.upsertSongsSuspend(collectedAudioFiles, refreshLibrary = true)
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
     * 只有在标签缺失时才回退至文件名与目录层级拆解。
     */
    private fun parseWebDavAudioFile(
        client: WebDavClient,
        config: WebDavConfig,
        item: WebDavItem,
        resolvedUrl: String
    ): AudioFile {
        val fileName = item.fileName
        val rawBaseName = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "").uppercase()

        var artist = ""
        var title = rawBaseName
        var album = ""
        var trackNumber = 0
        var durationMs = 0L
        var sampleRate = 0
        var bitRate = 0
        var bitsPerSample = 0
        var channelCount = 0

        // 1. 尝试注册鉴权并读取远端文件的音频元数据标签
        try {
            val req = client.buildPlaybackRequestForUrl(config, resolvedUrl, refreshAuthentication = false)
            val headerProvider: (String) -> Map<String, String> = { target ->
                client.buildPlaybackHeaders(config, target, refreshAuthentication = false)
            }
            RemoteHttpStreamRegistry.register(
                url = resolvedUrl,
                headers = req.headers,
                userAgent = req.userAgent,
                owner = "webdav-scanner",
                headerProvider = headerProvider,
                urlProvider = { resolvedUrl },
            )
            if (req.url != resolvedUrl) {
                RemoteHttpStreamRegistry.register(
                    url = req.url,
                    headers = req.headers,
                    userAgent = req.userAgent,
                    owner = "webdav-scanner",
                    headerProvider = headerProvider,
                    urlProvider = { req.url },
                )
            }

            val fullInfo = FfmpegMetadataReader.readFullInfo(resolvedUrl)
            val tags = fullInfo.tags
            val stream = fullInfo.stream

            if (tags.title.isNotBlank()) {
                title = tags.title.trim()
            }
            if (tags.artist.isNotBlank()) {
                artist = tags.artist.trim()
            } else if (tags.albumArtist.isNotBlank()) {
                artist = tags.albumArtist.trim()
            }
            if (tags.album.isNotBlank()) {
                album = tags.album.trim()
            }
            if (tags.trackNumber > 0) {
                trackNumber = tags.trackNumber
            }
            if (stream.durationMs > 0L) {
                durationMs = stream.durationMs
            }
            sampleRate = stream.sampleRate
            bitsPerSample = stream.bitsPerSample
            channelCount = stream.channels
            bitRate = stream.bitRate
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to read remote audio tags for $resolvedUrl: ${e.message}")
        }

        // 2. 如果标签中的 title 仍为空或直接等于原始文件名且没有歌手，执行文件名拆解规则
        if (title.isBlank()) {
            title = rawBaseName
        }
        if (artist.isBlank()) {
            val trackRegex = Regex("""^(\d{1,3})[\s.\-_]+(.+)$""")
            val trackMatch = trackRegex.find(title)
            if (trackMatch != null) {
                if (trackNumber == 0) {
                    trackNumber = trackMatch.groupValues[1].toIntOrNull() ?: 0
                }
                title = trackMatch.groupValues[2].trim()
            }

            if (title.contains(" - ")) {
                val parts = title.split(" - ", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    artist = parts[0].trim()
                    title = parts[1].trim()
                }
            }
        }

        // 3. 兜底目录层级推导
        if (album.isBlank() || artist.isBlank()) {
            val decodedSegments = runCatching {
                val parsedUrl = WebDavUrlTools.toSafeHttpUrl(resolvedUrl)
                parsedUrl?.pathSegments?.map {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                } ?: emptyList()
            }.getOrDefault(emptyList())

            if (decodedSegments.size >= 2) {
                val parentFolder = decodedSegments[decodedSegments.size - 2].trim()
                if (album.isBlank() && parentFolder.isNotBlank() && !parentFolder.equals("music", ignoreCase = true)) {
                    album = parentFolder
                }
                if (artist.isBlank() && decodedSegments.size >= 3) {
                    val grandParentFolder = decodedSegments[decodedSegments.size - 3].trim()
                    if (grandParentFolder.isNotBlank() && !grandParentFolder.equals("music", ignoreCase = true)) {
                        artist = grandParentFolder
                    }
                }
            }
        }

        if (artist.isBlank()) {
            artist = "WebDAV"
        }
        if (album.isBlank()) {
            album = "WebDAV"
        }

        val stableId = (resolvedUrl.hashCode().toLong() and 0x7FFFFFFF)

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
        )
    }
}

