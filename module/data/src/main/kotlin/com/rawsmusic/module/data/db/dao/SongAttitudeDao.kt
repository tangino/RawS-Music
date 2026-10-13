package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.SongAttitudeEntity

/**
 * 歌曲喜欢 / 不喜欢 DAO。
 *
 * 全部 suspend，不返回 Flow（本工程约定）。
 */
@Dao
interface SongAttitudeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attitude: SongAttitudeEntity)

    /** 批量写入给云端同步还原 / 批量「喜欢」用；REPLACE 保证重复 key 覆盖而非报错。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(attitudes: List<SongAttitudeEntity>)

    @Query("SELECT * FROM song_attitude WHERE song_key = :songKey LIMIT 1")
    suspend fun getByKey(songKey: String): SongAttitudeEntity?

    @Query("SELECT * FROM song_attitude WHERE is_favorite = 1 ORDER BY updated_at DESC")
    suspend fun getAllFavorites(): List<SongAttitudeEntity>

    @Query("SELECT * FROM song_attitude WHERE is_disliked = 1 ORDER BY updated_at DESC")
    suspend fun getAllDisliked(): List<SongAttitudeEntity>

    @Query("SELECT * FROM song_attitude")
    suspend fun getAll(): List<SongAttitudeEntity>

    @Query("DELETE FROM song_attitude WHERE song_key = :songKey")
    suspend fun deleteByKey(songKey: String)

    /**
     * 只清「不喜欢」标记，保留喜欢标记。
     * 为什么不做成 DELETE：一行可能同时是「喜欢 + 不喜欢」的中间态（迁移或同步时
     * 可能出现），无条件删行会把喜欢状态一起丢掉。
     */
    @Query("UPDATE song_attitude SET is_disliked = 0, updated_at = :updatedAt WHERE is_disliked = 1")
    suspend fun clearAllDisliked(updatedAt: Long)

    @Query("DELETE FROM song_attitude")
    suspend fun clearAll()
}
