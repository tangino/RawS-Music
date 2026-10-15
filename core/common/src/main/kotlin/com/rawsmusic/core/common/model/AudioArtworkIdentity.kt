package com.rawsmusic.core.common.model

/**
 * Builds the stable artwork identity used by every UI/provider entry point.
 *
 * A file-backed audio path owns its artwork identity. External album-art metadata is only a fallback
 * for virtual/library rows that do not have a real audio source. This prevents a scanner-provided
 * folder.jpg/content URI from bypassing the embedded-artwork probe before BitmapProvider sees the
 * current track.
 */
/**
 * 判断是否属于在线音乐虚拟路径、远程流或本地临时网络音频缓存。
 */
fun isOnlineAudioPath(path: String): Boolean {
    if (path.isBlank()) return false
    return path.startsWith("online://", ignoreCase = true) ||
        path.startsWith("http://", ignoreCase = true) ||
        path.startsWith("https://", ignoreCase = true) ||
        path.contains("/cache/online_audio", ignoreCase = true) ||
        path.contains("online_audio_v1", ignoreCase = true)
}

fun resolveAudioFirstArtworkKey(
    audioPath: String,
    fileSize: Long,
    dateModified: Long,
    externalArtworkPath: String = ""
): String {
    val external = externalArtworkPath.trim()
    val isExternalHttp = external.startsWith("http://", ignoreCase = true) ||
        external.startsWith("https://", ignoreCase = true)

    // 在线歌曲或者具有有效网络封面的非本地音频，网络封面享有最高权威
    if (isExternalHttp && (isOnlineAudioPath(audioPath) || !audioPath.isFileBackedArtworkSource())) {
        return if (external.startsWith("http://", ignoreCase = true)) {
            "https://" + external.substring(7)
        } else {
            external
        }
    }

    if (audioPath.isFileBackedArtworkSource()) {
        return "audio://$audioPath|$fileSize|$dateModified"
    }
    val remoteAudio = audioPath.startsWith("http://", ignoreCase = true) ||
        audioPath.startsWith("https://", ignoreCase = true)
    if (remoteAudio && external == audioPath.trim()) {
        return ""
    }
    return external.takeIf { it.isNotBlank() }.orEmpty()
}

/** True only when BitmapProvider can open the audio source as a genuine local music file. */
fun String.isFileBackedArtworkSource(): Boolean {
    if (isBlank()) return false
    if (isOnlineAudioPath(this)) return false
    return !startsWith("content://", ignoreCase = true)
}
