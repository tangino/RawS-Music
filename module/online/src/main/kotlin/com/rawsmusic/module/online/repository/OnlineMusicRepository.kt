package com.rawsmusic.module.online.repository

import android.util.LruCache
import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.source.OnlinePlatformSource
import com.rawsmusic.module.online.source.netease.NeteaseMusicSource
import com.rawsmusic.module.online.source.qq.QQMusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在线公共歌单数据仓库（单例）
 * 负责音源管理、多平台路由分发、内存 LRU 缓存与防抖
 */
class OnlineMusicRepository private constructor() {

    companion object {
        @Volatile
        private var instance: OnlineMusicRepository? = null

        fun getInstance(): OnlineMusicRepository {
            return instance ?: synchronized(this) {
                instance ?: OnlineMusicRepository().also { instance = it }
            }
        }
    }

    private val neteaseSource = NeteaseMusicSource()
    private val qqSource = QQMusicSource()
    private val kugouSource = com.rawsmusic.module.online.source.kugou.KugouMusicSource()
    private val kuwoSource = com.rawsmusic.module.online.source.kuwo.KuwoMusicSource()
    private val miguSource = com.rawsmusic.module.online.source.migu.MiguMusicSource()

    // 平台实例映射表
    private val sources = mapOf<OnlinePlatform, OnlinePlatformSource>(
        OnlinePlatform.NETEASE to neteaseSource,
        OnlinePlatform.QQ to qqSource,
        OnlinePlatform.KUGOU to kugouSource,
        OnlinePlatform.KUWO to kuwoSource,
        OnlinePlatform.MIGU to miguSource
    )

    /**
     * 绑定平台账号提供者，使音源网络请求自动携带登录 Cookie。
     *
     * 这里刻意不直接依赖 PlatformAccountManager：它的凭据存储要按决策 D7 改成 MMKV + Keystore
     * 加密（见 P2-08），尚未移植。改用函数注入让仓库层与账号存储层解耦，P2-08 完成后直接接上即可。
     */
    fun bindAuthProviders(
        cookieProvider: (OnlinePlatform) -> String,
        accountProvider: (OnlinePlatform) -> com.rawsmusic.module.online.auth.model.PlatformAccount?,
    ) {
        qqSource.setCookieProvider { cookieProvider(OnlinePlatform.QQ) }
        kugouSource.setAccountProvider { accountProvider(OnlinePlatform.KUGOU) }
        kugouSource.setCookieProvider { cookieProvider(OnlinePlatform.KUGOU) }
    }

    // 当前选中的平台
    private val _currentPlatform = MutableStateFlow(OnlinePlatform.NETEASE)
    val currentPlatform: StateFlow<OnlinePlatform> = _currentPlatform.asStateFlow()

    // 内存 LRU 缓存：歌单详情 (key: "platform_playlistId")
    private val detailCache = LruCache<String, Pair<OnlinePlaylist, List<OnlineSongItem>>>(50)
    // 内存缓存：分类标签 (key: platform)
    private val tagsCache = mutableMapOf<OnlinePlatform, List<OnlinePlaylistTag>>()
    // 内存缓存：排行榜 (key: platform)
    private val leaderboardCache = mutableMapOf<OnlinePlatform, List<OnlineLeaderboard>>()

    fun switchPlatform(platform: OnlinePlatform) {
        _currentPlatform.value = platform
    }

    private fun getSource(platform: OnlinePlatform = _currentPlatform.value): OnlinePlatformSource {
        return sources[platform] ?: neteaseSource
    }

    /**
     * 获取指定平台的分类标签
     */
    suspend fun getTags(platform: OnlinePlatform = _currentPlatform.value): Result<List<OnlinePlaylistTag>> {
        return runCatching {
            tagsCache[platform]?.let { return@runCatching it }
            val tags = getSource(platform).getTags()
            tagsCache[platform] = tags
            tags
        }
    }

    /**
     * 获取指定平台的歌单列表
     */
    suspend fun getPlaylists(
        tagId: String = "全部",
        page: Int = 1,
        pageSize: Int = 30,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<OnlinePlaylist>> {
        return runCatching {
            getSource(platform).getPlaylists(tagId, page, pageSize)
        }
    }

    /**
     * 获取官方排行榜
     */
    suspend fun getLeaderboards(platform: OnlinePlatform = _currentPlatform.value): Result<List<OnlineLeaderboard>> {
        return runCatching {
            leaderboardCache[platform]?.let { return@runCatching it }
            val list = getSource(platform).getLeaderboards()
            leaderboardCache[platform] = list
            list
        }
    }

    /**
     * 获取歌单详情（优先读缓存）
     */
    suspend fun getPlaylistDetail(
        playlistId: String,
        platform: OnlinePlatform = _currentPlatform.value,
        forceRefresh: Boolean = false
    ): Result<Pair<OnlinePlaylist, List<OnlineSongItem>>> {
        val cacheKey = "${platform.id}_$playlistId"
        if (!forceRefresh) {
            detailCache.get(cacheKey)?.let { cached ->
                if (cached.second.isNotEmpty()) {
                    return Result.success(cached)
                }
            }
        }
        return runCatching {
            val detail = getSource(platform).getPlaylistDetail(playlistId)
            if (detail.second.isNotEmpty()) {
                detailCache.put(cacheKey, detail)
            }
            detail
        }
    }

    /**
     * 搜索歌单
     */
    suspend fun searchPlaylists(
        keyword: String,
        page: Int = 1,
        pageSize: Int = 20,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<OnlinePlaylist>> {
        return runCatching {
            getSource(platform).searchPlaylists(keyword, page, pageSize)
        }
    }

    /**
     * 搜索指定平台的单曲歌曲
     */
    suspend fun searchSongs(
        keyword: String,
        page: Int = 1,
        pageSize: Int = 30,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<OnlineSongItem>> {
        return runCatching {
            getSource(platform).searchSongs(keyword, page, pageSize)
        }
    }

    /**
     * 并发搜索全部网络平台的单曲歌曲（按平台映射返回结果）
     */
    suspend fun searchSongsAllPlatforms(
        keyword: String,
        page: Int = 1,
        pageSize: Int = 20
    ): Map<OnlinePlatform, Result<List<OnlineSongItem>>> = coroutineScope {
        if (keyword.isBlank()) return@coroutineScope emptyMap()
        val allPlatforms = OnlinePlatform.values()
        val deferredList = allPlatforms.map { plat ->
            plat to async(Dispatchers.IO) {
                runCatching {
                    getSource(plat).searchSongs(keyword, page, pageSize)
                }
            }
        }
        deferredList.associate { pair ->
            pair.first to pair.second.await()
        }
    }

    /**
     * 全网歌曲聚合搜索（支持歌名、歌手/用户名，或两者组合搜索）
     * 自动交叉交织（Round-robin Interleaving）各平台返回的匹配歌曲，使全网多平台歌曲均匀呈现
     */
    suspend fun searchSongsAggregated(
        title: String = "",
        artist: String = "",
        page: Int = 1,
        pageSize: Int = 20
    ): List<OnlineSongItem> = coroutineScope {
        val t = title.trim()
        val a = artist.trim()
        val query = when {
            t.isNotBlank() && a.isNotBlank() -> "$t $a"
            t.isNotBlank() -> t
            a.isNotBlank() -> a
            else -> ""
        }
        if (query.isBlank()) return@coroutineScope emptyList()

        val resultsMap = searchSongsAllPlatforms(query, page, pageSize)
        val platformLists = resultsMap.values.mapNotNull { it.getOrNull()?.filter { s -> s.title.isNotBlank() } }

        val aggregated = mutableListOf<OnlineSongItem>()
        var maxIndex = 0
        for (list in platformLists) {
            if (list.size > maxIndex) maxIndex = list.size
        }

        for (i in 0 until maxIndex) {
            for (list in platformLists) {
                if (i < list.size) {
                    val item = list[i]
                    // 平台内部按 id 去重，避免重复加入
                    if (aggregated.none { it.id == item.id && it.platform == item.platform }) {
                        aggregated.add(item)
                    }
                }
            }
        }

        aggregated
    }

    /**
     * 获取歌手分类标签
     */
    suspend fun getArtistCategories(platform: OnlinePlatform = _currentPlatform.value): Result<List<com.rawsmusic.module.online.model.OnlineArtistCategory>> {
        return runCatching {
            getSource(platform).getArtistCategories()
        }
    }

    /**
     * 分页获取歌手列表
     */
    suspend fun getArtists(
        category: String = "全部",
        page: Int = 1,
        pageSize: Int = 30,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<com.rawsmusic.module.online.model.OnlineArtist>> {
        return runCatching {
            getSource(platform).getArtists(category, page, pageSize)
        }
    }

    /**
     * 搜索歌手
     */
    suspend fun searchArtists(
        keyword: String,
        page: Int = 1,
        pageSize: Int = 20,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<com.rawsmusic.module.online.model.OnlineArtist>> {
        return runCatching {
            getSource(platform).searchArtists(keyword, page, pageSize)
        }
    }

    /**
     * 获取歌手详情（含热门歌曲与专辑概览）
     */
    suspend fun getArtistDetail(
        artistId: String,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<com.rawsmusic.module.online.model.OnlineArtistDetail> {
        return runCatching {
            getSource(platform).getArtistDetail(artistId)
        }
    }

    /**
     * 分页获取歌手歌曲列表
     */
    suspend fun getArtistSongs(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 50,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<OnlineSongItem>> {
        return runCatching {
            getSource(platform).getArtistSongs(artistId, page, pageSize)
        }
    }

    /**
     * 分页获取歌手专辑列表
     */
    suspend fun getArtistAlbums(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 30,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<List<com.rawsmusic.module.online.model.OnlineAlbum>> {
        return runCatching {
            getSource(platform).getArtistAlbums(artistId, page, pageSize)
        }
    }

    /**
     * 获取专辑详情及曲目列表
     */
    suspend fun getAlbumDetail(
        albumId: String,
        platform: OnlinePlatform = _currentPlatform.value
    ): Result<Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>>> {
        return runCatching {
            getSource(platform).getAlbumDetail(albumId)
        }
    }

    /**
     * 智能解析并获取专辑详情（支持通过 albumId 直连或按专辑名+歌手名自动回退检索）
     */
    suspend fun resolveAlbumDetail(
        platform: OnlinePlatform,
        albumId: String?,
        albumTitle: String,
        artist: String,
        defaultCover: String? = null
    ): Result<Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>>> {
        return runCatching {
            // 1. 若有明确 albumId，优先直接拉取
            if (!albumId.isNullOrBlank() && albumId != "0" && albumId != "00000000000000") {
                try {
                    val direct = getSource(platform).getAlbumDetail(albumId)
                    if (direct.second.isNotEmpty()) {
                        return@runCatching direct
                    }
                } catch (_: Exception) {}
            }

            // 2. 尝试通过专辑名或歌手搜索歌曲，寻找该专辑的其他曲目
            val searchKey = if (albumTitle.isNotBlank() && albumTitle != "单曲" && albumTitle != "未知专辑") {
                "$albumTitle $artist".trim()
            } else {
                artist.ifEmpty { albumTitle }
            }

            if (searchKey.isNotBlank()) {
                try {
                    val searchList = getSource(platform).searchSongs(searchKey, 1, 30)
                    // 尝试匹配同专辑 ID 或同专辑名称的歌曲
                    val matchedByAlbum = if (albumTitle.isNotBlank() && albumTitle != "单曲" && albumTitle != "未知专辑") {
                        searchList.filter { it.album.equals(albumTitle, ignoreCase = true) }
                    } else emptyList()

                    val targetList = matchedByAlbum.ifEmpty { searchList }
                    val foundAlbumId = targetList.firstOrNull { !it.albumId.isNullOrBlank() }?.albumId

                    if (!foundAlbumId.isNullOrBlank() && foundAlbumId != albumId) {
                        try {
                            val albumRes = getSource(platform).getAlbumDetail(foundAlbumId)
                            if (albumRes.second.isNotEmpty()) {
                                return@runCatching albumRes
                            }
                        } catch (_: Exception) {}
                    }

                    if (targetList.isNotEmpty()) {
                        val first = targetList.first()
                        val resolvedAlbum = com.rawsmusic.module.online.model.OnlineAlbum(
                            id = foundAlbumId ?: albumId ?: "virtual_${platform.id}_${System.currentTimeMillis()}",
                            platform = platform,
                            title = if (albumTitle.isNotBlank() && albumTitle != "单曲") albumTitle else first.album,
                            coverUrl = first.coverUrl ?: defaultCover ?: "",
                            artist = if (artist.isNotBlank() && artist != "未知歌手") artist else first.artist,
                            songCount = targetList.size,
                            description = "收录匹配曲目的专属精选专辑"
                        )
                        return@runCatching Pair(resolvedAlbum, targetList)
                    }
                } catch (_: Exception) {}
            }

            // 3. 兜底返回单曲构成的独立专辑
            val fallbackAlbum = com.rawsmusic.module.online.model.OnlineAlbum(
                id = albumId ?: "album_${System.currentTimeMillis()}",
                platform = platform,
                title = albumTitle.ifEmpty { "单曲精选" },
                coverUrl = defaultCover ?: "",
                artist = artist.ifEmpty { "未知歌手" },
                songCount = 1
            )
            Pair(fallbackAlbum, emptyList())
        }
    }

    /**
     * 尝试从链接或输入文本中自动识别平台并提取歌单 ID
     * @return Pair<OnlinePlatform, PlaylistId> 或者 null
     */
    fun parseLinkOrText(text: String): Pair<OnlinePlatform, String>? {
        val trimmed = text.trim()
        if (trimmed.contains("qq.com") || trimmed.contains("y.qq.com")) {
            qqSource.extractPlaylistId(trimmed)?.let {
                return Pair(OnlinePlatform.QQ, it)
            }
        }
        if (trimmed.contains("163.com") || trimmed.contains("163cn.tv")) {
            neteaseSource.extractPlaylistId(trimmed)?.let {
                return Pair(OnlinePlatform.NETEASE, it)
            }
        }
        if (trimmed.contains("kugou.com")) {
            kugouSource.extractPlaylistId(trimmed)?.let {
                return Pair(OnlinePlatform.KUGOU, it)
            }
        }
        if (trimmed.contains("kuwo.cn")) {
            kuwoSource.extractPlaylistId(trimmed)?.let {
                return Pair(OnlinePlatform.KUWO, it)
            }
        }
        if (trimmed.contains("migu.cn")) {
            miguSource.extractPlaylistId(trimmed)?.let {
                return Pair(OnlinePlatform.MIGU, it)
            }
        }
        // 若没有识别到域名，尝试用当前平台的正则提取数字 ID
        val current = _currentPlatform.value
        getSource(current).extractPlaylistId(trimmed)?.let {
            return Pair(current, it)
        }
        return null
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        detailCache.evictAll()
        tagsCache.clear()
        leaderboardCache.clear()
    }
}
