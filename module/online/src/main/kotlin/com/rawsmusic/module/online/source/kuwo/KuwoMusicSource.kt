package com.rawsmusic.module.online.source.kuwo

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
 * 酷我音乐在线音源实现类
 */
class KuwoMusicSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : OnlinePlatformSource {

    override val platform: OnlinePlatform = OnlinePlatform.KUWO

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private suspend fun getApiRaw(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "http://www.kuwo.cn/")
            .header("csrf", "123")
            .header("Cookie", "kw_token=123")
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.body?.string() ?: throw IllegalStateException("酷我网络响应为空")
    }

    private suspend fun getApiJson(url: String): JSONObject {
        val raw = getApiRaw(url).trim()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            // 酷我接口经常返回单引号伪 JSON，容错转换为标准 JSON
            val fixed = raw.replace('\'', '"')
            JSONObject(fixed)
        }
    }

    private fun parseKuwoIsVip(sObj: JSONObject): Boolean {
        val pay = sObj.optString("pay", "")
        if (pay.isNotEmpty() && pay != "0" && pay != "false") return true
        if (sObj.optInt("is_listen_fee", 0) == 1) return true
        if (sObj.optInt("feeType", 0) > 0) return true
        if (sObj.optInt("isvip", sObj.optInt("is_vip", 0)) == 1) return true
        val payInfo = sObj.optJSONObject("payInfo")
        if (payInfo != null) {
            val listen = payInfo.optInt("listen", 0)
            val fee = payInfo.optInt("fee", 0)
            if (listen > 0 || fee > 0) return true
        }
        return false
    }

    private fun formatKuwoImg(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (trimmed.startsWith("http://")) return "https://${trimmed.removePrefix("http://")}"
        return trimmed
    }

    @Volatile
    private var dynamicTags: List<OnlinePlaylistTag>? = null

    companion object {
        /**
         * 酷我音乐官方真实分类 ID 预设（与官方 getTagPlayList id 严格对应）
         */
        private val PRESET_TAGS = listOf(
            OnlinePlaylistTag("全部", "全部", "默认"),
            // 曲风流派
            OnlinePlaylistTag("393", "流行", "流派"),
            OnlinePlaylistTag("389", "摇滚", "流派"),
            OnlinePlaylistTag("392", "民谣", "流派"),
            OnlinePlaylistTag("127", "古风", "流派"),
            OnlinePlaylistTag("391", "电子", "流派"),
            OnlinePlaylistTag("387", "说唱", "流派"),
            OnlinePlaylistTag("168", "DJ", "流派"),
            OnlinePlaylistTag("173", "轻音乐", "流派"),
            OnlinePlaylistTag("577", "纯音乐", "流派"),
            OnlinePlaylistTag("390", "古典", "流派"),
            OnlinePlaylistTag("397", "爵士", "流派"),
            OnlinePlaylistTag("394", "R&B", "流派"),
            // 语言
            OnlinePlaylistTag("37", "华语", "语种"),
            OnlinePlaylistTag("35", "欧美", "语种"),
            OnlinePlaylistTag("13", "粤语", "语种"),
            OnlinePlaylistTag("1091", "日语", "语种"),
            OnlinePlaylistTag("1093", "韩语", "语种"),
            // 主题与专区
            OnlinePlaylistTag("181", "ACG", "主题"),
            OnlinePlaylistTag("1265", "经典", "主题"),
            OnlinePlaylistTag("180", "影视", "主题"),
            OnlinePlaylistTag("2200", "情歌", "主题"),
            OnlinePlaylistTag("2189", "短视频", "主题"),
            // 心情
            OnlinePlaylistTag("146", "伤感", "心情"),
            OnlinePlaylistTag("62", "解压", "心情"),
            OnlinePlaylistTag("58", "治愈", "心情"),
            OnlinePlaylistTag("67", "安静", "心情"),
            OnlinePlaylistTag("137", "甜蜜", "心情"),
            // 场景
            OnlinePlaylistTag("376", "车载", "场景"),
            OnlinePlaylistTag("366", "运动", "场景"),
            OnlinePlaylistTag("354", "睡眠", "场景"),
            OnlinePlaylistTag("1876", "学习", "场景"),
            OnlinePlaylistTag("375", "旅行", "场景")
        )
    }

    override suspend fun getTags(): List<OnlinePlaylistTag> = withContext(Dispatchers.IO) {
        dynamicTags?.let { return@withContext it }
        try {
            val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList"
            val root = getApiJson(url)
            val dataArr = root.optJSONArray("data")
            if (dataArr != null && dataArr.length() > 0) {
                val list = mutableListOf<OnlinePlaylistTag>()
                list.add(OnlinePlaylistTag("全部", "全部", "默认"))
                for (i in 0 until dataArr.length()) {
                    val groupObj = dataArr.optJSONObject(i) ?: continue
                    val groupName = groupObj.optString("name").trim()
                    val subArr = groupObj.optJSONArray("data") ?: continue
                    for (j in 0 until subArr.length()) {
                        val item = subArr.optJSONObject(j) ?: continue
                        val cid = item.optString("id").trim()
                        val cname = item.optString("name").trim()
                        if (cid.isNotEmpty() && cname.isNotEmpty() && list.none { it.id == cid }) {
                            list.add(OnlinePlaylistTag(cid, cname, groupName))
                        }
                    }
                }
                if (list.size > 1) {
                    dynamicTags = list
                    return@withContext list
                }
            }
        } catch (_: Exception) {
            // 降级到预设标签
        }
        PRESET_TAGS
    }

    override suspend fun getPlaylists(
        tagId: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val list = mutableListOf<OnlinePlaylist>()
        val normalizedTag = tagId.trim()
        val isAll = normalizedTag.isEmpty() || normalizedTag == "全部" || normalizedTag == "0" || normalizedTag == "推荐"

        // 1. 如果是第一页，先加入酷我官方精选权威歌单/榜单，保证每位用户打开即有海量歌曲
        if (page == 1 && isAll) {
            list.addAll(getOfficialRecommendPlaylists())
        }

        // 2. 调用酷我官方分类歌单接口（必须传入酷我官方分类数字 ID）
        try {
            val url = if (isAll) {
                "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?pn=$page&rn=$pageSize&order=hot"
            } else {
                // 如果传入的是数字 ID 直接使用；如果是分类名称则匹配真实 ID
                val effectiveId = if (normalizedTag.toIntOrNull() != null) {
                    normalizedTag
                } else {
                    val allTags = dynamicTags ?: PRESET_TAGS
                    allTags.firstOrNull { it.name.equals(normalizedTag, ignoreCase = true) || it.id == normalizedTag }?.id
                        ?: when (normalizedTag) {
                            "流行" -> "393"
                            "摇滚" -> "389"
                            "民谣" -> "392"
                            "古风" -> "127"
                            "华语" -> "37"
                            "欧美" -> "35"
                            "粤语" -> "13"
                            "ACG" -> "181"
                            "电子" -> "391"
                            "DJ" -> "168"
                            "纯音乐" -> "577"
                            "经典" -> "1265"
                            "影视" -> "180"
                            "伤感" -> "146"
                            "治愈" -> "58"
                            "车载" -> "376"
                            "运动" -> "366"
                            "睡眠" -> "354"
                            "说唱", "嘻哈" -> "387"
                            else -> null
                        }
                }

                if (effectiveId != null) {
                    val encoded = URLEncoder.encode(effectiveId, "UTF-8")
                    "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?pn=$page&rn=$pageSize&id=$encoded"
                } else {
                    null
                }
            }

            if (url != null) {

            val root = getApiJson(url)
            val dataArr = root.optJSONObject("data")?.optJSONArray("data")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = obj.optString("name")
                    val cover = formatKuwoImg(obj.optString("img").ifEmpty { obj.optString("pic").ifEmpty { obj.optString("hts_pic") } })
                    val playCount = obj.optLong("listencnt", obj.optLong("total", 0L))
                    val trackCount = obj.optInt("total", 0)

                    if (id.isNotEmpty() && title.isNotEmpty() && list.none { it.id == id }) {
                        list.add(
                            OnlinePlaylist(
                                id = id,
                                platform = platform,
                                title = title,
                                coverUrl = cover,
                                playCount = playCount,
                                trackCount = trackCount,
                                creatorName = obj.optString("uname").ifEmpty { "酷我音乐" },
                                description = obj.optString("intro")
                            )
                        )
                    }
                }
            }
            }
        } catch (_: Exception) {
        }

        // 3. 若接口无数据，通过搜索接口补充
        if (list.isEmpty()) {
            val keyword = if (tagId == "全部" || tagId == "推荐" || tagId == "0") "热门" else tagId
            return@withContext searchPlaylists(keyword, page, pageSize)
        }

        list
    }

    private fun getOfficialRecommendPlaylists(): List<OnlinePlaylist> {
        return listOf(
            OnlinePlaylist("16", platform, "酷我热歌榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", 9900000L, 100, "酷我官方", "每日权威全网最热金曲"),
            OnlinePlaylist("17", platform, "酷我新歌榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", 5200000L, 100, "酷我官方", "每日首发潮流新声"),
            OnlinePlaylist("93", platform, "酷我飙升榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", 7800000L, 100, "酷我官方", "实时飙升最快歌曲"),
            OnlinePlaylist("104", platform, "华语流行精选", "http://img2.kwcdn.kuwo.cn/star/upload/5/5/1649672627397_.png", 3600000L, 100, "酷我官方", "华语经典与时下流行"),
            OnlinePlaylist("328", platform, "车载金曲榜", "http://img2.kwcdn.kuwo.cn/star/upload/15/15/1649671897119_.jpg", 4100000L, 100, "酷我官方", "公路自驾随行原声"),
            OnlinePlaylist("336", platform, "VIP热播榜", "https://kwimg2.kuwo.cn/star/upload/36/56/1683620971200_.png", 2900000L, 100, "酷我官方", "酷我会员热听风向标")
        )
    }

    override suspend fun getLeaderboards(): List<OnlineLeaderboard> = withContext(Dispatchers.IO) {
        try {
            val root = getApiJson("http://wapi.kuwo.cn/api/pc/bang/list")
            val childArr = root.optJSONArray("child") ?: return@withContext fallbackLeaderboards()

            val list = mutableListOf<OnlineLeaderboard>()
            for (i in 0 until childArr.length()) {
                val group = childArr.optJSONObject(i) ?: continue
                val groupChild = group.optJSONArray("child")
                if (groupChild != null) {
                    for (j in 0 until groupChild.length()) {
                        val obj = groupChild.optJSONObject(j) ?: continue
                        val id = obj.optString("sourceid").ifEmpty { obj.optString("id") }
                        val name = obj.optString("name")
                        val pic = obj.optString("pic").ifEmpty { obj.optString("pic2") }
                        val info = obj.optString("info")
                        if (name.isNotEmpty() && id.isNotEmpty()) {
                            list.add(
                                OnlineLeaderboard(
                                    id = id,
                                    platform = platform,
                                    title = name,
                                    coverUrl = pic,
                                    updateFrequency = info.ifEmpty { "每日更新" },
                                    topSongsPreview = listOf("权威榜单", "时下流行热歌")
                                )
                            )
                        }
                    }
                }
            }
            if (list.isEmpty()) fallbackLeaderboards() else list
        } catch (e: Exception) {
            fallbackLeaderboards()
        }
    }

    private fun fallbackLeaderboards(): List<OnlineLeaderboard> {
        return listOf(
            OnlineLeaderboard("16", platform, "酷我热歌榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", "每日更新"),
            OnlineLeaderboard("17", platform, "酷我新歌榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", "每日更新"),
            OnlineLeaderboard("93", platform, "酷我飙升榜", "https://kwimg3.kuwo.cn/star/upload/ecom/1684642476266.png", "每日更新"),
            OnlineLeaderboard("336", platform, "会员飙升榜", "https://kwimg2.kuwo.cn/star/upload/36/56/1683620971200_.png", "每日更新"),
            OnlineLeaderboard("104", platform, "酷我华语榜", "http://img2.kwcdn.kuwo.cn/star/upload/5/5/1649672627397_.png", "每周更新"),
            OnlineLeaderboard("328", platform, "车载歌曲榜", "http://img2.kwcdn.kuwo.cn/star/upload/15/15/1649671897119_.jpg", "每周更新")
        )
    }

    override suspend fun getPlaylistDetail(playlistId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> =
        withContext(Dispatchers.IO) {
            // 如果是官方排行榜 ID（小于等于 5 位数字）
            if (playlistId.length <= 5 && playlistId.toIntOrNull() != null) {
                return@withContext getRankDetail(playlistId)
            }

            var title = "酷我歌单"
            var coverUrl = ""
            var creatorName = "酷我用户"
            var playCount = 0L
            var description: String? = null
            val songs = mutableListOf<OnlineSongItem>()

            try {
                var currentPage = 0
                val pageSize = 300
                var hasMore = true
                var totalSongs = 0

                while (hasMore && currentPage < 5) { // 支持最多拉取 1500 首歌曲
                    val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId&pn=$currentPage&rn=$pageSize&encode=utf-8&keyset=pl2012&vipver=MUSIC_9.1.1.2_BCS2"
                    val root = getApiJson(url)

                    if (currentPage == 0) {
                        title = root.optString("title").ifEmpty { root.optString("name").ifEmpty { title } }
                        coverUrl = formatKuwoImg(root.optString("pic").ifEmpty { root.optString("hts_pic").ifEmpty { root.optString("img") } })
                        creatorName = root.optString("uname").ifEmpty { root.optString("nickname").ifEmpty { creatorName } }
                        playCount = root.optLong("playnum", root.optLong("playcnt", 0L))
                        description = root.optString("info").ifEmpty { root.optString("intro") }
                        totalSongs = root.optInt("total", 0)
                    }

                    val songsArr = root.optJSONArray("musiclist")
                    if (songsArr == null || songsArr.length() == 0) {
                        hasMore = false
                        break
                    }

                    val pageCount = songsArr.length()
                    for (i in 0 until pageCount) {
                        val sObj = songsArr.optJSONObject(i) ?: continue
                        val id = sObj.optString("id").ifEmpty { sObj.optString("musicrid").replace("MUSIC_", "") }
                        val name = sObj.optString("name").ifEmpty { sObj.optString("song_name") }
                        val artist = sObj.optString("artist").ifEmpty { sObj.optString("singer") }
                        val album = sObj.optString("album")
                        val duration = sObj.optLong("duration", sObj.optLong("song_duration", 0L)) * 1000L
                        val rawPic = sObj.optString("pic").ifEmpty { sObj.optString("pic120") }.ifEmpty { sObj.optString("albumpic") }
                        val pic = formatKuwoImg(rawPic)

                        if (id.isNotEmpty() && name.isNotEmpty() && songs.none { it.id == id }) {
                            songs.add(
                                OnlineSongItem(
                                    id = id,
                                    platform = platform,
                                    title = name,
                                    artist = artist.ifEmpty { "未知歌手" },
                                    album = album.ifEmpty { "单曲" },
                                    durationMs = duration,
                                    coverUrl = pic.ifEmpty { coverUrl },
                                    isVip = parseKuwoIsVip(sObj)
                                )
                            )
                        }
                    }

                    currentPage++
                    if (songs.size >= totalSongs || pageCount < pageSize) {
                        hasMore = false
                    }
                }
            } catch (_: Exception) {
            }

            // 智能扩充补充：如果歌单解析到的有效歌曲少于 15 首，按歌单风格关键词智能补充热门歌曲
            if (songs.size < 15) {
                val cleanTitle = title.replace(Regex("[|｜丨#【】《》\\[\\]()（）·]"), " ").trim()
                val keyword = if (cleanTitle.length > 1) cleanTitle else "热歌"
                val extraSongs = searchSongsByTitle(keyword, 50)
                for (extra in extraSongs) {
                    if (songs.none { it.id == extra.id || (it.title == extra.title && it.artist == extra.artist) }) {
                        songs.add(extra)
                    }
                }
            }

            val playlist = OnlinePlaylist(
                id = playlistId,
                platform = platform,
                title = title,
                coverUrl = coverUrl.ifEmpty { songs.firstOrNull()?.coverUrl ?: "" },
                playCount = if (playCount > 0) playCount else 100000L,
                trackCount = songs.size,
                creatorName = creatorName,
                description = description
            )

            Pair(playlist, songs)
        }

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlineSongItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val pn = page - 1
            val url = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=$pn&rn=$pageSize&rformat=json&encoding=utf8"
            val root = getApiJson(url)
            val listArr = root.optJSONArray("abslist") ?: return@withContext emptyList()

            val songs = mutableListOf<OnlineSongItem>()
            for (i in 0 until listArr.length()) {
                val obj = listArr.optJSONObject(i) ?: continue
                val id = obj.optString("MUSICRID").replace("MUSIC_", "").ifEmpty { obj.optString("id") }
                val rawTitle = obj.optString("SONGNAME").ifEmpty { obj.optString("name") }
                val title = cleanKuwoText(rawTitle)
                val artist = cleanKuwoText(obj.optString("ARTIST").ifEmpty { obj.optString("artist") })
                val album = cleanKuwoText(obj.optString("ALBUM").ifEmpty { obj.optString("album") })
                val duration = obj.optLong("DURATION", 0L) * 1000L

                // 1. 优先提取完整 URL 封面
                var pic = obj.optString("pic").ifEmpty {
                    obj.optString("web_pic").ifEmpty {
                        obj.optString("picpath").ifEmpty {
                            obj.optString("img")
                        }
                    }
                }
                // 2. 解析短路径专辑封面 web_albumpic_short / albumpic
                if (pic.isEmpty() || !pic.startsWith("http")) {
                    val albumShort = obj.optString("web_albumpic_short").ifEmpty { obj.optString("albumpic") }
                    if (albumShort.isNotEmpty()) {
                        val formatted = albumShort.replace("120/", "500/").replace("70/", "500/")
                        pic = if (formatted.startsWith("/")) "https://img1.kuwo.cn/star/albumcover$formatted"
                              else "https://img1.kuwo.cn/star/albumcover/$formatted"
                    }
                }
                // 3. 解析短路径歌手头像 web_artistpic_short / web_artistpic / artistpic
                if (pic.isEmpty() || !pic.startsWith("http")) {
                    val artistShort = obj.optString("web_artistpic_short").ifEmpty {
                        obj.optString("web_artistpic").ifEmpty { obj.optString("artistpic") }
                    }
                    if (artistShort.isNotEmpty()) {
                        val formatted = artistShort.replace("120/", "500/").replace("70/", "500/")
                        pic = if (formatted.startsWith("/")) "https://img1.kuwo.cn/star/starheads$formatted"
                              else "https://img1.kuwo.cn/star/starheads/$formatted"
                    }
                }

                val albumId = obj.optString("ALBUMID").ifEmpty { obj.optString("albumid") }

                if (id.isNotEmpty() && title.isNotEmpty()) {
                    songs.add(
                        OnlineSongItem(
                            id = id,
                            platform = platform,
                            title = title,
                            artist = artist.ifEmpty { "未知歌手" },
                            album = album.ifEmpty { "热歌" },
                            albumId = albumId.takeIf { it.isNotBlank() && it != "0" },
                            durationMs = duration,
                            coverUrl = pic,
                            isVip = parseKuwoIsVip(obj)
                        )
                    )
                }
            }
            songs
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun cleanKuwoText(str: String): String {
        return str.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .trim()
    }

    private suspend fun searchSongsByTitle(keyword: String, count: Int): List<OnlineSongItem> {
        return searchSongs(keyword, 1, count)
    }

    private suspend fun getRankDetail(rankId: String): Pair<OnlinePlaylist, List<OnlineSongItem>> =
        withContext(Dispatchers.IO) {
            val url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=$rankId&pn=0&rn=150"
            val root = getApiJson(url)
            val rankName = root.optString("name").ifEmpty { "酷我排行榜" }
            val coverUrl = root.optString("pic")
            val songsArr = root.optJSONArray("musiclist")

            val songs = mutableListOf<OnlineSongItem>()
            if (songsArr != null) {
                for (i in 0 until songsArr.length()) {
                    val sObj = songsArr.optJSONObject(i) ?: continue
                    val id = sObj.optString("id").ifEmpty { sObj.optString("musicrid").replace("MUSIC_", "") }
                    val name = sObj.optString("name")
                    val artist = sObj.optString("artist")
                    val album = sObj.optString("album")
                    val duration = sObj.optLong("song_duration", sObj.optLong("duration", 0L)) * 1000L

                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        songs.add(
                            OnlineSongItem(
                                id = id,
                                platform = platform,
                                title = name,
                                artist = artist.ifEmpty { "未知歌手" },
                                album = album.ifEmpty { "热歌" },
                                durationMs = duration,
                                coverUrl = coverUrl,
                                isVip = parseKuwoIsVip(sObj)
                            )
                        )
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
                creatorName = "酷我官方",
                description = root.optString("info").ifEmpty { "官方权威排行榜" }
            )

            Pair(playlist, songs)
        }

    override suspend fun searchPlaylists(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?all=$encoded&ft=playlist&itemset=web_2013&client=kt&pn=${page - 1}&rn=$pageSize&rformat=json&encoding=utf8"
            val root = getApiJson(url)
            val listArr = root.optJSONArray("abslist") ?: return@withContext emptyList()

            val list = mutableListOf<OnlinePlaylist>()
            for (i in 0 until listArr.length()) {
                val obj = listArr.optJSONObject(i) ?: continue
                val id = obj.optString("playlistid").ifEmpty { obj.optString("PLAYLISTID").ifEmpty { obj.optString("DC_TARGETID") } }
                val title = obj.optString("name").ifEmpty { obj.optString("NAME") }
                var cover = obj.optString("pic").ifEmpty { obj.optString("PIC").ifEmpty { obj.optString("hts_pic") } }
                val playCount = obj.optLong("playcnt", obj.optLong("PLAYCNT", 0L))
                val trackCount = obj.optInt("songnum", obj.optInt("SONGNUM", 0))

                if (id.isNotEmpty() && title.isNotEmpty()) {
                    list.add(
                        OnlinePlaylist(
                            id = id,
                            platform = platform,
                            title = title,
                            coverUrl = cover,
                            playCount = playCount,
                            trackCount = trackCount,
                            creatorName = obj.optString("nickname").ifEmpty { obj.optString("NICKNAME").ifEmpty { "酷我用户" } },
                            description = obj.optString("intro").ifEmpty { obj.optString("INFO") }
                        )
                    )
                }
            }
            if (list.isEmpty() && page == 1) {
                getOfficialRecommendPlaylists()
            } else list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun extractPlaylistId(urlOrText: String): String? {
        val trimmed = urlOrText.trim()
        val matcher = Pattern.compile("(?:playlist_detail/|playlist/|pid=|listid=|id=)(\\d+)").matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        if (trimmed.matches(Regex("^\\d{2,12}$"))) {
            return trimmed
        }
        return null
    }

    override suspend fun getAlbumDetail(albumId: String): Pair<com.rawsmusic.module.online.model.OnlineAlbum, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val url = "http://search.kuwo.cn/r.s?pn=0&rn=100&albumid=$albumId&ft=music&itemset=web_2013&client=kt&rformat=json&encoding=utf8"
        val root = getApiJson(url)
        val albumName = root.optString("name").ifEmpty { root.optString("album") }
        val artistName = root.optString("artist").ifEmpty { root.optString("artist_name") }
        var cover = root.optString("pic").ifEmpty { root.optString("albumpic") }
        if (cover.isNotEmpty() && !cover.startsWith("http")) {
            val formatted = cover.replace("120/", "500/")
            cover = if (formatted.startsWith("/")) "https://img1.kuwo.cn/star/albumcover$formatted"
                    else "https://img1.kuwo.cn/star/albumcover/$formatted"
        }
        val pubTime = root.optString("pub").ifEmpty { root.optString("publish_time") }
        val info = root.optString("info").ifEmpty { root.optString("intro") }

        val listArr = root.optJSONArray("musiclist") ?: root.optJSONArray("abslist") ?: JSONArray()
        val songs = mutableListOf<OnlineSongItem>()

        for (i in 0 until listArr.length()) {
            val obj = listArr.optJSONObject(i) ?: continue
            val id = obj.optString("MUSICRID").replace("MUSIC_", "").ifEmpty { obj.optString("id").ifEmpty { obj.optString("musicrid") } }
            val rawTitle = obj.optString("SONGNAME").ifEmpty { obj.optString("name").ifEmpty { obj.optString("songname") } }
            val title = cleanKuwoText(rawTitle)
            val artist = cleanKuwoText(obj.optString("ARTIST").ifEmpty { obj.optString("artist").ifEmpty { artistName } })
            val album = cleanKuwoText(obj.optString("ALBUM").ifEmpty { obj.optString("album").ifEmpty { albumName } })
            val duration = obj.optLong("DURATION", 0L) * 1000L

            var pic = obj.optString("pic").ifEmpty { obj.optString("web_pic") }
            if (pic.isEmpty() || !pic.startsWith("http")) {
                val albumShort = obj.optString("web_albumpic_short").ifEmpty { obj.optString("albumpic") }
                if (albumShort.isNotEmpty()) {
                    val formatted = albumShort.replace("120/", "500/").replace("70/", "500/")
                    pic = if (formatted.startsWith("/")) "https://img1.kuwo.cn/star/albumcover$formatted"
                          else "https://img1.kuwo.cn/star/albumcover/$formatted"
                } else {
                    pic = cover
                }
            }

            if (id.isNotEmpty() && title.isNotEmpty()) {
                songs.add(
                    OnlineSongItem(
                        id = id,
                        platform = platform,
                        title = title,
                        artist = artist.ifEmpty { "未知歌手" },
                        album = album.ifEmpty { albumName.ifEmpty { "专辑" } },
                        albumId = albumId,
                        durationMs = duration,
                        coverUrl = pic,
                        isVip = parseKuwoIsVip(obj)
                    )
                )
            }
        }

        val albumObj = com.rawsmusic.module.online.model.OnlineAlbum(
            id = albumId,
            platform = platform,
            title = albumName.ifEmpty { "专辑 $albumId" },
            coverUrl = cover,
            artist = artistName.ifEmpty { "未知歌手" },
            songCount = songs.size,
            publishTime = pubTime,
            description = info
        )

        Pair(albumObj, songs)
    }
}

