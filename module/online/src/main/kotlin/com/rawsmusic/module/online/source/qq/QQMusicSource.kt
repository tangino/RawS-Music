package com.rawsmusic.module.online.source.qq

import com.rawsmusic.module.online.model.OnlineLeaderboard
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlaylistTag
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.source.OnlinePlatformSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * QQ 音乐音源实现类
 */
class QQMusicSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private var cookieProvider: (() -> String)? = null
) : OnlinePlatformSource {

    override val platform: OnlinePlatform = OnlinePlatform.QQ

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun setCookieProvider(provider: () -> String) {
        this.cookieProvider = provider
    }

    /**
     * 向 QQ 音乐 musicu.fcg 发送通用网关请求
     */
    private suspend fun postMusicU(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("User-Agent", userAgent)
            .header("Referer", "https://y.qq.com/")
            .header("Host", "u.y.qq.com")

        cookieProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }

        val request = requestBuilder.post(body).build()
        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("QQ 音乐网络响应为空")
        JSONObject(bodyStr)
    }

    private fun parseQQIsVip(songObj: JSONObject): Boolean {
        val payObj = songObj.optJSONObject("pay")
        val payPlay = payObj?.optInt("pay_play", 0) ?: songObj.optInt("pay_play", songObj.optInt("payplay", 0))
        val payMonth = payObj?.optInt("pay_month", 0) ?: songObj.optInt("pay_month", 0)
        val fee = songObj.optInt("fee", 0)
        val isVip = songObj.optInt("isvip", songObj.optInt("is_vip", 0))
        return payPlay != 0 || payMonth != 0 || fee == 1 || isVip == 1
    }

    private fun formatQQImg(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (trimmed.startsWith("http://")) return "https://${trimmed.removePrefix("http://")}"
        return trimmed
    }

    override suspend fun getTags(): List<OnlinePlaylistTag> = withContext(Dispatchers.IO) {
        // QQ 音乐分类标签预设与兜底
        listOf(
            OnlinePlaylistTag("10000000", "全部", "默认"),
            OnlinePlaylistTag("165", "流行", "风格"),
            OnlinePlaylistTag("166", "摇滚", "风格"),
            OnlinePlaylistTag("167", "民谣", "风格"),
            OnlinePlaylistTag("168", "电子", "风格"),
            OnlinePlaylistTag("169", "说唱", "风格"),
            OnlinePlaylistTag("170", "轻音乐", "风格"),
            OnlinePlaylistTag("171", "爵士", "风格"),
            OnlinePlaylistTag("172", "古典", "风格"),
            OnlinePlaylistTag("173", "中国风", "风格"),
            OnlinePlaylistTag("174", "古风", "风格"),
            OnlinePlaylistTag("175", "ACG", "风格"),
            OnlinePlaylistTag("176", "影视原声", "主题"),
            OnlinePlaylistTag("177", "治愈", "心情"),
            OnlinePlaylistTag("178", "夜晚", "场景"),
            OnlinePlaylistTag("179", "运动", "场景")
        )
    }

    override suspend fun getPlaylists(tagId: String, page: Int, pageSize: Int): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val categoryId = if (tagId.isEmpty() || tagId == "全部") 10000000 else tagId.toIntOrNull() ?: 10000000
        val sin = (page - 1) * pageSize
        val ein = sin + pageSize - 1

        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
                "?sin=$sin&ein=$ein&categoryId=$categoryId&sortId=5&format=json&inCharset=utf8&outCharset=utf-8"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://y.qq.com/")
            .header("Host", "c.y.qq.com")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: return@withContext emptyList()
        val root = JSONObject(bodyStr)
        val dataObj = root.optJSONObject("data") ?: return@withContext emptyList()
        val listArr = dataObj.optJSONArray("list") ?: JSONArray()
        val result = mutableListOf<OnlinePlaylist>()

        for (i in 0 until listArr.length()) {
            val item = listArr.optJSONObject(i) ?: continue
            val dissid = item.optString("dissid")
            val dissname = item.optString("dissname")
            val imgurl = formatQQImg(item.optString("imgurl").ifEmpty { item.optString("cover").ifEmpty { item.optString("pic") } })
            val listennum = item.optLong("listennum")
            val creatorObj = item.optJSONObject("creator")
            val creatorName = creatorObj?.optString("name")
            val creatorAvatar = creatorObj?.optString("avatarUrl")
            val songNum = item.optInt("song_count")

            result.add(
                OnlinePlaylist(
                    id = dissid,
                    platform = OnlinePlatform.QQ,
                    title = dissname,
                    coverUrl = imgurl,
                    playCount = listennum,
                    trackCount = songNum,
                    creatorName = creatorName,
                    creatorAvatarUrl = creatorAvatar,
                    description = item.optString("introduction")
                )
            )
        }
        result
    }

    override suspend fun getLeaderboards(): List<OnlineLeaderboard> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            val req = JSONObject().apply {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetAll")
                put("param", JSONObject())
            }
            put("req_0", req)
        }

        val root = postMusicU(payload)
        val req0 = root.optJSONObject("req_0") ?: return@withContext emptyList()
        val dataObj = req0.optJSONObject("data") ?: return@withContext emptyList()
        val groupArr = dataObj.optJSONArray("group") ?: JSONArray()
        val result = mutableListOf<OnlineLeaderboard>()

        for (i in 0 until groupArr.length()) {
            val group = groupArr.optJSONObject(i) ?: continue
            val toplistArr = group.optJSONArray("toplist") ?: continue
            for (j in 0 until toplistArr.length()) {
                val item = toplistArr.optJSONObject(j) ?: continue
                val topId = item.optInt("topId").toString()
                val title = item.optString("title")
                val picUrl = item.optString("headPicUrl").ifEmpty { item.optString("frontPicUrl") }
                val update = item.optString("updateTime").ifEmpty { item.optString("period") }
                val songArr = item.optJSONArray("song")
                val previews = mutableListOf<String>()
                if (songArr != null) {
                    for (k in 0 until songArr.length()) {
                        val s = songArr.optJSONObject(k) ?: continue
                        val sTitle = s.optString("title")
                        val singer = s.optString("singerName")
                        if (sTitle.isNotEmpty()) {
                            previews.add("$sTitle - $singer")
                        }
                    }
                }

                result.add(
                    OnlineLeaderboard(
                        id = topId,
                        platform = OnlinePlatform.QQ,
                        title = title,
                        coverUrl = picUrl,
                        updateFrequency = update,
                        topSongsPreview = previews
                    )
                )
            }
        }
        result
    }

    override suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val numericId = playlistId.removePrefix("top_").toIntOrNull()
        // QQ 音乐官方排行榜 ID 通常为较小的数字 (如 4, 26, 27, 62 等)，普通歌单 disstid 通常为 10 位数
        if (playlistId.startsWith("top_") || (numericId != null && numericId < 100000)) {
            try {
                return@withContext getLeaderboardDetailInternal(numericId ?: playlistId.toInt())
            } catch (e: Exception) {
                // 若排行榜解析失败，继续尝试普通歌单
            }
        }

        val cleanDissId = playlistId.removePrefix("dir_")
        val activeCookie = cookieProvider?.invoke()?.takeIf { it.isNotBlank() } ?: "uin=0; qm_keyst=;"
        val uin = Regex("uin=([0-9]+)").find(activeCookie)?.groupValues?.getOrNull(1) ?: "0"

        try {
            val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                    "?type=1&json=1&utf8=1&onlysong=0&disstid=$cleanDissId&dirid=$cleanDissId&format=json&inCharset=utf8&outCharset=utf-8&g_tk=5381&hostUin=$uin&loginUin=$uin"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://y.qq.com/n/ryqq/playlist/$cleanDissId")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", activeCookie)
                .header("Host", "c.y.qq.com")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: throw IllegalStateException("QQ 歌单详情响应为空")
            val root = JSONObject(bodyStr)
            val cdlistArr = root.optJSONArray("cdlist")
            if (cdlistArr == null || cdlistArr.length() == 0) {
                // 若旧版 qzone 接口未能返回 cdlist，自动降级切换至 musicu.fcg 网关接口
                return@withContext getPlaylistDetailViaMusicU(cleanDissId)
            }
            val cdObj = cdlistArr.optJSONObject(0) ?: return@withContext getPlaylistDetailViaMusicU(cleanDissId)

            val dissid = cdObj.optString("disstid", cleanDissId)
            val dissname = cdObj.optString("dissname")
            val logo = formatQQImg(cdObj.optString("logo"))
            val desc = cdObj.optString("desc")
            val visitnum = cdObj.optLong("visitnum")
            val nickname = cdObj.optString("nickname")
            val headurl = formatQQImg(cdObj.optString("headurl"))
            val totalSongNum = cdObj.optInt("total_song_num")

            val playlist = OnlinePlaylist(
                id = dissid,
                platform = OnlinePlatform.QQ,
                title = dissname,
                coverUrl = logo,
                playCount = visitnum,
                trackCount = totalSongNum,
                creatorName = nickname,
                creatorAvatarUrl = headurl,
                description = desc
            )

            val songlistArr = cdObj.optJSONArray("songlist") ?: JSONArray()
            val songs = mutableListOf<OnlineSongItem>()

            for (i in 0 until songlistArr.length()) {
                val songObj = songlistArr.optJSONObject(i) ?: continue
                val songMid = songObj.optString("songmid")
                val songName = songObj.optString("songname")
                val interval = songObj.optLong("interval") * 1000 // 转换为毫秒
                val albumMid = songObj.optString("albummid")
                val albumName = songObj.optString("albumname")
                val isVip = parseQQIsVip(songObj)

                // 歌手列表
                val singerArr = songObj.optJSONArray("singer")
                val singers = mutableListOf<String>()
                if (singerArr != null) {
                    for (s in 0 until singerArr.length()) {
                        val sObj = singerArr.optJSONObject(s)
                        val sName = sObj?.optString("name")
                        if (!sName.isNullOrEmpty()) singers.add(sName)
                    }
                }
                val singerStr = if (singers.isEmpty()) "未知歌手" else singers.joinToString(", ")

                // 专辑封面 (QQ音乐标准专辑封面 URL 模版)
                val coverUrl = if (albumMid.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000${albumMid}.jpg"
                } else {
                    null
                }

                songs.add(
                    OnlineSongItem(
                        id = songMid,
                        platform = OnlinePlatform.QQ,
                        title = songName,
                        artist = singerStr,
                        album = albumName,
                        durationMs = interval,
                        coverUrl = coverUrl,
                        isVip = isVip
                    )
                )
            }

            val finalCoverUrl = if (logo.isNotEmpty()) logo else songs.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl ?: ""
            Pair(playlist.copy(coverUrl = finalCoverUrl), songs)
        } catch (e: Exception) {
            try {
                getPlaylistDetailViaMusicU(cleanDissId)
            } catch (e2: Exception) {
                if (numericId != null) {
                    getLeaderboardDetailInternal(numericId)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * 通过 QQ 音乐官方 musicu.fcg 网关获取歌单详情 (支持全量与现代歌单)
     */
    private suspend fun getPlaylistDetailViaMusicU(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val cleanId = playlistId.removePrefix("dir_")
        val dissIdNum = cleanId.toLongOrNull() ?: throw IllegalArgumentException("非法歌单 ID: $playlistId")
        val activeCookie = cookieProvider?.invoke()?.takeIf { it.isNotBlank() } ?: ""
        val uinLong = Regex("uin=([0-9]+)").find(activeCookie)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val authst = Regex("(?:qm_keyst|qqmusic_key|authst)=([A-Za-z0-9_-]+)").find(activeCookie)?.groupValues?.getOrNull(1) ?: ""

        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("cv", 4747474)
                put("ct", 24)
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", 0)
                put("platform", "yqq.json")
                put("needNewCode", 1)
                if (uinLong > 0L) put("uin", uinLong)
                if (authst.isNotBlank()) put("authst", authst)
                put("tmeLoginType", 2)
            })
            put("req_0", JSONObject().apply {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                put("param", JSONObject().apply {
                    put("disstid", dissIdNum)
                    put("dirid", dissIdNum)
                    put("userinfo", 1)
                    put("tag", 1)
                    put("order", 1)
                    put("song_begin", 0)
                    put("song_num", 1000)
                    if (uinLong > 0L) put("uin", uinLong)
                })
            })
        }

        val root = postMusicU(payload)
        val req0 = root.optJSONObject("req_0") ?: throw IllegalStateException("未能解析到 QQ 网关数据")
        val dataObj = req0.optJSONObject("data") ?: throw IllegalStateException("QQ 歌单网关响应为空")
        val dirinfo = dataObj.optJSONObject("dirinfo") ?: dataObj

        val title = dirinfo.optString("title").ifEmpty { dirinfo.optString("dissname") }.ifEmpty { "QQ 音乐精选歌单" }
        val logo = formatQQImg(dirinfo.optString("picurl").ifEmpty { dirinfo.optString("logo") })
        val desc = dirinfo.optString("desc")
        val listenNum = dirinfo.optLong("listennum")
        val nickname = dirinfo.optString("nickname")
        val totalSongNum = dirinfo.optInt("songnum")

        val playlist = OnlinePlaylist(
            id = playlistId,
            platform = OnlinePlatform.QQ,
            title = title,
            coverUrl = logo,
            playCount = listenNum,
            trackCount = totalSongNum,
            creatorName = nickname,
            creatorAvatarUrl = null,
            description = desc
        )

        val songlistArr = dataObj.optJSONArray("songlist") ?: JSONArray()
        val songs = mutableListOf<OnlineSongItem>()

        for (i in 0 until songlistArr.length()) {
            val songObj = songlistArr.optJSONObject(i) ?: continue
            val songMid = songObj.optString("mid")
                .ifEmpty { songObj.optString("songmid") }
                .ifEmpty { songObj.optLong("id").toString() }
            val songName = songObj.optString("name")
                .ifEmpty { songObj.optString("title") }
                .ifEmpty { songObj.optString("songname") }
            val interval = songObj.optLong("interval") * 1000L

            val albumObj = songObj.optJSONObject("album")
            val albumMid = albumObj?.optString("mid") ?: songObj.optString("albummid")
            val albumName = albumObj?.optString("name") ?: songObj.optString("albumname")

            val singerNameField = songObj.optString("singerName").ifEmpty { songObj.optString("singer_name") }
            val singerArr = songObj.optJSONArray("singer")
            val singers = mutableListOf<String>()
            if (singerNameField.isNotEmpty()) {
                singers.add(singerNameField)
            } else if (singerArr != null) {
                for (s in 0 until singerArr.length()) {
                    val sObj = singerArr.optJSONObject(s)
                    val sName = sObj?.optString("name") ?: sObj?.optString("title")
                    if (!sName.isNullOrEmpty()) singers.add(sName)
                }
            }
            val singerStr = if (singers.isEmpty()) "未知歌手" else singers.joinToString(", ")

            val cover = songObj.optString("cover")
            val coverUrl = if (cover.isNotEmpty()) {
                cover
            } else if (albumMid.isNotEmpty()) {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000${albumMid}.jpg"
            } else {
                null
            }

            val isVip = parseQQIsVip(songObj)

            songs.add(
                OnlineSongItem(
                    id = songMid,
                    platform = OnlinePlatform.QQ,
                    title = songName,
                    artist = singerStr,
                    album = albumName,
                    durationMs = interval,
                    coverUrl = coverUrl,
                    isVip = isVip
                )
            )
        }

        val finalCoverUrl = if (logo.isNotEmpty()) logo else songs.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl ?: ""
        Pair(playlist.copy(coverUrl = finalCoverUrl), songs)
    }

    private suspend fun getLeaderboardDetailInternal(topId: Int): Pair<OnlinePlaylist, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("cv", 4747474)
                put("ct", 24)
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", 0)
                put("platform", "yqq.json")
                put("needNewCode", 1)
            })
            put("req_0", JSONObject().apply {
                put("module", "musicToplist.ToplistInfoServer")
                put("method", "GetDetail")
                put("param", JSONObject().apply {
                    put("topId", topId)
                    put("offset", 0)
                    put("num", 100)
                    put("period", "")
                })
            })
        }

        val root = postMusicU(payload)
        val req0 = root.optJSONObject("req_0") ?: throw IllegalStateException("未能解析到 QQ 榜单数据")
        val dataObj = req0.optJSONObject("data") ?: throw IllegalStateException("QQ 榜单数据为空")
        val dataData = dataObj.optJSONObject("data") ?: dataObj

        val title = dataData.optString("title").ifEmpty { "QQ 音乐排行榜" }
        val headPic = formatQQImg(dataData.optString("headPicUrl").ifEmpty { dataData.optString("frontPicUrl") })
        val desc = dataData.optString("intro").ifEmpty { dataData.optString("period") }
        val totalNum = dataData.optInt("totalNum")

        println("QQ Leaderboard detail data keys: ${dataData.keys().asSequence().toList()}")

        val playlist = OnlinePlaylist(
            id = topId.toString(),
            platform = OnlinePlatform.QQ,
            title = title,
            coverUrl = headPic,
            playCount = 0L,
            trackCount = if (totalNum > 0) totalNum else 100,
            creatorName = "QQ 音乐官方",
            creatorAvatarUrl = null,
            description = desc
        )

        val songInfoList = dataData.optJSONArray("songInfoList")
            ?: dataData.optJSONArray("song_list")
            ?: dataData.optJSONArray("songList")
            ?: dataData.optJSONArray("list")
            ?: dataData.optJSONArray("song")
            ?: JSONArray()
        val songs = mutableListOf<OnlineSongItem>()

        for (i in 0 until songInfoList.length()) {
            val songObj = songInfoList.optJSONObject(i) ?: continue
            val songMid = songObj.optString("songMid")
                .ifEmpty { songObj.optString("mid") }
                .ifEmpty { songObj.optLong("songId").toString() }
            val songName = songObj.optString("title")
                .ifEmpty { songObj.optString("name") }
            val interval = songObj.optLong("interval") * 1000L

            val albumObj = songObj.optJSONObject("album")
            val albumMid = songObj.optString("albumMid")
                .ifEmpty { albumObj?.optString("mid") ?: "" }
            val albumName = songObj.optString("albumName")
                .ifEmpty { albumObj?.optString("name") ?: "" }

            val singerNameField = songObj.optString("singerName")
                .ifEmpty { songObj.optString("singer_name") }
            val singerArr = songObj.optJSONArray("singer")
            val singers = mutableListOf<String>()
            if (singerNameField.isNotEmpty()) {
                singers.add(singerNameField)
            } else if (singerArr != null) {
                for (s in 0 until singerArr.length()) {
                    val sObj = singerArr.optJSONObject(s)
                    val sName = sObj?.optString("name") ?: sObj?.optString("title")
                    if (!sName.isNullOrEmpty()) singers.add(sName)
                }
            }
            val singerStr = if (singers.isEmpty()) "未知歌手" else singers.joinToString(", ")

            val cover = songObj.optString("cover")
            val coverUrl = if (cover.isNotEmpty()) {
                cover
            } else if (albumMid.isNotEmpty()) {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000${albumMid}.jpg"
            } else {
                null
            }

            val isVip = parseQQIsVip(songObj)

            songs.add(
                OnlineSongItem(
                    id = songMid,
                    platform = OnlinePlatform.QQ,
                    title = songName,
                    artist = singerStr,
                    album = albumName,
                    durationMs = interval,
                    coverUrl = coverUrl,
                    isVip = isVip
                )
            )
        }

        val finalCoverUrl = if (headPic.isNotEmpty()) headPic else songs.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl ?: ""
        Pair(playlist.copy(coverUrl = finalCoverUrl), songs)
    }

    override suspend fun searchPlaylists(keyword: String, page: Int, pageSize: Int): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", "19")
                put("cv", "1873")
                put("uin", "0")
            })
            put("req", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("query", keyword)
                    put("search_type", 3) // 3 代表歌单搜索 (0: 单曲, 1: 歌手, 2: 专辑, 3: 歌单)
                    put("num_per_page", pageSize)
                    put("page_num", page)
                    put("highlight", 1)
                    put("grp", 1)
                })
            })
        }

        try {
            val root = postMusicU(payload)
            val reqObj = root.optJSONObject("req") ?: return@withContext emptyList()
            val dataObj = reqObj.optJSONObject("data") ?: return@withContext emptyList()
            val bodyObj = dataObj.optJSONObject("body") ?: return@withContext emptyList()
            val songlistObj = bodyObj.optJSONObject("songlist") ?: return@withContext emptyList()
            val listArr = songlistObj.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<OnlinePlaylist>()

            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val dissid = item.optString("dissid").ifEmpty { item.optString("docid") }
                val dissname = item.optString("dissname").ifEmpty { item.optString("dissname_hilight") }
                val imgurl = item.optString("imgurl")
                val listennum = item.optLong("listennum")
                val creatorObj = item.optJSONObject("creator")
                val creatorName = creatorObj?.optString("name")
                val creatorAvatar = creatorObj?.optString("avatarUrl")
                val songCount = item.optInt("song_count")
                val intro = item.optString("introduction")

                list.add(
                    OnlinePlaylist(
                        id = dissid,
                        platform = OnlinePlatform.QQ,
                        title = dissname,
                        coverUrl = imgurl,
                        playCount = listennum,
                        trackCount = songCount,
                        creatorName = creatorName,
                        creatorAvatarUrl = creatorAvatar,
                        description = intro
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
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("ct", "19")
                put("cv", "1873")
                put("uin", "0")
            })
            put("search", JSONObject().apply {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                put("param", JSONObject().apply {
                    put("query", keyword)
                    put("search_type", 0) // 0: 单曲搜索
                    put("num_per_page", pageSize)
                    put("page_num", page)
                    put("highlight", 1)
                })
            })
        }

        val list = mutableListOf<OnlineSongItem>()

        try {
            val root = postMusicU(payload)
            val listArr = root.optJSONObject("search")?.optJSONObject("data")?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
                ?: root.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
                ?: JSONArray()
            for (i in 0 until listArr.length()) {
                val sObj = listArr.optJSONObject(i) ?: continue
                val songMid = sObj.optString("mid").ifEmpty { sObj.optString("songmid") }
                var songName = sObj.optString("name").ifEmpty { sObj.optString("title") }
                if (songName.isEmpty()) songName = sObj.optString("songname")

                val singerArr = sObj.optJSONArray("singer")
                val singerNames = mutableListOf<String>()
                if (singerArr != null) {
                    for (j in 0 until singerArr.length()) {
                        val s = singerArr.optJSONObject(j) ?: continue
                        val sName = s.optString("name")
                        if (sName.isNotEmpty()) {
                            singerNames.add(sName)
                        }
                    }
                }

                val albumObj = sObj.optJSONObject("album")
                val albumName = albumObj?.optString("name") ?: sObj.optString("albumname")
                val albumMid = albumObj?.optString("mid")?.ifEmpty { albumObj.optString("pmid") }
                    ?: sObj.optString("albummid")

                var coverUrl = ""
                if (albumMid.isNotEmpty() && albumMid != "00000000000000" && albumMid != "0") {
                    coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                }
                if (coverUrl.isEmpty() && singerArr != null && singerArr.length() > 0) {
                    val firstSingerMid = singerArr.optJSONObject(0)?.optString("mid")
                        ?: singerArr.optJSONObject(0)?.optString("pmid") ?: ""
                    if (firstSingerMid.isNotEmpty() && firstSingerMid != "00000000000000" && firstSingerMid != "0") {
                        coverUrl = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$firstSingerMid.jpg"
                    }
                }
                if (coverUrl.isEmpty()) {
                    val vsPic = sObj.optString("pic").ifEmpty { sObj.optString("image") }
                    if (vsPic.isNotEmpty()) {
                        coverUrl = vsPic
                    }
                }

                val interval = sObj.optLong("interval", 0L) * 1000L
                val isVip = parseQQIsVip(sObj)

                if (songMid.isNotEmpty() && songName.isNotEmpty()) {
                    list.add(
                        OnlineSongItem(
                            id = songMid,
                            platform = platform,
                            title = songName,
                            artist = if (singerNames.isNotEmpty()) singerNames.joinToString(", ") else "未知歌手",
                            album = albumName.ifEmpty { "单曲" },
                            albumId = albumMid.takeIf { it.isNotBlank() && it != "00000000000000" && it != "0" },
                            durationMs = interval,
                            coverUrl = coverUrl,
                            isVip = isVip
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        if (list.isEmpty()) {
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$encoded&p=$page&n=$pageSize&format=json&t=0"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://y.qq.com/")
                    .build()
                val resp = client.newCall(req).execute().body?.string()
                if (resp != null) {
                    val root = JSONObject(resp)
                    val sArr = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                    if (sArr != null) {
                        for (i in 0 until sArr.length()) {
                            val sObj = sArr.optJSONObject(i) ?: continue
                            val songMid = sObj.optString("songmid").ifEmpty { sObj.optString("mid") }
                            val songName = sObj.optString("songname").ifEmpty { sObj.optString("name") }
                            val singerArr = sObj.optJSONArray("singer")
                            val singerNames = mutableListOf<String>()
                            if (singerArr != null) {
                                for (j in 0 until singerArr.length()) {
                                    singerArr.optJSONObject(j)?.optString("name")?.let { singerNames.add(it) }
                                }
                            }
                            val albumName = sObj.optString("albumname")
                            val albumMid = sObj.optString("albummid")
                            var coverUrl = if (albumMid.isNotEmpty() && albumMid != "00000000000000" && albumMid != "0") {
                                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                            } else ""
                            if (coverUrl.isEmpty() && singerArr != null && singerArr.length() > 0) {
                                val firstMid = singerArr.optJSONObject(0)?.optString("mid") ?: ""
                                if (firstMid.isNotEmpty() && firstMid != "00000000000000" && firstMid != "0") {
                                    coverUrl = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$firstMid.jpg"
                                }
                            }
                            val interval = sObj.optLong("interval", 0L) * 1000L
                            val isVip = parseQQIsVip(sObj)

                            if (songMid.isNotEmpty() && songName.isNotEmpty()) {
                                list.add(
                                    OnlineSongItem(
                                        id = songMid,
                                        platform = platform,
                                        title = songName,
                                        artist = if (singerNames.isNotEmpty()) singerNames.joinToString(", ") else "未知歌手",
                                        album = albumName.ifEmpty { "单曲" },
                                        albumId = albumMid.takeIf { it.isNotBlank() && it != "00000000000000" && it != "0" },
                                        durationMs = interval,
                                        coverUrl = coverUrl,
                                        isVip = isVip
                                    )
                                )
                            }
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
        // 匹配 id=123456 或 playlist/123456 或 taoge.html?id=123456
        val matcher = Pattern.compile("(?:id=|playlist/|disstid=)(\\d+)").matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    override suspend fun getArtistCategories(): List<com.rawsmusic.module.online.model.OnlineArtistCategory> {
        return listOf(
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "all_all_all", name = "全部热门"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "cn_man_all", name = "华语男歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "cn_woman_all", name = "华语女歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "cn_team_all", name = "华语组合"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "eu_man_all", name = "欧美男歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "eu_woman_all", name = "欧美女歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "eu_team_all", name = "欧美组合"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "k_man_all", name = "韩国男歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "k_woman_all", name = "韩国女歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "k_team_all", name = "韩国组合"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "j_man_all", name = "日本男歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "j_woman_all", name = "日本女歌手"),
            com.rawsmusic.module.online.model.OnlineArtistCategory(id = "j_team_all", name = "日本组合")
        )
    }

    override suspend fun getArtists(
        category: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineArtist> = withContext(Dispatchers.IO) {
        try {
            // key 格式: area_sex_genre, 默认 all_all_all
            val key = if (category.isNotBlank() && category != "全部") category else "all_all_all"
            val url = "https://c.y.qq.com/v8/fcg-bin/v8.fcg?channel=singer&page=list&key=$key&pagesize=$pageSize&pagenum=$page&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://y.qq.com/")
                .build()

            val res = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val dataObj = JSONObject(res).optJSONObject("data")
            val listArr = dataObj?.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<com.rawsmusic.module.online.model.OnlineArtist>()

            for (i in 0 until listArr.length()) {
                val sObj = listArr.optJSONObject(i) ?: continue
                val singerMid = sObj.optString("Fsinger_mid").ifEmpty { sObj.optString("singer_mid") }
                val singerName = sObj.optString("Fsinger_name").ifEmpty { sObj.optString("singer_name") }
                val pic = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg"

                if (singerMid.isNotEmpty() && singerName.isNotEmpty()) {
                    list.add(
                        com.rawsmusic.module.online.model.OnlineArtist(
                            id = singerMid,
                            platform = platform,
                            name = singerName,
                            avatarUrl = pic
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun searchArtists(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineArtist> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", "19")
                    put("cv", "1873")
                    put("uin", "0")
                })
                put("search", JSONObject().apply {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", JSONObject().apply {
                        put("query", keyword)
                        put("search_type", 1) // 1 代表搜索歌手
                        put("num_per_page", pageSize)
                        put("page_num", page)
                    })
                })
            }

            val root = postMusicU(payload)
            val listArr = root.optJSONObject("search")?.optJSONObject("data")?.optJSONObject("body")?.optJSONObject("singer")?.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<com.rawsmusic.module.online.model.OnlineArtist>()

            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val mid = item.optString("singerMID").ifEmpty { item.optString("singer_mid").ifEmpty { item.optString("mid") } }
                val name = item.optString("singerName").ifEmpty { item.optString("singer_name").ifEmpty { item.optString("name") } }
                var pic = item.optString("singerPic").ifEmpty { item.optString("singer_pic").ifEmpty { item.optString("pic") } }
                if (pic.isBlank() && mid.isNotEmpty()) {
                    pic = "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"
                }
                val songCount = item.optInt("songNum", item.optInt("song_count", 0))
                val albumCount = item.optInt("albumNum", item.optInt("album_count", 0))
                val mvCount = item.optInt("mvNum", item.optInt("mv_count", 0))

                if (mid.isNotEmpty() && name.isNotEmpty()) {
                    list.add(
                        com.rawsmusic.module.online.model.OnlineArtist(
                            id = mid,
                            platform = platform,
                            name = name,
                            avatarUrl = pic,
                            songCount = songCount,
                            albumCount = albumCount,
                            mvCount = mvCount
                        )
                    )
                }
            }

            if (list.isEmpty() && page == 1) {
                // 兜底通过智能联想直达
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val smartUrl = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=$encoded&format=json&inCharset=utf8&outCharset=utf-8"
                val smartReq = Request.Builder().url(smartUrl).header("User-Agent", userAgent).header("Referer", "https://y.qq.com/").build()
                val smartRes = client.newCall(smartReq).execute().body?.string()
                if (!smartRes.isNullOrBlank()) {
                    val clean = if (smartRes.startsWith("callback(") && smartRes.endsWith(")")) smartRes.substring(9, smartRes.length - 1) else smartRes
                    val smartSingers = JSONObject(clean).optJSONObject("data")?.optJSONObject("singer")?.optJSONArray("itemlist")
                    if (smartSingers != null) {
                        for (j in 0 until smartSingers.length()) {
                            val sObj = smartSingers.optJSONObject(j) ?: continue
                            val mid = sObj.optString("mid")
                            val name = sObj.optString("name")
                            val pic = sObj.optString("pic").ifEmpty { "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg" }
                            if (mid.isNotEmpty() && name.isNotEmpty()) {
                                list.add(
                                    com.rawsmusic.module.online.model.OnlineArtist(
                                        id = mid,
                                        platform = platform,
                                        name = name,
                                        avatarUrl = pic
                                    )
                                )
                            }
                        }
                    }
                }
            }

            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtistDetail(artistId: String): com.rawsmusic.module.online.model.OnlineArtistDetail = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("comm", JSONObject().apply {
                put("cv", 4747474)
                put("ct", 24)
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
                put("notice", 0)
                put("platform", "yqq.json")
                put("needNewCode", 1)
                put("uin", 0)
            })
            put("singerDetail", JSONObject().apply {
                put("module", "music.web_singer_info_svr")
                put("method", "get_singer_detail_info")
                put("param", JSONObject().apply {
                    put("singermid", artistId)
                })
            })
        }

        try {
            val root = postMusicU(payload)
            val dataObj = root.optJSONObject("singerDetail")?.optJSONObject("data")
            val singerInfo = dataObj?.optJSONObject("singer_info")
            val singerBrief = dataObj?.optString("singer_brief")
            val artistName = singerInfo?.optString("name")?.ifEmpty { null } ?: "歌手 $artistId"
            val avatarUrl = "https://y.gtimg.cn/music/photo_new/T001R500x500M000$artistId.jpg"
            val totalSong = dataObj?.optInt("total_song", 0) ?: 0
            val totalAlbum = dataObj?.optInt("total_album", 0) ?: 0
            val totalMv = dataObj?.optInt("total_mv", 0) ?: 0
            val fansCount = singerInfo?.optLong("fans", 0L) ?: 0L

            val songlistArr = dataObj?.optJSONArray("songlist") ?: JSONArray()
            val songs = mutableListOf<OnlineSongItem>()
            val albumMap = mutableMapOf<String, com.rawsmusic.module.online.model.OnlineAlbum>()

            for (i in 0 until songlistArr.length()) {
                val sObj = songlistArr.optJSONObject(i) ?: continue
                val songMid = sObj.optString("mid").ifEmpty { sObj.optString("songmid") }
                val songName = sObj.optString("name").ifEmpty { sObj.optString("title") }
                val singerArr = sObj.optJSONArray("singer")
                val singerNames = mutableListOf<String>()
                if (singerArr != null) {
                    for (j in 0 until singerArr.length()) {
                        singerArr.optJSONObject(j)?.optString("name")?.let { singerNames.add(it) }
                    }
                }
                val albumObj = sObj.optJSONObject("album")
                val albumName = albumObj?.optString("name") ?: ""
                val albumMid = albumObj?.optString("mid") ?: ""
                val albumPubTime = albumObj?.optString("time_public") ?: ""
                val coverUrl = if (albumMid.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                } else {
                    avatarUrl
                }
                val interval = sObj.optLong("interval", 0L) * 1000L
                val isVip = parseQQIsVip(sObj)

                if (songMid.isNotEmpty() && songName.isNotEmpty()) {
                    songs.add(
                        OnlineSongItem(
                            id = songMid,
                            platform = platform,
                            title = songName,
                            artist = if (singerNames.isNotEmpty()) singerNames.joinToString(", ") else artistName,
                            album = albumName,
                            durationMs = interval,
                            coverUrl = coverUrl,
                            isVip = isVip
                        )
                    )
                }

                if (albumMid.isNotEmpty() && albumName.isNotEmpty() && !albumMap.containsKey(albumMid)) {
                    albumMap[albumMid] = com.rawsmusic.module.online.model.OnlineAlbum(
                        id = albumMid,
                        platform = platform,
                        title = albumName,
                        coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg",
                        artist = artistName,
                        artistId = artistId,
                        songCount = 1,
                        publishTime = albumPubTime
                    )
                }
            }

            // 获取官方专辑列表（第一页）
            val officialAlbums = getArtistOfficialAlbums(artistId, artistName, 1, 30)
            val finalAlbums = if (officialAlbums.isNotEmpty()) {
                officialAlbums
            } else {
                albumMap.values.toList()
            }

            val artist = com.rawsmusic.module.online.model.OnlineArtist(
                id = artistId,
                platform = platform,
                name = artistName,
                avatarUrl = avatarUrl,
                songCount = if (totalSong > 0) totalSong else songs.size,
                albumCount = if (totalAlbum > 0) totalAlbum else finalAlbums.size,
                mvCount = totalMv,
                fansCount = fansCount,
                description = singerBrief
            )

            com.rawsmusic.module.online.model.OnlineArtistDetail(
                artist = artist,
                hotSongs = songs,
                albums = finalAlbums
            )
        } catch (_: Exception) {
            com.rawsmusic.module.online.model.OnlineArtistDetail(
                artist = com.rawsmusic.module.online.model.OnlineArtist(
                    id = artistId,
                    platform = platform,
                    name = "歌手 $artistId",
                    avatarUrl = "https://y.gtimg.cn/music/photo_new/T001R500x500M000$artistId.jpg"
                )
            )
        }
    }

    /**
     * 调用 QQ 音乐官方歌手专辑列表接口（精准获取歌手名下全部录音室/现场/EP专辑）
     */
    private suspend fun getArtistOfficialAlbums(
        artistId: String,
        artistName: String,
        page: Int = 1,
        pageSize: Int = 30
    ): List<com.rawsmusic.module.online.model.OnlineAlbum> = withContext(Dispatchers.IO) {
        try {
            val begin = (page - 1) * pageSize
            val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singermid=$artistId&order=time&begin=$begin&num=$pageSize&songstatus=1&format=json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Referer", "https://y.qq.com/")
                .build()

            val res = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val dataObj = JSONObject(res).optJSONObject("data")
            val listArr = dataObj?.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<com.rawsmusic.module.online.model.OnlineAlbum>()

            for (i in 0 until listArr.length()) {
                val item = listArr.optJSONObject(i) ?: continue
                val albMid = item.optString("albumMID").ifEmpty { item.optString("mid") }
                val albName = item.optString("albumName").ifEmpty { item.optString("name") }
                val singerName = item.optString("singerName").ifEmpty { artistName }
                val pubTime = item.optString("pubTime").ifEmpty { item.optString("publish_date") }
                val latestSong = item.optJSONObject("latest_song")
                val songCount = latestSong?.optInt("song_count", 0) ?: item.optInt("song_count", 0)
                val cover = if (albMid.isNotEmpty()) "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albMid.jpg" else ""

                if (albMid.isNotEmpty() && albName.isNotEmpty()) {
                    list.add(
                        com.rawsmusic.module.online.model.OnlineAlbum(
                            id = albMid,
                            platform = platform,
                            title = albName,
                            coverUrl = cover,
                            artist = singerName,
                            artistId = artistId,
                            songCount = songCount,
                            publishTime = pubTime
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun searchSongsByArtist(
        artistName: String,
        page: Int = 1,
        pageSize: Int = 30
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", "19")
                    put("cv", "1873")
                    put("uin", "0")
                })
                put("search", JSONObject().apply {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", JSONObject().apply {
                        put("query", artistName)
                        put("search_type", 0) // 0: 单曲搜索
                        put("num_per_page", pageSize)
                        put("page_num", page)
                        put("highlight", 1)
                    })
                })
            }
            val root = postMusicU(payload)
            val listArr = root.optJSONObject("search")?.optJSONObject("data")?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
            val list = mutableListOf<OnlineSongItem>()
            for (i in 0 until listArr.length()) {
                val sObj = listArr.optJSONObject(i) ?: continue
                val songMid = sObj.optString("mid").ifEmpty { sObj.optString("songmid") }
                val songName = sObj.optString("name").ifEmpty { sObj.optString("title") }
                val singerArr = sObj.optJSONArray("singer")
                val singerNames = mutableListOf<String>()
                var isMatchedArtist = false
                if (singerArr != null) {
                    for (j in 0 until singerArr.length()) {
                        val s = singerArr.optJSONObject(j) ?: continue
                        val sName = s.optString("name")
                        if (sName.isNotEmpty()) {
                            singerNames.add(sName)
                            if (sName.equals(artistName, ignoreCase = true) || sName.contains(artistName, ignoreCase = true)) {
                                isMatchedArtist = true
                            }
                        }
                    }
                }
                if (!isMatchedArtist) continue

                val albumObj = sObj.optJSONObject("album")
                val albumName = albumObj?.optString("name") ?: ""
                val albumMid = albumObj?.optString("mid") ?: ""
                val coverUrl = if (albumMid.isNotEmpty()) {
                    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
                } else ""
                val interval = sObj.optLong("interval", 0L) * 1000L
                val isVip = parseQQIsVip(sObj)

                if (songMid.isNotEmpty() && songName.isNotEmpty()) {
                    list.add(
                        OnlineSongItem(
                            id = songMid,
                            platform = platform,
                            title = songName,
                            artist = if (singerNames.isNotEmpty()) singerNames.joinToString(", ") else artistName,
                            album = albumName,
                            durationMs = interval,
                            coverUrl = coverUrl,
                            isVip = isVip
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getArtistSongs(
        artistId: String,
        page: Int,
        pageSize: Int
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        val detail = getArtistDetail(artistId)
        val artistName = detail.artist.name
        if (page == 1) {
            val searched = if (artistName.isNotBlank() && !artistName.startsWith("歌手")) {
                searchSongsByArtist(artistName, 1, pageSize)
            } else emptyList()
            val combined = (detail.hotSongs + searched).distinctBy { it.id }
            if (combined.isNotEmpty()) return@withContext combined
        }
        if (artistName.isNotBlank() && !artistName.startsWith("歌手")) {
            val searched = searchSongsByArtist(artistName, page, pageSize)
            if (searched.isNotEmpty()) {
                return@withContext searched
            }
        }
        if (page == 1) detail.hotSongs else emptyList()
    }

    override suspend fun getArtistAlbums(
        artistId: String,
        page: Int,
        pageSize: Int
    ): List<com.rawsmusic.module.online.model.OnlineAlbum> = withContext(Dispatchers.IO) {
        val detail = getArtistDetail(artistId)
        val artistName = detail.artist.name
        val official = getArtistOfficialAlbums(artistId, artistName, page, pageSize)
        if (official.isNotEmpty()) {
            return@withContext official
        }
        if (page == 1) detail.albums else emptyList()
    }

    override suspend fun getAlbumDetail(albumId: String): Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg?albummid=$albumId&format=json"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "https://y.qq.com/")
            .build()

        val res = client.newCall(req).execute().body?.string() ?: throw IllegalStateException("QQ 音乐专辑响应为空")
        val cleanJson = if (res.startsWith("callback(") && res.endsWith(")")) {
            res.substring(9, res.length - 1)
        } else res

        val root = JSONObject(cleanJson)
        val dataObj = root.optJSONObject("data") ?: throw IllegalStateException("专辑数据不存在")
        val albumName = dataObj.optString("name")
        val singerName = dataObj.optString("singername")
        val cover = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumId.jpg"
        val desc = dataObj.optString("desc")
        val pubTime = dataObj.optString("aDate")
        val company = dataObj.optString("company")

        val album = com.rawsmusic.module.online.model.OnlineAlbum(
            id = albumId,
            platform = platform,
            title = albumName,
            coverUrl = cover,
            artist = singerName,
            publishTime = pubTime,
            company = company,
            description = desc
        )

        val songArr = dataObj.optJSONArray("list") ?: JSONArray()
        val list = mutableListOf<OnlineSongItem>()

        for (i in 0 until songArr.length()) {
            val sObj = songArr.optJSONObject(i) ?: continue
            val songMid = sObj.optString("songmid")
            val songName = sObj.optString("songname")
            val singerArr = sObj.optJSONArray("singer")
            val singerNames = mutableListOf<String>()
            if (singerArr != null) {
                for (j in 0 until singerArr.length()) {
                    singerArr.optJSONObject(j)?.optString("name")?.let { singerNames.add(it) }
                }
            }
            val interval = sObj.optLong("interval", 0L) * 1000L
            val isVip = parseQQIsVip(sObj)

            if (songMid.isNotEmpty() && songName.isNotEmpty()) {
                list.add(
                    OnlineSongItem(
                        id = songMid,
                        platform = platform,
                        title = songName,
                        artist = singerNames.joinToString(", "),
                        album = albumName,
                        durationMs = interval,
                        coverUrl = cover,
                        isVip = isVip
                    )
                )
            }
        }

        Pair(album.copy(songCount = list.size), list)
    }
}

