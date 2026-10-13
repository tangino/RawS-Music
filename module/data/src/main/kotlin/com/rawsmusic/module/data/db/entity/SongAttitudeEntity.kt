package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 歌曲「喜欢 / 不喜欢」态度表。
 *
 * 为什么喜欢和不喜欢放同一行而不是两张表：二者互斥（同一首歌任意时刻只可能是
 * 喜欢 / 不喜欢 / 无态度之一），同一行可以原子地表达状态迁移，避免两张表出现
 * 「既喜欢又不喜欢」的脏数据。
 *
 * song_key 语义：
 * - 本地曲：沿用 UserPlaylistSongEntity.song_key 的取值规则，即
 *   AudioFile.playlistIdentityKey() —— 有 path 时是 `path|<小写斜杠化路径>`，
 *   无 path 时是 `media|<id>|<title>|<artist>|<album>|<duration>`。
 *   为什么必须复用：否则同一首歌在歌单表与态度表里会算出两个 key，出现「歌单里有
 *   这首歌，但喜欢状态读不到」的错位。
 * - 在线曲：`online://<platformId>/<songId>`，为将来接入在线曲目预留命名空间，
 *   与本地 key 的前缀（path| / media|）不会冲突。
 */
@Entity(
    tableName = "song_attitude",
    indices = [
        Index(value = ["is_favorite"]),
        Index(value = ["is_disliked"])
    ]
)
data class SongAttitudeEntity(
    /** 本地曲 = playlistIdentityKey()；在线曲 = "online://<platformId>/<songId>"。 */
    @PrimaryKey
    @ColumnInfo(name = "song_key")
    val songKey: String,

    /** 0 = 未喜欢，1 = 喜欢。用 Int 而非 Boolean 是为了与迁移 SQL 的 INTEGER 默认值一致。 */
    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Int = 0,

    @ColumnInfo(name = "is_disliked", defaultValue = "0")
    val isDisliked: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    companion object {
        const val FLAG_OFF = 0
        const val FLAG_ON = 1
    }
}
