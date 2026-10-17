package com.rawsmusic.ui.playlist

import android.content.Context
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.DISLIKED_PLAYLIST_ID
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.module.data.prefs.PlaylistStore

/**
 * 「喜欢 / 不喜欢」两个伪歌单的稳定 id。
 *
 * 为什么用伪 id 而不是真的建两条歌单：RawS 的「喜欢」已经有一套完整机制（`FAVORITES_PLAYLIST_ID`
 * 系统歌单，决策见 AGENTS.MD §9 D14），而「不喜欢」同样在系统歌单 `DISLIKED_PLAYLIST_ID` 和 `song_attitude`
 * 协同维护。伪 id 专门在导航参数里出现，**不直接作为普通歌单展示**，防止在自建分组里重复出现。
 */
const val ATTITUDE_FAVORITES_PLAYLIST_ID = "attitude:favorites"
const val ATTITUDE_DISLIKED_PLAYLIST_ID = "attitude:disliked"

fun isAttitudePlaylistId(playlistId: String): Boolean =
    playlistId == ATTITUDE_FAVORITES_PLAYLIST_ID || playlistId == ATTITUDE_DISLIKED_PLAYLIST_ID

/**
 * 把伪歌单渲染成 [UserPlaylist] 视图模型。
 *
 * 为什么复用 `UserPlaylist`：`PlaylistDetailScreen` 只认这个类型，而它的曲目解析
 * （`PlaylistStore.resolveOrderedPlaylistSongs`）按 `song_key` 对齐曲库并自愈网络虚拟歌曲。
 * 详情页的解析、播放、空态、统计全都原样可用，完全具备混合歌单特征。
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
        // 喜欢沿用既有系统歌单：完整保留在线曲目与存储条目，本地曲按曲库更新信息，不误删任何歌曲
        val favorites = playlistStore.playlists.value.firstOrNull { it.isFavorites }
        val storedSongs = favorites?.songs.orEmpty()
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val resolvedSongs = storedSongs.map { stored ->
            if (stored.isOnlineEntry()) {
                stored
            } else {
                val matched = byIdentity[stored.key]
                matched?.toUserPlaylistSong() ?: stored
            }
        }
        UserPlaylist(
            id = ATTITUDE_FAVORITES_PLAYLIST_ID,
            name = context.getString(R.string.playlist_attitude_favorites_title),
            songs = resolvedSongs,
            createdAt = favorites?.createdAt ?: 0L,
            updatedAt = favorites?.updatedAt ?: 0L,
        )
    }

    ATTITUDE_DISLIKED_PLAYLIST_ID -> {
        val disliked = playlistStore.playlists.value.firstOrNull { it.id == DISLIKED_PLAYLIST_ID }
        val storedSongs = disliked?.songs.orEmpty()
        val byIdentity = librarySongs.associateBy { it.playlistIdentityKey() }
        val resolvedSongs = if (storedSongs.isNotEmpty()) {
            storedSongs.map { stored ->
                if (stored.isOnlineEntry()) {
                    stored
                } else {
                    val matched = byIdentity[stored.key]
                    matched?.toUserPlaylistSong() ?: stored
                }
            }
        } else {
            librarySongs
                .filter { it.playlistIdentityKey() in dislikedKeys }
                .map { it.toUserPlaylistSong() }
        }
        UserPlaylist(
            id = ATTITUDE_DISLIKED_PLAYLIST_ID,
            name = context.getString(R.string.playlist_attitude_disliked_title),
            songs = resolvedSongs,
            createdAt = disliked?.createdAt ?: 0L,
            updatedAt = disliked?.updatedAt ?: 0L,
        )
    }

    else -> null
}
