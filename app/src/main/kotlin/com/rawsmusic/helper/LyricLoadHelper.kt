package com.rawsmusic.helper

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.scanner.LyricReader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stable identity of the audio segment whose lyrics are being rendered.
 *
 * Do not include mutable database/enrichment fields such as id or duration. The controller may
 * replace the current [AudioFile] with an enriched copy while the asynchronous lyric read is in
 * flight. CUE coordinates are part of the identity because multiple tracks can share one physical
 * path.
 */
internal fun AudioFile.lyricRequestKey(): String = buildString {
    append(path)
    append('|')
    append(cueOffsetMs)
    append('|')
    append(cueEndMs)
    append('|')
    append(cueTrackIndex)
}

/**
 * 歌词读取器。
 *
 * 只负责从文件读取歌词，读取完成后回调 setComposeLyricData。
 * 所有发布动作（Lyricon / PlayerService / TickerBridge）统一由 LyricsCoordinator 处理。
 */
class LyricLoadHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val setLyricEnabled: (Boolean) -> Unit,
    private val getCurrentSong: () -> AudioFile?,
    /**
     * 第三个参数为 `pending`：本机没有歌词、正在联网兜底时先发布一个占位词幕，
     * 让播放页立刻离开上一首的歌词；最终结果（歌词或空）稍后再发布一次。
     */
    private val setComposeLyricData: (AudioFile, LyricData, Boolean) -> Unit,
    private val setMiniLyricData: (LyricData) -> Unit,
    private val clearCurrentLyricText: () -> Unit,
    private val updateLyricAnchor: () -> Unit,
    private val applyLyricColors: () -> Unit,
    private val lyricsPublisher: LyricsPublisher
) {
    private val loadGeneration = AtomicInteger(0)

    fun load(songPath: String) {
        load(AudioFile(path = songPath))
    }

    fun load(song: AudioFile) {
        if (song.path.isBlank()) {
            val generation = loadGeneration.incrementAndGet()
            clearLyricsIfLatest(generation, song)
            return
        }

        val generation = loadGeneration.incrementAndGet()
        val requestKey = song.lyricRequestKey()
        clearCurrentLyricText()

        scope.launch(Dispatchers.IO) {
            // Parsing and animation-flag decoration both walk the full lyric structure. Keep that
            // immutable preparation off Main so a freshly written Lyrico sidecar cannot contend
            // with an immediate PLAYER <-> LYRIC shared transition.
            val localLyrics = LyricReader.readLyrics(song).withAnimationFlags()
            if (!localLyrics.isEmpty) {
                publish(song, localLyrics, pending = false, generation = generation, requestKey = requestKey)
                return@launch
            }

            // 本机没有任何歌词，下面要走联网兜底（可能要好几秒）。先把词幕换成当前歌曲的
            // 占位词幕：否则在这段时间里播放页会一直显示上一首的歌词。
            publish(song, LyricData(), pending = true, generation = generation, requestKey = requestKey)
            val onlineLyrics = fetchOnlineLyrics(song)
            publish(song, onlineLyrics, pending = false, generation = generation, requestKey = requestKey)
        }
    }

    private suspend fun fetchOnlineLyrics(song: AudioFile): LyricData {
        if (song.title.isBlank() && song.artist.isBlank()) return LyricData()
        val (platform, songId) = if (com.rawsmusic.core.common.model.isOnlineSongKey(song.path)) {
            val rest = song.path.removePrefix("online://")
            val p = rest.substringBefore('/', "")
            val id = rest.substringAfter('/', "")
            Pair(p.takeIf { it.isNotBlank() }, id.takeIf { it.isNotBlank() })
        } else {
            Pair(null, null)
        }
        val onlineLrc = com.rawsmusic.module.online.lyric.OnlineLyricFetcher.fetchLyric(
            title = song.title,
            artist = song.artist,
            platform = platform,
            songId = songId,
        )
        if (onlineLrc.isNullOrBlank()) return LyricData()
        return com.rawsmusic.module.scanner.parser.RawSLyricsParser.parse(onlineLrc).withAnimationFlags()
    }

    private suspend fun publish(
        song: AudioFile,
        data: LyricData,
        pending: Boolean,
        generation: Int,
        requestKey: String,
    ) {
        withContext(Dispatchers.Main) {
            if (loadGeneration.get() != generation) return@withContext
            val current = getCurrentSong()
            val isMatchingTrack = current != null && (
                current.lyricRequestKey() == requestKey ||
                (current.id == song.id && current.id != 0L) ||
                (current.path == song.path) ||
                (current.title.equals(song.title, ignoreCase = true) &&
                    current.artist.equals(song.artist, ignoreCase = true))
            )
            if (!isMatchingTrack) return@withContext

            setComposeLyricData(song, data, pending)
            if (pending) {
                // 只是把上一首的词幕换掉，播放器的歌词开关/颜色/胶囊文案都等最终结果再动，
                // 否则一次切歌会把同一套状态来回设置两遍。
                return@withContext
            }

            setMiniLyricData(data)
            setLyricEnabled(!data.isEmpty)

            if (!data.isEmpty) {
                applyLyricColors()
            } else {
                clearCurrentLyricText()
            }

            updateLyricAnchor()
        }
    }

    private fun clearLyricsIfLatest(generation: Int, requestSong: AudioFile) {
        if (loadGeneration.get() != generation) return
        val emptyData = LyricData()
        setComposeLyricData(requestSong, emptyData, false)
        setMiniLyricData(emptyData)
        clearCurrentLyricText()
        setLyricEnabled(false)
        updateLyricAnchor()
    }

}
