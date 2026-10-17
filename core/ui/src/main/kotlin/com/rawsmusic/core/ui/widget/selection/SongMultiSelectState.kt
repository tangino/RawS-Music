package com.rawsmusic.core.ui.widget.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.queueIdentity

/**
 * 歌曲批量多选状态控制器。
 *
 * 基于 AudioFile 的稳定 queueIdentity 进行跨重排与筛选的集合维护，
 * 同时提供与 VirtualList 联动的 selectedPositions 映射。
 */
@Stable
class SongMultiSelectState {
    var isActive by mutableStateOf(false)
        private set

    var selectedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    val selectedCount: Int
        get() = selectedKeys.size

    fun isSelected(song: AudioFile): Boolean = song.queueIdentity() in selectedKeys

    fun isSelected(key: String): Boolean = key in selectedKeys

    fun isAllSelected(songs: List<AudioFile>): Boolean {
        if (songs.isEmpty()) return false
        val allKeys = songs.map { it.queueIdentity() }
        return allKeys.isNotEmpty() && selectedKeys.containsAll(allKeys)
    }

    fun enter(initialSong: AudioFile? = null) {
        isActive = true
        selectedKeys = if (initialSong != null) {
            setOf(initialSong.queueIdentity())
        } else {
            emptySet()
        }
    }

    fun exit() {
        isActive = false
        selectedKeys = emptySet()
    }

    fun clear() {
        selectedKeys = emptySet()
    }

    fun toggle(song: AudioFile) {
        val key = song.queueIdentity()
        selectedKeys = if (key in selectedKeys) {
            selectedKeys - key
        } else {
            selectedKeys + key
        }
    }

    fun toggle(index: Int, songs: List<AudioFile>) {
        val song = songs.getOrNull(index) ?: return
        toggle(song)
    }

    fun toggleAll(songs: List<AudioFile>) {
        val allKeys = songs.map { it.queueIdentity() }.toSet()
        selectedKeys = if (allKeys.isNotEmpty() && selectedKeys.containsAll(allKeys)) {
            emptySet()
        } else {
            allKeys
        }
    }

    fun toggleRange(songs: List<AudioFile>) {
        val indices = songs.indices.filter { songs[it].queueIdentity() in selectedKeys }
        if (indices.size < 2) return
        val first = indices.first()
        val last = indices.last()
        if (last - first <= 1) return
        val insideKeys = (first + 1 until last).map { songs[it].queueIdentity() }.toSet()
        selectedKeys = if (insideKeys.all { it in selectedKeys }) {
            selectedKeys - insideKeys
        } else {
            selectedKeys + insideKeys
        }
    }

    fun getSelectedSongs(songs: List<AudioFile>): List<AudioFile> {
        if (selectedKeys.isEmpty()) return emptyList()
        return songs.filter { it.queueIdentity() in selectedKeys }
    }

    /**
     * 计算供 VirtualList 使用的索引位置集合。
     * 当处于多选激活状态且未勾选任何项时，返回 setOf(-1) 保持 checkbox 挂载。
     */
    fun computeListSelectedPositions(songs: List<AudioFile>): Set<Int> {
        if (!isActive) return emptySet()
        val positions = songs.indices.filter { songs[it].queueIdentity() in selectedKeys }.toSet()
        return if (positions.isEmpty()) setOf(-1) else positions
    }
}

@Composable
fun rememberSongMultiSelectState(): SongMultiSelectState {
    return remember { SongMultiSelectState() }
}
