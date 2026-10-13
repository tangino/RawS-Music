package com.rawsmusic.module.data.db

/**
 * playlist_groups 建表 DDL 常量。
 *
 * 同 OnlinePlaylistFavoriteSchema：DDL 与实体声明必须逐字符一致。
 * 注意：这里刻意不给 sort_order 加 DEFAULT。迁移 SQL 与实体都声明为无默认值，
 * 两边保持一致才不会触发 Room 的 schema 校验失败。
 */
internal object PlaylistGroupSchema {
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS playlist_groups (" +
        "name TEXT NOT NULL PRIMARY KEY, " +
        "sort_order INTEGER NOT NULL)"
}
