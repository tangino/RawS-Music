package com.rawsmusic.module.data.db

/**
 * song_attitude 建表 DDL 常量。
 *
 * 同 OnlinePlaylistFavoriteSchema：DDL 与实体声明必须逐字符一致，
 * 否则 Room 打开库时的 schema 校验会直接抛异常。
 */
internal object SongAttitudeSchema {
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS song_attitude (" +
        "song_key TEXT NOT NULL PRIMARY KEY, " +
        "is_favorite INTEGER NOT NULL DEFAULT 0, " +
        "is_disliked INTEGER NOT NULL DEFAULT 0, " +
        "updated_at INTEGER NOT NULL)"

    const val CREATE_INDEX_IS_FAVORITE =
        "CREATE INDEX IF NOT EXISTS index_song_attitude_is_favorite ON song_attitude(is_favorite)"

    const val CREATE_INDEX_IS_DISLIKED =
        "CREATE INDEX IF NOT EXISTS index_song_attitude_is_disliked ON song_attitude(is_disliked)"
}
