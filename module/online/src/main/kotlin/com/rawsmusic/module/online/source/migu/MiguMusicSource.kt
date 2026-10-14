package com.rawsmusic.module.online.source.migu

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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 咪咕音乐在线音源实现类
 */
class MiguMusicSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : OnlinePlatformSource {

    override val platform: OnlinePlatform = OnlinePlatform.MIGU

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private suspend fun getApi(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://m.music.migu.cn/")
            .header("channel", "0146951")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("咪咕网络响应为空")
        JSONObject(bodyStr)
    }

    private fun parseMiguIsVip(sObj: JSONObject): Boolean {
        val vipType = sObj.optString("vipType", "")
        val vipFlag = sObj.optString("vipFlag", "")
        val chargeAuditions = sObj.optString("chargeAuditions", "")
        val chargeType = sObj.optInt("chargeType", 0)
        val feeType = sObj.optInt("feeType", 0)
        val price = sObj.optInt("price", 0)
        return vipType == "1" || vipType == "2" || sObj.optInt("vipType", 0) > 0 ||
                vipFlag == "1" || chargeAuditions == "1" || chargeType > 0 || feeType > 0 || price > 0
    }

    override suspend fun getTags(): List<OnlinePlaylistTag> = withContext(Dispatchers.IO) {
        listOf(
            OnlinePlaylistTag("全部", "全部", "默认"),
            OnlinePlaylistTag("华语", "华语", "语种"),
            OnlinePlaylistTag("欧美", "欧美", "语种"),
            OnlinePlaylistTag("流行", "流行", "风格"),
            OnlinePlaylistTag("摇滚", "摇滚", "风格"),
            OnlinePlaylistTag("古风", "古风", "风格"),
            OnlinePlaylistTag("治愈", "治愈", "情感"),
            OnlinePlaylistTag("民谣", "民谣", "风格"),
            OnlinePlaylistTag("ACG", "ACG", "风格"),
            OnlinePlaylistTag("影视", "影视", "场景")
        )
    }

    override suspend fun getPlaylists(
        tagId: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val keyword = if (tagId == "全部" || tagId == "推荐" || tagId == "0") "热门" else tagId
        searchPlaylists(keyword, page, pageSize)
    }

    override suspend fun getLeaderboards(): List<OnlineLeaderboard> = withContext(Dispatchers.IO) {
        listOf(
            OnlineLeaderboard(
                id = "咪咕音乐榜",
                platform = platform,
                title = "咪咕音乐榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/b19a16f8880e4cbbaae9b7e71f9cf5fc.png",
                updateFrequency = "每周更新",
                topSongsPreview = listOf("咪咕官方精选", "权威潮流推荐")
            ),
            OnlineLeaderboard(
                id = "咪咕新歌榜",
                platform = platform,
                title = "咪咕新歌榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/9cf95fc0245a4a2cb588fc23f6e1f0e2.png",
                updateFrequency = "每日更新",
                topSongsPreview = listOf("最新热单首发", "每日新鲜出炉")
            ),
            OnlineLeaderboard(
                id = "咪咕热歌榜",
                platform = platform,
                title = "咪咕热歌榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/ff7d4133405c4ea8a0efd12d59ca7868.png",
                updateFrequency = "每日更新",
                topSongsPreview = listOf("全网飙升传唱", "亿万用户热播")
            ),
            OnlineLeaderboard(
                id = "影视金曲榜",
                platform = platform,
                title = "影视金曲榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/dfbbdafb2b7548c784fc570222a7f5ec.png",
                updateFrequency = "每周更新",
                topSongsPreview = listOf("热播大剧插曲", "大片原声音画")
            ),
            OnlineLeaderboard(
                id = "华语新歌榜",
                platform = platform,
                title = "华语新歌榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/7c4f420cf14643038f451f2b2361a91e.png",
                updateFrequency = "每日更新",
                topSongsPreview = listOf("华语流行新声", "实力歌手力作")
            ),
            OnlineLeaderboard(
                id = "欧美热歌榜",
                platform = platform,
                title = "欧美热歌榜",
                coverUrl = "https://d.music.migu.cn/common/image/20220610/c5be2bb6735e40e69cbfae5c3e4c4ca4.png",
                updateFrequency = "每周更新",
                topSongsPreview = listOf("Billboard同步", "欧美流行精选")
            )
        )
    }

    override suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> =
        withContext(Dispatchers.IO) {
            // 如果是纯数字歌单 ID，调用咪咕歌单详情与歌曲列表接口
            if (playlistId.matches(Regex("^\\d+$"))) {
                try {
                    var title = "咪咕歌单"
                    var coverUrl = ""
                    var creatorName = "咪咕用户"
                    var creatorAvatarUrl: String? = null
                    var description: String? = null
                    var playCount = 0L
                    var expectedTotalCount = 0

                    // 1. 查询歌单完整元数据（标题、封面图、播放量、作者信息）
                    try {
                        val metaUrl = "https://app.pd.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?needAll=0&resourceType=2021&resourceId=$playlistId"
                        val metaRoot = getApi(metaUrl)
                        val resArr = metaRoot.optJSONArray("resource")
                        val metaObj = resArr?.optJSONObject(0)
                        if (metaObj != null) {
                            title = metaObj.optString("title").ifEmpty { title }
                            description = metaObj.optString("summary")
                            creatorName = metaObj.optString("ownerName").ifEmpty { creatorName }
                            creatorAvatarUrl = metaObj.optString("ownerPic").takeIf { it.isNotBlank() }
                            val imgObj = metaObj.optJSONObject("imgItem")
                            val rawCover = imgObj?.optString("img")
                                ?: metaObj.optString("originalImgUrl").ifEmpty { metaObj.optString("pic") }
                            coverUrl = formatMiguImg(rawCover)

                            val opNumObj = metaObj.optJSONObject("opNumItem")
                            playCount = opNumObj?.optString("playNum")?.toLongOrNull()
                                ?: opNumObj?.optLong("playNum", 0L)
                                ?: 0L
                            expectedTotalCount = metaObj.optString("musicNum").toIntOrNull()
                                ?: metaObj.optInt("musicNum", 0)
                        }
                    } catch (_: Exception) {
                    }

                    // 2. 循环分页拉取全部歌曲列表
                    val songs = mutableListOf<OnlineSongItem>()
                    var pageNo = 1
                    val pageSize = 50
                    var hasMore = true

                    while (hasMore && pageNo <= 20) { // 最多拉取 1000 首歌
                        val songUrl = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/user/queryMusicListSongs.do?musicListId=$playlistId&pageNo=$pageNo&pageSize=$pageSize"
                        val root = getApi(songUrl)
                        val total = root.optInt("totalCount", 0)
                        if (expectedTotalCount <= 0 && total > 0) {
                            expectedTotalCount = total
                        }

                        val listArr = root.optJSONArray("list")
                        if (listArr == null || listArr.length() == 0) {
                            hasMore = false
                            break
                        }

                        val pageCount = listArr.length()
                        for (i in 0 until pageCount) {
                            val sObj = listArr.optJSONObject(i) ?: continue
                            val copyrightId = sObj.optString("copyrightId").ifEmpty {
                                sObj.optString("songId").ifEmpty { sObj.optString("contentId") }
                            }
                            val songName = sObj.optString("songName").ifEmpty {
                                sObj.optString("title").ifEmpty { sObj.optString("name") }
                            }
                            val artist = extractArtist(sObj)
                            val album = extractAlbum(sObj)
                            val durationMs = extractDuration(sObj)
                            val songCover = extractCover(sObj, coverUrl)

                            if (copyrightId.isNotEmpty() && songName.isNotEmpty() && songs.none { it.id == copyrightId }) {
                                songs.add(
                                    OnlineSongItem(
                                        id = copyrightId,
                                        platform = platform,
                                        title = songName,
                                        artist = artist,
                                        album = album,
                                        durationMs = durationMs,
                                        coverUrl = songCover,
                                        isVip = parseMiguIsVip(sObj)
                                    )
                                )
                            }
                        }

                        pageNo++
                        if ((expectedTotalCount > 0 && songs.size >= expectedTotalCount) || pageCount < pageSize) {
                            hasMore = false
                        }
                    }

                    if (songs.isNotEmpty()) {
                        val finalCover = coverUrl.ifEmpty { songs.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl ?: "" }
                        val playlist = OnlinePlaylist(
                            id = playlistId,
                            platform = platform,
                            title = title,
                            coverUrl = finalCover,
                            playCount = if (playCount > 0) playCount else 100000L,
                            trackCount = if (expectedTotalCount > 0) expectedTotalCount else songs.size,
                            creatorName = creatorName,
                            creatorAvatarUrl = creatorAvatarUrl,
                            description = description
                        )
                        return@withContext Pair(playlist, songs)
                    }
                } catch (_: Exception) {
                    // 降级使用搜索
                }
            }

            // 榜单或降级：按关键词搜索歌曲
            val keyword = playlistId
            val searchSongs = searchSongsByKeyword(keyword, 100)
            val playlist = OnlinePlaylist(
                id = playlistId,
                platform = platform,
                title = keyword,
                coverUrl = searchSongs.firstOrNull()?.coverUrl ?: "https://d.music.migu.cn/common/image/20220610/b19a16f8880e4cbbaae9b7e71f9cf5fc.png",
                playCount = 680000L,
                trackCount = searchSongs.size,
                creatorName = "咪咕官方",
                description = "咪咕音乐权威推荐"
            )
            Pair(playlist, searchSongs)
        }

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val switchJson = URLEncoder.encode("{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":0,\"mvSong\":0,\"songlist\":0,\"bestShow\":0}", "UTF-8")
            val url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=$encoded&pageNo=$page&pageSize=$pageSize&searchSwitch=$switchJson"
            val root = getApi(url)
            val listArr = root.optJSONObject("songResultData")?.optJSONArray("result") ?: return@withContext emptyList()

            val songs = mutableListOf<OnlineSongItem>()
            for (i in 0 until listArr.length()) {
                val obj = listArr.optJSONObject(i) ?: continue
                val id = obj.optString("copyrightId").ifEmpty { obj.optString("id") }
                val title = obj.optString("name").ifEmpty { obj.optString("songName") }
                val artist = extractArtist(obj)
                val album = extractAlbum(obj)
                val durationMs = extractDuration(obj)
                val cover = extractCover(obj, "")

                val albumId = obj.optJSONArray("albums")?.optJSONObject(0)?.optString("id")
                    ?: obj.optString("albumId")

                if (id.isNotEmpty() && title.isNotEmpty()) {
                    songs.add(
                        OnlineSongItem(
                            id = id,
                            platform = platform,
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = albumId.takeIf { it.isNotBlank() && it != "0" },
                            durationMs = durationMs,
                            coverUrl = cover,
                            isVip = parseMiguIsVip(obj)
                        )
                    )
                }
            }
            songs
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchSongsByKeyword(keyword: String, count: Int): List<OnlineSongItem> {
        return searchSongs(keyword, 1, count)
    }

    private fun extractArtist(obj: JSONObject): String {
        // 1. artists 数组（如 queryMusicListSongs: [{"name": "告五人"}]）
        obj.optJSONArray("artists")?.let { arr ->
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")
                if (!name.isNullOrBlank()) names.add(name.trim())
            }
            if (names.isNotEmpty()) return names.joinToString(" & ")
        }

        // 2. singers 数组（如 search_all: [{"name": "任贤齐"}]）
        obj.optJSONArray("singers")?.let { arr ->
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")
                if (!name.isNullOrBlank()) names.add(name.trim())
            }
            if (names.isNotEmpty()) return names.joinToString(" & ")
        }

        // 3. singerImg Map 对象
        obj.optJSONObject("singerImg")?.let { sImgMap ->
            val keys = sImgMap.keys()
            val names = mutableListOf<String>()
            while (keys.hasNext()) {
                val key = keys.next()
                val sObj = sImgMap.optJSONObject(key)
                val name = sObj?.optString("singerName")
                if (!name.isNullOrBlank()) names.add(name.trim())
            }
            if (names.isNotEmpty()) return names.joinToString(" & ")
        }

        // 4. 直接字段 singer / singerName / artist
        val direct = obj.optString("singerName").ifEmpty {
            obj.optString("singer").ifEmpty {
                obj.optString("artist")
            }
        }
        return if (direct.isNotBlank()) direct.trim() else "未知歌手"
    }

    private fun extractAlbum(obj: JSONObject): String {
        // 1. albums 数组（如 search_all: [{"name": "滚石30精选"}]）
        obj.optJSONArray("albums")?.let { arr ->
            val name = arr.optJSONObject(0)?.optString("name")
            if (!name.isNullOrBlank()) return name.trim()
        }

        // 2. 直接字段 album / albumName
        val albumStr = obj.optString("album").ifEmpty { obj.optString("albumName") }
        if (albumStr.isNotBlank() && albumStr != "null") {
            return albumStr.trim()
        }

        return "单曲"
    }

    private fun extractCover(obj: JSONObject, defaultCover: String): String {
        // 1. 本层 imgItems 数组
        obj.optJSONArray("imgItems")?.let { arr ->
            for (i in 0 until arr.length()) {
                val img = arr.optJSONObject(i)?.optString("img")
                if (!img.isNullOrBlank()) return formatMiguImg(img)
            }
        }

        // 2. albums[0] 内部的 imgItems 或 albumPicUrl
        obj.optJSONArray("albums")?.optJSONObject(0)?.let { albumObj ->
            albumObj.optJSONArray("imgItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val img = arr.optJSONObject(i)?.optString("img")
                    if (!img.isNullOrBlank()) return formatMiguImg(img)
                }
            }
            val albPic = albumObj.optString("albumPicUrl").ifEmpty { albumObj.optString("cover") }
            if (albPic.isNotBlank()) return formatMiguImg(albPic)
        }

        // 3. landscapImg
        val landscape = obj.optString("landscapImg")
        if (landscape.isNotBlank()) return formatMiguImg(landscape)

        // 4. albumPicUrl / cover / imgUrl / pic / largePic / mediumPic
        val albumPic = obj.optString("albumPicUrl").ifEmpty {
            obj.optString("cover").ifEmpty {
                obj.optString("imgUrl").ifEmpty {
                    obj.optString("pic").ifEmpty {
                        obj.optString("largePic").ifEmpty {
                            obj.optString("mediumPic").ifEmpty {
                                obj.optString("smallPic")
                            }
                        }
                    }
                }
            }
        }
        if (albumPic.isNotBlank()) return formatMiguImg(albumPic)

        // 5. singers[0] 内部的 imgItems 或 singerPicUrl
        obj.optJSONArray("singers")?.optJSONObject(0)?.let { singerObj ->
            singerObj.optJSONArray("imgItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val img = arr.optJSONObject(i)?.optString("img")
                    if (!img.isNullOrBlank()) return formatMiguImg(img)
                }
            }
            val sPic = singerObj.optString("singerPicUrl").ifEmpty { singerObj.optString("pic") }
            if (sPic.isNotBlank()) return formatMiguImg(sPic)
        }

        // 6. singerImg 中的 miguImgItems
        obj.optJSONObject("singerImg")?.let { sImgMap ->
            val keys = sImgMap.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val sObj = sImgMap.optJSONObject(key)
                val miguItems = sObj?.optJSONArray("miguImgItems")
                if (miguItems != null && miguItems.length() > 0) {
                    val img = miguItems.optJSONObject(0)?.optString("img")
                    if (!img.isNullOrBlank()) return formatMiguImg(img)
                }
            }
        }

        return defaultCover
    }

    private fun formatMiguImg(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        return trimmed
    }

    private fun extractDuration(obj: JSONObject): Long {
        val durationSec = obj.optLong("duration", 0L)
        if (durationSec > 0) return durationSec * 1000L

        val lengthStr = obj.optString("length") // 如 "00:04:30"
        if (lengthStr.isNotBlank() && lengthStr.contains(":")) {
            val parts = lengthStr.split(":")
            var sec = 0L
            for (part in parts) {
                sec = sec * 60 + (part.toLongOrNull() ?: 0L)
            }
            return sec * 1000L
        }
        return 0L
    }

    override suspend fun searchPlaylists(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val switchJson = URLEncoder.encode("{\"song\":0,\"album\":0,\"singer\":0,\"tagSong\":0,\"mvSong\":0,\"songlist\":1,\"bestShow\":0}", "UTF-8")
            val url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=$encoded&pageNo=$page&pageSize=$pageSize&searchSwitch=$switchJson"
            val root = getApi(url)
            val listArr = root.optJSONObject("songListResultData")?.optJSONArray("result")
                ?: return@withContext fallbackIfEmpty(page)

            val list = mutableListOf<OnlinePlaylist>()
            for (i in 0 until listArr.length()) {
                val obj = listArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifEmpty { obj.optString("musicListId") }
                val title = obj.optString("name").ifEmpty { obj.optString("title") }
                val rawCover = obj.optString("musicListPicUrl")
                    .ifEmpty { obj.optString("img") }
                    .ifEmpty { obj.optString("pic") }
                    .ifEmpty { obj.optString("cover") }
                val cover = formatMiguImg(rawCover)
                val playCount = obj.optString("playNum").toLongOrNull()
                    ?: obj.optLong("playNum", 0L)
                val trackCount = obj.optString("musicNum").toIntOrNull()
                    ?: obj.optString("songNum").toIntOrNull()
                    ?: obj.optInt("musicNum", obj.optInt("songNum", 0))

                if (id.isNotEmpty() && title.isNotEmpty()) {
                    list.add(
                        OnlinePlaylist(
                            id = id,
                            platform = platform,
                            title = title,
                            coverUrl = cover,
                            playCount = playCount,
                            trackCount = trackCount,
                            creatorName = obj.optString("userName").ifEmpty { "咪咕音乐" },
                            description = obj.optString("summary").ifEmpty { obj.optString("intro") }
                        )
                    )
                }
            }
            if (list.isEmpty()) fallbackIfEmpty(page) else list
        } catch (e: Exception) {
            fallbackIfEmpty(page)
        }
    }

    private fun fallbackIfEmpty(page: Int): List<OnlinePlaylist> {
        if (page > 1) return emptyList()
        return listOf(
            OnlinePlaylist("咪咕音乐榜", platform, "咪咕音乐榜", "https://d.music.migu.cn/common/image/20220610/b19a16f8880e4cbbaae9b7e71f9cf5fc.png", 500000L, 100, "咪咕官方", "权威潮流推荐"),
            OnlinePlaylist("咪咕新歌榜", platform, "咪咕新歌榜", "https://d.music.migu.cn/common/image/20220610/9cf95fc0245a4a2cb588fc23f6e1f0e2.png", 320000L, 100, "咪咕官方", "最新热单首发"),
            OnlinePlaylist("咪咕热歌榜", platform, "咪咕热歌榜", "https://d.music.migu.cn/common/image/20220610/ff7d4133405c4ea8a0efd12d59ca7868.png", 880000L, 100, "咪咕官方", "全网飙升传唱"),
            OnlinePlaylist("影视金曲榜", platform, "影视金曲榜", "https://d.music.migu.cn/common/image/20220610/dfbbdafb2b7548c784fc570222a7f5ec.png", 210000L, 100, "咪咕官方", "热播大剧插曲"),
            OnlinePlaylist("华语新歌榜", platform, "华语新歌榜", "https://d.music.migu.cn/common/image/20220610/7c4f420cf14643038f451f2b2361a91e.png", 430000L, 100, "咪咕官方", "华语流行新声"),
            OnlinePlaylist("欧美热歌榜", platform, "欧美热歌榜", "https://d.music.migu.cn/common/image/20220610/c5be2bb6735e40e69cbfae5c3e4c4ca4.png", 150000L, 100, "咪咕官方", "Billboard同步")
        )
    }

    override fun extractPlaylistId(urlOrText: String): String? {
        val trimmed = urlOrText.trim()
        val matcher = Pattern.compile("(?:playlist/|musicListId=|id=|playlistId=)(\\d+)").matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        if (trimmed.matches(Regex("^\\d{4,12}$"))) {
            return trimmed
        }
        return null
    }

    override suspend fun getAlbumDetail(albumId: String): Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId=$albumId&resourceType=2"
        val root = getApi(url)
        val resArr = root.optJSONArray("resource")
        val resObj = if (resArr != null && resArr.length() > 0) {
            resArr.optJSONObject(0) ?: JSONObject()
        } else {
            root.optJSONObject("resource") ?: root.optJSONObject("data") ?: JSONObject()
        }

        val title = resObj.optString("title").ifEmpty { resObj.optString("albumName") }
        val artist = extractArtist(resObj)
        val cover = extractCover(resObj, "")
        val pubTime = resObj.optString("publishTime").ifEmpty { resObj.optString("publishDate") }
        val intro = resObj.optString("summary").ifEmpty { resObj.optString("intro") }

        val songItemsArr = resObj.optJSONArray("songItems") ?: root.optJSONArray("songItems") ?: JSONArray()
        val songs = mutableListOf<OnlineSongItem>()

        for (i in 0 until songItemsArr.length()) {
            val obj = songItemsArr.optJSONObject(i) ?: continue
            val id = obj.optString("copyrightId").ifEmpty { obj.optString("id").ifEmpty { obj.optString("songId") } }
            val songTitle = obj.optString("songName").ifEmpty { obj.optString("name") }
            val songArtist = extractArtist(obj).ifEmpty { artist }
            val songCover = extractCover(obj, cover)
            val durationMs = extractDuration(obj)

            if (id.isNotEmpty() && songTitle.isNotEmpty()) {
                songs.add(
                    OnlineSongItem(
                        id = id,
                        platform = platform,
                        title = songTitle,
                        artist = songArtist,
                        album = title.ifEmpty { "专辑" },
                        albumId = albumId,
                        durationMs = durationMs,
                        coverUrl = songCover,
                        isVip = parseMiguIsVip(obj)
                    )
                )
            }
        }

        val album = com.rawsmusic.module.online.model.OnlineAlbum(
            id = albumId,
            platform = platform,
            title = title.ifEmpty { "专辑 $albumId" },
            coverUrl = cover,
            artist = artist.ifEmpty { "未知歌手" },
            songCount = songs.size,
            publishTime = pubTime,
            description = intro
        )

        Pair(album, songs)
    }
}

