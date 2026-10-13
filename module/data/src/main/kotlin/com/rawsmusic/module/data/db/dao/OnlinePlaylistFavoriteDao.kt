package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity

/**
 * 在线歌单收藏 DAO。
 *
 * 全部方法都是 suspend：本工程约定 DAO 不返回 Flow，UI 侧靠 Repository 在写入后
 * 主动刷新并发布 StateFlow（见 AGENTS.md §5.3 / DAO 约定）。
 */
@Dao
interface OnlinePlaylistFavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: OnlinePlaylistFavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(favorites: List<OnlinePlaylistFavoriteEntity>)

    @Query("SELECT * FROM online_playlist_favorites WHERE platform = :platform ORDER BY added_at DESC")
    suspend fun getByPlatform(platform: String): List<OnlinePlaylistFavoriteEntity>

    @Query("SELECT * FROM online_playlist_favorites ORDER BY added_at DESC")
    suspend fun getAll(): List<OnlinePlaylistFavoriteEntity>

    @Query("SELECT * FROM online_playlist_favorites WHERE playlist_key = :playlistKey LIMIT 1")
    suspend fun getByKey(playlistKey: String): OnlinePlaylistFavoriteEntity?

    @Query("DELETE FROM online_playlist_favorites WHERE playlist_key = :playlistKey")
    suspend fun deleteByKey(playlistKey: String)

    /** 仅更新分组，不动其它元数据；同时不刷新 added_at，避免「移分组」把收藏时间提早。 */
    @Query("UPDATE online_playlist_favorites SET group_name = :groupName WHERE playlist_key = :playlistKey")
    suspend fun updateGroup(playlistKey: String, groupName: String)

    @Query("DELETE FROM online_playlist_favorites")
    suspend fun clearAll()
}
