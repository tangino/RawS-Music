package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity

@Dao
interface UserPlaylistDao {
    @Query("SELECT * FROM user_playlists ORDER BY sort_order ASC")
    suspend fun getPlaylists(): List<UserPlaylistEntity>

    @Query("SELECT * FROM user_playlist_songs ORDER BY playlist_id ASC, sort_order ASC")
    suspend fun getSongs(): List<UserPlaylistSongEntity>

    @Query("SELECT COUNT(*) FROM user_playlists")
    suspend fun countPlaylists(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: UserPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(playlists: List<UserPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<UserPlaylistSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<UserPlaylistSongEntity>)

    @Query("DELETE FROM user_playlists WHERE playlist_id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query(
        "DELETE FROM user_playlist_songs " +
            "WHERE playlist_id = :playlistId AND song_key = :songKey"
    )
    suspend fun deleteSong(playlistId: String, songKey: String)

    @Query(
        "DELETE FROM user_playlist_songs " +
            "WHERE playlist_id = :playlistId AND song_key IN (:songKeys)"
    )
    suspend fun deleteSongs(playlistId: String, songKeys: List<String>)

    @Query("DELETE FROM user_playlist_songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: String)

    @Query(
        "UPDATE user_playlists SET name = :name, updated_at = :updatedAt " +
            "WHERE playlist_id = :playlistId"
    )
    suspend fun renamePlaylist(playlistId: String, name: String, updatedAt: Long)

    @Query("UPDATE user_playlists SET updated_at = :updatedAt WHERE playlist_id = :playlistId")
    suspend fun touchPlaylist(playlistId: String, updatedAt: Long)

    /** 把单个歌单移到指定分组（P2-03 分组功能用）。 */
    @Query(
        "UPDATE user_playlists SET group_name = :groupName, updated_at = :updatedAt " +
            "WHERE playlist_id = :playlistId"
    )
    suspend fun updatePlaylistGroup(playlistId: String, groupName: String, updatedAt: Long)

    /** 批量或单曲按标题和歌手更新缺失的网络封面路径（混合歌单云同步自愈用）。 */
    @Query(
        "UPDATE user_playlist_songs SET album_art_path = :artworkPath " +
            "WHERE title = :title AND artist = :artist AND (album_art_path = '' OR album_art_path IS NULL)"
    )
    suspend fun updateArtworkPathByTitleAndArtist(title: String, artist: String, artworkPath: String)

    /** 按歌曲唯一标识更新缺失的网络封面路径。 */
    @Query(
        "UPDATE user_playlist_songs SET album_art_path = :artworkPath " +
            "WHERE song_key = :songKey AND (album_art_path = '' OR album_art_path IS NULL)"
    )
    suspend fun updateArtworkPathBySongKey(songKey: String, artworkPath: String)

    /**
     * 把某个分组下的**全部**歌单改挂到另一个分组。分组改名（old→new）与删除分组（old→默认）共用它。
     *
     * ⚠️ 必须与 `playlist_groups` 的相应改动放在**同一个事务**里执行，否则中途失败会让歌单
     * 指向一个不存在的分组（悬空）。事务由 `PlaylistGroupStore` 用 `withTransaction` 保证。
     */
    @Query(
        "UPDATE user_playlists SET group_name = :newGroupName, updated_at = :updatedAt " +
            "WHERE group_name = :oldGroupName"
    )
    suspend fun reassignGroup(oldGroupName: String, newGroupName: String, updatedAt: Long)

    @Query("DELETE FROM user_playlist_songs")
    suspend fun clearSongs()

    @Query("DELETE FROM user_playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replaceAll(
        playlists: List<UserPlaylistEntity>,
        songs: List<UserPlaylistSongEntity>
    ) {
        clearSongs()
        clearPlaylists()
        upsertPlaylists(playlists)
        upsertSongs(songs)
    }
}
