package com.rawsmusic.module.online.playback

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.online.model.OnlineAlbum
import com.rawsmusic.module.online.model.OnlineArtist
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem

/**
 * 在线平台模型 ↔ RawS 音源协议模型的映射。
 *
 * 目的：广场歌曲只有平台 catalog 的**元数据**、没有播放地址。要让它进入 RawS 既有的
 * 播放链路（`MusicSourceAudioResolver` / `MusicSourcePlaybackController`），
 * 必须先换成 RawS 认识的 [RawSourceMediaItem]。
 *
 * ⚠️ **[toRawSourceMediaItem] 产出的是「身份」而不是「可解析的源」**：
 * 它的 `sourceId` 是平台 id（`netease`/`qq`/…），而 RawS 的解析器是按
 * 「用户已安装的 LX / MusicFree 音源 id」去查表的，所以这种 item **不能直接丢给解析器**。
 * 解析要走 [OnlinePlaybackBridge.selectBestCandidate] 先从用户音源里挑出候选，
 * 再交给既有的 `MusicSourceAudioResolver`。不要绕过这一步。
 */
object OnlineSourceMediaMapper {

    /** 在线曲目在 `user_playlist_songs.format` 里的标记；便于排查与导出时一眼认出在线曲。 */
    const val ONLINE_ENTRY_FORMAT = "online"

    /**
     * 在线歌曲的稳定标识，用于 `song_attitude.song_key` 与歌单行，**必须与 OrBit 端逐字一致**才能跨端互认。
     *
     * 格式的**唯一定义**在 `core:common` 的 `onlineSongKey`（`UserPlaylist.kt`）。这里委托过去而不是
     * 自己拼字符串 —— `:module:data` 的 `PlaylistStore` / `SongAttitudeRepository` 不能依赖本模块
     * （依赖方向是 online → data），两边各拼一份迟早会漂移，导致同一首在线曲在歌单表与态度表里
     * 算出两个 key。**不要**把这里改回字面量。
     */
    fun onlineSongKey(platform: OnlinePlatform, songId: String): String =
        com.rawsmusic.core.common.model.onlineSongKey(platform.id, songId)

    fun onlineSongKey(item: OnlineSongItem): String = onlineSongKey(item.platform, item.id)

    /**
     * 从 `online://<platformId>/<songId>` 串里解出平台与远端 id。
     *
     * 用 `substringAfter` 而不是 `split`：songId 本身可能含 `/`（少数平台的 id 带路径段），
     * 只按**第一个**斜杠切分才不会把它截断。
     *
     * @return 解析失败（前缀不对 / 平台不认识 / songId 为空）返回 null。
     */
    fun parseOnlineSongKey(key: String): Pair<OnlinePlatform, String>? {
        if (!com.rawsmusic.core.common.model.isOnlineSongKey(key)) return null
        val rest = key.removePrefix(com.rawsmusic.core.common.model.ONLINE_SONG_KEY_PREFIX)
        val platformId = rest.substringBefore('/', "")
        val songId = rest.substringAfter('/', "")
        if (songId.isBlank()) return null
        val platform = OnlinePlatform.fromId(platformId) ?: return null
        return platform to songId
    }

    /**
     * 反解：把本地歌单里存下的在线条目（或播放器手里的 `AudioFile`）还原成 [OnlineSongItem]，用于起播。
     *
     * 为什么需要它：在线曲目在歌单里只存了元数据快照 + `online://` 标识，**没有可播放地址**；
     * 真要播的时候必须回到 `OnlineSongItem` 形态，交给 `OnlinePlaybackLauncher` 去音源里解析。
     *
     * 注意：`AudioFile.path` 对在线条目就是那个 `online://` 串（见 [toUserPlaylistSong]），
     * 所以这里用 `path` 反解；`coverUrl` 无处可存（`albumArtPath` 是本地路径字段，不能塞 http URL），
     * 因此还原出来的 `coverUrl` 为 null —— 播放不依赖它。
     */
    fun toOnlineSongItem(song: AudioFile): OnlineSongItem? {
        val (platform, songId) = parseOnlineSongKey(song.path) ?: return null
        return OnlineSongItem(
            id = songId,
            platform = platform,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = null,
            durationMs = song.duration,
            coverUrl = null,
        )
    }

    /**
     * 在线歌曲 → 本地歌单行（P2-05b 约定的落库形态）。
     *
     * `key` 与 `path` 用**同一个** `online://` 串：OrBit 的 `isOnlineSong` 就是靠
     * `path.startsWith("online://")` 判断的，沿用这条约定能让「是否在线曲」跟着行走，
     * 也让从 OrBit 导出的歌单/备份能被识别。
     *
     * `id` 用负数虚拟 id（见 `generateVirtualSongId`），**不可能与本地曲的 MediaStore id 冲突**。
     * `albumArtPath` 故意留空：在线封面是 http URL，塞进这个字段会被下游当作本地文件路径使用而出错。
     */
    fun toUserPlaylistSong(item: OnlineSongItem): UserPlaylistSong = UserPlaylistSong(
        key = onlineSongKey(item),
        id = com.rawsmusic.core.common.model.generateVirtualSongId(item.platform.id, item.id),
        title = item.title,
        artist = item.artist,
        album = item.album,
        albumId = 0L,
        duration = item.durationMs,
        path = onlineSongKey(item),
        fileSize = 0L,
        format = ONLINE_ENTRY_FORMAT,
        sampleRate = 0,
        bitRate = 0,
        bitsPerSample = 0,
        albumArtPath = "",
        addedAt = System.currentTimeMillis(),
    )

    /** 在线歌单的稳定标识，取值约定 `<platformId>_<remoteId>`（与 `online_playlist_favorites.playlist_key` 一致）。 */
    fun onlinePlaylistKey(platform: OnlinePlatform, playlistId: String): String = "${platform.id}_$playlistId"

    fun onlinePlaylistKey(playlist: OnlinePlaylist): String = onlinePlaylistKey(playlist.platform, playlist.id)

    /**
     * 在线歌曲 → RawS 媒体项（**身份用途**，见类注释）。
     *
     * 歌手串按 OrBit 的习惯是多歌手用 `/` 分隔，这里用同一套拆分逻辑保证两边一致。
     */
    fun toRawSourceMediaItem(item: OnlineSongItem): RawSourceMediaItem = RawSourceMediaItem(
        sourceId = item.platform.id,
        remoteId = item.id,
        mediaType = RawSourceMediaType.Music,
        title = item.title,
        artists = splitArtistNames(item.artist),
        album = item.album,
        durationMs = item.durationMs,
        artworkUrl = item.coverUrl.orEmpty(),
        availableQualities = availableQualities(item.platform),
        // 原样保留平台与专辑 id，解析/详情回查时需要。
        sourcePayload = buildString {
            append("{\"platform\":\"").append(item.platform.id).append('"')
            item.albumId?.takeIf { it.isNotBlank() }?.let { append(",\"albumId\":\"").append(it).append('"') }
            append('}')
        },
    )

    fun toRawSourceMediaItem(playlist: OnlinePlaylist): RawSourceMediaItem = RawSourceMediaItem(
        sourceId = playlist.platform.id,
        remoteId = playlist.id,
        mediaType = RawSourceMediaType.Playlist,
        title = playlist.title,
        artworkUrl = playlist.coverUrl,
    )

    fun toRawSourceMediaItem(artist: OnlineArtist): RawSourceMediaItem = RawSourceMediaItem(
        sourceId = artist.platform.id,
        remoteId = artist.id,
        mediaType = RawSourceMediaType.Artist,
        title = artist.name,
        artworkUrl = artist.avatarUrl,
    )

    fun toRawSourceMediaItem(album: OnlineAlbum): RawSourceMediaItem = RawSourceMediaItem(
        sourceId = album.platform.id,
        remoteId = album.id,
        mediaType = RawSourceMediaType.Album,
        title = album.title,
        artists = splitArtistNames(album.artist),
        artworkUrl = album.coverUrl,
    )

    /** 多歌手拆分：与 [OnlinePlaybackMatcher.splitArtists] 同源，但保留可读原文（不归一化）。 */
    fun splitArtistNames(artistStr: String): List<String> {
        val cleaned = OnlinePlaybackMatcher.cleanText(artistStr)
            .replace("（", "(")
            .replace("）", ")")
        val withoutBracket = cleaned.replace(Regex("\\([^)]*\\)"), "").trim()
        val base = if (withoutBracket.isNotBlank()) withoutBracket else cleaned
        return base.split('/', '\\', ',', '，', '、', '&', '|', '+')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * 平台宣称可用的音质集合。
     *
     * ⚠️ 这是**声明**而非保证 —— 平台接口倒推不出稳定的可用音质，所以这里给的是
     * 「允许尝试的范围」，真正的可用性由解析 + [OnlinePlaybackProbe] 探活决定。
     * 取值范围与 OrBit 的音质阶梯对齐（`flac24bit` / `flac` / `320k` / `128k`）。
     */
    fun availableQualities(@Suppress("UNUSED_PARAMETER") platform: OnlinePlatform): Set<RawSourceQuality> = setOf(
        RawSourceQuality.Standard,
        RawSourceQuality.High,
        RawSourceQuality.Lossless,
        RawSourceQuality.HiRes,
    )

    /** OrBit 音质字符串 → RawS 音质枚举。`RawSourceQuality.fromKey` 已覆盖 320k/flac/flac24bit。 */
    fun qualityFromOrbitKey(key: String?): RawSourceQuality = RawSourceQuality.fromKey(key)
}
