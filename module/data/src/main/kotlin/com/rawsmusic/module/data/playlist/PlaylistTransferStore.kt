package com.rawsmusic.module.data.playlist

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.generateVirtualSongId
import com.rawsmusic.core.common.model.isOnlineSongKey
import com.rawsmusic.core.common.model.onlineSongKey
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.PlaylistStore
import java.io.File

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

    /**
     * 在线条目的 `format` 标记。⚠️ 它**只是给人看的标记**：
     * `UserPlaylistSong.isOnlineEntry()` 判的是 `key`/`path` 前缀，**不依赖这个字段**，
     * 所以即使这里留空也不影响识别。
     */
    private const val ONLINE_FORMAT_MARKER = "online"

    /** 导出歌单为 M3U8 文件（落在 `cacheDir/playlist_exports`），返回文件句柄。 */
    suspend fun exportM3u8(context: Context, playlist: UserPlaylist): File? =
        PlaylistM3u8Codec.writeExportFile(context, playlist)

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
        val store = PlaylistStore.getInstance(context)

        val onlineRows = mutableListOf<UserPlaylistSong>()
        /** 路径可能匹配得上的条目（保留原始下标，后面要按元数据补匹配）。 */
        val localCandidates = mutableListOf<M3u8Entry>()
        var entriesWithoutAnything = 0

        document.entries.forEach { entry ->
            val onlineParts = onlinePartsOf(entry)
            when {
                // 有明确的在线身份 → 直接构造在线行，不走曲库匹配。
                onlineParts != null -> {
                    val (platformId, songId) = onlineParts
                    onlineRows += UserPlaylistSong(
                        key = onlineSongKey(platformId, songId),
                        id = generateVirtualSongId(platformId, songId),
                        title = entry.title,
                        artist = entry.artist,
                        album = "",
                        albumId = 0L,
                        duration = entry.durationMs,
                        path = onlineSongKey(platformId, songId),
                        fileSize = 0L,
                        format = ONLINE_FORMAT_MARKER,
                        sampleRate = 0,
                        bitRate = 0,
                        bitsPerSample = 0,
                        albumArtPath = "",
                        addedAt = System.currentTimeMillis(),
                    )
                }

                entry.path.isNotBlank() -> localCandidates += entry

                // 既没有在线身份也没有路径：只能靠元数据找（下面统一处理）。
                entry.title.isNotBlank() -> localCandidates += entry

                else -> entriesWithoutAnything++
            }
        }

        // 第一轮：按路径匹配（复用既有三层回退逻辑，不重写）。
        val pathMatched = store.matchLibrarySongs(
            localCandidates.map { it.path }.filter { it.isNotBlank() },
            librarySongs,
        )
        val matchedKeys = pathMatched.mapTo(mutableSetOf()) { it.playlistIdentityKey() }

        // 第二轮：对没匹配上的条目用「歌名 + 歌手」找；跨设备导入时路径必然失效，这层是主力。
        val metaMatched = localCandidates
            .asSequence()
            .filter { it.title.isNotBlank() }
            .mapNotNull { entry -> matchByMetadata(entry, librarySongs, matchedKeys) }
            .distinctBy { it.playlistIdentityKey() }
            .toList()

        val localSongs = (pathMatched + metaMatched).distinctBy { it.playlistIdentityKey() }

        // 没匹配上的条数：既不在匹配结果里、也没有成功建成在线行的。
        val unmatched = (localCandidates.size - localSongs.size).coerceAtLeast(0) +
            entriesWithoutAnything
        if (unmatched > 0) {
            AppLogger.i(TAG, "M3U8 导入：${unmatched} 条没能匹配到本地曲库或在线身份")
        }

        val playlist = store.importResolvedPlaylist(
            name = document.title.ifBlank { fallbackName },
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

    // ------------------------------------------------------------------ 私有

    /**
     * 条目是否带在线身份：优先用显式标签，其次从 `path` 前缀推。
     *
     * @return `(platformId, songId)`；不是在线条目返回 null。
     */
    private fun onlinePartsOf(entry: M3u8Entry): Pair<String, String>? {
        val platformId = entry.onlinePlatformId
        val songId = entry.onlineSongId
        if (!platformId.isNullOrBlank() && !songId.isNullOrBlank()) return platformId to songId
        if (!isOnlineSongKey(entry.path)) return null
        val rest = entry.path.removePrefix("online://")
        val parsedPlatform = rest.substringBefore('/', "")
        val parsedSongId = rest.substringAfter('/', "")
        if (parsedPlatform.isBlank() || parsedSongId.isBlank()) return null
        return parsedPlatform to parsedSongId
    }

    /** 用「歌名 + 歌手」在曲库里找唯一匹配；已匹配过的跳过。 */
    private fun matchByMetadata(
        entry: M3u8Entry,
        librarySongs: List<AudioFile>,
        alreadyMatched: Set<String>,
    ): AudioFile? {
        val wantTitle = normalizeForMatch(entry.title)
        if (wantTitle.isEmpty()) return null
        val wantArtist = normalizeForMatch(entry.artist)
        val candidates = librarySongs.filter { song ->
            song.playlistIdentityKey() !in alreadyMatched && normalizeForMatch(song.title) == wantTitle
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        // 同名多首（翻唱/不同版本）时用歌手收敛；仍不唯一就**不猜**，宁可不导也不导错。
        if (wantArtist.isEmpty()) return null
        val byArtist = candidates.filter { normalizeForMatch(it.artist) == wantArtist }
        return byArtist.singleOrNull()
    }

    /**
     * 匹配用的归一化：小写 + 去掉空白与常见标点。
     *
     * ⚠️ **为什么不在 `:module:data` 里复用 `OnlinePlaybackMatcher.normalizeForCompare`**：
     * 那个类在 `:module:online`，而依赖方向是 `online → data`，这里引用不到。
     * 它的逻辑更复杂（处理特殊版本标签、括号副标题等），面向的是「跨平台找同一首歌」；
     * 这里是「本地曲库里按元数据找回自己的歌」，判定可以更严（同名多首直接放弃）。
     * 两者刻意不共享，避免为了复用把在线匹配的宽松策略带进本地导入。
     */
    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace(Regex("[\\s\\-_,，。.·•'\"()（）\\[\\]【】]"), "")
            .trim()
}
