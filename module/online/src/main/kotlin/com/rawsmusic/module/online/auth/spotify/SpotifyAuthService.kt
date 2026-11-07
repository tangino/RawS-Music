package com.rawsmusic.module.online.auth.spotify

import android.util.Base64
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import com.rawsmusic.module.online.store.PodcastStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Spotify 访问令牌刷新结果。
 */
data class TokenRefreshResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSec: Long,
)

/**
 * Spotify 关注播客同步报告。
 */
data class SpotifySyncReport(
    val success: Boolean,
    val totalShowsFound: Int = 0,
    val newAddedCount: Int = 0,
    val alreadyFavoritedCount: Int = 0,
    val errorMessage: String? = null,
)

/**
 * Spotify 授权与 API 通讯服务层。
 *
 * 实现了标准 OAuth 2.0 PKCE 流程与个人资料/关注播客同步。
 */
object SpotifyAuthService {
    private const val TAG = "SpotifyAuthService"
    const val CLIENT_ID = "bafaaa218fe747b8b4c8141177123a97"
    const val CLIENT_SECRET = "e3cc6815fab444d59e403956ca2a96f7"
    const val REDIRECT_URI = "rawsmusic://spotify-callback"

    private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    private const val ME_ENDPOINT = "https://api.spotify.com/v1/me"
    private const val SAVED_SHOWS_ENDPOINT = "https://api.spotify.com/v1/me/shows?limit=50"
    private const val SAVED_EPISODES_ENDPOINT = "https://api.spotify.com/v1/me/episodes?limit=50"
    private const val KEY_VERIFIER = "spotify_active_code_verifier_v1"

    private val mmkv by lazy { com.tencent.mmkv.MMKV.defaultMMKV() }

    @Volatile
    var lastExchangeError: String? = null

    /**
     * 准备并持久化一套全新的 PKCE 授权链接与对应 Verifier（避免页面重建导致状态丢失）。
     */
    fun prepareNewAuthUrl(redirectUri: String = REDIRECT_URI): String {
        val verifier = generateCodeVerifier()
        mmkv.encode(KEY_VERIFIER, verifier)
        lastExchangeError = null
        AppLogger.i(TAG, "已持久化当前授权流程的 PKCE verifier")
        return buildAuthorizeUrl(verifier, redirectUri)
    }

    /**
     * 获取当前持久化保存的 PKCE verifier。
     */
    fun getPersistedVerifier(): String {
        val saved = mmkv.decodeString(KEY_VERIFIER)
        if (!saved.isNullOrBlank()) return saved
        val fallback = generateCodeVerifier()
        mmkv.encode(KEY_VERIFIER, fallback)
        return fallback
    }

    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val permissiveSslSocketFactory = runCatching {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
        }.socketFactory
    }.getOrNull()

    private val client = OkHttpClient.Builder().apply {
        connectTimeout(25, TimeUnit.SECONDS)
        readTimeout(25, TimeUnit.SECONDS)
        retryOnConnectionFailure(true)
        if (permissiveSslSocketFactory != null) {
            sslSocketFactory(permissiveSslSocketFactory, permissiveTrustManager)
            hostnameVerifier { _, _ -> true }
        }
    }.build()

    /**
     * 生成 OAuth 2.0 PKCE code_verifier（43-128 位的安全随机字符串）。
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * 计算 PKCE code_challenge (SHA-256 + URL-Safe Base64)。
     */
    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * 构造完整的 Spotify OAuth 授权链接。
     */
    fun buildAuthorizeUrl(codeVerifier: String, redirectUri: String = REDIRECT_URI): String {
        val challenge = generateCodeChallenge(codeVerifier)
        val scopes = listOf(
            "user-read-private",
            "user-read-email",
            "user-library-read",
            "user-library-modify",
            "user-follow-read",
            "playlist-read-private",
            "user-read-playback-state",
        ).joinToString(" ")

        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
        val encodedScope = URLEncoder.encode(scopes, "UTF-8")
        val encodedChallenge = URLEncoder.encode(challenge, "UTF-8")

        return "$AUTH_ENDPOINT?client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&redirect_uri=$encodedRedirect" +
                "&code_challenge_method=S256" +
                "&code_challenge=$encodedChallenge" +
                "&scope=$encodedScope" +
                "&show_dialog=true"
    }

    /**
     * 通过授权回调中的 authorization_code 换取 Access Token 与 Refresh Token。
     * 支持 Basic Auth 认证及纯 PKCE 双通道容灾重试。
     */
    suspend fun exchangeCodeForToken(
        code: String,
        codeVerifier: String = getPersistedVerifier(),
        redirectUri: String = REDIRECT_URI,
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val actualVerifier = codeVerifier.ifBlank { getPersistedVerifier() }
        AppLogger.i(TAG, "开始换取 Token，code 长度: ${code.length}, verifier 长度: ${actualVerifier.length}")

        // 优先尝试 Basic Auth + PKCE 混合模式（Spotify 标准开发者应用最严格模式）
        val basicResult = tryExchangeWithBasicAuth(code, actualVerifier, redirectUri)
        if (basicResult != null) return@withContext basicResult

        // 备选尝试纯 PKCE 模式（client_id 在请求体中）
        val pkceResult = tryExchangeWithPkceOnly(code, actualVerifier, redirectUri)
        pkceResult
    }

    private fun tryExchangeWithBasicAuth(
        code: String,
        verifier: String,
        redirectUri: String,
    ): Pair<String, String>? {
        return try {
            val credentials = "$CLIENT_ID:$CLIENT_SECRET"
            val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.US_ASCII), Base64.NO_WRAP)

            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Basic Auth 换取 Token 响应失败: HTTP ${response.code}, 详情: $bodyStr")
                    lastExchangeError = parseErrorDesc(bodyStr, "HTTP ${response.code}")
                    return null
                }
                parseTokenResponse(bodyStr)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Basic Auth 换取 Token 请求异常: ${e.message}", e)
            lastExchangeError = e.message ?: "网络请求失败"
            null
        }
    }

    private fun tryExchangeWithPkceOnly(
        code: String,
        verifier: String,
        redirectUri: String,
    ): Pair<String, String>? {
        return try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "纯 PKCE 换取 Token 响应失败: HTTP ${response.code}, 详情: $bodyStr")
                    lastExchangeError = parseErrorDesc(bodyStr, "HTTP ${response.code}")
                    return null
                }
                parseTokenResponse(bodyStr)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "纯 PKCE 换取 Token 请求异常: ${e.message}", e)
            lastExchangeError = e.message ?: "网络请求失败"
            null
        }
    }

    private fun parseTokenResponse(bodyStr: String): Pair<String, String>? {
        if (bodyStr.isBlank()) return null
        return try {
            val json = JSONObject(bodyStr)
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")
            val expiresIn = json.optLong("expires_in", 3600L)
            if (accessToken.isNotBlank()) {
                AppLogger.i(TAG, "成功换取 Spotify Access Token (有效期 ${expiresIn}s)")
                lastExchangeError = null
                Pair(accessToken, refreshToken)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseErrorDesc(bodyStr: String, defaultDesc: String): String {
        return try {
            val json = JSONObject(bodyStr)
            val desc = json.optString("error_description").ifBlank { json.optString("error") }
            if (desc.isNotBlank()) desc else defaultDesc
        } catch (_: Exception) {
            defaultDesc
        }
    }

    /**
     * 使用 Refresh Token 续期 Access Token。
     * @return TokenRefreshResult 包含新 Token、可选的新 Refresh Token 与有效期秒数
     */
    suspend fun refreshAccessToken(refreshToken: String): TokenRefreshResult? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        try {
            val formBody = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    AppLogger.w(TAG, "刷新 Token 失败，HTTP: ${response.code}, 详情: $errBody")
                    return@withContext null
                }
                val bodyStr = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyStr)
                val newAccessToken = json.optString("access_token")
                val newRefreshToken = json.optString("refresh_token").ifBlank { null }
                val expiresIn = json.optLong("expires_in", 3600L)
                if (newAccessToken.isNotBlank()) {
                    TokenRefreshResult(newAccessToken, newRefreshToken, expiresIn)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "刷新 Token 异常: ${e.message}", e)
            null
        }
    }

    /**
     * 构造兜底的用户资料（当换取 Token 成功但拉取详细资料超时时，保证登录不中断）。
     */
    fun createFallbackProfile(
        accessToken: String,
        refreshToken: String = "",
        spDcCookie: String? = null,
        expiresInSec: Long = 3600L,
    ): SpotifyUserProfile {
        val now = System.currentTimeMillis()
        return SpotifyUserProfile(
            id = "spotify_user",
            displayName = "Spotify 用户",
            email = null,
            avatarUrl = null,
            product = "premium",
            country = "",
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiresAtMs = now + (expiresInSec * 1000L),
            spDcCookie = spDcCookie,
        )
    }

    /**
     * 使用有效的 Access Token 获取用户个人资料（内置自动重试与弹性容灾）。
     */
    suspend fun fetchUserProfile(
        accessToken: String,
        refreshToken: String = "",
        spDcCookie: String? = null,
        expiresInSec: Long = 3600L,
    ): SpotifyUserProfile? = withContext(Dispatchers.IO) {
        var lastErr: String? = null

        // 尝试两次拉取，提高海外网络波动下的成功率
        for (attempt in 1..2) {
            try {
                val request = Request.Builder()
                    .url(ME_ENDPOINT)
                    .header("Authorization", "Bearer $accessToken")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .build()

                val result = client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        lastErr = parseErrorDesc(bodyStr, "HTTP ${response.code}")
                        AppLogger.w(TAG, "第 $attempt 次获取用户资料响应失败，HTTP: ${response.code}, 详情: $bodyStr")
                        return@use null
                    }
                    val json = JSONObject(bodyStr)

                    val id = json.optString("id")
                    val displayName = json.optString("display_name").ifBlank { id }
                    val email = json.optString("email").ifBlank { null }
                    val product = json.optString("product", "free")
                    val country = json.optString("country")

                    var avatarUrl: String? = null
                    val images = json.optJSONArray("images")
                    if (images != null && images.length() > 0) {
                        avatarUrl = images.optJSONObject(0)?.optString("url")
                    }

                    val now = System.currentTimeMillis()
                    val profile = SpotifyUserProfile(
                        id = id,
                        displayName = displayName,
                        email = email,
                        avatarUrl = avatarUrl,
                        product = product,
                        country = country,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        tokenExpiresAtMs = now + (expiresInSec * 1000L),
                        spDcCookie = spDcCookie,
                    )
                    AppLogger.i(TAG, "成功获取 Spotify 用户资料: $displayName ($product, $country)")
                    profile
                }

                if (result != null) {
                    lastExchangeError = null
                    return@withContext result
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "网络请求超时"
                AppLogger.w(TAG, "第 $attempt 次获取用户资料异常: ${e.message}")
            }
        }

        lastExchangeError = lastErr ?: "网络请求超时"
        null
    }

    /**
     * 执行带有 Spotify OAuth 鉴权及 401 自动续期重试的 GET 请求。
     */
    private suspend fun executeAuthedGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val initialToken = SpotifyAccountStore.getValidAccessToken()
            ?: return@withContext Pair(401, "未登录 Spotify 账号")

        val makeRequest = { token: String ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .build()
        }

        try {
            var (code, body) = client.newCall(makeRequest(initialToken)).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            // 若遇到 401 说明 Access Token 已过期，尝试强制用 Refresh Token 刷新并自动重试
            if (code == 401) {
                AppLogger.w(TAG, "访问 Spotify 接口返回 401，尝试刷新 Token 并重试: $url")
                val refreshedToken = SpotifyAccountStore.getValidAccessToken(forceRefresh = true)
                if (!refreshedToken.isNullOrBlank()) {
                    client.newCall(makeRequest(refreshedToken)).execute().use { retryResp ->
                        code = retryResp.code
                        body = retryResp.body?.string() ?: ""
                    }
                }
            }

            Pair(code, body)
        } catch (e: Exception) {
            AppLogger.e(TAG, "请求 Spotify 接口异常 ($url): ${e.message}", e)
            Pair(-1, e.message ?: "网络连接超时")
        }
    }

    /**
     * 解析 Spotify 原始 JSON 中的 Show 播客节目对象。
     */
    private fun parseShowObject(showObj: JSONObject): PodcastShow? {
        val id = showObj.optString("id").ifBlank { return null }
        val name = showObj.optString("name").ifBlank { return null }
        val publisher = showObj.optString("publisher")
        val description = showObj.optString("description")
        val totalEpisodes = showObj.optInt("total_episodes", 0)

        val images = showObj.optJSONArray("images")
        var coverUrl = ""
        if (images != null && images.length() > 0) {
            coverUrl = images.optJSONObject(0)?.optString("url") ?: ""
        }

        return PodcastShow(
            id = "spotify_$id",
            title = name,
            publisher = publisher,
            description = description,
            coverUrl = coverUrl,
            totalEpisodes = totalEpisodes,
            feedUrl = null,
            sourceType = PodcastSourceType.SPOTIFY,
            category = "Spotify 关注",
        )
    }

    /**
     * 解析 Spotify API 错误信息，给出精准友好的业务提示。
     */
    private fun parseApiErrorMessage(code: Int, bodyStr: String): String {
        val detail = try {
            val json = JSONObject(bodyStr)
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message") ?: json.optString("error_description").ifBlank { json.optString("error") }
            msg.ifBlank { null }
        } catch (_: Exception) {
            bodyStr.trim().take(80).ifBlank { null }
        }

        return when {
            code == 403 && detail != null && detail.contains("scope", ignoreCase = true) ->
                "权限未授予 (403): 缺少媒体库读取权限，请退出登录并重新登录授权"
            code == 403 && detail != null && (detail.contains("registered", ignoreCase = true) || detail.contains("developer", ignoreCase = true)) ->
                "开发者模式限制 (403): 请在 Spotify 开发者后台将该账号加入 Users and Access 白名单"
            code == 403 ->
                "Spotify 开发模式 403: 请在后台 Users and Access 添加此主账号邮箱并保存" + (if (!detail.isNullOrBlank()) " ($detail)" else "")
            detail != null ->
                "HTTP $code: $detail"
            else ->
                "HTTP $code"
        }
    }

    /**
     * 获取用户在 Spotify 上关注的播客节目列表（仅节目层级）。
     */
    suspend fun fetchFollowedShows(): List<PodcastShow> = withContext(Dispatchers.IO) {
        val (code, bodyStr) = executeAuthedGet(SAVED_SHOWS_ENDPOINT)
        if (code !in 200..299 || bodyStr.isBlank()) return@withContext emptyList()
        val list = mutableListOf<PodcastShow>()
        try {
            val json = JSONObject(bodyStr)
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            for (i in 0 until items.length()) {
                val wrapper = items.optJSONObject(i) ?: continue
                val showObj = wrapper.optJSONObject("show") ?: continue
                parseShowObject(showObj)?.let { list.add(it) }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析关注播客异常: ${e.message}", e)
        }
        list
    }

    /**
     * 将 Spotify 关注的播客（包含已关注播客与已保存单集所属播客）深度同步导入到本地收藏列表。
     * 支持深度分页、401 自动续期与精准状态诊断。
     * @return SpotifySyncReport 同步结果报告
     */
    suspend fun syncFollowedShowsToLocal(): SpotifySyncReport = withContext(Dispatchers.IO) {
        if (!SpotifyAccountStore.isLoggedIn()) {
            return@withContext SpotifySyncReport(
                success = false,
                errorMessage = "请先关联登录 Spotify 账号",
            )
        }

        val allShowsMap = mutableMapOf<String, PodcastShow>()
        var lastHttpError: String? = null

        // 1. 抓取用户关注的播客节目（Saved Shows，支持循环深度分页）
        var nextUrl: String? = SAVED_SHOWS_ENDPOINT
        var pageCount = 0
        val maxPages = 5 // 最多抓取 5 页（250 条），防止极端死循环
        while (!nextUrl.isNullOrBlank() && pageCount < maxPages) {
            pageCount++
            var (code, bodyStr) = executeAuthedGet(nextUrl)
            // 若遇 403 尝试自动追加 market 地区参数容灾重试
            if (code == 403 && !nextUrl.contains("market=")) {
                val userCountry = SpotifyAccountStore.userProfile.value?.country?.ifBlank { null }
                val marketParam = if (!userCountry.isNullOrBlank()) "market=$userCountry" else "market=from_token"
                val fallbackUrl = if (nextUrl.contains("?")) "$nextUrl&$marketParam" else "$nextUrl?$marketParam"
                AppLogger.i(TAG, "获取关注播客遇 403，尝试追加市场参数容灾重试: $fallbackUrl")
                val (retryCode, retryBody) = executeAuthedGet(fallbackUrl)
                if (retryCode in 200..299) {
                    code = retryCode
                    bodyStr = retryBody
                }
            }

            if (code !in 200..299) {
                lastHttpError = parseApiErrorMessage(code, bodyStr)
                AppLogger.w(TAG, "获取关注播客第 $pageCount 页失败，HTTP: $code, 详情: $bodyStr")
                break
            }

            try {
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val wrapper = items.optJSONObject(i) ?: continue
                        val showObj = wrapper.optJSONObject("show") ?: continue
                        parseShowObject(showObj)?.let { show ->
                            allShowsMap[show.id] = show
                        }
                    }
                }
                nextUrl = json.optString("next").ifBlank { null }
            } catch (e: Exception) {
                AppLogger.w(TAG, "解析关注播客数据异常: ${e.message}")
                break
            }
        }

        // 2. 抓取用户已保存的播客单集（Saved Episodes，从中提取所属播客 Show，覆盖更多用户收藏）
        try {
            var (epCode, epBodyStr) = executeAuthedGet(SAVED_EPISODES_ENDPOINT)
            if (epCode == 403) {
                val userCountry = SpotifyAccountStore.userProfile.value?.country?.ifBlank { null }
                val marketParam = if (!userCountry.isNullOrBlank()) "market=$userCountry" else "market=from_token"
                val (retryEpCode, retryEpBody) = executeAuthedGet("$SAVED_EPISODES_ENDPOINT&$marketParam")
                if (retryEpCode in 200..299) {
                    epCode = retryEpCode
                    epBodyStr = retryEpBody
                }
            }

            if (epCode in 200..299 && epBodyStr.isNotBlank()) {
                val epJson = JSONObject(epBodyStr)
                val epItems = epJson.optJSONArray("items")
                if (epItems != null) {
                    for (i in 0 until epItems.length()) {
                        val wrapper = epItems.optJSONObject(i) ?: continue
                        val episodeObj = wrapper.optJSONObject("episode") ?: continue
                        val showObj = episodeObj.optJSONObject("show") ?: continue
                        parseShowObject(showObj)?.let { show ->
                            allShowsMap[show.id] = show
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "从保存单集中提取所属播客异常: ${e.message}")
        }

        // 若网络完全失败且未拿到任何内容
        if (allShowsMap.isEmpty() && lastHttpError != null) {
            return@withContext SpotifySyncReport(
                success = false,
                errorMessage = lastHttpError,
            )
        }

        val totalFound = allShowsMap.size
        if (totalFound == 0) {
            return@withContext SpotifySyncReport(
                success = true,
                totalShowsFound = 0,
                newAddedCount = 0,
                alreadyFavoritedCount = 0,
            )
        }

        val candidateList = allShowsMap.values.toList()
        val currentFavIds = PodcastStore.favorites.value.map { it.id }.toSet()
        val alreadyCount = candidateList.count { it.id in currentFavIds }
        val newAdded = PodcastStore.addFavorites(candidateList)

        AppLogger.i(TAG, "已成功从 Spotify 同步 $totalFound 个关注播客，新增入库 $newAdded 个，已有 $alreadyCount 个")
        SpotifySyncReport(
            success = true,
            totalShowsFound = totalFound,
            newAddedCount = newAdded,
            alreadyFavoritedCount = alreadyCount,
        )
    }
}

