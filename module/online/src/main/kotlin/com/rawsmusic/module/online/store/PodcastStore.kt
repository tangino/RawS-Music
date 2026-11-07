package com.rawsmusic.module.online.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastHistoryItem
import com.rawsmusic.module.online.model.PodcastShow
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播客统一存储管理器（负责播客节目收藏与单集播放历史的持久化存储与实时状态分发）。
 */
object PodcastStore {
    private const val TAG = "PodcastStore"
    private const val KEY_FAVORITES = "podcast_favorites_v1"
    private const val KEY_HISTORY = "podcast_history_v1"
    private const val MAX_HISTORY_SIZE = 300

    private val gson by lazy { Gson() }
    private val mmkv by lazy { MMKV.defaultMMKV() }

    private val _favorites = MutableStateFlow<List<PodcastShow>>(emptyList())
    val favorites: StateFlow<List<PodcastShow>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<PodcastHistoryItem>>(emptyList())
    val history: StateFlow<List<PodcastHistoryItem>> = _history.asStateFlow()

    init {
        loadFavorites()
        loadHistory()
    }

    private fun loadFavorites() {
        runCatching {
            val json = mmkv.decodeString(KEY_FAVORITES)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<PodcastShow>>() {}.type
                val list: List<PodcastShow> = gson.fromJson(json, type) ?: emptyList()
                _favorites.value = list
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "加载播客收藏失败: ${e.message}", e)
        }
    }

    private fun saveFavorites(list: List<PodcastShow>) {
        _favorites.value = list
        runCatching {
            val json = gson.toJson(list)
            mmkv.encode(KEY_FAVORITES, json)
        }.onFailure { e ->
            AppLogger.e(TAG, "持久化播客收藏失败: ${e.message}", e)
        }
    }

    private fun loadHistory() {
        runCatching {
            val json = mmkv.decodeString(KEY_HISTORY)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<PodcastHistoryItem>>() {}.type
                val list: List<PodcastHistoryItem> = gson.fromJson(json, type) ?: emptyList()
                _history.value = list
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "加载播客播放历史失败: ${e.message}", e)
        }
    }

    private fun saveHistory(list: List<PodcastHistoryItem>) {
        _history.value = list
        runCatching {
            val json = gson.toJson(list)
            mmkv.encode(KEY_HISTORY, json)
        }.onFailure { e ->
            AppLogger.e(TAG, "持久化播客播放历史失败: ${e.message}", e)
        }
    }

    /**
     * 判断某个播客节目是否已收藏。
     */
    fun isFavorite(showId: String): Boolean {
        return _favorites.value.any { it.id == showId }
    }

    /**
     * 切换某个播客节目的收藏状态。
     * @return 返回切换后的最新收藏状态（true 为已收藏，false 为已取消收藏）。
     */
    fun toggleFavorite(show: PodcastShow): Boolean {
        val current = _favorites.value.toMutableList()
        val index = current.indexOfFirst { it.id == show.id }
        val newStatus: Boolean
        if (index >= 0) {
            current.removeAt(index)
            newStatus = false
        } else {
            current.add(0, show)
            newStatus = true
        }
        saveFavorites(current)
        return newStatus
    }

    /**
     * 添加播客节目到收藏。
     */
    fun addFavorite(show: PodcastShow) {
        val current = _favorites.value.toMutableList()
        if (current.none { it.id == show.id }) {
            current.add(0, show)
            saveFavorites(current)
        }
    }

    /**
     * 批量添加播客节目到收藏（一次性原子更新持久化）。
     * @return 实际新增的数量
     */
    fun addFavorites(shows: List<PodcastShow>): Int {
        if (shows.isEmpty()) return 0
        val current = _favorites.value.toMutableList()
        var added = 0
        shows.forEach { show ->
            if (current.none { it.id == show.id }) {
                current.add(0, show)
                added++
            }
        }
        if (added > 0) {
            saveFavorites(current)
        }
        return added
    }

    /**
     * 取消收藏播客节目。
     */
    fun removeFavorite(showId: String) {
        val current = _favorites.value.toMutableList()
        if (current.removeAll { it.id == showId }) {
            saveFavorites(current)
        }
    }

    /**
     * 记录播客单集播放历史（自动去重并置顶最新记录）。
     */
    fun recordPlay(episode: PodcastEpisode, show: PodcastShow) {
        val current = _history.value.toMutableList()
        // 去重相同的 episode
        current.removeAll { it.episode.id == episode.id }
        current.add(0, PodcastHistoryItem(episode = episode, show = show, playedAt = System.currentTimeMillis()))
        if (current.size > MAX_HISTORY_SIZE) {
            current.subList(MAX_HISTORY_SIZE, current.size).clear()
        }
        saveHistory(current)
    }

    /**
     * 清空播客播放历史。
     */
    fun clearHistory() {
        saveHistory(emptyList())
    }

    /**
     * 删除单条播放历史。
     */
    fun removeHistoryItem(episodeId: String) {
        val current = _history.value.toMutableList()
        if (current.removeAll { it.episode.id == episodeId }) {
            saveHistory(current)
        }
    }
}

