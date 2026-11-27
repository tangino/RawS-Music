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
        private const val APPID = "716027609"
        private const val DAID = "383"
        private const val PT_3RD_AID = "100497308"
        private const val U1 = "https://graph.qq.com/oauth2.0/login_jump"
        private const val REDIRECT_URI = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%2F%2Fy.qq.com%2F"

        // 会话缓存，保留二维码对应的 login_sig 凭据
        private val qrSessionCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        private val COOKIE_ATTRIBUTES = setOf(
            "path", "domain", "expires", "max-age", "samesite", "secure", "httponly", "priority"
        )

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

        /**
         * 计算腾讯 g_tk (hash33, 初始值为 5381)
         */
        fun getGtk(key: String): Long {
            var hash = 5381L
            for (ch in key) {
                hash += (hash shl 5) + ch.code
            }
            return hash and 0x7fffffffL
        }

        /**
         * 解码腾讯 ptui 回调里的跳转 URL
         */
        fun unescapePtUrl(s: String): String {
            return s.replace("\\/", "/")
                .replace("\\x26", "&")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        }
    }

    override suspend fun getQrCode(): QrCodeData = withContext(Dispatchers.IO) {
        // 第 1 步: 请求 xlogin 获取 pt_login_sig 会话 Cookie
        val xloginUrl = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=$APPID&daid=$DAID&style=33&hide_title_bar=1&hide_border=1&target=self&s_url=${URLEncoder.encode(U1, "UTF-8")}&pt_3rd_aid=$PT_3RD_AID&theme=2"
        val xloginReq = Request.Builder()
            .url(xloginUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://graph.qq.com/")
            .build()

        var ptLoginSig = ""
        try {
            val xloginResp = client.newCall(xloginReq).execute()
            for (header in xloginResp.headers("Set-Cookie")) {
                val parts = header.split(";")
                for (part in parts) {
                    val kv = part.trim().split("=")
                    if (kv.size == 2 && kv[0] == "pt_login_sig") {
                        ptLoginSig = kv[1]
                        break
                    }
                }
                if (ptLoginSig.isNotBlank()) break
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "获取 pt_login_sig 遇到网络异常: ${e.message}")
        }

        // 第 2 步: 请求 ptqrshow 获取二维码与 qrsig
        val timestamp = System.currentTimeMillis()
        val qrShowUrl = "https://xui.ptlogin2.qq.com/ssl/ptqrshow?appid=$APPID&e=2&l=M&s=3&d=72&v=4&t=0.$timestamp&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&u1=${URLEncoder.encode(U1, "UTF-8")}"

        val qrShowReqBuilder = Request.Builder()
            .url(qrShowUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://xui.ptlogin2.qq.com/")

        if (ptLoginSig.isNotBlank()) {
            qrShowReqBuilder.header("Cookie", "pt_login_sig=$ptLoginSig")
        }

        val response = client.newCall(qrShowReqBuilder.build()).execute()
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

        if (ptLoginSig.isNotBlank()) {
            qrSessionCache[qrsig] = ptLoginSig
        }

        val compositeKey = if (ptLoginSig.isNotBlank()) "$qrsig@@$ptLoginSig" else qrsig

        QrCodeData(
            platform = platform,
            key = compositeKey,
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
     * 辅助解析 Set-Cookie 头并加入 Map。
     * 只提取分号前的首个 `name=value`（用 limit=2 避免吞掉含 `=` 的 Base64 Token），
     * 过滤掉 Path / Domain / Expires / Max-Age / SameSite 等 Cookie 属性，且不以空值覆盖已有凭据。
     */
    internal fun extractCookiesInto(header: String, cookieMap: MutableMap<String, String>) {
        val firstPair = header.substringBefore(';').trim()
        if (firstPair.isEmpty()) return
        val eqIdx = firstPair.indexOf('=')
        if (eqIdx <= 0) return
        val name = firstPair.substring(0, eqIdx).trim()
        val value = firstPair.substring(eqIdx + 1).trim()
        if (name.isEmpty()) return
        val lower = name.lowercase()
        if (lower in COOKIE_ATTRIBUTES) return
        if (value.isNotEmpty() || !cookieMap.containsKey(name)) {
            cookieMap[name] = value
        }
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
                    .header("Referer", "https://xui.ptlogin2.qq.com/")

                if (cookieHeader.isNotBlank()) {
                    requestBuilder.header("Cookie", cookieHeader)
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    // 收集当前跳的所有 Set-Cookie
                    for (header in response.headers("Set-Cookie")) {
                        extractCookiesInto(header, cookieMap)
                    }

                    val code = response.code
                    if (code in 300..399) {
                        val location = response.header("Location")
                        if (location.isNullOrBlank()) {
                            currentUrl = ""
                        } else {
                            currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                location
                            } else {
                                try {
                                    java.net.URI(currentUrl).resolve(location).toString()
                                } catch (_: Exception) {
                                    "https://graph.qq.com$location"
                                }
                            }
                            depth++
                        }
                    } else {
                        lastBody = response.body?.string() ?: ""
                        currentUrl = ""
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "重定向跟踪异常(depth=$depth): ${e.message}")
                break
            }
        }
        return Pair(cookieMap, lastBody)
    }

    override suspend fun checkQrStatus(key: String, extra: Map<String, String>): QrCheckResult = withContext(Dispatchers.IO) {
        val (qrsig, loginSig) = if (key.contains("@@")) {
            val parts = key.split("@@", limit = 2)
            Pair(parts[0], parts[1])
        } else {
            val sig = qrSessionCache[key] ?: extra["login_sig"] ?: ""
            Pair(key, sig)
        }

        val ptqrtoken = getPtQrToken(qrsig)
        val timestamp = System.currentTimeMillis()
        val url = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin?u1=${URLEncoder.encode(U1, "UTF-8")}&ptqrtoken=$ptqrtoken&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-$timestamp&js_ver=26030415&js_type=1&login_sig=$loginSig&pt_uistyle=40&aid=$APPID&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&"

        val cookieHeader = buildString {
            append("qrsig=$qrsig")
            if (loginSig.isNotBlank()) {
                append("; pt_login_sig=$loginSig")
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("Cookie", cookieHeader)
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.use { it.body?.string().orEmpty() }
        AppLogger.d(TAG, "ptqrlogin 响应数据: $bodyStr")

        val args = parsePtuiCallback(bodyStr)
        if (args.isEmpty()) {
            return@withContext QrCheckResult(QrStatus.WAITING, "等待扫码中...")
        }

        val code = args.getOrNull(0) ?: ""
        val jumpUrl = unescapePtUrl(args.getOrNull(2) ?: "")
        val message = args.getOrNull(4) ?: ""
        val nickname = args.getOrNull(5) ?: ""

        when (code) {
            "66" -> QrCheckResult(QrStatus.WAITING, message.ifBlank { "请使用手机 QQ 扫码" })
            "67" -> QrCheckResult(QrStatus.SCANNED, message.ifBlank { "二维码已扫描，请在手机上点击确认" })
            "65" -> QrCheckResult(QrStatus.EXPIRED, message.ifBlank { "二维码已失效，请点击刷新" })
            "0" -> {
                AppLogger.i(TAG, "ptqrlogin 收到登录成功通知(code=0)，开始获取跳转凭据")
                // 登录成功，提取第一阶段 Cookie
                val allCookies = mutableMapOf<String, String>()
                allCookies["qrsig"] = qrsig
                if (loginSig.isNotBlank()) allCookies["pt_login_sig"] = loginSig

                for (header in response.headers("Set-Cookie")) {
                    extractCookiesInto(header, allCookies)
                }

                // 跟踪跳转链获取完整登录 Cookie
                if (jumpUrl.isNotBlank()) {
                    val (redirectCookies, _) = followRedirectsAndCollectCookies(jumpUrl, allCookies)
                    allCookies.putAll(redirectCookies)
                }

                // 提取初步 uin 与凭据
                var rawUin = allCookies["uin"] ?: allCookies["p_uin"] ?: allCookies["login_uin"] ?: ""
                // 兜底：若 Cookie 中未提取到 uin，尝试从 jumpUrl 的 uin 参数提取
                if (rawUin.isBlank() && jumpUrl.isNotBlank()) {
                    val uinMatcher = Pattern.compile("[?&]uin=([^&]+)").matcher(jumpUrl)
                    if (uinMatcher.find()) {
                        rawUin = uinMatcher.group(1).orEmpty()
                    }
                }
                rawUin = rawUin.replace("^o0*".toRegex(), "")
                val pSkey = allCookies["p_skey"] ?: allCookies["skey"] ?: ""
                val ui = allCookies["ui"] ?: ""

                var finalUin = rawUin
                var qmKeyst = ""

                // 核心流程：使用 OAuth2.0 换取真正的 QQ 音乐 musickey 与 musicid（容错保护，超时不阻断主流程）
                try {
                    val gtk = if (pSkey.isNotBlank()) getGtk(pSkey) else 5381L
                    val authBody = "response_type=code&client_id=$PT_3RD_AID&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}&scope=get_user_info%2Cget_app_friends&state=state&switch=&from_ptlogin=1&src=1&update_auth=1&openapi=1010_1030&g_tk=$gtk&auth_time=$timestamp&ui=${ui.uppercase()}"
                    val cookieStr = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

                    val authReq = Request.Builder()
                        .url("https://graph.qq.com/oauth2.0/authorize")
                        .header("User-Agent", USER_AGENT)
                        .header("Origin", "https://graph.qq.com")
                        .header("Referer", "https://graph.qq.com/")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookieStr)
                        .post(authBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                        .build()

                    client.newCall(authReq).execute().use { authResp ->
                        for (header in authResp.headers("Set-Cookie")) {
                            extractCookiesInto(header, allCookies)
                        }

                        val loc = authResp.header("Location") ?: ""
                        val authContent = if (loc.isBlank()) (authResp.body?.string() ?: "") else ""
                        val codeMatcher = Pattern.compile("[?&]code=([^&]+)").matcher(if (loc.isNotBlank()) loc else authContent)
                        val oauthCode = if (codeMatcher.find()) {
                            java.net.URLDecoder.decode(codeMatcher.group(1), "UTF-8")
                        } else ""

                        if (oauthCode.isNotBlank()) {
                            AppLogger.d(TAG, "获取 QQ 互联 OAuth code 成功: $oauthCode")
                            val loginKey = allCookies["qqmusic_key"] ?: allCookies["p_skey"] ?: allCookies["skey"] ?: ""
                            val reqComm = JSONObject().apply {
                                put("g_tk", getGtk(loginKey))
                                put("platform", "yqq")
                                put("ct", 24)
                                put("cv", 0)
                            }
                            val reqParam = JSONObject().apply {
                                put("code", oauthCode)
                            }
                            val reqPayload = JSONObject().apply {
                                put("comm", reqComm)
                                put("req", JSONObject().apply {
                                    put("module", "QQConnectLogin.LoginServer")
                                    put("method", "QQLogin")
                                    put("param", reqParam)
                                })
                            }

                            val qqLoginReq = Request.Builder()
                                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                                .header("User-Agent", USER_AGENT)
                                .header("Origin", "https://y.qq.com")
                                .header("Referer", "https://y.qq.com/")
                                .header("Cookie", cookieStr)
                                .header("Content-Type", "application/json; charset=utf-8")
                                .post(reqPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .build()

                            client.newCall(qqLoginReq).execute().use { qqLoginResp ->
                                for (header in qqLoginResp.headers("Set-Cookie")) {
                                    extractCookiesInto(header, allCookies)
                                }
                                val qqLoginBody = qqLoginResp.body?.string() ?: ""
                                AppLogger.d(TAG, "QQLogin 换取响应: $qqLoginBody")
                                val qqLoginJson = JSONObject(qqLoginBody)
                                val loginData = qqLoginJson.optJSONObject("req")?.optJSONObject("data")
                                if (loginData != null) {
                                    val musickey = loginData.optString("musickey", "")
                                    val musicid = loginData.optLong("musicid", 0L)
                                    if (musickey.isNotBlank()) {
                                        qmKeyst = musickey
                                        allCookies["qm_keyst"] = musickey
                                        allCookies["qqmusic_key"] = musickey
                                        allCookies["psrf_musickey_id"] = musickey
                                    }
                                    if (musicid > 0L) {
                                        finalUin = musicid.toString()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "OAuth 置换 QQ 音乐凭据异常: ${e.message}")
                }

                if (finalUin.isNotBlank()) {
                    allCookies["uin"] = finalUin
                    allCookies["login_uin"] = finalUin
                    allCookies["euin"] = finalUin
                }
                val tokens = mutableMapOf<String, String>()
                if (finalUin.isNotBlank()) tokens["uin"] = finalUin
                if (qmKeyst.isNotBlank()) tokens["key"] = qmKeyst

                val defaultNickname = nickname.ifBlank {
                    if (finalUin.isNotBlank()) "QQ 用户 $finalUin" else "QQ 音乐用户"
                }

                val account = PlatformAccount(
                    platform = platform,
                    userId = finalUin.ifBlank { "qq_user" },
                    nickname = defaultNickname,
                    avatarUrl = if (finalUin.isNotBlank()) "https://q1.qlogo.cn/g?b=qq&nk=$finalUin&s=100" else "",
                    cookies = allCookies,
                    tokens = tokens,
                    updatedAt = System.currentTimeMillis()
                )

                // 尝试丰富用户信息与 VIP 状态
                val enrichedAccount = try {
                    refreshUserInfo(account)
                } catch (e: Exception) {
                    account
                }

                AppLogger.i(TAG, "QQ 音乐扫码登录成功: ${enrichedAccount.nickname} (${enrichedAccount.userId})")
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

            val bodyStr = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (bodyStr.isBlank()) return@withContext account
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

        // 方案 2: 若 GetBaseInfo 失败，尝试通过主页接口获取昵称和头像兜底
        try {
            val homepageUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?g_tk=5381&loginUin=$uin&hostUin=$uin&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&cid=205360838&ct=20&cv=269&reqfrom=1&reqtype=1&userid=$uin&uin=$uin"
            val hpReq = Request.Builder()
                .url(homepageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Cookie", account.toCookieHeader())
                .get()
                .build()

            val hpResp = client.newCall(hpReq).execute()
            val hpBody = hpResp.body?.string() ?: ""
            if (hpBody.isNotBlank()) {
                val hpJson = JSONObject(cleanJsonp(hpBody))
                val hpData = hpJson.optJSONObject("data")
                val creator = hpData?.optJSONObject("creator")
                if (creator != null) {
                    val nick = creator.optString("nick", account.nickname)
                    val headpic = creator.optString("headpic", account.avatarUrl)
                    if (nick.isNotBlank() || headpic.isNotBlank()) {
                        return@withContext account.copy(
                            nickname = nick.ifBlank { account.nickname },
                            avatarUrl = headpic.ifBlank { account.avatarUrl },
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        account
    }

    /**
     * 安全去除 JSONP 外层函数包装（例如 `MusicJsonCallback({...})`）。
     * 若响应本身就是纯 JSON（以 `{` 或 `[` 开头），直接原样返回，
     * 避免歌单名/昵称中的圆括号 `(...)` 被误截导致整段 JSON 损坏。
     */
    internal fun cleanJsonp(response: String): String {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        val start = trimmed.indexOf('(')
        val end = trimmed.lastIndexOf(')')
        if (start != -1 && end != -1 && start < end) {
            return trimmed.substring(start + 1, end).trim()
        }
        return trimmed
    }

    private fun resolveNumericUin(account: PlatformAccount): String {
        val candidates = listOf(
            account.userId,
            account.tokens["uin"].orEmpty(),
            account.cookies["uin"].orEmpty(),
            account.cookies["p_uin"].orEmpty(),
            account.cookies["login_uin"].orEmpty(),
            account.cookies["qqmusic_uin"].orEmpty(),
            account.cookies["wxuin"].orEmpty(),
        )
        for (raw in candidates) {
            val cleaned = raw.trim().replace(Regex("^[oO]0*"), "")
            if (cleaned.isNotEmpty() && cleaned.all { it.isDigit() } && cleaned != "0") {
                return cleaned
            }
        }
        return account.userId.trim()
    }

    private fun buildCleanCookieHeader(account: PlatformAccount, uin: String, authKey: String): String {
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in account.cookies) {
            if (k.lowercase() !in COOKIE_ATTRIBUTES && v.isNotBlank()) {
                merged[k] = v
            }
        }
        if (uin.isNotBlank() && uin.all { it.isDigit() }) {
            merged.putIfAbsent("uin", uin)
            merged.putIfAbsent("login_uin", uin)
            merged.putIfAbsent("qqmusic_uin", uin)
        }
        if (authKey.isNotBlank()) {
            merged.putIfAbsent("qm_keyst", authKey)
            merged.putIfAbsent("qqmusic_key", authKey)
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun unescapeHtmlEntities(raw: String): String {
        if (raw.isEmpty() || !raw.contains('&')) return raw
        return raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
    }

    override suspend fun getUserPlaylists(account: PlatformAccount): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val uin = resolveNumericUin(account)
        if (uin.isBlank() || uin == "qq_user") {
            throw IllegalStateException("QQ 账号缺少有效 UIN，请重新扫码或输入 Cookie 登录")
        }

        val list = mutableListOf<OnlinePlaylist>()
        val uinLong = uin.toLongOrNull() ?: 0L
        val authKey = account.cookies["qm_keyst"]?.takeIf { it.isNotBlank() }
            ?: account.cookies["qqmusic_key"]?.takeIf { it.isNotBlank() }
            ?: account.cookies["psrf_musickey_id"]?.takeIf { it.isNotBlank() }
            ?: account.tokens["key"]?.takeIf { it.isNotBlank() }
            ?: ""
        val gtkSource = authKey.ifBlank {
            account.cookies["p_skey"]?.takeIf { it.isNotBlank() }
                ?: account.cookies["skey"]?.takeIf { it.isNotBlank() }
                ?: ""
        }
        val gtk = if (gtkSource.isNotBlank()) getGtk(gtkSource) else 5381L
        val cookieHeader = buildCleanCookieHeader(account, uin, authKey)

        var anyApiSucceeded = false
        var authRejectedCount = 0

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
            val cleanTitle = unescapeHtmlEntities(title.trim())
            if (cleanId.isNotBlank() && cleanId != "0" && cleanTitle.isNotBlank()) {
                if (list.none { it.id == cleanId }) {
                    val normalizedCover = when {
                        coverUrl.startsWith("https://") -> coverUrl
                        coverUrl.startsWith("http://") -> "https://" + coverUrl.removePrefix("http://")
                        coverUrl.startsWith("//") -> "https:$coverUrl"
                        account.avatarUrl.isNotBlank() -> account.avatarUrl
                        else -> ""
                    }

                    list.add(
                        OnlinePlaylist(
                            id = cleanId,
                            platform = platform,
                            title = cleanTitle,
                            coverUrl = normalizedCover,
                            playCount = playCount,
                            trackCount = trackCount,
                            creatorName = unescapeHtmlEntities(creatorName).ifBlank { account.nickname },
                            creatorAvatarUrl = if (!isFavorite) account.avatarUrl else null,
                            customGroup = "QQ 音乐"
                        )
                    )
                }
            }
        }

        // 方案 1 (首选现代网关): musicu.fcg -> music.musicasset.PlaylistBaseRead.GetPlaylistByUin
        // 注意：param.uin 必须为字符串类型（传整数会返回 code=10006），可稳定拉取「我喜欢」与全部自建歌单
        try {
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", 24)
                    put("cv", 4747474)
                    put("g_tk", gtk)
                    if (uinLong > 0L) put("uin", uinLong)
                    if (authKey.isNotBlank()) put("authst", authKey)
                    put("format", "json")
                    put("platform", "yqq.json")
                })
                put("req", JSONObject().apply {
                    put("module", "music.musicasset.PlaylistBaseRead")
                    put("method", "GetPlaylistByUin")
                    put("param", JSONObject().apply {
                        put("uin", uin)
                    })
                })
            }
            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", cookieHeader)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val respBody = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            AppLogger.d(TAG, "QQ musicu 自建歌单接口响应(len=${respBody.length}): ${respBody.take(400)}")
            if (respBody.isNotBlank()) {
                val json = JSONObject(cleanJsonp(respBody))
                val reqObj = json.optJSONObject("req")
                val reqCode = reqObj?.optInt("code", -1) ?: -1
                if (reqCode == 0) {
                    anyApiSucceeded = true
                    val vPlaylist = reqObj?.optJSONObject("data")?.optJSONArray("v_playlist")
                    if (vPlaylist != null) {
                        for (i in 0 until vPlaylist.length()) {
                            val item = vPlaylist.optJSONObject(i) ?: continue
                            val tid = item.optLong("tid", 0L)
                            val dirId = item.optLong("dirId", item.optLong("dirid", 0L))
                            val id = if (tid > 0L) tid.toString() else if (dirId > 0L) "dir_$dirId" else ""
                            val title = item.optString("dirName", item.optString("title", ""))
                            val cover = item.optString("picUrl", "")
                                .ifBlank { item.optString("bigpicUrl", "") }
                                .ifBlank { item.optString("picurl", "") }
                            val songCnt = item.optInt("songNum", item.optInt("song_cnt", 0))
                            val listenNum = item.optLong("listenNum", item.optLong("listen_num", 0L))
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
                } else if (reqCode == 1000 || reqCode == 2001 || reqCode == 4000) {
                    authRejectedCount++
                    AppLogger.w(TAG, "musicu GetPlaylistByUin 返回鉴权错误 code=$reqCode")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "musicu 自建歌单接口解析失败: ${e.message}")
        }

        // 方案 2 (补充自建歌单): fcg_user_created_diss 自建歌单接口
        try {
            val createdDissUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss" +
                "?g_tk=$gtk&g_tk_new_20200303=$gtk&cv=4747474&ct=24&format=json&inCharset=utf-8&outCharset=utf-8" +
                "&notice=0&platform=yqq&needNewCode=1&loginUin=$uin&hostuin=$uin&uin=$uin&sin=0&size=200"
            val req = Request.Builder()
                .url(createdDissUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", cookieHeader)
                .get()
                .build()

            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            AppLogger.d(TAG, "QQ 用户自建歌单接口响应(len=${body.length}): ${body.take(400)}")

            if (body.isNotBlank()) {
                val json = JSONObject(cleanJsonp(body))
                val code = json.optInt("code", -1)
                if (code == 0) {
                    anyApiSucceeded = true
                    val disslist = json.optJSONObject("data")?.optJSONArray("disslist")
                    if (disslist != null) {
                        for (i in 0 until disslist.length()) {
                            val item = disslist.optJSONObject(i) ?: continue
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
                } else if (code == 1000 || code == 2001 || code == 4000) {
                    authRejectedCount++
                    AppLogger.w(TAG, "fcg_user_created_diss 返回鉴权错误 code=$code")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 QQ 用户自建歌单异常: ${e.message}", e)
        }

        // 方案 2: 个人主页接口（始终拉取，同时补齐「我喜欢」歌单、自建歌单与收藏歌单）
        try {
            val homepageUrl = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
                "?g_tk=$gtk&g_tk_new_20200303=$gtk&loginUin=$uin&hostUin=$uin&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0&cid=205360838&ct=20&cv=269&reqfrom=1&reqtype=1&userid=$uin&uin=$uin"
            val hpReq = Request.Builder()
                .url(homepageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", cookieHeader)
                .get()
                .build()

            val hpBody = client.newCall(hpReq).execute().use { it.body?.string().orEmpty() }
            AppLogger.d(TAG, "QQ 个人主页歌单接口响应(len=${hpBody.length}): ${hpBody.take(400)}")

            if (hpBody.isNotBlank()) {
                val hpJson = JSONObject(cleanJsonp(hpBody))
                val code = hpJson.optInt("code", -1)
                if (code == 0) {
                    anyApiSucceeded = true
                    val hpData = hpJson.optJSONObject("data")
                    if (hpData != null) {
                        // 解析「我喜欢」等默认歌单（mymusic）
                        val mymusic = hpData.optJSONArray("mymusic")
                        if (mymusic != null) {
                            for (i in 0 until mymusic.length()) {
                                val item = mymusic.optJSONObject(i) ?: continue
                                val id = item.optString("id", "").ifBlank {
                                    item.optLong("id", 0L).takeIf { it > 0L }?.toString().orEmpty()
                                }
                                val title = item.optString("title", "我喜欢")
                                val pic = item.optString("laypic", item.optString("picurl", ""))
                                val num = item.optInt("num0", item.optInt("num", 0))
                                if (id.isNotBlank() && num > 0) {
                                    addPlaylist(id, title, pic, num, 0L, account.nickname, false)
                                }
                            }
                        }

                        // 解析主页的 mydiss（自建歌单）
                        val mydiss = hpData.optJSONObject("mydiss")?.optJSONArray("list")
                        if (mydiss != null) {
                            for (i in 0 until mydiss.length()) {
                                val item = mydiss.optJSONObject(i) ?: continue
                                val id = item.optString("dissid", "")
                                    .ifBlank { item.optString("tid", "") }
                                    .ifBlank { item.optString("dirid", "").takeIf { it.isNotBlank() && it != "0" }?.let { "dir_$it" }.orEmpty() }
                                val title = item.optString("title", item.optString("diss_name", ""))
                                val pic = item.optString("picurl", item.optString("pic", ""))
                                val songCnt = item.optInt("song_cnt", item.optInt("song_num", 0))
                                val listenNum = item.optLong("listen_num", 0L)
                                addPlaylist(id, title, pic, songCnt, listenNum, account.nickname, false)
                            }
                        }

                        // 解析主页的 fav_diss（收藏歌单）
                        val favDiss = hpData.optJSONObject("fav_diss")?.optJSONArray("list")
                        if (favDiss != null) {
                            for (i in 0 until favDiss.length()) {
                                val item = favDiss.optJSONObject(i) ?: continue
                                val id = item.optString("dissid", "")
                                    .ifBlank { item.optString("tid", "") }
                                    .ifBlank { item.optString("dirid", "") }
                                val title = item.optString("title", item.optString("diss_name", ""))
                                val pic = item.optString("picurl", item.optString("pic", ""))
                                val songCnt = item.optInt("song_cnt", item.optInt("song_num", 0))
                                val creator = item.optString("nickname", "QQ 音乐用户")
                                addPlaylist(id, title, pic, songCnt, 0L, creator, true)
                            }
                        }
                    }
                } else if (code == 1000 || code == 2001 || code == 4000) {
                    authRejectedCount++
                    AppLogger.w(TAG, "fcg_get_profile_homepage 返回鉴权错误 code=$code")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "主页歌单接口解析失败: ${e.message}")
        }

        // 方案 3: 收藏歌单专用接口 fcg_get_profile_order_asset.fcg（独立补全全部收藏歌单）
        try {
            val favUrl = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg" +
                "?g_tk=$gtk&g_tk_new_20200303=$gtk&loginUin=$uin&hostUin=$uin&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0&ct=20&cid=205360956&userid=$uin&reqtype=3&sin=0&ein=100"
            val favReq = Request.Builder()
                .url(favUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Origin", "https://y.qq.com")
                .header("Cookie", cookieHeader)
                .get()
                .build()

            val favBody = client.newCall(favReq).execute().use { it.body?.string().orEmpty() }
            if (favBody.isNotBlank()) {
                val favJson = JSONObject(cleanJsonp(favBody))
                if (favJson.optInt("code", -1) == 0) {
                    anyApiSucceeded = true
                    val cdlist = favJson.optJSONObject("data")?.optJSONArray("cdlist")
                    if (cdlist != null) {
                        for (i in 0 until cdlist.length()) {
                            val item = cdlist.optJSONObject(i) ?: continue
                            val id = item.optString("dissid", "")
                                .ifBlank { item.optLong("dissid", 0L).takeIf { it > 0L }?.toString().orEmpty() }
                            val title = item.optString("dissname", item.optString("title", ""))
                            val pic = item.optString("logo", item.optString("picurl", ""))
                            val songCnt = item.optInt("songnum", item.optInt("song_cnt", 0))
                            val listenNum = item.optLong("listennum", 0L)
                            val creator = item.optString("nickname", "QQ 音乐用户")
                            addPlaylist(id, title, pic, songCnt, listenNum, creator, true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "收藏歌单接口解析失败: ${e.message}")
        }

        if (list.isEmpty() && !anyApiSucceeded) {
            val hint = if (authRejectedCount > 0) {
                "QQ 音乐登录凭据已失效或未获歌单授权，请重新扫码或更新 Cookie"
            } else {
                "拉取 QQ 音乐歌单失败，请检查网络后重试"
            }
            throw IllegalStateException(hint)
        }

        AppLogger.i(TAG, "成功为用户 $uin 拉取到 ${list.size} 个自建/收藏歌单")
        list
    }
}
