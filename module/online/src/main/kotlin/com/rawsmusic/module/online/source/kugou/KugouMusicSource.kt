package com.rawsmusic.module.online.source.kugou

import com.rawsmusic.core.common.utils.AppLogger

import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.source.OnlinePlatformSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 酷狗音乐在线音源实现类
 */
class KugouMusicSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private var cookieProvider: (() -> String)? = null,
    private var accountProvider: (() -> com.rawsmusic.module.online.auth.model.PlatformAccount?)? = null
) : OnlinePlatformSource {

    override val platform: OnlinePlatform = OnlinePlatform.KUGOU

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun setCookieProvider(provider: () -> String) {
        this.cookieProvider = provider
    }

    fun setAccountProvider(provider: () -> com.rawsmusic.module.online.auth.model.PlatformAccount?) {
        this.accountProvider = provider
    }

    private suspend fun getApi(url: String): JSONObject = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "http://m.kugou.com/")

        cookieProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }

        val request = requestBuilder.get().build()
        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("酷狗网络响应为空")
        JSONObject(bodyStr)
    }

    private fun formatKugouImg(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains("{size}")) {
            trimmed = trimmed.replace("{size}", "400")
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        if (trimmed.startsWith("http://")) {
            return "https://${trimmed.removePrefix("http://")}"
        }
        return trimmed
    }

    companion object {
        private val PRESET_TAGS = listOf(
            OnlinePlaylistTag("0", "全部", "默认"),
            OnlinePlaylistTag("1", "华语", "语种"),
            OnlinePlaylistTag("2", "流行", "风格"),
            OnlinePlaylistTag("3", "摇滚", "风格"),
            OnlinePlaylistTag("4", "民谣", "风格"),
            OnlinePlaylistTag("5", "古风", "风格"),
            OnlinePlaylistTag("6", "电子", "风格"),
            OnlinePlaylistTag("7", "说唱", "风格"),
            OnlinePlaylistTag("8", "轻音乐", "风格"),
            OnlinePlaylistTag("9", "欧美", "语种"),
            OnlinePlaylistTag("10", "日韩", "语种"),
            OnlinePlaylistTag("11", "粤语", "语种"),
            OnlinePlaylistTag("12", "治愈", "心情"),
            OnlinePlaylistTag("13", "伤感", "心情"),
            OnlinePlaylistTag("14", "运动", "场景"),
            OnlinePlaylistTag("15", "睡眠", "场景"),
            OnlinePlaylistTag("16", "学习", "场景"),
            OnlinePlaylistTag("17", "ACG", "主题"),
            OnlinePlaylistTag("18", "影视", "主题"),
            OnlinePlaylistTag("19", "经典", "主题")
        )
    }

    override suspend fun getTags(): List<OnlinePlaylistTag> = withContext(Dispatchers.IO) {
        PRESET_TAGS
    }

    override suspend fun getPlaylists(
        tagId: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val isAll = tagId.isEmpty() || tagId == "0" || tagId == "推荐" || tagId == "全部"
        val url = if (isAll) {
            "http://m.kugou.com/plist/index&json=true&page=$page"
        } else {
            val tagObj = PRESET_TAGS.firstOrNull { it.id == tagId || it.name == tagId }
            val keyword = tagObj?.name ?: tagId
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            "http://mobilecdn.kugou.com/api/v3/search/special?keyword=$encodedKeyword&page=$page&pagesize=$pageSize"
        }

        try {
            val root = getApi(url)
            val infoArr = root.optJSONObject("data")?.optJSONArray("info")
                ?: root.optJSONObject("plist")?.optJSONObject("list")?.optJSONArray("info")
                ?: return@withContext emptyList()

            val list = mutableListOf<OnlinePlaylist>()
            for (i in 0 until infoArr.length()) {
                val obj = infoArr.optJSONObject(i) ?: continue
                val id = obj.optString("specialid")
                val title = obj.optString("specialname")
                val rawCover = obj.optString("imgurl").ifEmpty {
                    obj.optString("pic").ifEmpty {
                        obj.optString("image").ifEmpty {
                            obj.optString("cover")
                        }
                    }
                }
                val coverUrl = formatKugouImg(rawCover)
                val playCount = obj.optLong("playcount", 0L)
                val trackCount = obj.optInt("songcount", 0)
                val creatorName = obj.optString("nickname").ifEmpty { obj.optString("user_name").ifEmpty { "酷狗音乐" } }

                list.add(
                    OnlinePlaylist(
                        id = id,
                        platform = platform,
                        title = title,
                        coverUrl = coverUrl,
                        playCount = playCount,
                        trackCount = trackCount,
                        creatorName = creatorName,
                        description = obj.optString("intro")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getLeaderboards(): List<OnlineLeaderboard> = withContext(Dispatchers.IO) {
        try {
            val root = getApi("http://mobilecdnbj.kugou.com/api/v5/rank/list")
            val infoArr = root.optJSONObject("data")?.optJSONArray("info") ?: return@withContext emptyList()

            val list = mutableListOf<OnlineLeaderboard>()
            for (i in 0 until infoArr.length()) {
                val obj = infoArr.optJSONObject(i) ?: continue
                val id = obj.optString("rankid")
                val title = obj.optString("rankname")
                var cover = obj.optString("imgurl")
                if (cover.contains("{size}")) {
                    cover = cover.replace("{size}", "400")
                }
                if (cover.isEmpty()) {
                    cover = obj.optString("banner7url").replace("{size}", "400")
                }

                val songsPreview = mutableListOf<String>()
                val songInfoArr = obj.optJSONArray("songinfo")
                if (songInfoArr != null) {
                    for (j in 0 until songInfoArr.length()) {
                        val sObj = songInfoArr.optJSONObject(j) ?: continue
                        val sName = sObj.optString("songname")
                        if (sName.isNotEmpty()) songsPreview.add(sName)
                    }
                }

                list.add(
                    OnlineLeaderboard(
                        id = "rank_$id",
                        platform = platform,
                        title = title,
                        coverUrl = cover,
                        updateFrequency = obj.optString("update_frequency").ifEmpty { "每日更新" },
                        topSongsPreview = songsPreview
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> =
        withContext(Dispatchers.IO) {
            // 如果显式带有 rank_ 排行榜前缀，才按排行榜处理
            if (playlistId.startsWith("rank_")) {
                return@withContext getRankDetail(playlistId.removePrefix("rank_"))
            }

            // 【优先核心】针对酷狗用户云歌单（自建/收藏），优先使用移动端云歌单协议拉取歌曲
            try {
                val account = accountProvider?.invoke()
                val authService = com.rawsmusic.module.online.auth.service.KugouMusicAuthService()
                val (cloudPlaylist, cloudSongs) = authService.getPlaylistTracks(playlistId, account)
                if (cloudSongs.isNotEmpty()) {
                    AppLogger.i("KugouMusicSource", "成功从酷狗云歌单核心接口拉取到 ${cloudSongs.size} 首歌曲 (playlistId=$playlistId)")
                    val finalPlaylist = cloudPlaylist ?: OnlinePlaylist(
                        id = playlistId,
                        platform = platform,
                        title = "酷狗歌单",
                        coverUrl = cloudSongs.firstOrNull()?.coverUrl ?: "",
                        trackCount = cloudSongs.size,
                        creatorName = account?.nickname ?: "酷狗音乐"
                    )
                    return@withContext Pair(finalPlaylist, cloudSongs)
                }
            } catch (e: Exception) {
                AppLogger.w("KugouMusicSource", "从酷狗云歌单接口获取失败: ${e.message}，尝试公共精选歌单接口")
            }

            // 1. 获取歌单元数据
            var title = "酷狗歌单"
            var coverUrl = ""
            var creatorName = "酷狗音乐"
            var playCount = 0L
            var description: String? = null

            try {
                val infoRoot = getApi("http://mobilecdnbj.kugou.com/api/v5/special/info?specialid=$playlistId")
                val infoData = infoRoot.optJSONObject("data")
                if (infoData != null) {
                    title = infoData.optString("specialname").ifEmpty { title }
                    coverUrl = infoData.optString("imgurl").replace("{size}", "400")
                    creatorName = infoData.optString("nickname").ifEmpty {
                        infoData.optString("singername").ifEmpty { creatorName }
                    }
                    playCount = infoData.optLong("playcount", 0L)
                    description = infoData.optString("intro")
                }
            } catch (_: Exception) {
            }

            // 2. 获取歌单歌曲列表（官方移动端专用 API，包含 union_cover 与专辑信息）
            val songs = mutableListOf<OnlineSongItem>()
            try {
                val songUrl = "http://mobilecdnbj.kugou.com/api/v5/special/song?specialid=$playlistId&page=1&pagesize=200&plat=0&version=9108"
                val root = getApi(songUrl)
                val infoArr = root.optJSONObject("data")?.optJSONArray("info")

                if (infoArr != null) {
                    for (i in 0 until infoArr.length()) {
                        val sObj = infoArr.optJSONObject(i) ?: continue
                        val songItem = parseKugouSongItem(sObj, coverUrl)
                        if (songItem != null) {
                            songs.add(songItem)
                        }
                    }
                }
            } catch (_: Exception) {
                // 备用网页版接口降级
                try {
                    val fallbackUrl = "https://m.kugou.com/plist/list/$playlistId?json=true"
                    val root = getApi(fallbackUrl)
                    val infoObj = root.optJSONObject("info")?.optJSONObject("list")
                    if (title == "酷狗歌单") {
                        title = infoObj?.optString("specialname") ?: title
                        coverUrl = infoObj?.optString("imgurl")?.replace("{size}", "400") ?: coverUrl
                        creatorName = infoObj?.optString("nickname") ?: creatorName
                    }
                    val songsArr = root.optJSONObject("list")?.optJSONObject("list")?.optJSONArray("info")
                    if (songsArr != null) {
                        for (i in 0 until songsArr.length()) {
                            val sObj = songsArr.optJSONObject(i) ?: continue
                            val songItem = parseKugouSongItem(sObj, coverUrl)
                            if (songItem != null) {
                                songs.add(songItem)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val playlist = OnlinePlaylist(
                id = playlistId,
                platform = platform,
                title = title,
                coverUrl = coverUrl.ifEmpty { songs.firstOrNull()?.coverUrl ?: "" },
                playCount = playCount,
                trackCount = songs.size,
                creatorName = creatorName,
                description = description
            )

            Pair(playlist, songs)
        }

    private suspend fun getRankDetail(rankId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> =
        withContext(Dispatchers.IO) {
            val url = "http://mobilecdnbj.kugou.com/api/v5/rank/song?rankid=$rankId&page=1&pagesize=200"
            val root = getApi(url)
            val infoArr = root.optJSONObject("data")?.optJSONArray("info")

            val rankName = root.optJSONObject("data")?.optJSONObject("rank_info")?.optString("rankname") ?: "酷狗排行榜"
            var coverUrl = root.optJSONObject("data")?.optJSONObject("rank_info")?.optString("banner7url") ?: ""
            if (coverUrl.contains("{size}")) {
                coverUrl = coverUrl.replace("{size}", "400")
            }

            val songs = mutableListOf<OnlineSongItem>()
            if (infoArr != null) {
                for (i in 0 until infoArr.length()) {
                    val sObj = infoArr.optJSONObject(i) ?: continue
                    val songItem = parseKugouSongItem(sObj, coverUrl)
                    if (songItem != null) {
                        songs.add(songItem)
                    }
                }
            }

            val playlist = OnlinePlaylist(
                id = rankId,
                platform = platform,
                title = rankName,
                coverUrl = coverUrl,
                playCount = 0L,
                trackCount = songs.size,
                creatorName = "酷狗官方",
                description = "官方权威排行榜"
            )

            Pair(playlist, songs)
        }

    private fun parseKugouSongItem(sObj: JSONObject, defaultCover: String): OnlineSongItem? {
        val hash = sObj.optString("hash").ifEmpty { sObj.optString("320hash").ifEmpty { sObj.optString("sqhash") } }
        val audioId = sObj.optString("audio_id").ifEmpty { sObj.optString("album_audio_id") }
        val id = hash.ifEmpty { audioId }
        if (id.isEmpty()) return null

        val filename = sObj.optString("filename")
        val remark = sObj.optString("remark")

        // 歌手提取
        var singerName = sObj.optString("singername")
        if (singerName.isBlank()) {
            val authorsArr = sObj.optJSONArray("authors")
            if (authorsArr != null && authorsArr.length() > 0) {
                val names = mutableListOf<String>()
                for (j in 0 until authorsArr.length()) {
                    val aName = authorsArr.optJSONObject(j)?.optString("author_name")
                    if (!aName.isNullOrBlank()) names.add(aName.trim())
                }
                if (names.isNotEmpty()) singerName = names.joinToString(" & ")
            }
        }
        if (singerName.isBlank() && filename.contains("-")) {
            singerName = filename.substringBefore("-").trim()
        }
        if (singerName.isBlank()) singerName = "未知歌手"

        // 歌曲名提取
        var songName = sObj.optString("songname")
        if (songName.isBlank() && remark.isNotBlank()) {
            songName = remark
        }
        if (songName.isBlank() && filename.contains("-")) {
            songName = filename.substringAfter("-").trim()
        }
        if (songName.isBlank()) {
            songName = filename.ifEmpty { "酷狗单曲" }
        }

        // 专辑名提取
        var albumName = sObj.optString("album_name")
        if (albumName.isBlank()) {
            albumName = sObj.optJSONObject("album_info")?.optString("album_name") ?: ""
        }
        if (albumName.isBlank()) {
            albumName = sObj.optJSONObject("trans_param")?.optString("album_name") ?: ""
        }
        if (albumName.isBlank()) {
            albumName = if (remark.isNotBlank() && remark != songName) remark else "单曲"
        }

        // 歌曲专属封面提取
        var songCover = ""
        val unionCover = sObj.optJSONObject("trans_param")?.optString("union_cover")
        if (!unionCover.isNullOrBlank()) {
            songCover = unionCover.replace("{size}", "400")
        }
        if (songCover.isBlank()) {
            val albumCover = sObj.optString("album_sizable_cover")
            if (albumCover.isNotBlank()) {
                songCover = albumCover.replace("{size}", "400")
            }
        }
        if (songCover.isBlank()) {
            val sizableCover = sObj.optJSONObject("album_info")?.optString("sizable_cover")
            if (!sizableCover.isNullOrBlank()) {
                songCover = sizableCover.replace("{size}", "400")
            }
        }
        if (songCover.isBlank()) {
            val authorAvatar = sObj.optJSONArray("authors")?.optJSONObject(0)?.optString("sizable_avatar")
                ?: sObj.optJSONArray("authors")?.optJSONObject(0)?.optString("avatar")
            if (!authorAvatar.isNullOrBlank()) {
                songCover = authorAvatar.replace("{size}", "400")
            }
        }
        if (songCover.isBlank()) {
            val img = sObj.optString("imgurl").ifEmpty {
                sObj.optString("img").ifEmpty {
                    sObj.optString("image").ifEmpty {
                        sObj.optString("cover").ifEmpty {
                            sObj.optString("singer_sizable_cover")
                        }
                    }
                }
            }
            if (img.isNotBlank()) {
                songCover = img.replace("{size}", "400")
            }
        }
        if (songCover.isBlank() && defaultCover.isNotBlank()) {
            songCover = defaultCover
        }

        val durationMs = sObj.optLong("duration", sObj.optLong("duration_high", 0L)) * 1000L
        val isVip = sObj.optInt("privilege", 0) > 0 ||
                sObj.optInt("320privilege", 0) == 10 ||
                sObj.optInt("pkg_price", 0) > 0 ||
                sObj.optInt("pay_type", 0) > 0 ||
                sObj.optInt("fee", 0) > 0 ||
                sObj.optInt("is_vip", 0) == 1

        val albumId = sObj.optString("album_id").ifEmpty {
            sObj.optJSONObject("album_info")?.optString("album_id")?.ifEmpty {
                sObj.optJSONObject("trans_param")?.optString("cpy_album_id")
            }
        }

        return OnlineSongItem(
            id = id,
            platform = platform,
            title = songName,
            artist = singerName,
            album = albumName,
            albumId = albumId?.takeIf { it.isNotBlank() && it != "0" },
            durationMs = durationMs,
            coverUrl = songCover,
            isVip = isVip
        )
    }

    override suspend fun searchPlaylists(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://mobilecdn.kugou.com/api/v3/search/special?keyword=$encoded&page=$page&pagesize=$pageSize"
        try {
            val root = getApi(url)
            val infoArr = root.optJSONObject("data")?.optJSONArray("info") ?: return@withContext emptyList()

            val list = mutableListOf<OnlinePlaylist>()
            for (i in 0 until infoArr.length()) {
                val obj = infoArr.optJSONObject(i) ?: continue
                val id = obj.optString("specialid")
                val title = obj.optString("specialname")
                var cover = obj.optString("imgurl").replace("{size}", "400")
                val playCount = obj.optLong("playcount", 0L)
                val trackCount = obj.optInt("songcount", 0)

                list.add(
                    OnlinePlaylist(
                        id = id,
                        platform = platform,
                        title = title,
                        coverUrl = cover,
                        playCount = playCount,
                        trackCount = trackCount,
                        creatorName = obj.optString("nickname").ifEmpty { "酷狗用户" },
                        description = obj.optString("intro")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val list = mutableListOf<OnlineSongItem>()

        // 优先使用官方移动端搜索接口
        try {
            val url = "http://mobilecdn.kugou.com/api/v3/search/song?keyword=$encoded&page=$page&pagesize=$pageSize"
            val root = getApi(url)
            val infoArr = root.optJSONObject("data")?.optJSONArray("info")
            if (infoArr != null && infoArr.length() > 0) {
                for (i in 0 until infoArr.length()) {
                    val sObj = infoArr.optJSONObject(i) ?: continue
                    val songItem = parseKugouSongItem(sObj, "")
                    if (songItem != null) {
                        list.add(songItem)
                    }
                }
            }
        } catch (_: Exception) {}

        if (list.isNotEmpty()) return@withContext list

        // 备用接口降级
        try {
            val url = "http://songsearch.kugou.com/song_search_v2?keyword=$encoded&page=$page&pagesize=$pageSize&platform=WebFilter"
            val root = getApi(url)
            val infoArr = root.optJSONObject("data")?.optJSONArray("lists")
            if (infoArr != null) {
                for (i in 0 until infoArr.length()) {
                    val sObj = infoArr.optJSONObject(i) ?: continue
                    val hash = sObj.optString("FileHash").ifEmpty { sObj.optString("HQFileHash").ifEmpty { sObj.optString("SQFileHash") } }
                    val audioId = sObj.optString("Audioid").ifEmpty { sObj.optString("Scid") }
                    val id = hash.ifEmpty { audioId }
                    val songName = sObj.optString("SongName")
                    val singerName = sObj.optString("SingerName")
                    val albumName = sObj.optString("AlbumName")
                    val duration = sObj.optLong("Duration", 0L) * 1000L
                    val isVip = sObj.optInt("Privilege", 0) > 0 ||
                            sObj.optInt("PayType", 0) > 0 ||
                            sObj.optInt("privilege", 0) > 0 ||
                            sObj.optInt("pay_type", 0) > 0 ||
                            sObj.optInt("fee", 0) > 0 ||
                            sObj.optInt("is_vip", 0) == 1

                    var cover = sObj.optString("Image").ifEmpty {
                        sObj.optString("AlbumImage").ifEmpty {
                            sObj.optString("SingerImage").ifEmpty {
                                sObj.optString("Cover")
                            }
                        }
                    }
                    if (cover.isNotEmpty()) {
                        cover = cover.replace("{size}", "400")
                    }

                    if (id.isNotEmpty() && songName.isNotEmpty()) {
                        list.add(
                            OnlineSongItem(
                                id = id,
                                platform = platform,
                                title = songName,
                                artist = singerName.ifEmpty { "未知歌手" },
                                album = albumName.ifEmpty { "单曲" },
                                durationMs = duration,
                                coverUrl = cover,
                                isVip = isVip
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        list
    }

    override fun extractPlaylistId(urlOrText: String): String? {
        val trimmed = urlOrText.trim()
        val matcher = Pattern.compile("(?:specialid=|plist/list/|special/|id=)(\\d+)").matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        if (trimmed.matches(Regex("^\\d{4,12}$"))) {
            return trimmed
        }
        return null
    }

    override suspend fun getAlbumDetail(albumId: String): Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        var albumTitle = "酷狗专辑"
        var singerName = "未知歌手"
        var coverUrl = ""
        var publishTime: String? = null
        var company: String? = null
        var desc: String? = null
        var songCount = 0

        // 1. 获取专辑信息
        try {
            val infoUrl = "http://mobilecdn.kugou.com/api/v3/album/info?albumid=$albumId"
            val root = getApi(infoUrl)
            val dataObj = root.optJSONObject("data")
            if (dataObj != null) {
                albumTitle = dataObj.optString("albumname").ifEmpty { dataObj.optString("album_name") }
                singerName = dataObj.optString("singername").ifEmpty { dataObj.optString("author_name") }
                val img = dataObj.optString("imgurl").ifEmpty { dataObj.optString("sizable_cover") }
                if (img.isNotEmpty()) {
                    coverUrl = img.replace("{size}", "500")
                }
                publishTime = dataObj.optString("publish_time").ifEmpty { dataObj.optString("publishtime") }
                company = dataObj.optString("company")
                desc = dataObj.optString("intro").ifEmpty { dataObj.optString("description") }
                songCount = dataObj.optInt("songcount", 0)
            }
        } catch (_: Exception) {}

        // 2. 获取专辑曲目
        val songs = mutableListOf<OnlineSongItem>()
        try {
            val songUrl = "http://mobilecdn.kugou.com/api/v3/album/song?albumid=$albumId&page=1&pagesize=100"
            val root = getApi(songUrl)
            val infoArr = root.optJSONObject("data")?.optJSONArray("info")
            if (infoArr != null) {
                for (i in 0 until infoArr.length()) {
                    val sObj = infoArr.optJSONObject(i) ?: continue
                    val songItem = parseKugouSongItem(sObj, coverUrl)
                    if (songItem != null) {
                        songs.add(songItem)
                    }
                }
            }
        } catch (_: Exception) {}

        val album = com.rawsmusic.module.online.model.OnlineAlbum(
            id = albumId,
            platform = platform,
            title = albumTitle.ifEmpty { "专辑 $albumId" },
            coverUrl = coverUrl,
            artist = singerName.ifEmpty { "未知歌手" },
            songCount = if (songCount > 0) songCount else songs.size,
            publishTime = publishTime,
            company = company,
            description = desc
        )

        Pair(album, songs)
    }
}
