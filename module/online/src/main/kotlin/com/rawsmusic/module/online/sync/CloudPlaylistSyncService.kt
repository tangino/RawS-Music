package com.rawsmusic.module.online.sync

import android.content.Context
import com.rawsmusic.core.common.model.FAVORITES_PLAYLIST_ID
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistGroupOpResult
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

/** 还原结果，用于给用户「还原了多少」的明确反馈。 */
data class CloudRestoreReport(
    val playlistsWritten: Int,
    val groupsCreated: Int,
    val favoritesApplied: Int,
    val dislikedApplied: Int,
    val onlineFavoritesApplied: Int,
    val updatedAt: Long,
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
    suspend fun buildPayload(
        context: Context,
        selectedLocalIds: Set<String>? = null,
        selectedOnlineKeys: Set<String>? = null,
        includeFavorites: Boolean = true,
        includeDisliked: Boolean = true,
    ): CloudPlaylistPayload {
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
            .filter { selectedLocalIds == null || it.id in selectedLocalIds }
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
        val favoriteSongs = if (!includeFavorites) emptyList() else buildList {
            favoritesPlaylist?.songs?.forEach { song ->
                add(CloudPlaylistJson.songOf(song, isFavorite = true, isDisliked = song.key in dislikedKeys))
            }
            val alreadyIncluded = favoritesPlaylist?.songs?.mapTo(mutableSetOf()) { it.key } ?: mutableSetOf()
            onlineFavoriteKeys.filterNot { it in alreadyIncluded }.forEach { key ->
                add(CloudPlaylistJson.songOfKey(key, isFavorite = true, isDisliked = key in dislikedKeys))
            }
        }

        // ③ 「不喜欢的音乐」：本地 + 在线的全部不喜欢（只有 key，故用 songOfKey）。
        val dislikedSongs = if (!includeDisliked) emptyList() else dislikedKeys.map { key ->
            CloudPlaylistJson.songOfKey(
                key = key,
                isFavorite = key in favoriteKeys || key in onlineFavoriteKeys,
                isDisliked = true,
            )
        }

        val allPlaylists = mutableListOf<CloudPlaylistDto>().apply {
            addAll(localDtos)
            if (includeFavorites) {
                add(CloudPlaylistJson.systemPlaylist(CloudPlaylistJson.SYSTEM_PLAYLIST_FAVORITE, favoriteSongs))
            }
            if (includeDisliked) {
                add(CloudPlaylistJson.systemPlaylist(CloudPlaylistJson.SYSTEM_PLAYLIST_DISLIKED, dislikedSongs))
            }
        }

        val onlineFavoritesToUpload = if (selectedOnlineKeys == null) {
            OnlinePlaylistFavoriteStore.favorites.value
        } else {
            OnlinePlaylistFavoriteStore.favorites.value.filter {
                it.playlistKey in selectedOnlineKeys || "${it.platform}_${it.remoteId}" in selectedOnlineKeys
            }
        }

        val payload = CloudPlaylistPayload(
            playlistsJson = CloudPlaylistJson.buildPlaylistsJson(allPlaylists),
            localPlaylistCount = localDtos.size,
            onlineFavoriteCount = onlineFavoritesToUpload.size,
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
     * 从云端下载并**落回本地**。
     *
     * ## 合并语义在服务端，客户端只负责「把云端结果落回本地」
     *
     * `mode=merge` 的合并是**服务端**做的，restore 拿到的已经是合并后的结果。
     * 所以客户端这边只需要幂等地写入：普通歌单按**同名**合并（`importResolvedPlaylist` 已保证
     * 按 key 去重），重复还原不会产生重复行。
     *
     * ## 三个必须处理的契约细节
     *
     * 1. **「我喜欢的音乐」/「不喜欢的音乐」不能当成普通歌单建** —— 它们是契约用来承载
     *    like/dislike 的载体（契约里没有独立表或接口）。认名字跳过，改走 `song_attitude`
     *    与既有 `favorites` 系统歌单。否则会建出两个与伪歌单重名、语义又不同的真歌单。
     * 2. **态度从「所有歌单里的所有歌」的 `isFavorite`/`isDisliked` 标记读**，而不是只读那两个
     *    系统歌单 —— 同一首歌可能出现在普通歌单里也带标记，全都认才幂等。
     * 3. **`groups_json` 里的「系统」不建成分组** —— 它是契约内部标记，RawS 把系统歌单建模成
     *    伪卡而不是分组行；建出来只会得到一个永远空的分组。
     *
     * ⚠️ 本地曲的 `song_key` 必须让 core 去算（`path|…` 前缀），不能拿 `path` 顶替 ——
     * 见 [CloudPlaylistJson.localSongKeyOf]。
     */
    /**
     * 从云端拉取备份数据（仅获取，不落盘），供 UI 预览和细粒度选择。
     */
    suspend fun fetchCloudPlaylists(context: Context): CloudSyncResult<CloudPlaylistBackup> =
        CloudSyncClient.restorePlaylists(context)

    /**
     * 从云端下载并**落回本地**。
     *
     * @param selectedLocalNames 选中的本地歌单名称集合；null 表示还原全部。
     * @param selectedOnlineFavoriteIds 选中的在线收藏歌单 ID 集合；null 表示还原全部。
     * @param restoreFavorites 是否还原「我喜欢的音乐」。
     * @param restoreDisliked 是否还原「不喜欢的音乐」。
     * @param backup 已预拉取的云端备份；若为 null 则自动请求云端拉取。
     */
    suspend fun restore(
        context: Context,
        selectedLocalNames: Set<String>? = null,
        selectedOnlineFavoriteIds: Set<String>? = null,
        restoreFavorites: Boolean = true,
        restoreDisliked: Boolean = true,
        backup: CloudPlaylistBackup? = null,
    ): CloudSyncResult<CloudRestoreReport> = try {
        val downloadedBackup = backup ?: when (val downloaded = CloudSyncClient.restorePlaylists(context)) {
            is CloudSyncResult.Failure -> return downloaded
            is CloudSyncResult.Success -> downloaded.data
        }
        CloudSyncResult.Success(
            applyBackup(
                context = context,
                backup = downloadedBackup,
                selectedLocalNames = selectedLocalNames,
                selectedOnlineFavoriteIds = selectedOnlineFavoriteIds,
                restoreFavorites = restoreFavorites,
                restoreDisliked = restoreDisliked,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "还原云端歌单失败", error)
        CloudSyncResult.Failure(error.message.orEmpty().ifBlank { "还原失败" })
    }

    private suspend fun applyBackup(
        context: Context,
        backup: CloudPlaylistBackup,
        selectedLocalNames: Set<String>? = null,
        selectedOnlineFavoriteIds: Set<String>? = null,
        restoreFavorites: Boolean = true,
        restoreDisliked: Boolean = true,
    ): CloudRestoreReport {
        val store = PlaylistStore.getInstance(context)
        store.awaitBackupReady()
        PlaylistGroupStore.refresh(context)
        SongAttitudeRepository.refresh(context)
        OnlinePlaylistFavoriteStore.refresh(context)

        val downloaded = CloudPlaylistJson.parsePlaylists(backup.playlistsJson)

        // ① 先建分组：后面「把歌单移到分组」才有落点。
        val wantedGroups = CloudPlaylistJson.parseGroups(backup.groupsJson)
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    it != CloudPlaylistJson.SYSTEM_GROUP_NAME &&
                    it != CloudPlaylistJson.DEFAULT_GROUP_NAME
            }
            .distinct()
        var groupsCreated = 0
        wantedGroups.forEach { name ->
            if (PlaylistGroupStore.create(context, name) == PlaylistGroupOpResult.Success) {
                groupsCreated++
            }
        }

        // ② 普通歌单（跳过两个系统歌单，并按用户选定名称筛选）。
        var playlistsWritten = 0
        downloaded
            .filter { CloudPlaylistJson.systemKindOf(it) == null && it.name.isNotBlank() }
            .filter { selectedLocalNames == null || it.name in selectedLocalNames }
            .forEach { dto ->
                val onlineRows = dto.songs.mapNotNull(CloudPlaylistJson::toOnlineSongRow)
                val localSongs = dto.songs
                    .filterNot(CloudPlaylistJson::isOnlineSong)
                    .filter { it.path.isNotBlank() }
                    .map(CloudPlaylistJson::toAudioFile)
                val imported = store.importResolvedPlaylist(
                    name = dto.name,
                    localSongs = localSongs,
                    onlineSongs = onlineRows,
                )
                if (imported != null) {
                    playlistsWritten++
                    val group = dto.groupName.trim()
                    if (group.isNotEmpty() &&
                        group != CloudPlaylistJson.DEFAULT_GROUP_NAME &&
                        group != CloudPlaylistJson.SYSTEM_GROUP_NAME
                    ) {
                        PlaylistGroupStore.movePlaylist(context, imported.id, group)
                    }
                }
            }

        // ③ 态度：从所有歌单里的所有歌读标记，保证幂等。
        val allSongs = downloaded.flatMap { it.songs }
            .distinctBy { CloudPlaylistJson.localSongKeyOf(it) }

        var favoritesAppliedCount = 0
        var dislikedAppliedCount = 0

        if (restoreFavorites) {
            val favoriteLocal = allSongs
                .filter { it.isFavorite && !CloudPlaylistJson.isOnlineSong(it) && it.path.isNotBlank() }
                .map(CloudPlaylistJson::toAudioFile)
            if (favoriteLocal.isNotEmpty()) {
                store.addSongsToPlaylist(FAVORITES_PLAYLIST_ID, favoriteLocal)
            }
            val favoriteOnlineKeys = allSongs
                .filter { it.isFavorite && CloudPlaylistJson.isOnlineSong(it) }
                .map(CloudPlaylistJson::localSongKeyOf)
            if (favoriteOnlineKeys.isNotEmpty()) {
                SongAttitudeRepository.setOnlineFavorites(context, favoriteOnlineKeys, true)
            }
            favoritesAppliedCount = favoriteLocal.size + favoriteOnlineKeys.size
        }

        if (restoreDisliked) {
            val dislikedKeys = allSongs
                .filter { it.isDisliked }
                .map(CloudPlaylistJson::localSongKeyOf)
                .filter { it.isNotBlank() }
            dislikedKeys.forEach { key ->
                SongAttitudeRepository.setDisliked(context, key, true)
            }
            dislikedAppliedCount = dislikedKeys.size
        }

        // ④ 在线歌单收藏。
        var onlineFavoritesApplied = 0
        CloudPlaylistJson.parseOnlineFavorites(backup.onlineFavoritesJson).forEach { dto ->
            if (selectedOnlineFavoriteIds != null &&
                dto.id !in selectedOnlineFavoriteIds &&
                "${dto.platform}_${dto.id}" !in selectedOnlineFavoriteIds
            ) {
                return@forEach
            }
            val playlist = CloudPlaylistJson.toOnlinePlaylist(dto) ?: return@forEach
            val group = dto.customGroup.trim()
                .ifEmpty { CloudPlaylistJson.DEFAULT_GROUP_NAME }
            OnlinePlaylistFavoriteStore.add(context, playlist, group)
            onlineFavoritesApplied++
        }

        val report = CloudRestoreReport(
            playlistsWritten = playlistsWritten,
            groupsCreated = groupsCreated,
            favoritesApplied = favoritesAppliedCount,
            dislikedApplied = dislikedAppliedCount,
            onlineFavoritesApplied = onlineFavoritesApplied,
            updatedAt = backup.updatedAt,
        )
        AppLogger.i(
            TAG,
            "云端还原完成：歌单 ${report.playlistsWritten}、新建分组 ${report.groupsCreated}、" +
                "喜欢 ${report.favoritesApplied}、不喜欢 ${report.dislikedApplied}、" +
                "在线收藏 ${report.onlineFavoritesApplied}",
        )
        return report
    }

    /**
     * 上传到云端。
     *
     * @param mode [CloudSyncClient.MODE_MERGE]（增量合并，默认）或 [CloudSyncClient.MODE_OVERWRITE]。
     * @param selectedLocalIds 选中的本地歌单 ID 集合；为 null 时上传全部本地歌单。
     * @param selectedOnlineKeys 选中的在线收藏唯一键集合；为 null 时上传全部在线收藏。
     * @param includeFavorites 是否包含我喜欢的音乐。
     * @param includeDisliked 是否包含不喜欢的音乐。
     */
    suspend fun upload(
        context: Context,
        mode: String = AppPreferences.CloudSync.uploadMode,
        selectedLocalIds: Set<String>? = null,
        selectedOnlineKeys: Set<String>? = null,
        includeFavorites: Boolean = true,
        includeDisliked: Boolean = true,
    ): CloudSyncResult<Int> = try {
        val payload = buildPayload(
            context = context,
            selectedLocalIds = selectedLocalIds,
            selectedOnlineKeys = selectedOnlineKeys,
            includeFavorites = includeFavorites,
            includeDisliked = includeDisliked,
        )
        val onlineFavorites = OnlinePlaylistFavoriteStore.favorites.value.filter { fav ->
            selectedOnlineKeys == null ||
                fav.playlistKey in selectedOnlineKeys ||
                "${fav.platform}_${fav.remoteId}" in selectedOnlineKeys
        }
        CloudSyncClient.backupPlaylists(
            context = context,
            playlistsJson = payload.playlistsJson,
            onlineFavoritesJson = CloudPlaylistJson.buildOnlineFavoritesJson(onlineFavorites),
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

    /**
     * 删除云端选中的歌单。
     */
    suspend fun deleteSelectedCloudPlaylists(
        context: Context,
        localPlaylistNames: List<String>,
        onlinePlaylistIds: List<String>,
    ): CloudSyncResult<Int> =
        CloudSyncClient.deletePlaylists(
            context = context,
            deleteAll = false,
            localPlaylistNames = localPlaylistNames,
            onlinePlaylistIds = onlinePlaylistIds,
        )
}
