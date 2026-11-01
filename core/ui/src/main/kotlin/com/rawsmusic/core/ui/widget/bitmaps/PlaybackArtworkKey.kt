package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isFileBackedArtworkSource
import com.rawsmusic.core.common.model.resolveAudioFirstArtworkKey

/**
 * Single playback/lyrics/overlay artwork identity.
 *
 * The design keeps page transitions, lyrics, fullscreen and notification surfaces bound to the same
 * artwork type/id and only changes bounds/tier. RawSMusic does not have a scanner-backed artwork type/id yet,
 * so these surfaces must converge on the current audio file's versioned artwork key instead of
 * mixing albumArtPath/content-uri keys with audio-file keys.
 */
fun AudioFile?.resolvePlaybackArtworkKey(fallback: String? = null): String? {
    val song = this ?: return fallback?.takeIf { it.isNotBlank() && !RemoteArtworkDownloader.isPlaceholderUrl(it) }

    val isLocalSource = song.path.isFileBackedArtworkSource()
    val fileKey = song.fileArtworkKeyOrNull()

    // 规则：如果匹配到本地歌曲，或者歌曲本身是通过本地添加进歌单的，如果本地歌曲有封面，优先使用本地封面
    if (isLocalSource && !fileKey.isNullOrBlank()) {
        return fileKey
    }

    // 本地无封面，或非本地音源：检查是否存在有效的网络封面
    val rawExternal = song.albumArtPath.trim().ifBlank { fallback?.trim().orEmpty() }
    val external = if (RemoteArtworkDownloader.isPlaceholderUrl(rawExternal)) "" else rawExternal
    if (external.startsWith("http://", ignoreCase = true) || external.startsWith("https://", ignoreCase = true)) {
        return if (external.startsWith("http://", ignoreCase = true)) {
            "https://" + external.substring(7)
        } else {
            external
        }
    }

    // 网络也无封面时，若为本地音源，尝试探测本地物理文件内嵌封面兜底
    if (isLocalSource) {
        val audioFirstKey = resolveAudioFirstArtworkKey(
            audioPath = song.path,
            fileSize = song.fileSize,
            dateModified = song.dateModified,
            externalArtworkPath = external,
        )
        if (audioFirstKey.isNotBlank()) return audioFirstKey
    }

    return external.takeIf { it.isNotBlank() }
}

fun AudioFile.fileArtworkKeyOrNull(): String? {
    if (!path.isFileBackedArtworkSource()) return null
    // Artwork belongs to the physical source file, not to a logical CUE row.  AudioFile.coverKey
    // already provides the canonical file-version identity used by list holders/provider caches.
    // Reuse that exact key here so playback, fullscreen, queue and list surfaces share one source
    // authority instead of creating a second "...|0" identity for ordinary non-CUE songs.
    return coverKey.takeIf { it.isNotBlank() }
}

fun String.isLocalArtworkSource(): Boolean = isFileBackedArtworkSource()
