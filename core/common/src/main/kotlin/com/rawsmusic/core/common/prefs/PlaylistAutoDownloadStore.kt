package com.rawsmusic.core.common.prefs

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 本地歌单（网络/混合）级「播完自动保存（下载）」选项持久化管理（基于 MMKV）。
 *
 * 仅对本地歌单生效，与 [PlayedPlaylistManager.currentPlaylist] 对齐。
 */
object PlaylistAutoDownloadStore {
    private val kv by lazy { MMKV.defaultMMKV() }
    private const val KEY_ENABLED_PLAYLISTS = "playlist_auto_download_enabled_keys_v1"

    private val _enabledKeys = MutableStateFlow(loadKeys())
    val enabledKeys: StateFlow<Set<String>> = _enabledKeys.asStateFlow()

    fun localKey(playlistId: String): String = "local:${playlistId.trim()}"

    fun isEnabled(playlistKey: String): Boolean = playlistKey in _enabledKeys.value

    fun isLocalEnabled(playlistId: String): Boolean = isEnabled(localKey(playlistId))

    fun isEnabledForCurrentPlaylist(): Boolean {
        val current = PlayedPlaylistManager.currentPlaylist.value ?: return false
        if (current.isOnline) return false
        return isEnabled(localKey(current.id))
    }

    fun setEnabled(playlistKey: String, enabled: Boolean) {
        if (playlistKey.isBlank()) return
        val current = _enabledKeys.value
        val updated = if (enabled) current + playlistKey else current - playlistKey
        if (updated == current) return
        _enabledKeys.value = updated
        saveKeys(updated)
    }

    fun toggle(playlistKey: String): Boolean {
        val next = !isEnabled(playlistKey)
        setEnabled(playlistKey, next)
        return next
    }

    private fun loadKeys(): Set<String> = runCatching {
        val raw = kv.decodeString(KEY_ENABLED_PLAYLISTS, null).orEmpty()
        if (raw.isBlank()) return@runCatching emptySet()
        val arr = JSONArray(raw)
        buildSet {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.startsWith("local:") }?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    private fun saveKeys(keys: Set<String>) {
        runCatching {
            val arr = JSONArray()
            keys.forEach { arr.put(it) }
            kv.encode(KEY_ENABLED_PLAYLISTS, arr.toString())
        }
    }
}
