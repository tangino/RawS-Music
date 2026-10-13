package com.rawsmusic.ui.playlist

import android.content.Context
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.module.data.prefs.PlaylistStore

/**
 * 「喜欢 / 不喜欢」两个伪歌单的稳定 id。
 *
 * 为什么用伪 id 而不是真的建两条歌单：RawS 的「喜欢」已经有一套完整机制（`FAVORITES_PLAYLIST_ID`
 * 系统歌单，决策见 AGENTS.MD §9 D14），而「不喜欢」只存在于 `song_attitude` 表里。这里再建两条
 * 真实歌单就会多出两个事实来源。伪 id 只在导航参数里出现，**不写 Room**，也不会与
 * `PlaylistStore` 生成的 `playlist-<uuid>` 冲突。
 */
const val ATTITUDE_FAVORITES_PLAYLIST_ID = "attitude:favorites"
const val ATTITUDE_DISLIKED_PLAYLIST_ID = "attitude:disliked"

fun isAttitudePlaylistId(playlistId: String): Boolean =
    playlistId == ATTITUDE_FAVORITES_PLAYLIST_ID || playlistId == ATTITUDE_DISLIKED_PLAYLIST_ID

/**
 * 把伪歌单渲染成 [UserPlaylist] 视图模型。
 *
 * 为什么复用 `UserPlaylist`：`PlaylistDetailScreen` 只认这个类型，而它的曲目解析
 * （`PlaylistStore.resolveSongs`）按 `song_key` 对齐曲库。曲目直接从曲库过滤出来，
 * 于是详情页的解析、播放、空态、统计全都原样可用，不需要第二套实现。
 * 返回的对象**只存在于内存**。
 *
 * [dislikedKeys] 由调用方从 `SongAttitudeRepository.dislikedKeys` 收集后传入：
 * 直接在函数里读 `.value` 不会建立快照依赖，界面不会随态度变化重组。
 */
fun attitudePlaylistView(
    context: Context,
    playlistStore: PlaylistStore,
    librarySongs: List<AudioFile>,
    playlistId: String,
    dislikedKeys: Set<String>,
): UserPlaylist? = when (playlistId) {
    ATTITUDE_FAVORITES_PLAYLIST_ID -> {
        // 喜欢沿用既有系统歌单，走它自己的解析路径（文件被删时还能回落到存储条目）。
        val favorites = playlistStore.playlists.value.firstOrNull { it.isFavorites }
        UserPlaylist(
            id = ATTITUDE_FAVORITES_PLAYLIST_ID,
            name = context.getString(R.string.playlist_attitude_favorites_title),
            songs = playlistStore.resolveSongs(favorites, librarySongs).map { it.toUserPlaylistSong() },
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    ATTITUDE_DISLIKED_PLAYLIST_ID -> UserPlaylist(
        id = ATTITUDE_DISLIKED_PLAYLIST_ID,
        name = context.getString(R.string.playlist_attitude_disliked_title),
        songs = librarySongs
            .filter { it.playlistIdentityKey() in dislikedKeys }
            .map { it.toUserPlaylistSong() },
        createdAt = 0L,
        updatedAt = 0L,
    )

    else -> null
}
