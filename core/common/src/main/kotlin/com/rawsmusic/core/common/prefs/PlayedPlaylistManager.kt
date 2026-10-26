package com.rawsmusic.core.common.prefs

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 播放过的歌单模型（涵盖当前播放歌单与上次播放歌单）。
 */
data class PlayedPlaylistEntry(
    val id: String,
    val name: String,
    val isOnline: Boolean = false,
    val platformId: String? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val lastPlayedSongTitle: String? = null,
    val lastPlayedSongArtist: String? = null,
    val lastPlayedTimeMs: Long = System.currentTimeMillis(),
)

/**
 * 当前播放歌单与上次播放歌单管理器（单例，基于 MMKV 持久化）。
 */
object PlayedPlaylistManager {
    private val kv by lazy { MMKV.defaultMMKV() }
    private const val KEY_CURRENT = "played_playlist_current_json"
    private const val KEY_PREVIOUS = "played_playlist_previous_json"

    private val _currentPlaylist = MutableStateFlow<PlayedPlaylistEntry?>(loadEntry(KEY_CURRENT))
    val currentPlaylist: StateFlow<PlayedPlaylistEntry?> = _currentPlaylist.asStateFlow()

    private val _previousPlaylist = MutableStateFlow<PlayedPlaylistEntry?>(loadEntry(KEY_PREVIOUS))
    val previousPlaylist: StateFlow<PlayedPlaylistEntry?> = _previousPlaylist.asStateFlow()

    fun recordPlayedPlaylist(
        id: String,
        name: String,
        isOnline: Boolean = false,
        platformId: String? = null,
        coverUrl: String? = null,
        trackCount: Int = 0,
        lastPlayedSongTitle: String? = null,
        lastPlayedSongArtist: String? = null,
    ) {
        if (id.isBlank() || name.isBlank()) return
        // 严格歌单校验：非歌单内容（歌手、专辑、单曲等）绝不进入播放歌单卡片
        if (id.startsWith("artist_") || id.startsWith("album_") || name.contains("热门歌曲") || name.contains("单曲搜索")) {
            recordNonPlaylistPlayed()
            return
        }
        val current = _currentPlaylist.value
        val effectiveCover = coverUrl?.takeIf { it.isNotBlank() }
            ?: current?.takeIf { it.id == id && it.isOnline == isOnline }?.coverUrl
        val newEntry = PlayedPlaylistEntry(
            id = id,
            name = name,
            isOnline = isOnline,
            platformId = platformId,
            coverUrl = effectiveCover,
            trackCount = trackCount,
            lastPlayedSongTitle = lastPlayedSongTitle ?: current?.takeIf { it.id == id }?.lastPlayedSongTitle,
            lastPlayedSongArtist = lastPlayedSongArtist ?: current?.takeIf { it.id == id }?.lastPlayedSongArtist,
            lastPlayedTimeMs = System.currentTimeMillis(),
        )
        if (current != null && current.id == id && current.isOnline == isOnline) {
            // 同一个歌单更新信息
            _currentPlaylist.value = newEntry
            saveEntry(KEY_CURRENT, newEntry)
            return
        }
        // 切换了歌单，将旧的当前歌单移到 previousPlaylist
        if (current != null) {
            _previousPlaylist.value = current
            saveEntry(KEY_PREVIOUS, current)
        } else if (_previousPlaylist.value?.id == id && _previousPlaylist.value?.isOnline == isOnline) {
            // 如果原本没有当前播放歌单，但上次播放歌单恰好是该歌单，则将上次播放置空，避免双卡片重复
            _previousPlaylist.value = null
            saveEntry(KEY_PREVIOUS, null)
        }
        _currentPlaylist.value = newEntry
        saveEntry(KEY_CURRENT, newEntry)
    }

    /**
     * 播放非歌单内容（全部歌曲、专辑、艺术家、最近播放、文件夹等）时，
     * 原“当前播放歌单”沉淀为“上次播放歌单”，当前歌单置空。
     * （与 OrBitPlayer 的 PlayedPlaylistManager 保持完全一致）。
     */
    fun recordNonPlaylistPlayed() {
        val current = _currentPlaylist.value ?: return
        _previousPlaylist.value = current
        saveEntry(KEY_PREVIOUS, current)
        _currentPlaylist.value = null
        saveEntry(KEY_CURRENT, null)
    }

    /**
     * 如果当前歌单缺少封面，且当前播放的曲目具备封面时，自动补齐封面。
     */
    fun updateCurrentPlaylistCoverIfBlank(coverKey: String) {
        if (coverKey.isBlank()) return
        val current = _currentPlaylist.value ?: return
        if (current.coverUrl.isNullOrBlank()) {
            val updated = current.copy(coverUrl = coverKey)
            _currentPlaylist.value = updated
            saveEntry(KEY_CURRENT, updated)
        }
    }

    private fun loadEntry(key: String): PlayedPlaylistEntry? {
        val jsonStr = kv.decodeString(key)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val json = JSONObject(jsonStr)
            val id = json.optString("id")
            val name = json.optString("name")
            if (id.isBlank() || name.isBlank() || id.startsWith("artist_") || id.startsWith("album_") || name.contains("热门歌曲")) {
                kv.remove(key)
                return null
            }
            PlayedPlaylistEntry(
                id = id,
                name = name,
                isOnline = json.optBoolean("isOnline", false),
                platformId = json.optString("platformId").takeIf { it.isNotBlank() },
                coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
                trackCount = json.optInt("trackCount", 0),
                lastPlayedSongTitle = json.optString("lastPlayedSongTitle").takeIf { it.isNotBlank() },
                lastPlayedSongArtist = json.optString("lastPlayedSongArtist").takeIf { it.isNotBlank() },
                lastPlayedTimeMs = json.optLong("lastPlayedTimeMs", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveEntry(key: String, entry: PlayedPlaylistEntry?) {
        if (entry == null) {
            kv.remove(key)
            return
        }
        try {
            val json = JSONObject().apply {
                put("id", entry.id)
                put("name", entry.name)
                put("isOnline", entry.isOnline)
                put("platformId", entry.platformId.orEmpty())
                put("coverUrl", entry.coverUrl.orEmpty())
                put("trackCount", entry.trackCount)
                put("lastPlayedSongTitle", entry.lastPlayedSongTitle.orEmpty())
                put("lastPlayedSongArtist", entry.lastPlayedSongArtist.orEmpty())
                put("lastPlayedTimeMs", entry.lastPlayedTimeMs)
            }
            kv.encode(key, json.toString())
        } catch (_: Exception) {}
    }
}
