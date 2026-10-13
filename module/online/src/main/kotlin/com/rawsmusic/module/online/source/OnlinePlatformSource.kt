package com.rawsmusic.module.online.source

import com.rawsmusic.module.online.model.OnlineAlbum
import com.rawsmusic.module.online.model.OnlineArtist
import com.rawsmusic.module.online.model.OnlineArtistCategory
import com.rawsmusic.module.online.model.OnlineArtistDetail
import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.model.OnlineSongItem

/**
 * 统一在线平台适配接口（移植自 OrBitPlayer 的 `IOnlineMusicSource`）。
 *
 * 五个平台的开放能力并不对等：酷我/咪咕/酷狗只实现了一部分，
 * 因此**歌手与专辑相关方法保留默认空实现**，由各平台按需覆盖。
 * 这样新增平台时不必被迫实现全部能力。
 */
interface OnlinePlatformSource {

    val platform: OnlinePlatform

    /** 获取歌单分类标签。 */
    suspend fun getTags(): List<OnlinePlaylistTag>

    /**
     * 按分类标签分页获取热门歌单。
     * @param tagId 标签 ID（"全部" 表示不带分类筛选）
     * @param page 页码，从 1 开始
     */
    suspend fun getPlaylists(tagId: String = "全部", page: Int = 1, pageSize: Int = 30): List<OnlinePlaylist>

    /** 获取官方排行榜列表。 */
    suspend fun getLeaderboards(): List<OnlineLeaderboard>

    /** 获取歌单完整详情及歌曲列表。 */
    suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>>

    /** 搜索歌单。 */
    suspend fun searchPlaylists(keyword: String, page: Int = 1, pageSize: Int = 20): List<OnlinePlaylist>

    /** 搜索单曲（歌曲名、歌手名或两者组合）。 */
    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 30): List<OnlineSongItem> = emptyList()

    /** 从分享链接或纯文本中提取歌单 ID；识别不了返回 null。 */
    fun extractPlaylistId(urlOrText: String): String?

    /** 获取歌手分类列表。 */
    suspend fun getArtistCategories(): List<OnlineArtistCategory> = emptyList()

    /** 分页获取热门/分类歌手列表。 */
    suspend fun getArtists(category: String = "全部", page: Int = 1, pageSize: Int = 30): List<OnlineArtist> = emptyList()

    /** 搜索歌手。 */
    suspend fun searchArtists(keyword: String, page: Int = 1, pageSize: Int = 20): List<OnlineArtist> = emptyList()

    /** 获取歌手详情（含热门歌曲与专辑）。 */
    suspend fun getArtistDetail(artistId: String): OnlineArtistDetail = OnlineArtistDetail(
        artist = OnlineArtist(id = artistId, platform = platform, name = "", avatarUrl = ""),
    )

    /** 获取歌手的歌曲列表。 */
    suspend fun getArtistSongs(artistId: String, page: Int = 1, pageSize: Int = 50): List<OnlineSongItem> = emptyList()

    /** 获取歌手的专辑列表。 */
    suspend fun getArtistAlbums(artistId: String, page: Int = 1, pageSize: Int = 30): List<OnlineAlbum> = emptyList()

    /** 获取专辑详情及专辑内的所有歌曲。 */
    suspend fun getAlbumDetail(albumId: String): Pair<OnlineAlbum, List<OnlineSongItem>> = Pair(
        OnlineAlbum(id = albumId, platform = platform, title = "", coverUrl = "", artist = ""),
        emptyList(),
    )
}
