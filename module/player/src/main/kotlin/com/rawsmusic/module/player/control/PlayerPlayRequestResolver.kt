package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile

internal data class ResolvedPlayRequest(
    val queue: List<AudioFile>,
    val index: Int,
)

/** Pure queue/identity policy used before a transport play request reaches decoder ownership. */
internal class PlayerPlayRequestResolver(
    private val logWarning: (String) -> Unit,
) {
    fun sameItem(left: AudioFile, right: AudioFile): Boolean {
        if (left == right) return true
        if (left.path == right.path &&
            left.cueOffsetMs == right.cueOffsetMs &&
            left.cueTrackIndex == right.cueTrackIndex
        ) return true
        if (left.id != 0L && left.id == right.id) return true
        if (left.title.isNotBlank() && left.title.equals(right.title, ignoreCase = true)) {
            val leftArtist = left.artist.trim()
            val rightArtist = right.artist.trim()
            if (leftArtist.equals(rightArtist, ignoreCase = true) ||
                leftArtist.isBlank() || rightArtist.isBlank() ||
                leftArtist.contains(rightArtist, ignoreCase = true) ||
                rightArtist.contains(leftArtist, ignoreCase = true)
            ) return true
        }
        return false
    }

    fun sameQueue(left: List<AudioFile>, right: List<AudioFile>): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index -> sameItem(left[index], right[index]) }
    }

    fun resolve(
        song: AudioFile,
        requestedQueue: List<AudioFile>,
        requestedIndex: Int,
        currentQueue: List<AudioFile>,
    ): ResolvedPlayRequest {
        if (requestedQueue.isNotEmpty()) {
            val safeIndex = requestedIndex.coerceIn(0, requestedQueue.lastIndex)
            // 先按“路径 / id”精确匹配。搜索里同歌名、同歌手的多个版本在 sameItem() 看来完全
            // 相同（它有意忽略 path/id，兼容元数据刷新）；如果先用 sameItem()，第一条会把
            // 第三条“认领”掉，播放页于是显示第一条的标题/封面。
            if (sameExactItem(requestedQueue[safeIndex], song)) {
                return ResolvedPlayRequest(requestedQueue, safeIndex)
            }
            val exactIndex = requestedQueue.indexOfFirst { sameExactItem(it, song) }
            if (exactIndex >= 0) {
                logWarning(
                    "play queue exact index corrected: requested=$requestedIndex resolved=$exactIndex " +
                        "title=${song.title} queueSize=${requestedQueue.size}"
                )
                return ResolvedPlayRequest(requestedQueue, exactIndex)
            }
            if (sameItem(requestedQueue[safeIndex], song)) {
                return ResolvedPlayRequest(requestedQueue, safeIndex)
            }
            val identityIndex = requestedQueue.indexOfFirst { sameItem(it, song) }
            if (identityIndex >= 0) {
                logWarning(
                    "play queue index corrected: requested=$requestedIndex resolved=$identityIndex " +
                        "title=${song.title} queueSize=${requestedQueue.size}"
                )
                return ResolvedPlayRequest(requestedQueue, identityIndex)
            }
            if (requestedQueue.size > 1) {
                logWarning(
                    "play queue snapshot item updated at index $safeIndex: title=${song.title} " +
                        "previousTitle=${requestedQueue[safeIndex].title} queueSize=${requestedQueue.size}"
                )
                val updatedQueue = requestedQueue.toMutableList().apply {
                    set(safeIndex, song)
                }
                return ResolvedPlayRequest(updatedQueue, safeIndex)
            }
            return ResolvedPlayRequest(listOf(song), 0)
        }

        val existingIndex = currentQueue.indexOfFirst { sameExactItem(it, song) }
            .takeIf { it >= 0 }
            ?: currentQueue.indexOfFirst { sameItem(it, song) }
        return if (existingIndex >= 0) {
            ResolvedPlayRequest(currentQueue, existingIndex)
        } else if (currentQueue.isNotEmpty()) {
            ResolvedPlayRequest(currentQueue + song, currentQueue.size)
        } else {
            ResolvedPlayRequest(listOf(song), 0)
        }
    }

    /** Identity that can distinguish two versions of the same song; unlike [sameItem]. */
    private fun sameExactItem(left: AudioFile, right: AudioFile): Boolean {
        if (left.path == right.path &&
            left.cueOffsetMs == right.cueOffsetMs &&
            left.cueTrackIndex == right.cueTrackIndex
        ) return true
        return left.id != 0L && left.id == right.id
    }

    fun shouldUseManualSwitch(
        currentSong: AudioFile?,
        requestedSong: AudioFile,
        controllerPlaying: Boolean,
        enginePlaying: Boolean,
        usbExclusiveActive: Boolean,
        configuredManualFadeMs: Int,
    ): Boolean {
        // 网络曲目的音频载荷是“解析完直链后换源起播”，不能按普通切歌再走一遍
        // manual switch 的淡出/crossfade（会和播放器内部的换源过渡叠加成残响回响）。
        // 换源前的防爆音淡出由 PlayerController.playInternal() 在 ffmpegPlayer.play() 之前
        // 单独完成（online_switch_out / online_switch_in），这里只要拒绝二次过渡。
        if (requestedSong.encodingFormat == com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER) {
            return false
        }
        val current = currentSong ?: return false
        if (sameItem(current, requestedSong)) return false
        if (!controllerPlaying && !enginePlaying) return false
        return usbExclusiveActive || configuredManualFadeMs > 0
    }
}
