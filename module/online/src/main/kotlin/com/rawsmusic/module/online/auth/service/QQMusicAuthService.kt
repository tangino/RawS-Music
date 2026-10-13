package com.rawsmusic.module.online.auth.service

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.auth.model.PlatformAccount
import com.rawsmusic.module.online.auth.model.QrCheckResult
import com.rawsmusic.module.online.auth.model.QrCodeData
import com.rawsmusic.module.online.auth.model.QrStatus
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
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
 * QQ 音乐扫码认证与用户服务实现
 */
class QQMusicAuthService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false) // 手动拦截跳转以提取 Cookies
        .build()
) : IPlatformAuthService {

    override val platform: OnlinePlatform = OnlinePlatform.QQ

    companion object {
        private const val TAG = "QQMusicAuthService"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 计算腾讯 Web ptlogin 的 ptqrtoken 哈希值 (严格匹配前端 32 位有符号整数溢出规则)
         */
        fun getPtQrToken(qrsig: String): Long {
            var e = 0
            for (i in 0 until qrsig.length) {
                e += (e shl 5) + qrsig[i].code
            }
            return 2147483647L and e.toLong()
        }
    }

    override suspend fun getQrCode(): QrCodeData = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=0.$timestamp&daid=384&pt_3rd_aid=100497308"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()

        val response = client.newCall(request).execute()
        val imageBytes = response.body?.bytes() ?: throw IllegalStateException("获取 QQ 登录二维码图片失败")

        // 从 Set-Cookie 中提取 qrsig
        val setCookieHeaders = response.headers("Set-Cookie")
        var qrsig = ""
        for (header in setCookieHeaders) {
            val parts = header.split(";")
            for (part in parts) {
                val kv = part.trim().split("=")
                if (kv.size == 2 && kv[0] == "qrsig") {
                    qrsig = kv[1]
                    break
                }
            }
            if (qrsig.isNotBlank()) break
        }

        if (qrsig.isBlank()) {
            throw IllegalStateException("获取 QQ 登录凭据 qrsig 失败")
        }

        QrCodeData(
            platform = platform,
            key = qrsig,
            qrImageBytes = imageBytes,
            expireTimeMs = 180_000L
        )
    }

    /**
     * 解析 ptuiCB(...) 中的所有参数字符串
     */
    private fun parsePtuiCallback(responseStr: String): List<String> {
        val start = responseStr.indexOf("(")
        val end = responseStr.lastIndexOf(")")
        if (start == -1 || end == -1 || start >= end) return emptyList()

        val content = responseStr.substring(start + 1, end)
        val args = mutableListOf<String>()
        val regex = Pattern.compile("'(.*?)'|\"(.*?)\"")
        val matcher = regex.matcher(content)
        while (matcher.find()) {
            val v1 = matcher.group(1)
            val v2 = matcher.group(2)
            args.add(v1 ?: v2 ?: "")
        }
        return args
    }

    /**
     * 自动追踪多跳重定向并合并收集所有 Set-Cookie
     */
    private fun followRedirectsAndCollectCookies(initialUrl: String, initialCookies: Map<String, String>): Pair<Map<String, String>, String> {
        val cookieMap = initialCookies.toMutableMap()
        var currentUrl = initialUrl
        var depth = 0
        var lastBody = ""

        while (depth < 6 && currentUrl.isNotBlank()) {
            try {
                val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val requestBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")

                if (cookieHeader.isNotBlank()) {
                    requestBuilder.header("Cookie", cookieHeader)
                }

                val response = client.newCall(requestBuilder.build()).execute()

                // 收集当前跳的所有 Set-Cookie
                for (header in response.headers("Set-Cookie")) {
                    val parts = header.split(";")
                    for (part in parts) {
                        val kv = part.trim().split("=")
                        if (kv.size == 2 && kv[0].isNotBlank()) {
                            cookieMap[kv[0]] = kv[1]
                        }
                    }
                }

                val code = response.code
                if (code in 300..399) {
                    val location = response.header("Location") ?: break
                    currentUrl = if (location.startsWith("http")) location else "https://graph.qq.com$location"
                    depth++
                } else {
                    lastBody = response.body?.string() ?: ""
                    break
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "重定向跟踪异常(depth=$depth): ${e.message}")
                break
            }
        }
        return Pair(cookieMap, lastBody)
    }

    override suspend fun checkQrStatus(key: String, extra: Map<String, String>): QrCheckResult = withContext(Dispatchers.IO) {
        val qrsig = key
        val ptqrtoken = getPtQrToken(qrsig)
        val timestamp = System.currentTimeMillis()
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptqrtoken=$ptqrtoken&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-$timestamp&js_ver=24032115&js_type=1&login_sig=&pt_uistyle=40&aid=716027609&daid=384&pt_3rd_aid=100497308"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("Cookie", "qrsig=$qrsig")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: ""
        AppLogger.d(TAG, "ptqrlogin 响应数据: $bodyStr")

        val args = parsePtuiCallback(bodyStr)
        if (args.isEmpty()) {
            return@withContext QrCheckResult(QrStatus.WAITING, "等待扫码中...")
        }

        val code = args.getOrNull(0) ?: ""
        val jumpUrl = args.getOrNull(2) ?: ""
        val message = args.getOrNull(4) ?: ""
        val nickname = args.getOrNull(5) ?: ""

        when (code) {
            "66" -> QrCheckResult(QrStatus.WAITING, message.ifBlank { "请使用手机 QQ 扫码" })
            "67" -> QrCheckResult(QrStatus.SCANNED, message.ifBlank { "二维码已扫描，请在手机上点击确认" })
            "65" -> QrCheckResult(QrStatus.EXPIRED, message.ifBlank { "二维码已失效，请点击刷新" })
            "0" -> {
                // 登录成功，提取第一阶段 Cookie
                val initialCookies = mutableMapOf<String, String>()
                initialCookies["qrsig"] = qrsig

                for (header in response.headers("Set-Cookie")) {
                    val parts = header.split(";")
                    for (part in parts) {
                        val kv = part.trim().split("=")
                        if (kv.size == 2 && kv[0].isNotBlank()) {
                            initialCookies[kv[0]] = kv[1]
                        }
                    }
                }

                // 跟踪跳转链获取完整登录 Cookie
                val (allCookies, _) = if (jumpUrl.isNotBlank()) {
                    followRedirectsAndCollectCookies(jumpUrl, initialCookies)
                } else {
                    Pair(initialCookies, "")
                }

                // 提取 uin
                var uin = allCookies["uin"] ?: allCookies["p_uin"] ?: ""
                uin = uin.replace("^o0*".toRegex(), "")

                val account = PlatformAccount(
                    platform = platform,
                    userId = uin,
                    nickname = nickname.ifBlank { if (uin.isNotBlank()) "QQ 用户 $uin" else "QQ 音乐用户" },
                    avatarUrl = if (uin.isNotBlank()) "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=100" else "",
                    cookies = allCookies,
                    tokens = mapOf("uin" to uin),
                    updatedAt = System.currentTimeMillis()
                )

                // 尝试丰富用户信息与 VIP 状态
                val enrichedAccount = try {
                    refreshUserInfo(account)
                } catch (e: Exception) {
                    account
                }

                AppLogger.i(TAG, "QQ 音乐登录成功: ${enrichedAccount.nickname} (${enrichedAccount.userId})")
                QrCheckResult(QrStatus.SUCCESS, "登录成功", enrichedAccount)
            }
            else -> QrCheckResult(QrStatus.WAITING, message.ifBlank { "等待扫码中..." })
        }
    }

    override suspend fun refreshUserInfo(account: PlatformAccount): PlatformAccount = withContext(Dispatchers.IO) {
        val uin = account.userId
        if (uin.isBlank()) return@withContext account

        val uinLong = uin.toLongOrNull() ?: 0L
        val authKey = account.cookies["qm_keyst"] ?: account.cookies["qqmusic_key"] ?: account.cookies["psrf_musickey_id"] ?: account.tokens["key"] ?: ""

        try {
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", 24)
                    put("cv", 0)
                    if (uinLong > 0) put("uin", uinLong)
                    if (authKey.isNotBlank()) put("authst", authKey)
                })
                put("userinfo", JSONObject().apply {
                    put("module", "music.musicuser.UserBaseInfo")
                    put("method", "GetBaseInfo")
                    put("param", JSONObject().apply {
                        put("uin", uin)
                    })
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = payload.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", account.toCookieHeader())
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext account
            AppLogger.d(TAG, "刷新 QQ 用户信息响应: $bodyStr")
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("userinfo")?.optJSONObject("data")

            if (data != null) {
                val nick = data.optString("nick", account.nickname)
                val headpic = data.optString("headpic", account.avatarUrl)
                val isVip = data.optInt("vip_type", 0) > 0 || data.optInt("isvip", 0) > 0
                val vipLevel = data.optString("vip_level", "")

                return@withContext account.copy(
                    nickname = nick.ifBlank { account.nickname },
                    avatarUrl = headpic.ifBlank { account.avatarUrl },
                    isVip = isVip,
                    vipLevel = if (isVip) "VIP $vipLevel".trim() else "",
                    updatedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "刷新 QQ 音乐用户信息失败: ${e.message}", e)
        }
        account
    }

    /**
     * 去除 JSONP 外层函数包装，例如 MusicJsonCallback(...)
     */
    private fun cleanJsonp(response: String): String {
        val trimmed = response.trim()
        val start = trimmed.indexOf("(")
        val end = trimmed.lastIndexOf(")")
        if (start != -1 && end != -1 && start < end) {
            return trimmed.substring(start + 1, end).trim()
        }
        return trimmed
    }

    override suspend fun getUserPlaylists(account: PlatformAccount): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val uin = account.userId
        if (uin.isBlank()) return@withContext emptyList()

        val list = mutableListOf<OnlinePlaylist>()
        val uinLong = uin.toLongOrNull() ?: 0L
        val authKey = account.cookies["qm_keyst"] ?: account.cookies["qqmusic_key"] ?: account.cookies["psrf_musickey_id"] ?: account.tokens["key"] ?: ""

        fun addPlaylist(
            id: String,
            title: String,
            coverUrl: String,
            trackCount: Int,
            playCount: Long = 0L,
            creatorName: String = account.nickname,
            isFavorite: Boolean = false
        ) {
            val cleanId = id.trim()
            val cleanTitle = title.trim()
            if (cleanId.isNotBlank() && cleanId != "0" && cleanTitle.isNotBlank()) {
                if (list.none { it.id == cleanId }) {
                    // 处理封面图
                    val finalCover = if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                        coverUrl
                    } else if (account.avatarUrl.isNotBlank()) {
                        account.avatarUrl
                    } else {
                        ""
                    }

                    list.add(
                        OnlinePlaylist(
                            id = cleanId,
                            platform = platform,
                            title = cleanTitle,
                            coverUrl = finalCover,
                            playCount = playCount,
                            trackCount = trackCount,
                            creatorName = creatorName.ifBlank { account.nickname },
                            creatorAvatarUrl = if (!isFavorite) account.avatarUrl else null,
                            customGroup = "QQ 音乐"
                        )
                    )
                }
            }
        }

        // 方案 1 (核心有效源): fcg_user_created_diss 移动端/Web自建歌单接口
        try {
            val createdDissUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?cv=4747474&ct=24&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=1&uin=$uin&hostuin=$uin&sin=0&size=100"
            val req = Request.Builder()
                .url(createdDissUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", account.toCookieHeader())
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            AppLogger.d(TAG, "QQ 用户自建歌单接口响应: $body")

            if (body.isNotBlank()) {
                val json = JSONObject(cleanJsonp(body))
                val disslist = json.optJSONObject("data")?.optJSONArray("disslist")
                if (disslist != null) {
                    for (i in 0 until disslist.length()) {
                        val item = disslist.getJSONObject(i)
                        val tid = item.optLong("tid", 0L)
                        val dirid = item.optLong("dirid", 0L)
                        val id = if (tid > 0L) tid.toString() else if (dirid > 0L) "dir_$dirid" else ""
                        val title = item.optString("diss_name", "")
                        val cover = item.optString("diss_cover", "")
                        val songCnt = item.optInt("song_cnt", 0)
                        val listenNum = item.optLong("listen_num", 0L)

                        addPlaylist(
                            id = id,
                            title = title,
                            coverUrl = cover,
                            trackCount = songCnt,
                            playCount = listenNum,
                            creatorName = account.nickname,
                            isFavorite = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 QQ 用户自建歌单异常: ${e.message}", e)
        }

        // 方案 2: 用户个人主页接口获取收藏歌单及兜底
        if (list.size <= 3) {
            try {
                val homepageUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?g_tk=5381&loginUin=$uin&hostUin=$uin&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&cid=205360838&ct=20&cv=269&reqfrom=1&reqtype=1&userid=$uin&uin=$uin"
                val hpReq = Request.Builder()
                    .url(homepageUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")
                    .header("Origin", "https://y.qq.com")
                    .header("Cookie", account.toCookieHeader())
                    .get()
                    .build()

                val hpResp = client.newCall(hpReq).execute()
                val hpBody = hpResp.body?.string() ?: ""

                if (hpBody.isNotBlank()) {
                    val hpJson = JSONObject(cleanJsonp(hpBody))
                    val hpData = hpJson.optJSONObject("data")
                    if (hpData != null) {
                        // 解析主页的 mydiss
                        val mydiss = hpData.optJSONObject("mydiss")?.optJSONArray("list")
                        if (mydiss != null) {
                            for (i in 0 until mydiss.length()) {
                                val item = mydiss.getJSONObject(i)
                                val id = item.optString("dissid", item.optString("dirid", item.optString("tid", "")))
                                val title = item.optString("title", item.optString("diss_name", ""))
                                val pic = item.optString("pic", item.optString("picurl", ""))
                                val songCnt = item.optInt("song_cnt", item.optInt("song_num", 0))
                                val listenNum = item.optLong("listen_num", 0L)
                                addPlaylist(id, title, pic, songCnt, listenNum, account.nickname, false)
                            }
                        }

                        // 解析主页的 fav_diss (收藏歌单)
                        val favDiss = hpData.optJSONObject("fav_diss")?.optJSONArray("list")
                        if (favDiss != null) {
                            for (i in 0 until favDiss.length()) {
                                val item = favDiss.getJSONObject(i)
                                val id = item.optString("dissid", item.optString("dirid", item.optString("tid", "")))
                                val title = item.optString("title", item.optString("diss_name", ""))
                                val pic = item.optString("pic", item.optString("picurl", ""))
                                val songCnt = item.optInt("song_cnt", item.optInt("song_num", 0))
                                val creator = item.optString("nickname", "QQ 音乐用户")
                                addPlaylist(id, title, pic, songCnt, 0L, creator, true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "主页歌单接口解析失败: ${e.message}")
            }
        }

        AppLogger.i(TAG, "成功为用户 $uin 拉取到 ${list.size} 个自建/收藏歌单")
        list
    }
}
