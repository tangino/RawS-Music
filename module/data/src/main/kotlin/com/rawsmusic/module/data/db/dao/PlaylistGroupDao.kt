package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.PlaylistGroupEntity

/**
 * 歌单分组 DAO。
 *
 * 全部 suspend，不返回 Flow（本工程约定）。
 */
@Dao
interface PlaylistGroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: PlaylistGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(groups: List<PlaylistGroupEntity>)

    @Query("SELECT * FROM playlist_groups ORDER BY sort_order ASC")
    suspend fun getAllBySortOrder(): List<PlaylistGroupEntity>

    /**
     * 分组改名。name 是主键，UPDATE 直接改写主键值即可，无需删旧插新。
     * 调用方（PlaylistGroupStore）必须把本方法与「同步修改 user_playlists.group_name」
     * 放进同一个事务，否则会出现歌单指向已不存在分组的悬空状态。
     * 若 newName 已存在会触发主键冲突（SQLiteConstraintException），该冲突应由
     * 调用方在 UI 层提前拦截并提示，而不是在这里静默合并两个分组。
     */
    @Query("UPDATE playlist_groups SET name = :newName WHERE name = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("DELETE FROM playlist_groups WHERE name = :name")
    suspend fun delete(name: String)

    @Query("UPDATE playlist_groups SET sort_order = :sortOrder WHERE name = :name")
    suspend fun updateSortOrder(name: String, sortOrder: Int)
}
