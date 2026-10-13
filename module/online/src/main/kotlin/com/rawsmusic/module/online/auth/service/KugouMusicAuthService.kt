package com.rawsmusic.module.online.auth.service

import android.graphics.BitmapFactory
import android.util.Base64
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.auth.model.PlatformAccount
import com.rawsmusic.module.online.auth.model.QrCheckResult
import com.rawsmusic.module.online.auth.model.QrCodeData
import com.rawsmusic.module.online.auth.model.QrStatus
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlineSongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 酷狗音乐扫码认证与用户服务实现
 */
class KugouMusicAuthService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) : IPlatformAuthService {

    override val platform: OnlinePlatform = OnlinePlatform.KUGOU

    companion object {
        private const val TAG = "KugouMusicAuthService"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val APP_ID = "1005"
        private const val SRC_APP_ID = "2919"
        private const val PLAT_ID = "4"
        private const val WEB_SIGN_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        private const val ANDROID_SIGN_SALT = "OIlwieks28dk2k092lksi2UIkp"
    }

    /**
     * 计算 MD5 哈希（32位小写十六进制）
     */
    private fun calculateMd5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算设备 MID（对应 MakcRe/KuGouMusicApi 中 calculateMid 算法）
     */
    private fun calculateMid(str: String): String {
        val digest = calculateMd5(str)
        return try {
            java.math.BigInteger(digest, 16).toString()
        } catch (_: Exception) {
            "0"
        }
    }

    /**
     * 获取客户端固定 MID 标识
     */
    private fun getMid(): String {
        return calculateMid("kugou_orbit_player_client")
    }

    /**
     * 酷狗 Web 版请求签名（encryptType: web）
     */
    private fun signWebParams(params: Map<String, Any>, data: String = ""): String {
        val sortedParamsStr = params.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key=$value"
        }
        return calculateMd5("$WEB_SIGN_SALT$sortedParamsStr$data$WEB_SIGN_SALT")
    }

    /**
     * 酷狗 Android 客户端网关请求签名（encryptType: android）
     */
    private fun signAndroidParams(params: Map<String, Any>, bodyJson: String): String {
        val sortedParamsStr = params.toSortedMap().entries.joinToString("") { (key, value) ->
            "$key=$value"
        }
        return calculateMd5("$ANDROID_SIGN_SALT$sortedParamsStr$bodyJson$ANDROID_SIGN_SALT")
    }

    override suspend fun getQrCode(): QrCodeData = withContext(Dispatchers.IO) {
        val clientTimeSec = System.currentTimeMillis() / 1000
        val mid = getMid()
        val queryParams = linkedMapOf<String, Any>(
            "appid" to 1014,
            "clienttime" to clientTimeSec,
            "clientver" to 20489,
            "dfid" to "-",
            "mid" to mid,
            "plat" to 4,
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&",
            "srcappid" to 2919,
            "type" to 1,
            "uuid" to "-"
        )
        val signature = signWebParams(queryParams)
        queryParams["signature"] = signature

        val queryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}" }
        val url = "https://login-user.kugou.com/v2/qrcode?$queryString"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kugou.com/")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw IllegalStateException("获取酷狗二维码响应为空")
        val json = JSONObject(bodyStr)
        val data = json.optJSONObject("data") ?: throw IllegalStateException("酷狗二维码数据为空: $bodyStr")

        val key = data.optString("qrcode", data.optString("key", ""))
        val qrcodeImg = data.optString("qrcode_img", data.optString("qrcode", ""))

        if (key.isBlank()) {
            throw IllegalStateException("酷狗二维码凭证 key 获取失败")
        }

        var imageBytes: ByteArray? = null
        if (qrcodeImg.startsWith("data:image")) {
            val base64Data = qrcodeImg.substringAfter("base64,")
            imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
        }

        val qrUrl = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"

        QrCodeData(
            platform = platform,
            key = key,
            qrUrl = qrUrl,
            qrImageBytes = imageBytes,
            expireTimeMs = 120_000L
        )
    }

    override suspend fun checkQrStatus(key: String, extra: Map<String, String>): QrCheckResult = withContext(Dispatchers.IO) {
        val clientTimeSec = System.currentTimeMillis() / 1000
        val mid = getMid()
        val queryParams = linkedMapOf<String, Any>(
            "appid" to 1005,
            "clienttime" to clientTimeSec,
            "clientver" to 20489,
            "dfid" to "-",
            "mid" to mid,
            "plat" to 4,
            "qrcode" to key,
            "srcappid" to 2919,
            "uuid" to "-"
        )
        val signature = signWebParams(queryParams)
        queryParams["signature"] = signature

        val queryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}" }
        val url = "https://login-user.kugou.com/v2/get_userinfo_qrcode?$queryString"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kugou.com/")
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: return@withContext QrCheckResult(QrStatus.ERROR, "响应为空")
        val json = JSONObject(bodyStr)
        val data = json.optJSONObject("data")

        // 状态码：0 为过期，1 为等待扫码，2 为待确认，4 为授权登录成功
        val status = data?.optInt("status", -1) ?: json.optInt("status", -1)

        when (status) {
            1 -> QrCheckResult(QrStatus.WAITING, "请使用酷狗音乐 App 扫码")
            2 -> QrCheckResult(QrStatus.SCANNED, "二维码已扫描，请在手机上点击确认")
            0 -> QrCheckResult(QrStatus.EXPIRED, "二维码已失效，请点击刷新")
            4 -> {
                // 登录成功
                val token = data?.optString("token", "") ?: ""
                val userid = data?.optString("userid", "") ?: ""

                if (token.isBlank() || userid.isBlank()) {
                    return@withContext QrCheckResult(QrStatus.ERROR, "登录成功但未能解析到 Token 或 UserID")
                }

                val nickname = (data?.optString("nickname")?.takeIf { it.isNotBlank() }
                    ?: data?.optString("username")?.takeIf { it.isNotBlank() }
                    ?: "酷狗用户_$userid")
                val pic = data?.optString("pic", "") ?: ""
                val vipType = data?.optInt("vip_type", 0) ?: 0

                val cookiesMap = mapOf(
                    "token" to token,
                    "userid" to userid,
                    "KugouID" to userid,
                    "KUGOU_API_MID" to mid,
                    "dfid" to "-"
                )

                val account = PlatformAccount(
                    platform = platform,
                    userId = userid,
                    nickname = nickname.ifBlank { "酷狗用户_$userid" },
                    avatarUrl = pic,
                    isVip = vipType > 0,
                    vipLevel = if (vipType > 0) "VIP $vipType" else "",
                    cookies = cookiesMap,
                    tokens = mapOf("token" to token, "userid" to userid, "mid" to mid),
                    updatedAt = System.currentTimeMillis()
                )

                // 尝试丰富用户信息
                val enriched = try {
                    refreshUserInfo(account)
                } catch (e: Exception) {
                    account
                }

                QrCheckResult(QrStatus.SUCCESS, "登录成功", enriched)
            }
            else -> QrCheckResult(QrStatus.ERROR, json.optString("error_msg", "扫码状态异常($status)"))
        }
    }

    override suspend fun refreshUserInfo(account: PlatformAccount): PlatformAccount = withContext(Dispatchers.IO) {
        val token = account.getPrimaryToken()
        val userid = account.userId
        if (token.isBlank() || userid.isBlank()) return@withContext account

        val candidateUrls = listOf(
            "https://gateway.kugou.com/v3/user/get_user_info?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID&clienttime=${System.currentTimeMillis() / 1000}&clientver=20489",
            "https://gateway.kugou.com/v1/user/get_user_info?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID",
            "https://www.kugou.com/yy/index.php?r=login/getuserinfo&userid=$userid&token=$token"
        )

        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kugou.com/")
                    .header("Origin", "https://www.kugou.com")
                    .header("Cookie", account.toCookieHeader())

                if (url.contains("gateway.kugou.com")) {
                    reqBuilder.header("x-router", "usercenter.kugou.com")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                val bodyStr = response.body?.string() ?: continue
                val json = JSONObject(bodyStr)
                val data = json.optJSONObject("data") ?: json.optJSONObject("response") ?: json

                val nickname = data.optString("nickname", data.optString("username", account.nickname))
                val pic = data.optString("pic", data.optString("user_pic", account.avatarUrl))
                val vipType = data.optInt("vip_type", data.optInt("vipType", 0))
                val isVip = vipType > 0
                val vipEndTime = data.optLong("vip_end_time", 0L)

                if (nickname.isNotBlank() || pic.isNotBlank() || isVip) {
                    return@withContext account.copy(
                        nickname = nickname.ifBlank { account.nickname },
                        avatarUrl = pic.ifBlank { account.avatarUrl },
                        isVip = isVip,
                        vipLevel = if (isVip) "VIP $vipType" else "",
                        vipExpireTime = if (vipEndTime > 0) vipEndTime * 1000L else account.vipExpireTime,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "刷新酷狗音乐用户信息异常 (url=$url): ${e.message}")
            }
        }
        account
    }

    /**
     * 通过酷狗网关云歌单服务 (/v7/get_all_list) 获取指定类型的歌单
     * @param type 歌单类型：1 为自建歌单，2 为收藏/云歌单
     */
    private fun fetchCloudPlaylistsByType(
        account: PlatformAccount,
        type: Int,
        page: Int = 1,
        pageSize: Int = 100
    ): List<OnlinePlaylist> {
        val token = account.getPrimaryToken()
        val userid = account.userId
        val clientTimeSec = System.currentTimeMillis() / 1000
        val mid = account.tokens["mid"] ?: account.cookies["KUGOU_API_MID"] ?: getMid()

        val bodyJsonObj = JSONObject().apply {
            put("userid", userid.toLongOrNull() ?: userid)
            put("token", token)
            put("total_ver", 979)
            put("type", type)
            put("page", page)
            put("pagesize", pageSize)
        }
        val bodyStr = bodyJsonObj.toString()

        val queryParams = linkedMapOf<String, Any>(
            "appid" to 1005,
            "clienttime" to clientTimeSec,
            "clientver" to 20489,
            "dfid" to "-",
            "mid" to mid,
            "plat" to 1,
            "token" to token,
            "userid" to (userid.toLongOrNull() ?: userid),
            "uuid" to "-"
        )
        val signature = signAndroidParams(queryParams, bodyStr)
        queryParams["signature"] = signature

        val queryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val url = "https://gateway.kugou.com/v7/get_all_list?$queryString"

        val requestBody = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("x-router", "cloudlist.service.kugou.com")
            .header("dfid", "-")
            .header("mid", mid)
            .header("clienttime", clientTimeSec.toString())
            .header("kg-rc", "1")
            .header("kg-thash", "5d816a0")
            .header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")

        val cookie = account.toCookieHeader()
        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
        }

        val response = client.newCall(reqBuilder.build()).execute()
        val bodyStrResponse = response.body?.string() ?: return emptyList()
        AppLogger.d(TAG, "酷狗云歌单(/v7/get_all_list, type=$type) 响应: ${bodyStrResponse.take(300)}")

        val json = try {
            JSONObject(bodyStrResponse)
        } catch (_: Exception) {
            return emptyList()
        }

        val errorCode = json.optInt("error_code", 0)
        if (errorCode == 20017) {
            AppLogger.w(TAG, "酷狗云歌单返回 20017 (登录凭证失效或需重新扫码授权登录)")
        }

        val data = json.optJSONObject("data") ?: json.optJSONObject("response") ?: json
        val arrayKeys = listOf("info", "lists", "list", "playlists", "special_list", "rows", "items", "collect", "created", "songlist")
        val arrays = mutableListOf<JSONArray>()
        for (k in arrayKeys) {
            val arr = data.optJSONArray(k) ?: json.optJSONArray(k)
            if (arr != null && arr.length() > 0) {
                arrays.add(arr)
            }
        }
        val rootArr = json.optJSONArray("data")
        if (rootArr != null && rootArr.length() > 0) {
            arrays.add(rootArr)
        }

        val result = mutableListOf<OnlinePlaylist>()
        val seen = mutableSetOf<String>()

        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val listId = item.optString("listid", item.optString("specialid", item.optString("id", item.optString("global_collection_id", item.optString("collection_id", item.optString("dissid", ""))))))
                if (listId.isBlank() || !seen.add(listId)) continue

                val title = item.optString("name", item.optString("specialname", item.optString("title", item.optString("playlist_name", item.optString("collection_name", "未命名歌单")))))
                var pic = item.optString("pic", item.optString("img", item.optString("cover", item.optString("pic_300", item.optString("flexible_cover", "")))))
                if (pic.contains("{size}")) {
                    pic = pic.replace("{size}", "400")
                }
                val count = item.optInt("count", item.optInt("songcount", item.optInt("total", item.optInt("song_count", item.optInt("track_count", 0)))))
                val itemType = item.optInt("type", type)
                val groupName = if (itemType == 1) "我创建的歌单" else "我收藏的歌单"

                result.add(
                    OnlinePlaylist(
                        id = listId,
                        platform = platform,
                        title = title,
                        coverUrl = pic,
                        trackCount = count,
                        creatorName = item.optString("username", item.optString("nickname", account.nickname)),
                        creatorAvatarUrl = item.optString("user_pic", account.avatarUrl),
                        customGroup = groupName
                    )
                )
            }
        }
        return result
    }

    override suspend fun getUserPlaylists(account: PlatformAccount): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val token = account.getPrimaryToken()
        val userid = account.userId
        if (token.isBlank() || userid.isBlank()) return@withContext emptyList()

        val list = mutableListOf<OnlinePlaylist>()
        val seenIds = mutableSetOf<String>()

        // 1. 优先调用酷狗网关云歌单接口 /v7/get_all_list (cloudlist.service.kugou.com)
        val cloudTypes = listOf(2, 1)
        for (type in cloudTypes) {
            try {
                val cloudLists = fetchCloudPlaylistsByType(account, type = type)
                for (p in cloudLists) {
                    if (seenIds.add(p.id)) {
                        list.add(p)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取酷狗云歌单异常(type=$type): ${e.message}")
            }
        }

        if (list.isNotEmpty()) {
            AppLogger.d(TAG, "成功从云歌单接口拉取到 ${list.size} 个酷狗歌单")
            return@withContext list
        }

        // 2. 若云歌单接口未拉取到数据，降级回退至备选接口
        val candidateUrls = listOf(
            "https://login.user.kugou.com/v1/user_playlist/get_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID",
            "https://login.user.kugou.com/v1/user/get_user_info?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID",
            "https://login.user.kugou.com/v1/user_playlist/get_collect_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID",
            "https://gateway.kugou.com/v1/user_playlist/get_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID&pagesize=100",
            "https://gateway.kugou.com/v2/user_playlist/get_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID&pagesize=100",
            "https://gateway.kugou.com/v3/user_playlist/get_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID&pagesize=100",
            "http://m.kugou.com/user/plist?userid=$userid&token=$token&json=true",
            "http://m.kugou.com/user/collection?userid=$userid&token=$token&json=true",
            "https://www.kugou.com/yy/index.php?r=play/getlist&userid=$userid&token=$token",
            "http://mobilecdn.kugou.com/api/v3/special/list?userid=$userid&token=$token&pagesize=100",
            "https://gateway.kugou.com/openapi/v1/user_playlist/get_list?userid=$userid&token=$token&appid=$APP_ID&platid=$PLAT_ID&pagesize=100"
        )

        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kugou.com/")
                    .header("Origin", "https://www.kugou.com")
                    .header("Cookie", account.toCookieHeader())

                if (url.contains("gateway.kugou.com")) {
                    reqBuilder.header("x-router", "user_playlist.kugou.com")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                val bodyStr = response.body?.string() ?: continue
                AppLogger.d(TAG, "酷狗歌单备选响应 (url=$url): ${bodyStr.take(500)}")
                if (bodyStr.isBlank() || bodyStr.startsWith("<html", ignoreCase = true)) continue

                val json = try {
                    JSONObject(bodyStr)
                } catch (_: Exception) {
                    continue
                }

                val data = json.optJSONObject("data") ?: json.optJSONObject("response") ?: json
                val arrayKeys = listOf("info", "lists", "list", "playlists", "special_list", "rows", "items", "collect", "created", "songlist")
                val arrays = mutableListOf<JSONArray>()
                for (k in arrayKeys) {
                    val arr = data.optJSONArray(k) ?: json.optJSONArray(k)
                    if (arr != null && arr.length() > 0) {
                        arrays.add(arr)
                    }
                }

                val rootArr = json.optJSONArray("data")
                if (rootArr != null && rootArr.length() > 0) {
                    arrays.add(rootArr)
                }

                for (arr in arrays) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val listId = item.optString("listid", item.optString("specialid", item.optString("id", item.optString("global_collection_id", item.optString("collection_id", item.optString("dissid", ""))))))
                        val title = item.optString("name", item.optString("specialname", item.optString("title", item.optString("playlist_name", item.optString("collection_name", "未命名歌单")))))
                        val pic = item.optString("pic", item.optString("img", item.optString("cover", item.optString("pic_300", item.optString("flexible_cover", "")))))
                        val count = item.optInt("count", item.optInt("songcount", item.optInt("total", item.optInt("song_count", item.optInt("track_count", 0)))))

                        if (listId.isNotBlank() && !seenIds.contains(listId)) {
                            seenIds.add(listId)
                            list.add(
                                OnlinePlaylist(
                                    id = listId,
                                    platform = platform,
                                    title = title,
                                    coverUrl = pic,
                                    trackCount = count,
                                    creatorName = item.optString("username", item.optString("nickname", account.nickname)),
                                    creatorAvatarUrl = item.optString("user_pic", account.avatarUrl),
                                    customGroup = "酷狗音乐"
                                )
                            )
                        }
                    }
                }

                if (list.isNotEmpty()) {
                    AppLogger.d(TAG, "成功从备选接口拉取到 ${list.size} 个酷狗歌单 (url=$url)")
                    break
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "获取酷狗歌单候选接口异常 (url=$url): ${e.message}")
            }
        }
        list
    }

    override suspend fun getPlaylistTracks(playlistId: String, account: PlatformAccount?): Pair<OnlinePlaylist?, List<OnlineSongItem>> = withContext(Dispatchers.IO) {
        val clientTimeSec = System.currentTimeMillis() / 1000
        val mid = getMid()
        val token = account?.getPrimaryToken() ?: ""
        val userid = account?.userId ?: "0"

        val songs = mutableListOf<OnlineSongItem>()
        var playlistInfo: OnlinePlaylist? = null

        // 方案 1: 优先请求酷狗云歌单核心接口 (/v4/get_list_all_file_v3)
        try {
            val bodyJsonObj = JSONObject().apply {
                put("listid", playlistId.toLongOrNull() ?: playlistId)
                put("userid", userid.toLongOrNull() ?: userid)
                put("token", token)
                put("type", 0)
                put("page", 1)
                put("pagesize", 300)
                put("area_code", 1)
                put("allplatform", 1)
                put("show_cover", 1)
            }
            val bodyStr = bodyJsonObj.toString()

            val queryParams = linkedMapOf<String, Any>(
                "appid" to 1005,
                "clienttime" to clientTimeSec,
                "clientver" to 20489,
                "dfid" to "-",
                "mid" to mid,
                "plat" to 1,
                "token" to token,
                "userid" to (userid.toLongOrNull() ?: userid),
                "uuid" to "-"
            )
            val signature = signAndroidParams(queryParams, bodyStr)
            queryParams["signature"] = signature

            val queryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=$v" }
            val url = "https://gateway.kugou.com/v4/get_list_all_file_v3?$queryString"

            val requestBody = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
                .header("x-router", "cloudlist.service.kugou.com")
                .header("dfid", "-")
                .header("mid", mid)
                .header("clienttime", clientTimeSec.toString())
                .header("kg-rc", "1")
                .header("kg-thash", "5d816a0")
                .header("kg-rec", "1")
                .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")

            val cookie = account?.toCookieHeader() ?: ""
            if (cookie.isNotBlank()) {
                reqBuilder.header("Cookie", cookie)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val respBody = response.body?.string() ?: ""
            AppLogger.d(TAG, "酷狗云歌单歌曲接口(/v4/get_list_all_file_v3) 响应: ${respBody.take(400)}")

            if (respBody.isNotBlank()) {
                val json = JSONObject(respBody)
                val data = json.optJSONObject("data") ?: json
                // 解析歌单基础信息
                val listName = data.optString("list_name", data.optString("name", ""))
                val listPic = data.optString("pic", data.optString("img", data.optString("cover", ""))).replace("{size}", "400")
                val listAuthor = data.optString("list_create_username", data.optString("nickname", account?.nickname ?: "酷狗用户"))

                // 解析歌曲列表
                val songArrays = listOf("info", "lists", "list", "songs", "items")
                for (key in songArrays) {
                    val arr = data.optJSONArray(key)
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) {
                            val sObj = arr.optJSONObject(i) ?: continue
                            val songItem = parseSongObject(sObj, listPic)
                            if (songItem != null) {
                                songs.add(songItem)
                            }
                        }
                        if (songs.isNotEmpty()) break
                    }
                }

                if (listName.isNotBlank() || listPic.isNotBlank()) {
                    playlistInfo = OnlinePlaylist(
                        id = playlistId,
                        platform = platform,
                        title = listName.ifBlank { "酷狗歌单" },
                        coverUrl = listPic.ifBlank { songs.firstOrNull()?.coverUrl ?: "" },
                        trackCount = if (songs.isNotEmpty()) songs.size else data.optInt("count", 0),
                        creatorName = listAuthor
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "调用酷狗云歌单歌曲接口(/v4/get_list_all_file_v3)异常: ${e.message}")
        }

        if (songs.isNotEmpty()) {
            AppLogger.i(TAG, "成功从云歌单接口(/v4/get_list_all_file_v3)获取到 ${songs.size} 首歌曲")
            return@withContext Pair(playlistInfo, songs)
        }

        // 方案 2: 若方案 1 未获取到，尝试新版公共/收藏集合接口 (/pubsongs/v2/get_other_list_file_nofilt)
        try {
            val queryParams = linkedMapOf<String, Any>(
                "appid" to 1005,
                "area_code" to 1,
                "begin_idx" to 0,
                "clienttime" to clientTimeSec,
                "clientver" to 20489,
                "dfid" to "-",
                "extend_fields" to "abtags,hot_cmt,popularization",
                "global_collection_id" to playlistId,
                "mid" to mid,
                "mode" to 1,
                "pagesize" to 300,
                "personal_switch" to 1,
                "plat" to 1,
                "type" to 1,
                "uuid" to "-"
            )
            val signature = signAndroidParams(queryParams, "")
            queryParams["signature"] = signature

            val queryString = queryParams.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}" }
            val url = "https://gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt?$queryString"

            val reqBuilder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
                .header("dfid", "-")
                .header("mid", mid)
                .header("clienttime", clientTimeSec.toString())

            val response = client.newCall(reqBuilder.build()).execute()
            val respBody = response.body?.string() ?: ""
            AppLogger.d(TAG, "酷狗公共集合歌曲(/pubsongs/v2/get_other_list_file_nofilt) 响应: ${respBody.take(400)}")

            if (respBody.isNotBlank()) {
                val json = JSONObject(respBody)
                val data = json.optJSONObject("data") ?: json
                val arr = data.optJSONArray("info") ?: data.optJSONArray("list") ?: data.optJSONArray("songs")
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val songItem = parseSongObject(sObj, "")
                        if (songItem != null) {
                            songs.add(songItem)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "调用酷狗公共集合歌曲接口异常: ${e.message}")
        }

        Pair(playlistInfo, songs)
    }

    private fun parseSongObject(sObj: JSONObject, defaultCover: String): OnlineSongItem? {
        val hash = sObj.optString("hash").ifEmpty {
            sObj.optString("audio_hash").ifEmpty {
                sObj.optString("filehash").ifEmpty {
                    sObj.optString("320hash").ifEmpty {
                        sObj.optString("sqhash")
                    }
                }
            }
        }
        val audioId = sObj.optString("audio_id").ifEmpty {
            sObj.optString("album_audio_id").ifEmpty {
                sObj.optString("mixsongid").ifEmpty {
                    sObj.optString("id")
                }
            }
        }
        val id = hash.ifEmpty { audioId }
        if (id.isEmpty()) return null

        val rawFilename = sObj.optString("name").ifEmpty {
            sObj.optString("filename").ifEmpty {
                sObj.optString("audio_name").ifEmpty {
                    sObj.optString("songname")
                }
            }
        }
        val filename = rawFilename.replace(Regex("\\.(mp3|flac|wav|ape|ogg|m4a|aac)$", RegexOption.IGNORE_CASE), "").trim()
        val remark = sObj.optString("remark")

        var singerName = sObj.optString("author_name").ifEmpty {
            sObj.optString("singer_name").ifEmpty {
                sObj.optString("singername")
            }
        }
        if (singerName.isBlank()) {
            val singerInfoArr = sObj.optJSONArray("singerinfo")
            if (singerInfoArr != null && singerInfoArr.length() > 0) {
                val names = mutableListOf<String>()
                for (j in 0 until singerInfoArr.length()) {
                    val sName = singerInfoArr.optJSONObject(j)?.optString("name")
                    if (!sName.isNullOrBlank()) names.add(sName.trim())
                }
                if (names.isNotEmpty()) singerName = names.joinToString(" & ")
            }
        }
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

        var songName = sObj.optString("songname")
        if (songName.isBlank() && remark.isNotBlank()) songName = remark
        if (songName.isBlank() && filename.contains("-")) songName = filename.substringAfter("-").trim()
        if (songName.isBlank()) songName = filename.ifEmpty { "酷狗单曲" }

        var albumName = sObj.optString("album_name").ifEmpty { sObj.optString("album_title") }
        if (albumName.isBlank()) albumName = sObj.optJSONObject("albuminfo")?.optString("name") ?: ""
        if (albumName.isBlank()) albumName = sObj.optJSONObject("album_info")?.optString("album_name") ?: ""
        if (albumName.isBlank()) albumName = sObj.optJSONObject("trans_param")?.optString("album_name") ?: ""
        if (albumName.isBlank()) albumName = if (remark.isNotBlank() && remark != songName) remark else "单曲"

        var songCover = sObj.optString("cover").ifEmpty {
            sObj.optString("pic").ifEmpty {
                sObj.optString("img").ifEmpty {
                    sObj.optString("album_sizable_cover")
                }
            }
        }
        val unionCover = sObj.optJSONObject("trans_param")?.optString("union_cover")
        if (!unionCover.isNullOrBlank()) {
            songCover = unionCover
        }
        if (songCover.isNotBlank()) {
            songCover = songCover.replace("{size}", "400")
        } else {
            songCover = defaultCover
        }

        var durationMs = sObj.optLong("timelen", sObj.optLong("timelength", sObj.optLong("duration", 0L)))
        if (durationMs in 1..3600) {
            durationMs *= 1000
        }

        return OnlineSongItem(
            id = id,
            platform = platform,
            title = songName.trim(),
            artist = singerName.trim(),
            album = albumName.trim(),
            albumId = sObj.optString("album_id").ifEmpty { sObj.optString("album_audio_id").ifEmpty { null } },
            durationMs = durationMs,
            coverUrl = songCover.ifBlank { null }
        )
    }
}
