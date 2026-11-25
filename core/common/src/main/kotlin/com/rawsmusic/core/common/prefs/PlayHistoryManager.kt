package com.rawsmusic.core.common.prefs

import com.rawsmusic.core.common.model.AudioFile
import com.tencent.mmkv.MMKV
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史播放歌曲记录项
 */
data class PlayHistoryItem(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long = 0L,
    val path: String = "",
    val coverKey: String = "",
    val albumArtPath: String = "",
    val playedAt: Long = System.currentTimeMillis(),
) {
    fun toAudioFile(): AudioFile {
        return AudioFile(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            path = path,
            albumArtPath = coverKey.ifBlank { albumArtPath },
        )
    }
}

/**
 * 历史播放歌曲管理器（单例，基于 MMKV 高效持久化）。
 */
object PlayHistoryManager {
    private val kv by lazy { MMKV.defaultMMKV() }
    private const val KEY_HISTORY_LIST = "play_history_items_json"
    private const val KEY_MAX_COUNT = "play_history_max_count"

    /** 记录数上限硬性约束：最高 2000 首，最低 10 首，默认 200 首 */
    const val MAX_LIMIT_CEILING = 2000
    const val MIN_LIMIT_FLOOR = 10
    const val DEFAULT_LIMIT = 200

    /** 单线程后台持久化执行器，避免 2000 条历史记录 JSON 序列化阻塞主线程 */
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-PlayHistoryPersist").apply { isDaemon = true }
    }

    private val _maxCountFlow = MutableStateFlow(
        kv.decodeInt(KEY_MAX_COUNT, DEFAULT_LIMIT).coerceIn(MIN_LIMIT_FLOOR, MAX_LIMIT_CEILING)
    )
    val maxCountFlow: StateFlow<Int> = _maxCountFlow.asStateFlow()

    var maxHistoryCount: Int
        get() = _maxCountFlow.value
        set(value) {
            val clamped = value.coerceIn(MIN_LIMIT_FLOOR, MAX_LIMIT_CEILING)
            _maxCountFlow.value = clamped
            kv.encode(KEY_MAX_COUNT, clamped)
            trimToLimit(clamped)
        }

    private val _history = MutableStateFlow<List<PlayHistoryItem>>(loadHistory())
    val history: StateFlow<List<PlayHistoryItem>> = _history.asStateFlow()

    /**
     * 判断是否为播客音频（播客由专属 PodcastStore 单独管理，不混入主界面历史播放卡片与全局播放历史）。
     */
    fun isPodcastPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.startsWith("online://podcast", ignoreCase = true) ||
            path.contains("/podcast/", ignoreCase = true) ||
            path.startsWith("podcast://", ignoreCase = true)
    }

    /**
     * 检测在线歌曲路径与封面 URL 是否发生跨平台错位（如 `online://qq/...` 却携带网易云 `126.net`/`163.com` 封面）。
     */
    fun isCrossPlatformDirtyCover(path: String?, url: String?): Boolean {
        if (path.isNullOrBlank() || url.isNullOrBlank()) return false
        val p = path.trim().lowercase()
        val u = url.trim().lowercase()
        return when {
            p.startsWith("online://qq/") -> u.contains("126.net") || u.contains("163.com")
            p.startsWith("online://netease/") -> u.contains("gtimg.cn") || u.contains("y.qq.com")
            else -> false
        }
    }

    /**
     * 点击播放歌曲后自动记录进入历史。
     * 若已在历史中，则移到最前（更新为最新播放时间），并根据上限自动裁剪。
     */
    fun recordSong(song: AudioFile) {
        if (song.title.isBlank() && song.path.isBlank()) return
        if (isPodcastPath(song.path)) return
        val current = _history.value.toMutableList()
        // 去重：优先比较非空 path，若无有效 path 则比较 title + artist
        current.removeAll {
            (song.path.isNotBlank() && it.path == song.path) ||
            (song.title.isNotBlank() && it.title == song.title && it.artist == song.artist)
        }
        val safeCoverKey = song.coverKey.takeUnless { isCrossPlatformDirtyCover(song.path, it) }.orEmpty()
        val safeArtPath = song.albumArtPath.takeUnless { isCrossPlatformDirtyCover(song.path, it) }.orEmpty()
        val newItem = PlayHistoryItem(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = song.duration,
            path = song.path,
            coverKey = safeCoverKey,
            albumArtPath = safeArtPath,
            playedAt = System.currentTimeMillis(),
        )
        current.add(0, newItem)
        val limit = maxHistoryCount
        val trimmed = if (current.size > limit) current.take(limit) else current
        _history.value = trimmed
        saveHistory(trimmed)
    }

    /**
     * 当后台同源封面自愈完成后，同步更新播放历史中对应在线歌曲的封面路径。
     */
    fun refreshOnlineTrackArtwork(title: String, artist: String, platformId: String, coverUrl: String) {
        if (title.isBlank() || coverUrl.isBlank()) return
        val current = _history.value
        if (current.isEmpty()) return
        val expectedPrefix = if (platformId.isNotBlank()) "online://${platformId.lowercase()}/" else "online://"
        var changed = false
        val updated = current.map { item ->
            val isTargetOnline = item.path.lowercase().startsWith(expectedPrefix) &&
                item.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || item.artist.equals(artist, ignoreCase = true))
            val needsHeal = item.albumArtPath.isBlank() ||
                isCrossPlatformDirtyCover(item.path, item.albumArtPath) ||
                isCrossPlatformDirtyCover(item.path, item.coverKey)
            if (isTargetOnline && needsHeal) {
                changed = true
                item.copy(coverKey = coverUrl, albumArtPath = coverUrl)
            } else {
                item
            }
        }
        if (changed) {
            _history.value = updated
            saveHistory(updated)
        }
    }

    /**
     * 从历史中移除指定单曲
     */
    fun removeSong(item: PlayHistoryItem) {
        val current = _history.value.toMutableList()
        current.removeAll {
            it.playedAt == item.playedAt ||
            (it.path.isNotBlank() && it.path == item.path) ||
            (it.title == item.title && it.artist == item.artist)
        }
        _history.value = current
        saveHistory(current)
    }

    /**
     * 清空全部播放历史
     */
    fun clearHistory() {
        _history.value = emptyList()
        persistExecutor.execute {
            runCatching { kv.remove(KEY_HISTORY_LIST) }
        }
    }

    private fun trimToLimit(limit: Int) {
        val current = _history.value
        if (current.size > limit) {
            val trimmed = current.take(limit)
            _history.value = trimmed
            saveHistory(trimmed)
        }
    }

    private fun loadHistory(): List<PlayHistoryItem> {
        val jsonStr = kv.decodeString(KEY_HISTORY_LIST)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<PlayHistoryItem>()
            val limit = maxHistoryCount
            var hadPodcastOrDirtyCover = false
            for (i in 0 until minOf(array.length(), limit)) {
                val obj = array.optJSONObject(i) ?: continue
                val path = obj.optString("path", "")
                if (isPodcastPath(path)) {
                    hadPodcastOrDirtyCover = true
                    continue
                }
                val rawCoverKey = obj.optString("coverKey", "")
                val rawAlbumArt = obj.optString("albumArtPath", "")
                val dirtyKey = isCrossPlatformDirtyCover(path, rawCoverKey)
                val dirtyArt = isCrossPlatformDirtyCover(path, rawAlbumArt)
                if (dirtyKey || dirtyArt) {
                    hadPodcastOrDirtyCover = true
                }
                list.add(
                    PlayHistoryItem(
                        id = obj.optLong("id", 0L),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        duration = obj.optLong("duration", 0L),
                        path = path,
                        coverKey = if (dirtyKey) "" else rawCoverKey,
                        albumArtPath = if (dirtyArt) "" else rawAlbumArt,
                        playedAt = obj.optLong("playedAt", 0L),
                    )
                )
            }
            if (hadPodcastOrDirtyCover) {
                saveHistory(list)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<PlayHistoryItem>) {
        // 拷贝不可变快照后在后台单线程顺序序列化写入，避免 2000 条记录阻塞切歌主线程
        val snapshot = list.toList()
        persistExecutor.execute {
            try {
                val array = JSONArray()
                for (item in snapshot) {
                    array.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("title", item.title)
                            put("artist", item.artist)
                            put("album", item.album)
                            put("duration", item.duration)
                            put("path", item.path)
                            put("coverKey", item.coverKey)
                            put("albumArtPath", item.albumArtPath)
                            put("playedAt", item.playedAt)
                        }
                    )
                }
                kv.encode(KEY_HISTORY_LIST, array.toString())
            } catch (_: Exception) {}
        }
    }
}
