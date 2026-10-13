package com.rawsmusic.core.common.model

const val FAVORITES_PLAYLIST_ID = "favorites"

data class UserPlaylist(
    val id: String,
    val name: String,
    val songs: List<UserPlaylistSong>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isFavorites: Boolean get() = id == FAVORITES_PLAYLIST_ID
}

data class UserPlaylistSong(
    val key: String,
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val path: String,
    val fileSize: Long,
    val format: String,
    val sampleRate: Int,
    val bitRate: Int,
    val bitsPerSample: Int,
    val albumArtPath: String,
    val addedAt: Long
)

fun AudioFile.playlistIdentityKey(): String {
    if (path.isNotBlank()) return "path|${path.replace('\\', '/').lowercase()}"
    return "media|$id|$title|$artist|$album|$duration"
}

// ============================================================================
// 在线曲目的标识（P2-05b）
//
// 为什么这几件事必须放在 core:common 而不是 :module:online：`SongAttitudeRepository` 与
// `PlaylistStore` 都在 :module:data，而依赖方向是 online → data。若把在线 key 的格式定义在
// online 模块，data 模块就只能**再抄一份**格式，两边一旦漂移，同一首在线曲在歌单表与态度表里
// 会算出两个 key，出现「歌单里有这首、喜欢状态读不到」的错位。
// 因此在线 key 的格式在这里**唯一定义**，:module:online 的 OnlineSourceMediaMapper 委托过来。
// ============================================================================

/** 在线曲目 key 的前缀。`path` 与 `song_key` 都用它开头来标记「这是在线曲」。 */
const val ONLINE_SONG_KEY_PREFIX = "online://"

/** 在线曲目的稳定标识：`online://<platformId>/<songId>`。 */
fun onlineSongKey(platformId: String, songId: String): String =
    "$ONLINE_SONG_KEY_PREFIX$platformId/$songId"

/** 该 key 是否代表在线曲目。 */
fun isOnlineSongKey(key: String): Boolean = key.startsWith(ONLINE_SONG_KEY_PREFIX)

/**
 * 在线曲目在本地歌单里的虚拟 id。
 *
 * 算法照搬 OrBit 的 `OnlineAudioSourceManager.generateVirtualSongId`：
 * `"<platformId>:<songId>".hashCode()` 取**负值**（0 时退化为 `-999999L`）。
 *
 * 为什么必须是负数：`user_playlist_songs.id` 在本地曲上存的是 MediaStore 的真实 id（正数），
 * 用负数可以让「这是在线曲」仅凭 id 就能判断，且**不可能**与本地曲撞 id。
 * 为什么必须**稳定**：跨端云同步要靠这个值对齐同一首在线曲，换算法会让已同步的数据对不上。
 */
fun generateVirtualSongId(platformId: String, songId: String): Long {
    val hash = "$platformId:$songId".hashCode().toLong()
    return when {
        hash > 0L -> -hash
        hash == 0L -> -999999L
        else -> hash
    }
}

/** 该行是否是在线曲目（`key` 或 `path` 任一以 `online://` 开头都算，兼容 OrBit 写下的数据）。 */
fun UserPlaylistSong.isOnlineEntry(): Boolean =
    isOnlineSongKey(key) || isOnlineSongKey(path)

fun AudioFile.toUserPlaylistSong(): UserPlaylistSong {
    return UserPlaylistSong(
        key = playlistIdentityKey(),
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        path = path,
        fileSize = fileSize,
        format = format,
        sampleRate = sampleRate,
        bitRate = bitRate,
        bitsPerSample = bitsPerSample,
        albumArtPath = albumArtPath,
        addedAt = System.currentTimeMillis()
    )
}

fun UserPlaylistSong.toAudioFile(): AudioFile {
    return AudioFile(
        id = id,
        path = path,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        fileSize = fileSize,
        format = format,
        sampleRate = sampleRate,
        bitRate = bitRate,
        bitsPerSample = bitsPerSample,
        albumArtPath = albumArtPath
    )
}
