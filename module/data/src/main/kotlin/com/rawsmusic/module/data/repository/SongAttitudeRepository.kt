package com.rawsmusic.module.data.repository

import android.content.Context
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.SongAttitudeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 一首歌的态度。三态互斥，对应 `NONE → FAVORITE → DISLIKED → NONE` 的循环。 */
enum class SongAttitudeState {
    NONE,
    FAVORITE,
    DISLIKED,
}

/**
 * 歌曲「喜欢 / 不喜欢」。
 *
 * ## ⚠️ 职责边界（重要，必须读完再调用）
 *
 * RawS **本来就有**一套「喜欢」机制：系统歌单 `FAVORITES_PLAYLIST_ID`（名为「我喜欢的音乐」），
 * 由 `PlaylistStore.toggleFavorite` / `isFavorite` / `favoriteSongKeys` 提供，并且**已经打通**了
 * 播放器（`PlayerService`）、长按菜单（`SongActionSheetHelper`）、歌单页（`PlaylistScreen` 的心形与
 * 改名/删除保护）以及 WebDAV 备份（`BackupAndroidPersistence` / `BackupArchiveCodec`）。
 *
 * 因此本仓库**不接管本地曲的「喜欢」** —— 那会造成两个事实来源，心形状态在播放器与歌单页之间
 * 不一致。分工如下（决策记录见 `AGENTS.MD` §9 D14）：
 *
 * | 场景 | 归属 |
 * | --- | --- |
 * | **本地曲的「喜欢」** | `PlaylistStore`（既有机制，**不要**用本仓库） |
 * | **不喜欢（本地 + 在线）** | 本仓库的 `song_attitude.is_disliked`（RawS 原本没有） |
 * | **在线曲的「喜欢」** | 本仓库的 `song_attitude.is_favorite`（既有歌单只能装 `AudioFile`，装不下在线曲） |
 *
 * 也就是说：`song_attitude.is_favorite` 只对**在线曲**有意义。云端同步时两侧都要上传 ——
 * 本地曲的喜欢在歌单 JSON 里，在线曲的喜欢与所有不喜欢在这里。
 *
 * ## 为什么 DAO 不返回 Flow
 * 本工程 DAO 全是 `suspend`（约定见 `AGENTS.MD` §5.3），所以每次写入后都要 [refresh] 重新查询
 * 并发布 `StateFlow`，否则界面不会更新。
 */
object SongAttitudeRepository {

    private val _dislikedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** 全部「不喜欢」的 song_key。 */
    val dislikedKeys: StateFlow<Set<String>> = _dislikedKeys.asStateFlow()

    private val _onlineFavoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    /** **仅在线曲**的「喜欢」song_key；本地曲请读 `PlaylistStore.favoriteSongKeys()`。 */
    val onlineFavoriteKeys: StateFlow<Set<String>> = _onlineFavoriteKeys.asStateFlow()

    private fun dao(context: Context) =
        MusicDatabase.getInstance(context.applicationContext).songAttitudeDao()

    /** 重新查询并发布两个集合。所有写操作之后都必须调它。 */
    suspend fun refresh(context: Context) {
        val all = withContext(Dispatchers.IO) { dao(context).getAll() }
        _dislikedKeys.value = all.filter { it.isDisliked == SongAttitudeEntity.FLAG_ON }
            .mapTo(mutableSetOf()) { it.songKey }
        // 只保留 `online://` 前缀的喜欢：本地曲的喜欢归 PlaylistStore，混进来会让两处状态看起来打架。
        _onlineFavoriteKeys.value = all
            .filter { it.isFavorite == SongAttitudeEntity.FLAG_ON && it.songKey.startsWith(ONLINE_KEY_PREFIX) }
            .mapTo(mutableSetOf()) { it.songKey }
    }

    /** 判定在线曲的喜欢状态。本地曲请改用 `PlaylistStore.isFavorite`。 */
    fun isOnlineFavorite(songKey: String): Boolean = songKey in _onlineFavoriteKeys.value

    fun isDisliked(songKey: String): Boolean = songKey in _dislikedKeys.value

    suspend fun setDisliked(context: Context, songKey: String, disliked: Boolean) =
        withContext(Dispatchers.IO) {
            val existing = dao(context).getByKey(songKey)
            if (existing == null && !disliked) {
                // 本来就没有态度，取消不喜欢是无操作 —— 不要白写一行全 0 记录。
                return@withContext
            }
            val favoriteFlag = existing?.isFavorite ?: SongAttitudeEntity.FLAG_OFF
            dao(context).upsert(
                SongAttitudeEntity(
                    songKey = songKey,
                    isFavorite = if (disliked) SongAttitudeEntity.FLAG_OFF else favoriteFlag,
                    isDisliked = if (disliked) SongAttitudeEntity.FLAG_ON else SongAttitudeEntity.FLAG_OFF,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            refresh(context)
        }

    suspend fun removeDislike(context: Context, songKey: String) = setDisliked(context, songKey, false)

    /** 清空所有「不喜欢」。保留「喜欢」标记（DAO 里也是这么实现的）。 */
    suspend fun clearAllDislikes(context: Context) = withContext(Dispatchers.IO) {
        dao(context).clearAllDisliked(System.currentTimeMillis())
        refresh(context)
    }

    /** 设置在线曲的喜欢状态。传 `favorite = true` 会顺带清掉不喜欢（二者互斥）。 */
    suspend fun setOnlineFavorite(context: Context, songKey: String, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            val existing = dao(context).getByKey(songKey)
            dao(context).upsert(
                SongAttitudeEntity(
                    songKey = songKey,
                    isFavorite = if (favorite) SongAttitudeEntity.FLAG_ON else SongAttitudeEntity.FLAG_OFF,
                    isDisliked = if (favorite) {
                        SongAttitudeEntity.FLAG_OFF
                    } else {
                        existing?.isDisliked ?: SongAttitudeEntity.FLAG_OFF
                    },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            refresh(context)
        }

    /** 批量设置在线曲的喜欢（批量「喜欢」操作用）。 */
    suspend fun setOnlineFavorites(context: Context, songKeys: Collection<String>, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            if (songKeys.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            dao(context).upsertAll(
                songKeys.map { key ->
                    SongAttitudeEntity(
                        songKey = key,
                        isFavorite = if (favorite) SongAttitudeEntity.FLAG_ON else SongAttitudeEntity.FLAG_OFF,
                        isDisliked = SongAttitudeEntity.FLAG_OFF,
                        updatedAt = now,
                    )
                },
            )
            refresh(context)
        }

    /**
     * 在线曲的三态循环：`NONE → FAVORITE → DISLIKED → NONE`。
     *
     * ⚠️ **只对在线曲使用**。本地曲的喜欢由 `PlaylistStore` 负责，走这个循环会与既有机制冲突。
     */
    suspend fun cycleOnline(context: Context, songKey: String): SongAttitudeState {
        val existing = withContext(Dispatchers.IO) { dao(context).getByKey(songKey) }
        val current = when {
            existing?.isFavorite == SongAttitudeEntity.FLAG_ON -> SongAttitudeState.FAVORITE
            existing?.isDisliked == SongAttitudeEntity.FLAG_ON -> SongAttitudeState.DISLIKED
            else -> SongAttitudeState.NONE
        }
        val next = when (current) {
            SongAttitudeState.NONE -> SongAttitudeState.FAVORITE
            SongAttitudeState.FAVORITE -> SongAttitudeState.DISLIKED
            SongAttitudeState.DISLIKED -> SongAttitudeState.NONE
        }
        when (next) {
            SongAttitudeState.NONE -> withContext(Dispatchers.IO) {
                // 回到无态度：整行删掉，避免留下全 0 的垃圾行。
                dao(context).deleteByKey(songKey)
                refresh(context)
            }
            SongAttitudeState.FAVORITE -> setOnlineFavorite(context, songKey, true)
            SongAttitudeState.DISLIKED -> setDisliked(context, songKey, true)
        }
        return next
    }

    private const val ONLINE_KEY_PREFIX = "online://"
}
