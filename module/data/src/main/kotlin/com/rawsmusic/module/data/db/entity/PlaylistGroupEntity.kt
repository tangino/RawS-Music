package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 歌单分组。
 *
 * 为什么用 name 当主键而不是自增 id：分组名在 UI 上是用户可见且要求唯一的
 * （两个同名分组无法区分），直接用 name 当主键可以让「改名」退化为一次
 * 事务内删旧插新，也免去 user_playlists.group_name 再存一份外键 id ——
 * group_name 列直接存分组名，读歌单时无需 join。
 *
 * 默认分组「默认」不在这里预置：它由 user_playlists.group_name 的列默认值兜底，
 * 删除分组时其上歌单回落该值即可，避免删掉「默认」分组后出现无归属歌单。
 */
@Entity(tableName = "playlist_groups")
data class PlaylistGroupEntity(
    @PrimaryKey
    val name: String,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int
) {
    companion object {
        const val DEFAULT_GROUP_NAME = "默认"
    }
}
