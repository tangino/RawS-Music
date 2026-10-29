package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Collects the controller flows used by the main Compose shell. */
internal class MainActivityPlaybackObserverCoordinator(
    private val scope: CoroutineScope,
    private val controller: () -> PlayerController?,
    private val onPlaybackState: (PlayState) -> Unit,
    private val onCurrentSong: (AudioFile) -> Unit,
    private val onPosition: (positionMs: Long, durationMs: Long) -> Unit,
    private val onSampleRateChanged: (Int) -> Unit,
    private val onPlayModeChanged: (PlayMode) -> Unit,
) {
    private var boundController: PlayerController? = null
    private var lastPublishedOnlineIdentity: String? = null
    private val jobs = mutableListOf<Job>()

    fun start(target: PlayerController? = controller()) {
        target ?: return
        if (boundController === target && jobs.size == 6 && jobs.all { it.isActive }) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        boundController = target
        lastPublishedOnlineIdentity = null
        jobs += scope.launch(Dispatchers.Main) {
            target.playState.collect { state ->
                onPlaybackState(state)
            }
        }
        jobs += scope.launch(Dispatchers.Main) {
            target.currentSong.collect { song ->
                if (song != null) {
                    onCurrentSong(song)
                }
            }
        }
        jobs += scope.launch(Dispatchers.Main) {
            com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController.snapshot.collect { onlineSnapshot ->
                val onlineItem = onlineSnapshot.currentItem
                if (onlineItem == null) {
                    lastPublishedOnlineIdentity = null
                    return@collect
                }
                val cur = target.currentSong.value
                val isOnlineCurrent = cur != null && (
                    com.rawsmusic.core.common.model.isOnlineSongKey(cur.path) ||
                    cur.encodingFormat == com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER ||
                    cur.path.startsWith("http://", ignoreCase = true) ||
                    cur.path.startsWith("https://", ignoreCase = true)
                )
                val isPreparingOnline = onlineSnapshot.status ==
                    com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus.Preparing ||
                    onlineSnapshot.status ==
                    com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus.Resolving
                if (!isOnlineCurrent && !isPreparingOnline) {
                    lastPublishedOnlineIdentity = null
                    return@collect
                }
                val virtualSong = AudioFile(
                    id = if (cur?.id != null && cur.id != 0L) cur.id else onlineItem.stableIdentity.hashCode().toLong(),
                    path = com.rawsmusic.core.common.model.onlineSongKey(onlineItem.sourceId, onlineItem.remoteId),
                    title = onlineItem.title,
                    artist = onlineItem.artists.joinToString(", "),
                    album = onlineItem.album,
                    duration = onlineItem.durationMs.coerceAtLeast(0L),
                    format = "ONLINE",
                    albumArtPath = onlineItem.artworkUrl,
                    encodingFormat = com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER,
                )
                // MusicSourcePlaybackController.snapshot also updates on every position tick.
                // Publishing on each tick re-ran miniPlayer.updateSong + lyrics.markSongPending,
                // which made the bottom bar title/artist/lyric flicker continuously. Publish only
                // when the online identity (not the clock) actually changes.
                val identity = buildString {
                    append(virtualSong.path).append('|')
                    append(virtualSong.title).append('|')
                    append(virtualSong.artist).append('|')
                    append(virtualSong.album).append('|')
                    append(virtualSong.duration).append('|')
                    append(virtualSong.albumArtPath)
                }
                if (identity == lastPublishedOnlineIdentity) return@collect
                lastPublishedOnlineIdentity = identity
                // Once the controller has committed the same semantic online item, its currentSong
                // is authoritative. Re-publishing the snapshot copy here caused the title/cover to
                // alternate between two equivalent-but-not-identical AudioFile instances.
                if (cur != null &&
                    cur.path == virtualSong.path &&
                    cur.title == virtualSong.title &&
                    cur.artist == virtualSong.artist &&
                    cur.albumArtPath == virtualSong.albumArtPath
                ) {
                    return@collect
                }
                onCurrentSong(virtualSong)
            }
        }
        jobs += scope.launch(Dispatchers.Main) {
            val boundController = target
            // PLAYER owns the full 20 Hz timeline directly. The shell observer only drives
            // mini-player/external-lyric/service side effects, so keep that Main-thread fan-out
            // lower-rate while duration/song changes still publish immediately.
            combine(
                boundController.position
                    .map { it / SHELL_TIMELINE_INTERVAL_MS }
                    .distinctUntilChanged(),
                boundController.duration,
                boundController.currentSong,
            ) { _, durationMs, _ ->
                boundController.position.value to durationMs
            }.collect { (positionMs, durationMs) ->
                onPosition(positionMs, durationMs)
            }
        }
        jobs += scope.launch(Dispatchers.Main) {
            target.usbOutputSampleRate.collect { sampleRate ->
                onSampleRateChanged(sampleRate)
            }
        }
        jobs += scope.launch(Dispatchers.Main) {
            target.playMode.collect { mode ->
                onPlayModeChanged(mode)
            }
        }
    }

    private companion object {
        const val SHELL_TIMELINE_INTERVAL_MS = 100L
    }
}
