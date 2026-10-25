package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile

/**
 * Resolves the queue position from the committed song identity and the same queue snapshot.
 *
 * PlayerController publishes currentSong and PlayQueue on separate StateFlows. Compose can therefore
 * observe the new song on one frame and the old queue.currentIndex on the next frame. Album-art
 * motion must not interpret that transient pair as a same-position metadata refresh: Reference starts
 * its ArtworkPagerMotion/ArtworkPager programmatic move from the committed current-track item, not from two independently
 * sampled fields.
 *
 * The exact audio/CUE identity wins when it is present in [queueSongs]. [reportedIndex] remains a
 * fallback for priority/virtual items that are intentionally outside that list snapshot.
 */
fun resolvePlaybackQueueBindingIndex(
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    reportedIndex: Int,
): Int {
    if (currentSong != null && queueSongs.isNotEmpty()) {
        val reported = queueSongs.getOrNull(reportedIndex)
        if (reported != null && isSameQueueTrack(reported, currentSong)) {
            return reportedIndex
        }
        val exactIndex = queueSongs.indexOfFirst { candidate ->
            isSameQueueTrack(candidate, currentSong)
        }
        if (exactIndex >= 0) return exactIndex
    }
    return reportedIndex.takeIf { it in queueSongs.indices } ?: -1
}

private fun isSameQueueTrack(a: AudioFile, b: AudioFile): Boolean {
    if (a.path == b.path && a.cueOffsetMs == b.cueOffsetMs && a.cueTrackIndex == b.cueTrackIndex) {
        return true
    }
    if (a.id != 0L && a.id == b.id) {
        return true
    }
    val aOnline = com.rawsmusic.core.common.model.isOnlineSongKey(a.path)
    val bOnline = com.rawsmusic.core.common.model.isOnlineSongKey(b.path)
    if (aOnline || bOnline) {
        if (a.title.isNotBlank() && a.title.equals(b.title, ignoreCase = true)) {
            val aArtist = a.artist.trim()
            val bArtist = b.artist.trim()
            if (aArtist.isEmpty() || bArtist.isEmpty() ||
                aArtist.equals(bArtist, ignoreCase = true) ||
                aArtist.contains(bArtist, ignoreCase = true) ||
                bArtist.contains(aArtist, ignoreCase = true)
            ) {
                return true
            }
        }
    }
    return false
}
