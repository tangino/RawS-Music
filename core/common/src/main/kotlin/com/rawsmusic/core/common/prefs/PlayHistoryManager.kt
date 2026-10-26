package com.rawsmusic.core.common.prefs

import com.rawsmusic.core.common.model.AudioFile
import com.tencent.mmkv.MMKV
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

    /** 记录数上限硬性约束：最高 500 首，最低 10 首，默认 200 首 */
    const val MAX_LIMIT_CEILING = 500
    const val MIN_LIMIT_FLOOR = 10
    const val DEFAULT_LIMIT = 200

    var maxHistoryCount: Int
        get() = kv.decodeInt(KEY_MAX_COUNT, DEFAULT_LIMIT).coerceIn(MIN_LIMIT_FLOOR, MAX_LIMIT_CEILING)
        set(value) {
            val clamped = value.coerceIn(MIN_LIMIT_FLOOR, MAX_LIMIT_CEILING)
            kv.encode(KEY_MAX_COUNT, clamped)
            trimToLimit(clamped)
        }

    private val _history = MutableStateFlow<List<PlayHistoryItem>>(loadHistory())
    val history: StateFlow<List<PlayHistoryItem>> = _history.asStateFlow()

    /**
     * 点击播放歌曲后自动记录进入历史。
     * 若已在历史中，则移到最前（更新为最新播放时间），并根据上限自动裁剪。
     */
    fun recordSong(song: AudioFile) {
        if (song.title.isBlank() && song.path.isBlank()) return
        val current = _history.value.toMutableList()
        // 去重：优先比较非空 path，若无有效 path 则比较 title + artist
        current.removeAll {
            (song.path.isNotBlank() && it.path == song.path) ||
            (song.title.isNotBlank() && it.title == song.title && it.artist == song.artist)
        }
        val newItem = PlayHistoryItem(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            duration = song.duration,
            path = song.path,
            coverKey = song.coverKey,
            albumArtPath = song.albumArtPath,
            playedAt = System.currentTimeMillis(),
        )
        current.add(0, newItem)
        val limit = maxHistoryCount
        val trimmed = if (current.size > limit) current.take(limit) else current
        _history.value = trimmed
        saveHistory(trimmed)
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
        kv.remove(KEY_HISTORY_LIST)
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
            for (i in 0 until minOf(array.length(), limit)) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(
                    PlayHistoryItem(
                        id = obj.optLong("id", 0L),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        duration = obj.optLong("duration", 0L),
                        path = obj.optString("path", ""),
                        coverKey = obj.optString("coverKey", ""),
                        albumArtPath = obj.optString("albumArtPath", ""),
                        playedAt = obj.optLong("playedAt", 0L),
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(list: List<PlayHistoryItem>) {
        try {
            val array = JSONArray()
            for (item in list) {
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
