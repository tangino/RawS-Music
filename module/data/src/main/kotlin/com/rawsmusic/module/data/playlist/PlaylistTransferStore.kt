package com.rawsmusic.module.data.playlist

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.isOnlineSongKey
import com.rawsmusic.core.common.model.onlineUserPlaylistSong
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.PlaylistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** M3U8 导入结果。字段够 UI 报告「导入了几首、几首没匹配上」。 */
data class TransferImportResult(
    val playlist: UserPlaylist?,
    val localCount: Int,
    val onlineCount: Int,
    val unmatchedCount: Int,
) {
    val totalCount: Int get() = localCount + onlineCount
}

/**
 * 歌单导入导出（P2-06 的编排层）。
 *
 * 职责边界：
 * - **编解码**在 [PlaylistM3u8Codec]（纯函数）
 * - **落库与合并**在 `PlaylistStore.importResolvedPlaylist`（既有存储的唯一写入口）
 * - 这里只做「解码 → 分类（本地/在线）→ 匹配曲库 → 交给 store」这段编排
 *
 * ## 为什么不能只靠路径匹配
 *
 * 既有 `PlaylistStore.importTextPlaylist` 只按路径匹配，`#` 行整个跳过，所以
 * `#EXTINF` 里的「歌手 - 歌名」白丢。而 M3U8 恰恰常常只有元数据没有可用路径
 * （跨设备导入时路径必然不同）。所以这里在路径匹配之后再补一层**元数据匹配**。
 *
 * ## 在线曲目为什么能进来
 *
 * 见 `P2-05b`：在线条目用 `online://<platformId>/<songId>` 当 `key` 与 `path`、
 * 用 `generateVirtualSongId` 当负数 id。注意 **`:module:data` 不能依赖 `:module:online`**
 * （依赖方向是 online → data），所以在线行在这里用 core 的函数自己构造，
 * 而不是调 `OnlineSourceMediaMapper`。
 */
object PlaylistTransferStore {

    private const val TAG = "PlaylistTransfer"

    /** 导出歌单为 M3U8 文件（落在 `cacheDir/playlist_exports`），返回文件句柄。 */
    suspend fun exportM3u8(context: Context, playlist: UserPlaylist): File? =
        PlaylistM3u8Codec.writeExportFile(context, playlist)

    /** 导出歌单为 JSON 字符串。 */
    fun exportJson(playlist: UserPlaylist): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("title", playlist.name)
        root.put("trackCount", playlist.songs.size)
        root.put("exportTimestamp", System.currentTimeMillis())
        val songsArray = JSONArray()
        playlist.songs.forEach { song ->
            val songObj = JSONObject().apply {
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("durationMs", song.duration)
                put("path", song.path)
                val (plat, id) = onlinePartsOf(song.key) ?: onlinePartsOf(song.path) ?: (null to null)
                if (plat != null) put("platform", plat)
                if (id != null) put("songId", id)
            }
            songsArray.put(songObj)
        }
        root.put("songs", songsArray)
        return root.toString(2)
    }

    /** 导出歌单为 JSON 文件（落在 `cacheDir/playlist_exports`），返回文件句柄。 */
    suspend fun exportJson(context: Context, playlist: UserPlaylist): File? =
        withContext(Dispatchers.IO) {
            try {
                val directory = File(context.cacheDir, PlaylistM3u8Codec.EXPORT_DIR).apply { mkdirs() }
                val fileName = PlaylistM3u8Codec.sanitizeFileName(playlist.name.ifBlank { "playlist" }) + ".json"
                val file = File(directory, fileName)
                file.writeText(exportJson(playlist), Charsets.UTF_8)
                file
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "导出 JSON 失败：${playlist.name}", error)
                null
            }
        }

    /**
     * 将导出内容保存至系统的公共 Download 目录（`Download/RawS-Music/`）。
     */
    suspend fun saveToDownloads(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String = "application/octet-stream",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val sanitized = PlaylistM3u8Codec.sanitizeFileName(fileName.substringBeforeLast('.')) +
                if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitized)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RawS-Music")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("无法创建公共导出文件")
                resolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { osw ->
                        osw.write(content)
                    }
                }
                "已保存至「下载/RawS-Music/$sanitized」"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RawS-Music").apply { mkdirs() }
                val targetFile = File(dir, sanitized)
                FileOutputStream(targetFile).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { osw ->
                        osw.write(content)
                    }
                }
                "已保存至「${targetFile.absolutePath}」"
            }
        }
    }

    /**
     * 调起系统 Sharesheet 分享歌单文件。
     */
    fun sharePlaylistFile(
        context: Context,
        file: File,
        title: String,
        mimeType: String,
        chooserTitle: String = "导出/分享歌单",
    ) {
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n(来自 RawS-Music)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * 从 M3U8 文本导入歌单。
     *
     * @param fallbackName M3U8 里没有 `#PLAYLIST:` 时用的名字（通常传文件名）。
     */
    suspend fun importM3u8(
        context: Context,
        text: String,
        librarySongs: List<AudioFile>,
        fallbackName: String,
    ): TransferImportResult {
        val document = PlaylistM3u8Codec.decode(text)
        val candidates = document.entries.map { entry ->
            TransferCandidate(
                title = entry.title,
                artist = entry.artist,
                durationMs = entry.durationMs,
                path = entry.path,
                onlinePlatformId = entry.onlinePlatformId,
                onlineSongId = entry.onlineSongId,
            )
        }
        val playlistName = document.title.ifBlank { fallbackName }
        return importCandidates(context, candidates, librarySongs, playlistName)
    }

    /**
     * 从 JSON 文本导入歌单（支持单歌单 JSON 与全量备份 JSON）。
     */
    suspend fun importJson(
        context: Context,
        text: String,
        librarySongs: List<AudioFile>,
        fallbackName: String,
    ): TransferImportResult {
        val trimmed = text.trim()
        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            return TransferImportResult(null, 0, 0, 0)
        }

        // 若是全量备份格式 { "playlists": [...] }
        val playlistsArray = root.optJSONArray("playlists")
        if (playlistsArray != null && playlistsArray.length() > 0) {
            val firstPlaylist = playlistsArray.optJSONObject(0)
            if (firstPlaylist != null) {
                return parseAndImportSinglePlaylistJson(context, firstPlaylist, librarySongs, fallbackName)
            }
        }

        return parseAndImportSinglePlaylistJson(context, root, librarySongs, fallbackName)
    }

    /**
     * 从纯文本清单导入歌单（每行一首，格式支持 "歌名 - 歌手"、"歌手 - 歌名" 或纯 "歌名"）。
     */
    suspend fun importPlainText(
        context: Context,
        text: String,
        librarySongs: List<AudioFile>,
        playlistName: String,
    ): TransferImportResult {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") && !it.startsWith("#") }
        if (lines.isEmpty()) {
            return TransferImportResult(null, 0, 0, 0)
        }

        val candidates = lines.map { rawLine ->
            // 剥离可能存在的行首序号，如 "1. "、"01. "、"1、"、"#1 "
            val line = rawLine.replace(Regex("^(?:#?\\d+[.、\\s-]+)"), "").trim()
            val (title, artist) = when {
                line.contains(" - ") -> line.split(" - ", limit = 2).let { it[0].trim() to it[1].trim() }
                line.contains(" — ") -> line.split(" — ", limit = 2).let { it[0].trim() to it[1].trim() }
                line.contains('\t') -> line.split('\t', limit = 2).let { it[0].trim() to it[1].trim() }
                else -> line to ""
            }
            TransferCandidate(
                title = title,
                artist = artist,
                durationMs = 0L,
                path = "",
                onlinePlatformId = null,
                onlineSongId = null,
            )
        }

        return importCandidates(context, candidates, librarySongs, playlistName)
    }

    // ------------------------------------------------------------------ 内部编排

    private data class TransferCandidate(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val path: String,
        val onlinePlatformId: String?,
        val onlineSongId: String?,
    )

    private suspend fun parseAndImportSinglePlaylistJson(
        context: Context,
        root: JSONObject,
        librarySongs: List<AudioFile>,
        fallbackName: String,
    ): TransferImportResult {
        val title = root.optString("title", "").ifBlank {
            root.optString("name", fallbackName)
        }
        val songsArray = root.optJSONArray("songs") ?: return TransferImportResult(null, 0, 0, 0)
        val candidates = mutableListOf<TransferCandidate>()
        for (i in 0 until songsArray.length()) {
            val obj = songsArray.optJSONObject(i) ?: continue
            val songTitle = obj.optString("title", "").ifBlank { obj.optString("name", "") }
            val artist = obj.optString("artist", "")
            val durationMs = obj.optLong("durationMs", obj.optLong("duration", 0L))
            val path = obj.optString("path", "")
            val platform = obj.optString("platform", "").takeIf { it.isNotBlank() }
            val songId = obj.optString("songId", "").takeIf { it.isNotBlank() }
            candidates += TransferCandidate(
                title = songTitle,
                artist = artist,
                durationMs = durationMs,
                path = path,
                onlinePlatformId = platform,
                onlineSongId = songId,
            )
        }
        return importCandidates(context, candidates, librarySongs, title)
    }

    private suspend fun importCandidates(
        context: Context,
        candidates: List<TransferCandidate>,
        librarySongs: List<AudioFile>,
        playlistName: String,
    ): TransferImportResult {
        val store = PlaylistStore.getInstance(context)
        val onlineRows = mutableListOf<UserPlaylistSong>()
        val localCandidates = mutableListOf<TransferCandidate>()
        var entriesWithoutAnything = 0

        candidates.forEach { candidate ->
            val onlineParts = onlinePartsOf(candidate)
            when {
                onlineParts != null -> {
                    val (platformId, songId) = onlineParts
                    onlineRows += onlineUserPlaylistSong(
                        platformId = platformId,
                        songId = songId,
                        title = candidate.title,
                        artist = candidate.artist,
                        durationMs = candidate.durationMs,
                    )
                }
                candidate.path.isNotBlank() -> localCandidates += candidate
                candidate.title.isNotBlank() -> localCandidates += candidate
                else -> entriesWithoutAnything++
            }
        }

        // 第一轮：按路径匹配本地曲库
        val pathMatched = store.matchLibrarySongs(
            localCandidates.map { it.path }.filter { it.isNotBlank() },
            librarySongs,
        )
        val matchedKeys = pathMatched.mapTo(mutableSetOf()) { it.playlistIdentityKey() }

        // 第二轮：元数据兜底匹配
        val metaMatched = localCandidates
            .asSequence()
            .filter { it.title.isNotBlank() }
            .mapNotNull { candidate -> matchByMetadata(candidate, librarySongs, matchedKeys) }
            .distinctBy { it.playlistIdentityKey() }
            .toList()

        val localSongs = (pathMatched + metaMatched).distinctBy { it.playlistIdentityKey() }

        val unmatched = (localCandidates.size - localSongs.size).coerceAtLeast(0) +
            entriesWithoutAnything
        if (unmatched > 0) {
            AppLogger.i(TAG, "歌单导入：${unmatched} 条没能匹配到本地曲库或在线身份")
        }

        val playlist = store.importResolvedPlaylist(
            name = playlistName,
            localSongs = localSongs,
            onlineSongs = onlineRows,
        )
        return TransferImportResult(
            playlist = playlist,
            localCount = localSongs.size,
            onlineCount = onlineRows.size,
            unmatchedCount = unmatched,
        )
    }

    private fun onlinePartsOf(candidate: TransferCandidate): Pair<String, String>? {
        val platformId = candidate.onlinePlatformId
        val songId = candidate.onlineSongId
        if (!platformId.isNullOrBlank() && !songId.isNullOrBlank()) return platformId to songId
        return onlinePartsOf(candidate.path)
    }

    private fun onlinePartsOf(pathOrKey: String): Pair<String, String>? {
        if (!isOnlineSongKey(pathOrKey)) return null
        val rest = pathOrKey.removePrefix("online://")
        val parsedPlatform = rest.substringBefore('/', "")
        val parsedSongId = rest.substringAfter('/', "")
        if (parsedPlatform.isBlank() || parsedSongId.isBlank()) return null
        return parsedPlatform to parsedSongId
    }

    private fun matchByMetadata(
        candidate: TransferCandidate,
        librarySongs: List<AudioFile>,
        alreadyMatched: Set<String>,
    ): AudioFile? {
        val wantTitle = normalizeForMatch(candidate.title)
        if (wantTitle.isEmpty()) return null
        val wantArtist = normalizeForMatch(candidate.artist)
        val candidates = librarySongs.filter { song ->
            song.playlistIdentityKey() !in alreadyMatched && normalizeForMatch(song.title) == wantTitle
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        if (wantArtist.isEmpty()) return null
        val byArtist = candidates.filter { normalizeForMatch(it.artist) == wantArtist }
        return byArtist.singleOrNull()
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace(Regex("[\\s\\-_,，。.·•'\"()（）\\[\\]【】]"), "")
            .trim()
}
