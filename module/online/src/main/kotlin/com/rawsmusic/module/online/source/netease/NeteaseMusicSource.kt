package com.rawsmusic.module.online.source.netease

import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.source.OnlinePlatformSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 网易云音乐音源实现类
 */
class NeteaseMusicSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : OnlinePlatformSource {

    override val platform: OnlinePlatform = OnlinePlatform.NETEASE

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultCookie = "os=pc; appver=2.9.7; osver=Microsoft-Windows-10; NMTID=00O_orbit_player;"

    /**
     * 发送 GET 请求
     */
    private suspend fun getApi(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://music.163.com/")
            .header("Host", "music.163.com")
            .header("Cookie", defaultCookie)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("网易云网络响应为空")
        JSONObject(bodyStr)
    }

    /**
     * 发送 POST 请求
     */
    private suspend fun postApi(url: String, formBody: FormBody): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://music.163.com/")
            .header("Host", "music.163.com")
            .header("Cookie", defaultCookie)
            .post(formBody)
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("网易云网络响应为空")
        JSONObject(bodyStr)
    }

    override suspend fun getTags(): List<OnlinePlaylistTag> = withContext(Dispatchers.IO) {
        try {
            val root = getApi("https://music.163.com/api/playlist/catalogue")
            val subArr = root.optJSONArray("sub")
            val tags = mutableListOf<OnlinePlaylistTag>()
            tags.add(OnlinePlaylistTag("全部", "全部", "默认"))
            tags.add(OnlinePlaylistTag("华语", "华语", "语种"))
            tags.add(OnlinePlaylistTag("欧美", "欧美", "语种"))
            tags.add(OnlinePlaylistTag("流行", "流行", "风格"))
            tags.add(OnlinePlaylistTag("摇滚", "摇滚", "风格"))
            tags.add(OnlinePlaylistTag("民谣", "民谣", "风格"))
            tags.add(OnlinePlaylistTag("电子", "电子", "风格"))
            tags.add(OnlinePlaylistTag("ACG", "ACG", "风格"))
            tags.add(OnlinePlaylistTag("治愈", "治愈", "情感"))

            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val obj = subArr.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    val cat = obj.optString("category")
                    if (name.isNotEmpty() && tags.none { it.name == name }) {
                        tags.add(OnlinePlaylistTag(name, name, cat))
                    }
                }
            }
            tags
        } catch (e: Exception) {
            // 兜底常用标签
            listOf(
                OnlinePlaylistTag("全部", "全部"),
                OnlinePlaylistTag("华语", "华语"),
                OnlinePlaylistTag("欧美", "欧美"),
                OnlinePlaylistTag("流行", "流行"),
                OnlinePlaylistTag("摇滚", "摇滚"),
                OnlinePlaylistTag("民谣", "民谣"),
                OnlinePlaylistTag("电子", "电子"),
                OnlinePlaylistTag("ACG", "ACG"),
                OnlinePlaylistTag("治愈", "治愈"),
                OnlinePlaylistTag("夜晚", "夜晚")
            )
        }
    }

    override suspend fun getPlaylists(tagId: String, page: Int, pageSize: Int): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val encodedCat = URLEncoder.encode(if (tagId.isEmpty()) "全部" else tagId, "UTF-8")
        val url = "https://music.163.com/api/playlist/list?cat=$encodedCat&order=hot&offset=$offset&limit=$pageSize&total=true"
        val root = getApi(url)
        val playlistsArr = root.optJSONArray("playlists") ?: JSONArray()
        val result = mutableListOf<OnlinePlaylist>()

        for (i in 0 until playlistsArr.length()) {
            val item = playlistsArr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            var cover = item.optString("coverImgUrl")
            if (cover.isNotEmpty() && !cover.contains("?param=")) {
                cover = "$cover?param=300y300"
            }
            val playCount = item.optLong("playCount")
            val trackCount = item.optInt("trackCount")
            val creatorObj = item.optJSONObject("creator")
            val creatorName = creatorObj?.optString("nickname")
            val creatorAvatar = creatorObj?.optString("avatarUrl")
            val desc = item.optString("description")

            result.add(
                OnlinePlaylist(
                    id = id,
                    platform = OnlinePlatform.NETEASE,
                    title = name,
                    coverUrl = cover,
                    playCount = playCount,
                    trackCount = trackCount,
                    creatorName = creatorName,
                    creatorAvatarUrl = creatorAvatar,
                    description = desc
                )
            )
        }
        result
    }

    override suspend fun getLeaderboards(): List<OnlineLeaderboard> = withContext(Dispatchers.IO) {
        val root = getApi("https://music.163.com/api/toplist")
        val listArr = root.optJSONArray("list") ?: JSONArray()
        val result = mutableListOf<OnlineLeaderboard>()

        for (i in 0 until listArr.length()) {
            val item = listArr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            var cover = item.optString("coverImgUrl")
            if (cover.isNotEmpty() && !cover.contains("?param=")) {
                cover = "$cover?param=300y300"
            }
            val updateFreq = item.optString("updateFrequency")
            val tracks = item.optJSONArray("tracks")
            val previews = mutableListOf<String>()
            if (tracks != null) {
                for (j in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(j) ?: continue
                    val first = t.optString("first")
                    val second = t.optString("second")
                    if (first.isNotEmpty()) {
                        previews.add("$first - $second")
                    }
                }
            }

            result.add(
                OnlineLeaderboard(
                    id = id,
                    platform = OnlinePlatform.NETEASE,
                    title = name,
                    coverUrl = cover,
                    updateFrequency = updateFreq,
                    topSongsPreview = previews
                )
            )
        }
        result
    }

    override suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val url = "https://music.163.com/api/v1/playlist/detail?id=$playlistId"
        val root = getApi(url)
        val playlistObj = root.optJSONObject("playlist") ?: throw IllegalStateException("解析歌单详情失败")

        val id = playlistObj.optLong("id").toString()
        val title = playlistObj.optString("name")
        var cover = playlistObj.optString("coverImgUrl")
        if (cover.isNotEmpty() && !cover.contains("?param=")) {
            cover = "$cover?param=500y500"
        }
        val playCount = playlistObj.optLong("playCount")
        val trackCount = playlistObj.optInt("trackCount")
        val creatorObj = playlistObj.optJSONObject("creator")
        val creatorName = creatorObj?.optString("nickname")
        val creatorAvatar = creatorObj?.optString("avatarUrl")
        val desc = playlistObj.optString("description")

        val playlist = OnlinePlaylist(
            id = id,
            platform = OnlinePlatform.NETEASE,
            title = title,
            coverUrl = cover,
            playCount = playCount,
            trackCount = trackCount,
            creatorName = creatorName,
            creatorAvatarUrl = creatorAvatar,
            description = desc
        )

        // 解析全部歌曲 ID 列表
        val trackIdsArr = playlistObj.optJSONArray("trackIds")
        val trackIds = mutableListOf<String>()
        if (trackIdsArr != null) {
            for (i in 0 until trackIdsArr.length()) {
                val tidObj = trackIdsArr.optJSONObject(i)
                val tid = tidObj?.optLong("id")?.toString() ?: continue
                if (tid.isNotEmpty() && tid != "0") {
                    trackIds.add(tid)
                }
            }
        }

        val tracksArr = playlistObj.optJSONArray("tracks") ?: JSONArray()
        val defaultSongs = mutableListOf<OnlineSongItem>()
        for (i in 0 until tracksArr.length()) {
            val trackObj = tracksArr.optJSONObject(i) ?: continue
            parseSongItem(trackObj)?.let { defaultSongs.add(it) }
        }

        // 如果存在 trackIds 并且数量大于默认 tracks，则通过批量接口获取完整歌曲列表
        val songs = if (trackIds.size > defaultSongs.size) {
            try {
                val fullSongs = fetchSongDetailsByIds(trackIds)
                if (fullSongs.isNotEmpty()) fullSongs else defaultSongs
            } catch (e: Exception) {
                defaultSongs
            }
        } else if (defaultSongs.isNotEmpty()) {
            defaultSongs
        } else if (trackIds.isNotEmpty()) {
            try {
                fetchSongDetailsByIds(trackIds)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        Pair(playlist, songs)
    }

    /**
     * 根据歌曲 ID 列表分批获取所有歌曲详情
     */
    private suspend fun fetchSongDetailsByIds(trackIds: List<String>): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        val songMap = mutableMapOf<String, OnlineSongItem>()
        val chunks = trackIds.chunked(500)
        for (chunk in chunks) {
            try {
                val cArray = JSONArray()
                for (id in chunk) {
                    cArray.put(JSONObject().apply { put("id", id) })
                }
                val formBody = FormBody.Builder()
                    .add("c", cArray.toString())
                    .add("ids", "[${chunk.joinToString(",")}]")
                    .build()
                val root = postApi("https://music.163.com/api/v3/song/detail", formBody)
                val songsArr = root.optJSONArray("songs") ?: continue
                for (i in 0 until songsArr.length()) {
                    val trackObj = songsArr.optJSONObject(i) ?: continue
                    val songItem = parseSongItem(trackObj) ?: continue
                    songMap[songItem.id] = songItem
                }
            } catch (e: Exception) {
                // 忽略当前分批错误，尽可能解析其它批次
            }
        }
        trackIds.mapNotNull { songMap[it] }
    }

    /**
     * 解析单首网易云歌曲 JSON 对象
     */
    private fun parseSongItem(trackObj: JSONObject): OnlineSongItem? {
        val songId = trackObj.optLong("id").toString()
        if (songId.isEmpty() || songId == "0") return null

        val songName = trackObj.optString("name").ifEmpty { trackObj.optString("title") }
        val dt = trackObj.optLong("dt").let { if (it > 0) it else trackObj.optLong("duration") }
        val fee = trackObj.optInt("fee") // 1: vip, 4: 购买专辑等
        val isVip = fee == 1 || fee == 4

        // 艺术家: ar 或 artists
        val arArr = trackObj.optJSONArray("ar") ?: trackObj.optJSONArray("artists")
        val artists = mutableListOf<String>()
        var artistPicUrl = ""
        if (arArr != null) {
            for (a in 0 until arArr.length()) {
                val aObj = arArr.optJSONObject(a)
                val aName = aObj?.optString("name") ?: aObj?.optString("title")
                if (!aName.isNullOrEmpty()) artists.add(aName)
                if (artistPicUrl.isEmpty() && aObj != null) {
                    val aPic = aObj.optString("img1v1Url").ifEmpty { aObj.optString("picUrl") }
                    if (aPic.isNotEmpty() && !aPic.contains("default_avatar")) {
                        artistPicUrl = aPic
                    }
                }
            }
        }
        val artistStr = if (artists.isEmpty()) "未知歌手" else artists.joinToString(", ")

        // 专辑: al 或 album
        val alObj = trackObj.optJSONObject("al") ?: trackObj.optJSONObject("album")
        val albumName = alObj?.optString("name") ?: "未知专辑"
        val albId = alObj?.optLong("id")?.takeIf { it > 0 }?.toString()
        var picUrl = alObj?.optString("picUrl")?.ifEmpty { null }
            ?: alObj?.optString("pic_str")?.let { if (it.isNotEmpty() && it.startsWith("http")) it else null }
            ?: trackObj.optString("picUrl").ifEmpty { null }
            ?: artistPicUrl.ifEmpty { null }

        if (!picUrl.isNullOrEmpty() && !picUrl.contains("?param=")) {
            picUrl = "$picUrl?param=300y300"
        }

        return OnlineSongItem(
            id = songId,
            platform = OnlinePlatform.NETEASE,
            title = songName,
            artist = artistStr,
            album = albumName,
            albumId = albId,
            durationMs = dt,
            coverUrl = picUrl,
            isVip = isVip
        )
    }

    override suspend fun searchPlaylists(keyword: String, page: Int, pageSize: Int): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val formBody = FormBody.Builder()
            .add("s", keyword)
            .add("type", "1000") // 1000 代表歌单
            .add("offset", offset.toString())
            .add("limit", pageSize.toString())
            .add("total", "true")
            .build()

        val root = postApi("https://music.163.com/api/search/get/web?csrf_token=", formBody)
        val resultObj = root.optJSONObject("result") ?: return@withContext emptyList()
        val playlistsArr = resultObj.optJSONArray("playlists") ?: return@withContext emptyList()
        val list = mutableListOf<OnlinePlaylist>()

        for (i in 0 until playlistsArr.length()) {
            val item = playlistsArr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            var cover = item.optString("coverImgUrl")
            if (cover.isNotEmpty() && !cover.contains("?param=")) {
                cover = "$cover?param=300y300"
            }
            val playCount = item.optLong("playCount")
            val trackCount = item.optInt("trackCount")
            val creatorObj = item.optJSONObject("creator")
            val creatorName = creatorObj?.optString("nickname")
            val desc = item.optString("description")

            list.add(
                OnlinePlaylist(
                    id = id,
                    platform = OnlinePlatform.NETEASE,
                    title = name,
                    coverUrl = cover,
                    playCount = playCount,
                    trackCount = trackCount,
                    creatorName = creatorName,
                    description = desc
                )
            )
        }
        list
    }

    override suspend fun searchSongs(keyword: String, page: Int, pageSize: Int): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val formBody = FormBody.Builder()
            .add("s", keyword)
            .add("type", "1") // 1 代表单曲歌曲搜索
            .add("offset", offset.toString())
            .add("limit", pageSize.toString())
            .add("total", "true")
            .build()

        val list = mutableListOf<OnlineSongItem>()

        // 1. 优先使用稳定网页搜索接口
        try {
            val root = postApi("https://music.163.com/api/search/get/web?csrf_token=", formBody)
            val resultObj = root.optJSONObject("result")
            val songsArr = resultObj?.optJSONArray("songs")
            if (songsArr != null) {
                for (i in 0 until songsArr.length()) {
                    val item = songsArr.optJSONObject(i) ?: continue
                    parseSongItem(item)?.let { list.add(it) }
                }
            }
        } catch (_: Exception) {}

        // 2. 备用 cloudsearch 降级
        if (list.isEmpty()) {
            try {
                val root = postApi("https://music.163.com/api/cloudsearch/pc", formBody)
                val resultObj = root.optJSONObject("result")
                val songsArr = resultObj?.optJSONArray("songs")
                if (songsArr != null && songsArr.length() > 0) {
                    for (i in 0 until songsArr.length()) {
                        val item = songsArr.optJSONObject(i) ?: continue
                        parseSongItem(item)?.let { list.add(it) }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. 若有缺失封面的歌曲，批量补充详情（获取高清大图 al.picUrl）
        val missingCoverIds = list.filter { it.coverUrl.isNullOrEmpty() }.map { it.id }
        if (missingCoverIds.isNotEmpty()) {
            try {
                val detailedSongs = fetchSongDetailsByIds(missingCoverIds).associateBy { it.id }
                if (detailedSongs.isNotEmpty()) {
                    return@withContext list.map { song ->
                        if (song.coverUrl.isNullOrEmpty() && detailedSongs.containsKey(song.id)) {
                            detailedSongs[song.id] ?: song
                        } else {
                            song
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        list
    }

    override fun extractPlaylistId(urlOrText: String): String? {
        val trimmed = urlOrText.trim()
        // 纯数字 ID
        if (trimmed.matches(Regex("^\\d+$"))) {
            return trimmed
        }
        // 包含 id=123456
        val matcher = Pattern.compile("id=(\\d+)").matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        // 包含 playlist/123456
        val matcher2 = Pattern.compile("playlist/(\\d+)").matcher(trimmed)
        if (matcher2.find()) {
            return matcher2.group(1)
        }
        return null
    }

    override suspend fun getArtistCategories(): List<com.rawsmusic.module.online.model.OnlineArtistCategory> {
        return listOf(
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "7_1", name = "华语男歌手", area = "7", type = "1"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "7_2", name = "华语女歌手", area = "7", type = "2"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "7_3", name = "华语乐队/组合", area = "7", type = "3"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "96_1", name = "欧美男歌手", area = "96", type = "1"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "96_2", name = "欧美女歌手", area = "96", type = "2"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "96_3", name = "欧美乐队/组合", area = "96", type = "3"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "8_1", name = "日本男歌手", area = "8", type = "1"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "8_2", name = "日本女歌手", area = "8", type = "2"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "8_3", name = "日本乐队/组合", area = "8", type = "3"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "16_1", name = "韩国男歌手", area = "16", type = "1"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "16_2", name = "韩国女歌手", area = "16", type = "2"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "16_3", name = "韩国乐队/组合", area = "16", type = "3")
        )
    }

    override suspend fun getArtists(
        category: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineArtist> = withContext(Dispatchers.IO) {
        var area = "-1"
        var type = "-1"
        if (category.contains("_")) {
            val parts = category.split("_")
            area = parts.getOrNull(0) ?: "-1"
            type = parts.getOrNull(1) ?: "-1"
        }
        val offset = (page - 1) * pageSize
        val url = "https://music.163.com/api/v1/artist/list?categoryCode=0&area=$area&type=$type&initial=-1&offset=$offset&limit=$pageSize&total=true"
        val root = getApi(url)
        val artistsArr = root.optJSONArray("artists") ?: return@withContext emptyList()
        val list = mutableListOf<com.rawsmusic.module.online.model.OnlineArtist>()

        for (i in 0 until artistsArr.length()) {
            val item = artistsArr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            var picUrl = item.optString("picUrl").ifEmpty { item.optString("img1v1Url") }
            if (picUrl.isNotEmpty() && !picUrl.contains("?param=")) {
                picUrl = "$picUrl?param=300y300"
            }
            val musicSize = item.optInt("musicSize", 0)
            val albumSize = item.optInt("albumSize", 0)
            val mvSize = item.optInt("mvSize", 0)
            val aliasArr = item.optJSONArray("alias")
            val aliasList = mutableListOf<String>()
            if (aliasArr != null) {
                for (j in 0 until aliasArr.length()) {
                    aliasArr.optString(j).takeIf { it.isNotBlank() }?.let { aliasList.add(it) }
                }
            }

            list.add(
                com.rawsmusic.module.online.model.OnlineArtist(
                    id = id,
                    platform = platform,
                    name = name,
                    avatarUrl = picUrl,
                    songCount = musicSize,
                    albumCount = albumSize,
                    mvCount = mvSize,
                    alias = aliasList
                )
            )
        }
        list
    }

    override suspend fun searchArtists(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineArtist> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val formBody = FormBody.Builder()
            .add("s", keyword)
            .add("type", "100") // 100 代表歌手
            .add("offset", offset.toString())
            .add("limit", pageSize.toString())
            .add("total", "true")
            .build()

        val root = postApi("https://music.163.com/api/search/get/web?csrf_token=", formBody)
        val resultObj = root.optJSONObject("result") ?: return@withContext emptyList()
        val artistsArr = resultObj.optJSONArray("artists") ?: return@withContext emptyList()
        val list = mutableListOf<com.rawsmusic.module.online.model.OnlineArtist>()

        for (i in 0 until artistsArr.length()) {
            val item = artistsArr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val name = item.optString("name")
            var picUrl = item.optString("picUrl").ifEmpty { item.optString("img1v1Url") }
            if (picUrl.isNotEmpty() && !picUrl.contains("?param=")) {
                picUrl = "$picUrl?param=300y300"
            }
            val albumSize = item.optInt("albumSize", 0)
            val mvSize = item.optInt("mvSize", 0)
            val aliasArr = item.optJSONArray("alias")
            val aliasList = mutableListOf<String>()
            if (aliasArr != null) {
                for (j in 0 until aliasArr.length()) {
                    aliasArr.optString(j).takeIf { it.isNotBlank() }?.let { aliasList.add(it) }
                }
            }

            list.add(
                com.rawsmusic.module.online.model.OnlineArtist(
                    id = id,
                    platform = platform,
                    name = name,
                    avatarUrl = picUrl,
                    albumCount = albumSize,
                    mvCount = mvSize,
                    alias = aliasList
                )
            )
        }
        list
    }

    override suspend fun getArtistDetail(artistId: String): com.rawsmusic.module.online.model.OnlineArtistDetail = withContext(Dispatchers.IO) {
        // 1. 获取歌手基本信息
        var artistName = ""
        var avatarUrl = ""
        var desc: String? = null
        var songCount = 0
        var albumCount = 0
        val aliasList = mutableListOf<String>()

        try {
            val detailRoot = getApi("https://music.163.com/api/v1/artist/detail?id=$artistId")
            val artistObj = detailRoot.optJSONObject("data")?.optJSONObject("artist")
            if (artistObj != null) {
                artistName = artistObj.optString("name")
                avatarUrl = artistObj.optString("avatar").ifEmpty { artistObj.optString("cover") }
                desc = artistObj.optString("briefDesc")
                songCount = artistObj.optInt("musicSize", 0)
                albumCount = artistObj.optInt("albumSize", 0)
                val aliasArr = artistObj.optJSONArray("alias")
                if (aliasArr != null) {
                    for (i in 0 until aliasArr.length()) {
                        aliasArr.optString(i).takeIf { it.isNotBlank() }?.let { aliasList.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. 获取热门歌曲
        val hotSongs = mutableListOf<OnlineSongItem>()
        try {
            val songsRoot = getApi("https://music.163.com/api/artist/50/hot?id=$artistId")
            val songsArr = songsRoot.optJSONArray("songs")
            if (songsArr != null) {
                if (artistName.isEmpty() && songsArr.length() > 0) {
                    val firstAr = songsArr.optJSONObject(0)?.optJSONArray("ar")?.optJSONObject(0)
                    artistName = firstAr?.optString("name") ?: ""
                }
                for (i in 0 until songsArr.length()) {
                    val sObj = songsArr.optJSONObject(i) ?: continue
                    val id = sObj.optLong("id").toString()
                    val title = sObj.optString("name")
                    val arArr = sObj.optJSONArray("ar")
                    val arNames = mutableListOf<String>()
                    if (arArr != null) {
                        for (j in 0 until arArr.length()) {
                            arArr.optJSONObject(j)?.optString("name")?.let { arNames.add(it) }
                        }
                    }
                    val alObj = sObj.optJSONObject("al")
                    val albumTitle = alObj?.optString("name") ?: ""
                    var pic = alObj?.optString("picUrl") ?: ""
                    if (pic.isNotEmpty() && !pic.contains("?param=")) {
                        pic = "$pic?param=300y300"
                    }
                    val dt = sObj.optLong("dt", 0L)
                    val fee = sObj.optInt("fee", 0)

                    hotSongs.add(
                        OnlineSongItem(
                            id = id,
                            platform = platform,
                            title = title,
                            artist = if (arNames.isNotEmpty()) arNames.joinToString(", ") else artistName,
                            album = albumTitle,
                            durationMs = dt,
                            coverUrl = pic,
                            isVip = fee == 1 || fee == 4
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        if (songCount <= 0) songCount = hotSongs.size

        // 3. 获取专辑列表
        val albums = getArtistAlbums(artistId, 1, 30)
        if (albumCount <= 0) albumCount = albums.size

        if (avatarUrl.isNotEmpty() && !avatarUrl.contains("?param=")) {
            avatarUrl = "$avatarUrl?param=500y500"
        }
        if (avatarUrl.isEmpty() && hotSongs.isNotEmpty()) {
            avatarUrl = hotSongs.first().coverUrl ?: ""
        }

        val artist = com.rawsmusic.module.online.model.OnlineArtist(
            id = artistId,
            platform = platform,
            name = artistName.ifEmpty { "歌手 $artistId" },
            avatarUrl = avatarUrl,
            songCount = songCount,
            albumCount = albumCount,
            description = desc,
            alias = aliasList
        )

        com.rawsmusic.module.online.model.OnlineArtistDetail(
            artist = artist,
            hotSongs = hotSongs,
            albums = albums
        )
    }

    override suspend fun getArtistSongs(
        artistId: String,
        page: Int,
        pageSize: Int
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val url = "https://music.163.com/api/v1/artist/songs?id=$artistId&order=hot&limit=$pageSize&offset=$offset"
        val root = getApi(url)
        val songsArr = root.optJSONArray("songs") ?: return@withContext emptyList()
        val list = mutableListOf<OnlineSongItem>()

        for (i in 0 until songsArr.length()) {
            val sObj = songsArr.optJSONObject(i) ?: continue
            val id = sObj.optLong("id").toString()
            val title = sObj.optString("name")
            val arArr = sObj.optJSONArray("ar")
            val arNames = mutableListOf<String>()
            if (arArr != null) {
                for (j in 0 until arArr.length()) {
                    arArr.optJSONObject(j)?.optString("name")?.let { arNames.add(it) }
                }
            }
            val alObj = sObj.optJSONObject("al")
            val albumTitle = alObj?.optString("name") ?: ""
            var pic = alObj?.optString("picUrl") ?: ""
            if (pic.isNotEmpty() && !pic.contains("?param=")) {
                pic = "$pic?param=300y300"
            }
            val dt = sObj.optLong("dt", 0L)
            val fee = sObj.optInt("fee", 0)

            list.add(
                OnlineSongItem(
                    id = id,
                    platform = platform,
                    title = title,
                    artist = arNames.joinToString(", "),
                    album = albumTitle,
                    durationMs = dt,
                    coverUrl = pic,
                    isVip = fee == 1 || fee == 4
                )
            )
        }
        list
    }

    override suspend fun getArtistAlbums(
        artistId: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineAlbum> = withContext(Dispatchers.IO) {
        val offset = (page - 1) * pageSize
        val url = "https://music.163.com/api/artist/albums/$artistId?limit=$pageSize&offset=$offset"
        val root = getApi(url)
        val hotAlbums = root.optJSONArray("hotAlbums") ?: return@withContext emptyList()
        val list = mutableListOf<com.rawsmusic.module.online.model.OnlineAlbum>()

        for (i in 0 until hotAlbums.length()) {
            val aObj = hotAlbums.optJSONObject(i) ?: continue
            val id = aObj.optLong("id").toString()
            val title = aObj.optString("name")
            var picUrl = aObj.optString("picUrl")
            if (picUrl.isNotEmpty() && !picUrl.contains("?param=")) {
                picUrl = "$picUrl?param=300y300"
            }
            val artistObj = aObj.optJSONObject("artist")
            val artistName = artistObj?.optString("name") ?: ""
            val size = aObj.optInt("size", 0)
            val publishTimeLong = aObj.optLong("publishTime", 0L)
            val publishTime = if (publishTimeLong > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(publishTimeLong))
            } else null
            val company = aObj.optString("company")
            val desc = aObj.optString("description")

            list.add(
                com.rawsmusic.module.online.model.OnlineAlbum(
                    id = id,
                    platform = platform,
                    title = title,
                    coverUrl = picUrl,
                    artist = artistName,
                    artistId = artistId,
                    songCount = size,
                    publishTime = publishTime,
                    company = company,
                    description = desc
                )
            )
        }
        list
    }

    override suspend fun getAlbumDetail(albumId: String): Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val url = "https://music.163.com/api/v1/album/$albumId"
        val root = getApi(url)
        val albumObj = root.optJSONObject("album") ?: throw IllegalStateException("专辑数据不存在")
        val id = albumObj.optLong("id").toString()
        val title = albumObj.optString("name")
        var cover = albumObj.optString("picUrl")
        if (cover.isNotEmpty() && !cover.contains("?param=")) {
            cover = "$cover?param=500y500"
        }
        val artistObj = albumObj.optJSONObject("artist")
        val artistName = artistObj?.optString("name") ?: ""
        val artistId = artistObj?.optLong("id")?.toString()
        val size = albumObj.optInt("size", 0)
        val publishTimeLong = albumObj.optLong("publishTime", 0L)
        val publishTime = if (publishTimeLong > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(publishTimeLong))
        } else null
        val company = albumObj.optString("company")
        val desc = albumObj.optString("description")

        val album = com.rawsmusic.module.online.model.OnlineAlbum(
            id = id,
            platform = platform,
            title = title,
            coverUrl = cover,
            artist = artistName,
            artistId = artistId,
            songCount = size,
            publishTime = publishTime,
            company = company,
            description = desc
        )

        val songsArr = root.optJSONArray("songs") ?: albumObj.optJSONArray("songs")
        val songList = mutableListOf<OnlineSongItem>()
        if (songsArr != null) {
            for (i in 0 until songsArr.length()) {
                val sObj = songsArr.optJSONObject(i) ?: continue
                val songId = sObj.optLong("id").toString()
                val songName = sObj.optString("name")
                val arArr = sObj.optJSONArray("ar")
                val arNames = mutableListOf<String>()
                if (arArr != null) {
                    for (j in 0 until arArr.length()) {
                        arArr.optJSONObject(j)?.optString("name")?.let { arNames.add(it) }
                    }
                }
                val dt = sObj.optLong("dt", 0L)
                val fee = sObj.optInt("fee", 0)

                songList.add(
                    OnlineSongItem(
                        id = songId,
                        platform = platform,
                        title = songName,
                        artist = if (arNames.isNotEmpty()) arNames.joinToString(", ") else artistName,
                        album = title,
                        durationMs = dt,
                        coverUrl = cover,
                        isVip = fee == 1 || fee == 4
                    )
                )
            }
        }

        Pair(album, songList)
    }
}

