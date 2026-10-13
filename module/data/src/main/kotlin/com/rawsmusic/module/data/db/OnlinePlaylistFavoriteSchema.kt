package com.rawsmusic.module.data.db

/**
 * online_playlist_favorites 建表 DDL 常量。
 *
 * 为什么单独抽出常量：Room 在打开数据库时会校验「迁移执行后的实际表结构」与
 * 「实体编译期推导出的期望表结构」是否逐字符一致，任何差异都会抛
 * IllegalStateException 让 App 直接崩。把 DDL 集中成常量，保证 MIGRATION_6_7
 * 与实体声明只能从同一处取值，避免两处各写一遍 SQL 而悄悄漂移。
 */
internal object OnlinePlaylistFavoriteSchema {
    const val CREATE_TABLE = "CREATE TABLE IF NOT EXISTS online_playlist_favorites (" +
        "playlist_key TEXT NOT NULL PRIMARY KEY, " +
        "platform TEXT NOT NULL, " +
        "remote_id TEXT NOT NULL, " +
        "title TEXT NOT NULL, " +
        "cover_url TEXT NOT NULL DEFAULT '', " +
        "play_count INTEGER NOT NULL DEFAULT 0, " +
        "track_count INTEGER NOT NULL DEFAULT 0, " +
        "creator_name TEXT NOT NULL DEFAULT '', " +
        "creator_avatar_url TEXT NOT NULL DEFAULT '', " +
        "description TEXT NOT NULL DEFAULT '', " +
        "group_name TEXT NOT NULL DEFAULT '默认', " +
        "added_at INTEGER NOT NULL)"

    // 索引名必须是 Room 的约定名 index_<表名>_<列名>，否则迁移校验同样会失败。
    const val CREATE_INDEX_PLATFORM =
        "CREATE INDEX IF NOT EXISTS index_online_playlist_favorites_platform " +
            "ON online_playlist_favorites(platform)"
}
