package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 在线歌单收藏。
 *
 * playlist_key 取值约定：`"<platformId>_<remoteId>"`。
 * 为什么用复合串当主键而不是自增 id：同一首歌单在不同平台上 remoteId 可能重名，
 * 只有「平台 + 远端 id」才全局唯一；同时这个 key 可以直接作为云端同步时的合并标识，
 * 避免为了同步再额外维护一份远端 id ↔ 本地 id 的映射表。
 */
@Entity(
    tableName = "online_playlist_favorites",
    indices = [Index(value = ["platform"])]
)
data class OnlinePlaylistFavoriteEntity(
    /** 取值为 "<platformId>_<remoteId>"，见类注释。 */
    @PrimaryKey
    @ColumnInfo(name = "playlist_key")
    val playlistKey: String,

    /** 平台标识，如 netease / qq / kugou / kuwo / migu。 */
    val platform: String,

    /** 平台侧的原始歌单 id（单平台内唯一）。 */
    @ColumnInfo(name = "remote_id")
    val remoteId: String,

    val title: String,

    @ColumnInfo(name = "cover_url", defaultValue = "")
    val coverUrl: String = "",

    @ColumnInfo(name = "play_count", defaultValue = "0")
    val playCount: Long = 0,

    @ColumnInfo(name = "track_count", defaultValue = "0")
    val trackCount: Int = 0,

    @ColumnInfo(name = "creator_name", defaultValue = "")
    val creatorName: String = "",

    @ColumnInfo(name = "creator_avatar_url", defaultValue = "")
    val creatorAvatarUrl: String = "",

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    /** 收藏时所属分组，默认落在「默认」分组下。 */
    @ColumnInfo(name = "group_name", defaultValue = "'默认'")
    val groupName: String = DEFAULT_GROUP_NAME,

    @ColumnInfo(name = "added_at")
    val addedAt: Long
) {
    companion object {
        /** 与迁移 SQL、PlaylistGroupEntity 的默认分组名保持一致。 */
        const val DEFAULT_GROUP_NAME = "默认"
    }
}
