package com.rawsmusic.module.online.sync

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.ONLINE_SONG_KEY_PREFIX
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.onlineUserPlaylistSong
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist

// ============================================================================
// 云端 JSON 契约的 DTO 与序列化
//
// **字段名来自 `docs/cloud-sync-contract.md` §4，逐字照抄，不要改名、不要"优化"。**
// 这套 JSON 是 OrBit ↔ RawS ↔ Worker 三方共用的交换格式，任何字段名改动都会让
// 跨端数据对不上（铁律 3：客户端适配后端，不改后端）。
//
// 注意契约里的三处**不对称**，很容易猜错：
//   1. 请求字段是 camelCase（`playlistsJson` / `onlineFavoritesJson` / `groupsJson`），
//      而响应字段是 snake_case（`playlists_json` / `online_favorites_json` / `groups_json`）。
//   2. JSON **内容**里的字段又是 camelCase（`groupName` / `durationMs` / `isOnline` …）。
//   3. `sourcePlatform` / 在线收藏的 `platform` 要的是**大写枚举名**
//      （`NETEASE|QQ|KUGOU|KUWO|MIGU`），不是本项目内部用的平台 id（小写）。
//      Kotlin 枚举的 `name` 恰好就是这个大写形式，所以用 `platform.name`。
// ============================================================================

/** `playlists_json` 里的一首歌。 */
data class CloudSongDto(
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String,
    @SerializedName("durationMs") val durationMs: Long,
    /** 本地路径，或 `online://<platformId>/<songId>`。也是服务端合并时的去重键之一。 */
    @SerializedName("path") val path: String,
    @SerializedName("albumArtUri") val albumArtUri: String,
    @SerializedName("coverUrl") val coverUrl: String,
    @SerializedName("isOnline") val isOnline: Boolean,
    /** 大写平台枚举名；非在线曲为 `""`。 */
    @SerializedName("sourcePlatform") val sourcePlatform: String,
    @SerializedName("isFavorite") val isFavorite: Boolean,
    @SerializedName("isDisliked") val isDisliked: Boolean,
)

/** `playlists_json` 里的一个歌单。 */
data class CloudPlaylistDto(
    @SerializedName("name") val name: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("createdAt") val createdAt: Long,
    @SerializedName("songs") val songs: List<CloudSongDto>,
)

/** `online_favorites_json` 里的一项（收藏的在线歌单）。 */
data class CloudOnlineFavoriteDto(
    @SerializedName("id") val id: String,
    /** 大写平台枚举名。 */
    @SerializedName("platform") val platform: String,
    @SerializedName("title") val title: String,
    @SerializedName("coverUrl") val coverUrl: String,
    @SerializedName("trackCount") val trackCount: Int,
    @SerializedName("creatorName") val creatorName: String,
    @SerializedName("creatorAvatarUrl") val creatorAvatarUrl: String,
    @SerializedName("description") val description: String,
    @SerializedName("customGroup") val customGroup: String,
)

object CloudPlaylistJson {

    private val gson = Gson()

    // ---- 契约里的系统歌单与分组常量（**逐字照抄，不要本地化**）----

    /** 服务端/跨端约定的系统歌单名。喜欢在这里上传，识别的也是它。 */
    const val SYSTEM_PLAYLIST_FAVORITE = "我喜欢的音乐"
    const val SYSTEM_PLAYLIST_DISLIKED = "不喜欢的音乐"

    /** 系统歌单所属的分组名。服务端合并时**保留**它（只丢「默认」）。 */
    const val SYSTEM_GROUP_NAME = "系统"

    /** 服务端在 merge 时**永远丢弃**这个分组名，所以上传侧直接不带它。 */
    const val DEFAULT_GROUP_NAME = "默认"

    // ---- 平台名的线上形式 ----

    /**
     * 平台在本契约里的线上取值 = **大写枚举名**（`NETEASE` / `QQ` / ...）。
     *
     * Kotlin 枚举的 `name` 正好是这个形式，所以直接用；**不要**用 `OnlinePlatform.id`（小写）。
     */
    fun platformWireName(platform: OnlinePlatform): String = platform.name

    /** 反解线上平台名；认不出返回 null（宁可不写，也不要猜成别的平台）。 */
    fun platformFromWireName(value: String?): OnlinePlatform? {
        if (value.isNullOrBlank()) return null
        return OnlinePlatform.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: OnlinePlatform.fromId(value)
    }

    // ---- 构建 ----

    /** 把 [CloudPlaylistDto] 列表序列化成 `playlistsJson`（顶层是数组）。 */
    fun buildPlaylistsJson(playlists: List<CloudPlaylistDto>): String = gson.toJson(playlists)

    /**
     * 一个**本地普通歌单** → DTO。
     *
     * 喜欢/不喜欢状态由调用方传入（它们不在 `UserPlaylistSong` 上）：
     * 本地曲的喜欢来自 `PlaylistStore.favoriteSongKeys()`，不喜欢来自 `SongAttitudeRepository`。
     */
    fun localPlaylist(
        playlist: UserPlaylist,
        groupName: String,
        favoriteKeys: Set<String> = emptySet(),
        dislikedKeys: Set<String> = emptySet(),
    ): CloudPlaylistDto = CloudPlaylistDto(
        name = playlist.name,
        groupName = groupName.ifBlank { DEFAULT_GROUP_NAME },
        createdAt = playlist.createdAt,
        songs = playlist.songs.map { song ->
            songOf(
                song = song,
                isFavorite = song.key in favoriteKeys,
                isDisliked = song.key in dislikedKeys,
            )
        },
    )

    /**
     * **系统歌单**（喜欢 / 不喜欢）→ DTO。
     *
     * 契约把这层信息塞进 `playlists_json` 的两个特殊名字里，而不是独立的表或接口，
     * 所以名字与分组名必须是上面那两个常量。
     */
    fun systemPlaylist(
        name: String,
        songs: List<CloudSongDto>,
    ): CloudPlaylistDto = CloudPlaylistDto(
        name = name,
        groupName = SYSTEM_GROUP_NAME,
        createdAt = 0L,
        songs = songs,
    )

    /** 本地歌单里的一首歌 → DTO。 */
    fun songOf(
        song: UserPlaylistSong,
        isFavorite: Boolean,
        isDisliked: Boolean,
    ): CloudSongDto {
        val online = song.isOnlineEntry()
        return CloudSongDto(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.duration,
            path = song.path,
            // ⚠️ 这是**本地文件路径**，只在同设备还原时有用；跨设备还原时接收端会重新取封面。
            // 契约允许这里是空串，所以没有封面时填 ""。
            albumArtUri = song.albumArtPath,
            // RawS 的歌单行里没有 http 封面地址（见 P2-05b：不能把 URL 塞进 albumArtPath），
            // 因此恒为空串 —— 这是"没有该字段"而不是"忘了填"。
            coverUrl = "",
            isOnline = online,
            sourcePlatform = if (online) platformIdOfPath(song.path)?.let(::uppercaseOrEmpty) ?: "" else "",
            isFavorite = isFavorite,
            isDisliked = isDisliked,
        )
    }

    /**
     * **只有 key、没有元数据**的歌曲 → DTO（用于还原「不喜欢」）。
     *
     * 为什么可以只有 path：服务端合并的去重键是 `path || "${title}__${artist}"`，
     * 所以只要 `path` 正确，这条记录就**认得出是哪首歌**，元数据空了也不影响身份。
     * 在线曲的 key（`online://...`）本身也不会和本地路径撞。
     */
    fun songOfKey(key: String, isFavorite: Boolean, isDisliked: Boolean): CloudSongDto {
        val platformId = platformIdOfPath(key)
        return CloudSongDto(
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            path = key,
            albumArtUri = "",
            coverUrl = "",
            isOnline = platformId != null,
            sourcePlatform = platformId?.uppercase() ?: "",
            isFavorite = isFavorite,
            isDisliked = isDisliked,
        )
    }

    /** 在线歌单收藏 → `onlineFavoritesJson`。 */
    fun buildOnlineFavoritesJson(favorites: List<OnlinePlaylistFavoriteEntity>): String {
        val dtos = favorites.map { favorite ->
            CloudOnlineFavoriteDto(
                id = favorite.remoteId,
                // 收藏表里存的是小写平台 id，契约要大写枚举名。
                platform = OnlinePlatform.fromId(favorite.platform)?.name
                    ?: favorite.platform.uppercase(),
                title = favorite.title,
                coverUrl = favorite.coverUrl,
                trackCount = favorite.trackCount,
                creatorName = favorite.creatorName,
                creatorAvatarUrl = favorite.creatorAvatarUrl,
                description = favorite.description,
                // ⚠️ 字段名不对称：契约 JSON 里叫 `customGroup`，而本地实体里叫 `groupName`。
                // 这是 OrBit 侧的历史命名，不要「统一」——契约优先。
                customGroup = favorite.groupName.ifBlank { DEFAULT_GROUP_NAME },
            )
        }
        return gson.toJson(dtos)
    }

    /**
     * 分组名数组 → `groupsJson`。
     *
     * 两条处理：
     * - **丢掉「默认」**：服务端在 merge 时永远丢弃它；而 `overwrite` 模式不做合并过滤，
     *   带着它上传会把「默认」真的存进云端分组列表。所以上传侧统一不带。
     * - **总是带上「系统」**：喜欢/不喜欢两个系统歌单属于它，服务端会保留。
     */
    fun buildGroupsJson(groupNames: Collection<String>): String {
        val normalized = groupNames
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != DEFAULT_GROUP_NAME && it != SYSTEM_GROUP_NAME }
            .distinct()
        return gson.toJson(normalized + SYSTEM_GROUP_NAME)
    }

    // ---- 解析（还原方向） ----

    /** 契约里的两个系统歌单。识别靠**名字**（契约没有独立字段区分它们）。 */
    enum class SystemPlaylistKind { FAVORITE, DISLIKED }

    /**
     * 判断云端歌单是不是「喜欢 / 不喜欢」这两个系统歌单。
     *
     * 契约里它们只是名字特殊的普通歌单，**必须**认出来并改走 `song_attitude` / `favorites`，
     * 否则会在本地建出两个和伪歌单重名、语义又不同的真实歌单。
     *
     * 名字对得上就认，**不看 `groupName`** —— 名字是跨端锚点，而分组名在 `overwrite` 模式下
     * 可能被用户改成别的。
     */
    fun systemKindOf(playlist: CloudPlaylistDto): SystemPlaylistKind? = when (playlist.name) {
        SYSTEM_PLAYLIST_FAVORITE -> SystemPlaylistKind.FAVORITE
        SYSTEM_PLAYLIST_DISLIKED -> SystemPlaylistKind.DISLIKED
        else -> null
    }

    /** 解析 `playlistsJson`。空串或 `null` 返回空列表（云端可能还没写过这段）。 */
    fun parsePlaylists(json: String?): List<CloudPlaylistDto> {
        if (json.isNullOrBlank()) return emptyList()
        return gson.fromJson(json, Array<CloudPlaylistDto>::class.java)?.toList().orEmpty()
    }

    /** 解析 `onlineFavoritesJson`。 */
    fun parseOnlineFavorites(json: String?): List<CloudOnlineFavoriteDto> {
        if (json.isNullOrBlank()) return emptyList()
        return gson.fromJson(json, Array<CloudOnlineFavoriteDto>::class.java)?.toList().orEmpty()
    }

    /** 解析 `groupsJson`（就是一个字符串数组）。 */
    fun parseGroups(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty()
    }

    /**
     * 云端的一首**本地**歌 → [AudioFile]，交给 `PlaylistStore` 去转行。
     *
     * ⚠️ `id` 故意留 0：云端的歌曲 id 是**别的设备**上的 MediaStore id，搬到本机会指错歌。
     * 歌单行的去重与匹配都走 `key`（由 `path` 经 `playlistIdentityKey()` 算出），不依赖 id。
     */
    fun toAudioFile(song: CloudSongDto): AudioFile = AudioFile(
        id = 0L,
        path = song.path,
        title = song.title,
        artist = song.artist,
        album = song.album,
        duration = song.durationMs,
        albumArtPath = song.albumArtUri,
    )

    /**
     * 云端的一首**在线**歌 → 本地在线行；`path` 不是 `online://` 串时返回 null。
     *
     * 行的落库形态统一由 core 的 `onlineUserPlaylistSong` 定义（见 `P2-05b`）。
     */
    fun toOnlineSongRow(song: CloudSongDto): UserPlaylistSong? {
        val parts = onlinePartsOf(song.path) ?: return null
        return onlineUserPlaylistSong(
            platformId = parts.first,
            songId = parts.second,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
        )
    }

    /** 该歌曲是否应作为**在线行**落库（`path` 是 `online://` 串）。 */
    fun isOnlineSong(song: CloudSongDto): Boolean = onlinePartsOf(song.path) != null

    /**
     * 云端一首歌在**本地**的 `song_key`（用于 `song_attitude`）。
     *
     * ⚠️ 两类歌的 key 规则**不同**，很容易写错：
     * - **在线曲**：key 就是那个 `online://` 串本身
     * - **本地曲**：key 是 `playlistIdentityKey()`，形如 `path|<归一化路径>`（**带前缀**）
     *
     * 所以本地曲必须经过 `toUserPlaylistSong()` 让 core 去算 —— 直接拿 `path` 当 key 的话，
     * `song_attitude` 里会存一条永远不会被匹配到的记录（而且看不出错）。
     */
    fun localSongKeyOf(song: CloudSongDto): String =
        if (isOnlineSong(song)) song.path else toAudioFile(song).toUserPlaylistSong().key

    /** 云端在线收藏 → [OnlinePlaylist]（供 `OnlinePlaylistFavoriteStore.add` 使用）。 */
    fun toOnlinePlaylist(dto: CloudOnlineFavoriteDto): OnlinePlaylist? {
        val platform = platformFromWireName(dto.platform) ?: return null
        return OnlinePlaylist(
            id = dto.id,
            platform = platform,
            title = dto.title,
            coverUrl = dto.coverUrl,
            playCount = 0L,
            trackCount = dto.trackCount,
            creatorName = dto.creatorName,
            creatorAvatarUrl = dto.creatorAvatarUrl,
            description = dto.description,
        )
    }

    /** 从 `online://<platformId>/<songId>` 取两段；不是在线串返回 null。 */
    fun onlinePartsOf(path: String): Pair<String, String>? {
        if (!path.startsWith(ONLINE_SONG_KEY_PREFIX)) return null
        val rest = path.removePrefix(ONLINE_SONG_KEY_PREFIX)
        // 按第一个斜杠切：songId 本身可能含 `/`。
        val platformId = rest.substringBefore('/', "")
        val songId = rest.substringAfter('/', "")
        if (platformId.isBlank() || songId.isBlank()) return null
        return platformId to songId
    }

    // ---- 私有工具 ----

    /** 从 `online://<platformId>/<songId>` 里取平台 id（小写）；不是在线 key 返回 null。 */
    private fun platformIdOfPath(path: String): String? {
        if (!path.startsWith("online://")) return null
        val rest = path.removePrefix("online://")
        return rest.substringBefore('/', "").ifBlank { null }
    }

    private fun uppercaseOrEmpty(value: String): String = value.uppercase()
}
