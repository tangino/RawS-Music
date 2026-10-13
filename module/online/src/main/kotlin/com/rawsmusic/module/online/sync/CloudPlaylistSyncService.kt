package com.rawsmusic.module.online.sync

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistGroupStore
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.SongAttitudeRepository
import com.rawsmusic.module.online.store.OnlinePlaylistFavoriteStore
import kotlinx.coroutines.CancellationException

/** 一次上传所需的三个 JSON 串 + 统计（统计用于 UI 提示与自检）。 */
data class CloudPlaylistPayload(
    val playlistsJson: String,
    /** 普通歌单数（不含两个系统歌单）。 */
    val localPlaylistCount: Int,
    val onlineFavoriteCount: Int,
    /** 系统歌单里承载的喜欢 / 不喜欢条目数，便于「上传了多少」的提示。 */
    val favoriteCount: Int,
    val dislikedCount: Int,
)

/**
 * 歌单云同步的业务编排（P2-12 的上传侧）。
 *
 * 分工：[CloudPlaylistJson] 负责**格式**，[CloudSyncClient] 负责**传输**，
 * 这里负责**把本地四处数据源装配成一次上传**。
 *
 * ## 为什么上传前必须先把四个 Store 都 refresh 一遍
 *
 * 本工程的 DAO 全是 `suspend`、不返回 `Flow`（`AGENTS.MD` §5.3），各 Store 的 `StateFlow`
 * 只在被 `refresh` 之后才是新鲜数据。云同步是「一次性快照」操作，**用陈旧的内存态上传会把
 * 云端数据写回旧版本**（而且因为服务端是 last-write-wins，旧数据会真的覆盖掉新数据）。
 * 所以这里宁可多查四次库，也要保证快照是最新的。
 *
 * ## 喜欢/不喜欢为什么要拆进两个系统歌单
 *
 * 契约（`docs/cloud-sync-contract.md` §4）里喜欢/不喜欢**没有独立表或接口**，
 * 而是作为 `我喜欢的音乐` / `不喜欢的音乐` 两个系统歌单塞进 `playlists_json`，
 * 每首歌上带 `isFavorite` / `isDisliked` 标记。这是跨端互认的锚点，不要改成自定义结构。
 *
 * 在线曲的「喜欢」没有本地元数据（只有 `online://` key），所以用
 * [CloudPlaylistJson.songOfKey] 上传 —— 身份在 `path` 里，服务端按 `path` 去重，认得出。
 */
object CloudPlaylistSyncService {

    private const val TAG = "CloudSync"

    /** 组装上传载荷。会先刷新四个数据源，确保快照新鲜。 */
    suspend fun buildPayload(context: Context): CloudPlaylistPayload {
        val store = PlaylistStore.getInstance(context)
        // 等 Room 侧完成首次水合，否则可能拿到空列表。
        store.awaitBackupReady()

        PlaylistGroupStore.refresh(context)
        SongAttitudeRepository.refresh(context)
        OnlinePlaylistFavoriteStore.refresh(context)

        val playlists = store.playlists.value
        val groupOf = PlaylistGroupStore.playlistGroups.value
        val favoriteKeys = store.favoriteSongKeys()
        val dislikedKeys = SongAttitudeRepository.dislikedKeys.value
        val onlineFavoriteKeys = SongAttitudeRepository.onlineFavoriteKeys.value

        // ① 普通歌单：排除系统 favorites —— 它由下面的系统歌单承载，重复上传会让服务端按 name
        //    合并出两个同名歌单（一个是真歌单、一个是系统语义），语义混乱。
        val localDtos = playlists
            .filterNot { it.isFavorites }
            .map { playlist ->
                CloudPlaylistJson.localPlaylist(
                    playlist = playlist,
                    groupName = groupOf[playlist.id] ?: CloudPlaylistJson.DEFAULT_GROUP_NAME,
                    favoriteKeys = favoriteKeys,
                    dislikedKeys = dislikedKeys,
                )
            }

        // ② 「我喜欢的音乐」：本地喜欢的完整行（有元数据）+ 在线喜欢的 key（只有 key）。
        val favoritesPlaylist = playlists.firstOrNull { it.isFavorites }
        val favoriteSongs = buildList {
            favoritesPlaylist?.songs?.forEach { song ->
                add(CloudPlaylistJson.songOf(song, isFavorite = true, isDisliked = song.key in dislikedKeys))
            }
            val alreadyIncluded = favoritesPlaylist?.songs?.mapTo(mutableSetOf()) { it.key } ?: mutableSetOf()
            onlineFavoriteKeys.filterNot { it in alreadyIncluded }.forEach { key ->
                add(CloudPlaylistJson.songOfKey(key, isFavorite = true, isDisliked = key in dislikedKeys))
            }
        }

        // ③ 「不喜欢的音乐」：本地 + 在线的全部不喜欢（只有 key，故用 songOfKey）。
        val dislikedSongs = dislikedKeys.map { key ->
            CloudPlaylistJson.songOfKey(
                key = key,
                isFavorite = key in favoriteKeys || key in onlineFavoriteKeys,
                isDisliked = true,
            )
        }

        val allPlaylists = localDtos +
            CloudPlaylistJson.systemPlaylist(CloudPlaylistJson.SYSTEM_PLAYLIST_FAVORITE, favoriteSongs) +
            CloudPlaylistJson.systemPlaylist(CloudPlaylistJson.SYSTEM_PLAYLIST_DISLIKED, dislikedSongs)

        val payload = CloudPlaylistPayload(
            playlistsJson = CloudPlaylistJson.buildPlaylistsJson(allPlaylists),
            localPlaylistCount = localDtos.size,
            onlineFavoriteCount = OnlinePlaylistFavoriteStore.favorites.value.size,
            favoriteCount = favoriteSongs.size,
            dislikedCount = dislikedSongs.size,
        )
        AppLogger.i(
            TAG,
            "云同步载荷：本地歌单 ${payload.localPlaylistCount}、" +
                "喜欢 ${payload.favoriteCount}、不喜欢 ${payload.dislikedCount}、" +
                "在线收藏 ${payload.onlineFavoriteCount}、JSON ${payload.playlistsJson.length} 字节",
        )
        return payload
    }

    /**
     * 上传到云端。
     *
     * @param mode [CloudSyncClient.MODE_MERGE]（增量合并，默认）或 [CloudSyncClient.MODE_OVERWRITE]。
     */
    suspend fun upload(
        context: Context,
        mode: String = AppPreferences.CloudSync.uploadMode,
    ): CloudSyncResult<Int> = try {
        val payload = buildPayload(context)
        CloudSyncClient.backupPlaylists(
            context = context,
            playlistsJson = payload.playlistsJson,
            onlineFavoritesJson = CloudPlaylistJson.buildOnlineFavoritesJson(
                OnlinePlaylistFavoriteStore.favorites.value,
            ),
            groupsJson = CloudPlaylistJson.buildGroupsJson(
                PlaylistGroupStore.groups.value.map { it.name },
            ),
            mode = mode,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "组装上传载荷失败", error)
        CloudSyncResult.Failure(error.message.orEmpty().ifBlank { "上传失败" })
    }
}
