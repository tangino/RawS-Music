package com.rawsmusic.module.online.store

import android.content.Context
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 在线歌单收藏的读写入口。
 *
 * 为什么需要这一层：本工程的 DAO **全部是 `suspend`、不返回 `Flow`**（约定见 `AGENTS.MD` §5.3），
 * 所以写入之后必须由这里主动重新查询并把结果推给 `StateFlow`，否则 UI 不会更新。
 * 任何绕过本 Store 直接写 DAO 的代码都会让界面停在旧数据上。
 */
object OnlinePlaylistFavoriteStore {

    private const val TAG = "OnlinePlaza"

    private val _favorites = MutableStateFlow<List<OnlinePlaylistFavoriteEntity>>(emptyList())
    val favorites: StateFlow<List<OnlinePlaylistFavoriteEntity>> = _favorites.asStateFlow()

    private fun dao(context: Context) =
        MusicDatabase.getInstance(context.applicationContext).onlinePlaylistFavoriteDao()

    /** 重新查询全量收藏并发布。所有写操作之后都必须调它。 */
    suspend fun refresh(context: Context) {
        _favorites.value = withContext(Dispatchers.IO) { dao(context).getAll() }
    }

    suspend fun isFavorite(context: Context, playlistKey: String): Boolean =
        withContext(Dispatchers.IO) { dao(context).getByKey(playlistKey) != null }

    /**
     * 收藏一个在线歌单（已收藏则更新元数据，不会重置 `addedAt` 以外的东西）。
     *
     * @return true 表示新增，false 表示本来就在收藏里。
     */
    suspend fun add(
        context: Context,
        playlist: OnlinePlaylist,
        groupName: String = OnlinePlaylistFavoriteEntity.DEFAULT_GROUP_NAME,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = OnlineSourceMediaMapper.onlinePlaylistKey(playlist)
        val existed = dao(context).getByKey(key) != null
        dao(context).upsert(
            OnlinePlaylistFavoriteEntity(
                playlistKey = key,
                platform = playlist.platform.id,
                remoteId = playlist.id,
                title = playlist.title,
                coverUrl = playlist.coverUrl,
                playCount = playlist.playCount,
                trackCount = playlist.trackCount,
                creatorName = playlist.creatorName.orEmpty(),
                creatorAvatarUrl = playlist.creatorAvatarUrl.orEmpty(),
                description = playlist.description.orEmpty(),
                groupName = groupName,
                addedAt = System.currentTimeMillis(),
            ),
        )
        refresh(context)
        !existed
    }

    suspend fun remove(context: Context, playlistKey: String) = withContext(Dispatchers.IO) {
        dao(context).deleteByKey(playlistKey)
        refresh(context)
    }

    /** 只改分组，不动 `addedAt`（DAO 里也是这么实现的）。 */
    suspend fun setGroup(context: Context, playlistKey: String, groupName: String) = withContext(Dispatchers.IO) {
        dao(context).updateGroup(playlistKey, groupName)
        refresh(context)
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        dao(context).clearAll()
        refresh(context)
    }

    /** 当前已有分组的去重列表，用于分组筛选 chip（默认分组永远排最前）。 */
    fun groupNames(): List<String> {
        val groups = _favorites.value.map { it.groupName }.distinct().sorted()
        val default = OnlinePlaylistFavoriteEntity.DEFAULT_GROUP_NAME
        return listOf(default) + groups.filter { it != default }
    }
}
