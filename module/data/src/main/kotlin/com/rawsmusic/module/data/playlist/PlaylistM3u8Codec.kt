package com.rawsmusic.module.data.playlist

import android.content.Context
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.isOnlineSongKey
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 解析出来的一条 M3U8 条目。 */
data class M3u8Entry(
    val title: String,
    val artist: String,
    val durationMs: Long,
    /** 本地路径，或 `online://<platformId>/<songId>` 串。可能为空（比如只有元数据）。 */
    val path: String,
    val coverUrl: String?,
    val onlinePlatformId: String?,
    val onlineSongId: String?,
) {
    /** 是否是在线曲目：显式标签或 `online://` 路径任一命中即算（兼容 OrBit 导出的文件）。 */
    val isOnline: Boolean
        get() = !onlineSongId.isNullOrBlank() || isOnlineSongKey(path)
}

/** 解析出来的整个歌单。 */
data class M3u8Playlist(
    val title: String,
    val description: String,
    val coverUrl: String?,
    val platformId: String?,
    val originalId: String?,
    val creatorName: String?,
    val entries: List<M3u8Entry>,
)

/**
 * 歌单 ↔ M3U8 编解码。
 *
 * ## 格式来源
 *
 * 逐条对照 OrBit 的 `PlaylistTransferManager.exportToM3u8`（`,:217-249`）写的，
 * **只有自定义标签前缀换了名**：OrBit 用 `#EXT-ORBIT-*`，本移植用 **`#EXT-RAWS-*`**。
 *
 * > ⚠️ 根 `AGENTS.MD` 里写的是「把 `#EXTORBIT-*` 改名为 `#EXTRAWS-*`」，**这个描述不准确**：
 * > OrBit 实际用的是 **`#EXT-ORBIT-`**（带连字符）。为了与源格式保持同形，这里统一成
 * > `#EXT-RAWS-`。解码侧**两种前缀都认**，所以能直接读 OrBit 导出的文件。
 *
 * ## 为什么在线曲也能进出
 *
 * OrBit 这套格式本来就带在线曲支持（`#EXT-ORBIT-ONLINE:` + `online://` 路径），这与
 * 本移植 `P2-05b` 定的表示法一致，所以在线曲目导出后仍能被识别、导入时也不会丢。
 *
 * ## 只做编解码，不做匹配
 *
 * 这里**不**去曲库里找歌、也不写数据库 —— 那是 `PlaylistStore` 的职责。本对象是纯函数 + 一个
 * 落盘辅助，便于单测与复用（导出/备份/分享都走它）。
 *
 * ## 导出侧的已知取舍（不是遗漏）
 *
 * OrBit 的 `TransferPlaylist` 带 `coverUrl` / `platform` / `creatorName`，所以它能写
 * `#EXT-ORBIT-COVER` / `-PLATFORM` / `-CREATOR` 与每首的 `#EXTIMG`。
 * 而 RawS 的 `UserPlaylist` / `UserPlaylistSong` **没有这些字段**（歌单只有 id/name/songs/时间；
 * 歌曲的 `albumArtPath` 是**本地文件路径**，不是 http 封面地址）。
 * 因此本移植**不写**这些标签，但**解码侧全部认识** —— 这样 OrBit 导出的完整文件能被读进来，
 * 我们导出的文件也只是少了封面/创建者这类装饰信息，歌曲本身一条不少。
 * 若将来 `UserPlaylist` 加了这些字段，直接在这里补上写入即可。
 */
object PlaylistM3u8Codec {

    private const val TAG = "PlaylistTransfer"

    private const val RAWS_TAG_PREFIX = "#EXT-RAWS-"
    private const val ORBIT_TAG_PREFIX = "#EXT-ORBIT-"

    private const val TAG_COVER = "COVER"
    private const val TAG_PLATFORM = "PLATFORM"
    private const val TAG_CREATOR = "CREATOR"
    private const val TAG_ONLINE = "ONLINE"

    /** `#EXTINF` 里没有歌手时用的占位符；与 OrBit 保持一致以便互认。 */
    private const val UNKNOWN_ARTIST = "Unknown Artist"

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

    // ------------------------------------------------------------------ 编码

    /**
     * 序列化歌单为标准 M3U8（扩展标签见类注释）。
     *
     * 换行固定用 `\n`：M3U8 是跨平台的交换格式，**不要**跟随 Windows 的 CRLF，
     * 否则同一份歌单在不同平台导出的字节不同、diff 噪音也大。
     */
    fun encode(playlist: UserPlaylist): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(playlist.name).append('\n')
        append('\n')

        playlist.songs.forEach { song ->
            val seconds = (song.duration / 1000L).coerceAtLeast(0L)
            append("#EXTINF:").append(seconds).append(',')
            append(song.artist.ifBlank { UNKNOWN_ARTIST })
                .append(" - ")
                .append(song.title)
                .append('\n')

            if (song.isOnlineEntry()) {
                val platformId = platformIdOf(song)
                val songId = songIdOf(song)
                if (platformId != null && songId != null) {
                    append(RAWS_TAG_PREFIX).append(TAG_ONLINE)
                        .append(':').append(platformId)
                        .append(':').append(songId)
                        .append('\n')
                }
            }
            append(song.path).append("\n\n")
        }
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解析 M3U8 文本。
     *
     * 容错策略：**能解析多少算多少**，不因为某一行异常就整体失败 —— 歌单文件常被手工编辑过。
     * 但「一个条目都没有」会返回空 `entries`，由调用方决定是否当作失败。
     */
    fun decode(text: String): M3u8Playlist {
        var title = ""
        var description = ""
        var coverUrl: String? = null
        var platformId: String? = null
        var originalId: String? = null
        var creatorName: String? = null

        val entries = mutableListOf<M3u8Entry>()

        // 当前 `#EXTINF` / 扩展标签积累的状态，遇到路径行时消费掉并清空。
        var pendingTitle = ""
        var pendingArtist = ""
        var pendingDurationMs = 0L
        var pendingCover: String? = null
        var pendingPlatformId: String? = null
        var pendingSongId: String? = null

        fun flush() {
            pendingTitle = ""
            pendingArtist = ""
            pendingDurationMs = 0L
            pendingCover = null
            pendingPlatformId = null
            pendingSongId = null
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit

                line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                    title = line.substringAfter(':').trim()
                }

                line.startsWith("#DESCRIPTION:", ignoreCase = true) -> {
                    description = line.substringAfter(':').trim()
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // `#EXTINF:<秒>,<歌手> - <歌名>`；秒后面可能还有别的属性，取第一个逗号之前。
                    val payload = line.substringAfter(':')
                    val seconds = payload.substringBefore(',').trim().toLongOrNull() ?: 0L
                    pendingDurationMs = seconds * 1000L
                    val info = payload.substringAfter(',', "").trim()
                    val separator = info.indexOf(" - ")
                    if (separator >= 0) {
                        pendingArtist = info.substring(0, separator).trim()
                        pendingTitle = info.substring(separator + 3).trim()
                    } else {
                        // 拆不开就把整串当歌名（与 OrBit 的处理一致）。
                        pendingTitle = info
                        pendingArtist = ""
                    }
                }

                line.startsWith("#EXTIMG:", ignoreCase = true) -> {
                    pendingCover = line.substringAfter(':').trim().ifBlank { null }
                }

                // 自定义标签：两种前缀都认，便于直接读 OrBit 导出的文件。
                line.startsWith(RAWS_TAG_PREFIX, ignoreCase = true) ||
                    line.startsWith(ORBIT_TAG_PREFIX, ignoreCase = true) -> {
                    val body = if (line.startsWith(RAWS_TAG_PREFIX, ignoreCase = true)) {
                        line.substring(RAWS_TAG_PREFIX.length)
                    } else {
                        line.substring(ORBIT_TAG_PREFIX.length)
                    }
                    val name = body.substringBefore(':').uppercase()
                    val value = body.substringAfter(':', "").trim()
                    when (name) {
                        TAG_COVER -> coverUrl = value.ifBlank { null }
                        TAG_CREATOR -> creatorName = value.ifBlank { null }
                        TAG_PLATFORM -> {
                            platformId = value.substringBefore(':').ifBlank { null }
                            originalId = value.substringAfter(':', "").ifBlank { null }
                        }
                        TAG_ONLINE -> {
                            pendingPlatformId = value.substringBefore(':').ifBlank { null }
                            pendingSongId = value.substringAfter(':', "").ifBlank { null }
                        }
                    }
                }

                line.startsWith('#') -> Unit // 未知标签：忽略，不当作路径

                else -> {
                    // 路径行：消费掉前面积累的元数据。
                    val path = line.trim('"')
                    entries += M3u8Entry(
                        title = pendingTitle,
                        artist = pendingArtist,
                        durationMs = pendingDurationMs,
                        path = path,
                        coverUrl = pendingCover,
                        onlinePlatformId = pendingPlatformId,
                        onlineSongId = pendingSongId,
                    )
                    flush()
                }
            }
        }

        return M3u8Playlist(
            title = title,
            description = description,
            coverUrl = coverUrl,
            platformId = platformId,
            originalId = originalId,
            creatorName = creatorName,
            entries = entries,
        )
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 把歌单导出成 M3U8 文件，返回文件句柄（调用方自己决定分享还是提示路径）。
     *
     * 落在 `cacheDir/playlist_exports`：这是**缓存**目录，系统清理时被删掉是可接受的
     * （用户已经在别处保有了内容）。
     */
    suspend fun writeExportFile(context: Context, playlist: UserPlaylist): File? =
        withContext(Dispatchers.IO) {
            try {
                val directory = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                val fileName = sanitizeFileName(playlist.name.ifBlank { "playlist" }) + ".m3u8"
                val file = File(directory, fileName)
                file.writeText(encode(playlist), Charsets.UTF_8)
                file
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 取消必须原样抛出，不能伪装成「导出失败」。
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "导出 M3U8 失败：${playlist.name}", error)
                null
            }
        }

    /** 去掉文件名里非法字符；空名字退化为 `playlist`。 */
    fun sanitizeFileName(name: String): String =
        name.replace(ILLEGAL_FILE_CHARS, "_").trim().ifBlank { "playlist" }

    const val EXPORT_DIR = "playlist_exports"

    // ------------------------------------------------------------------ 私有工具

    private fun platformIdOf(song: UserPlaylistSong): String? =
        onlinePartsOf(song.key)?.first ?: onlinePartsOf(song.path)?.first

    private fun songIdOf(song: UserPlaylistSong): String? =
        onlinePartsOf(song.key)?.second ?: onlinePartsOf(song.path)?.second

    /** 从 `online://<platformId>/<songId>` 里解出两段；按**第一个**斜杠切，songId 本身可能含 `/`。 */
    private fun onlinePartsOf(value: String): Pair<String, String>? {
        if (!isOnlineSongKey(value)) return null
        val rest = value.removePrefix("online://")
        val platformId = rest.substringBefore('/', "")
        val songId = rest.substringAfter('/', "")
        if (platformId.isBlank() || songId.isBlank()) return null
        return platformId to songId
    }
}
