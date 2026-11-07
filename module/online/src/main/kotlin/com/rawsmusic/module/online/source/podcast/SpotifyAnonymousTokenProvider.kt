package com.rawsmusic.module.online.source.podcast

import android.util.Base64
import com.rawsmusic.core.common.utils.AppLogger
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Spotify 访问 Token 管理与提供器。
 *
 * 采用三级弹性容灾策略：
 * 1. 官方开发者 Client Credentials 鉴权（已配置用户专属凭证）
 * 2. 官方 Web Player 匿名 Token
 * 3. 失败时自动降级由开放式 RSS / iTunes 引擎托底保障
 */
object SpotifyAnonymousTokenProvider {
    private const val TAG = "SpotifyTokenProvider"
    private const val TOKEN_URL = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    private const val OAUTH_TOKEN_URL = "https://accounts.spotify.com/api/token"

    // 用户专属 Spotify 开发者凭据
    @Volatile
    var customClientId: String = "bafaaa218fe747b8b4c8141177123a97"
    @Volatile
    var customClientSecret: String = "e3cc6815fab444d59e403956ca2a96f7"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    @Volatile
    private var lastFailureTimeMs: Long = 0L
    private const val FAILURE_COOLDOWN_MS = 30_000L

    /**
     * 获取有效的 Spotify 访问 Token，若不可用则返回 null。
     */
    @Synchronized
    fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAtMs - 120_000L) {
            return cachedToken
        }

        if (now - lastFailureTimeMs < FAILURE_COOLDOWN_MS) {
            return null
        }

        // 1. 优先尝试使用用户提供的官方 Client Credentials 获取官方 Token
        val officialToken = fetchOfficialOAuthToken(now)
        if (!officialToken.isNullOrBlank()) {
            return officialToken
        }

        // 2. 备选方案：尝试 Web Player 匿名 Token
        val anonymousToken = fetchWebAnonymousToken(now)
        if (!anonymousToken.isNullOrBlank()) {
            return anonymousToken
        }

        lastFailureTimeMs = now
        return null
    }

    /**
     * 通过 Spotify 官方开发者凭证获取 Access Token。
     */
    private fun fetchOfficialOAuthToken(now: Long): String? {
        val cid = customClientId.trim()
        val sec = customClientSecret.trim()
        if (cid.isBlank() || sec.isBlank()) return null

        return try {
            val credentials = "$cid:$sec"
            val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            val formBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .build()

            val request = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(formBody)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "官方 Client Credentials 获取失败，HTTP: ${response.code}")
                    return null
                }
                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token")
                val expiresInSec = json.optLong("expires_in", 3600L)

                if (token.isNotBlank()) {
                    cachedToken = token
                    tokenExpiresAtMs = now + (expiresInSec * 1000L)
                    AppLogger.i(TAG, "已成功通过官方开发者凭证获取 Spotify Token (有效期 ${expiresInSec}s)")
                    token
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "官方 OAuth Token 请求异常: ${e.message}")
            null
        }
    }

    /**
     * 尝试拉取 Web Player 匿名 Token。
     */
    private fun fetchWebAnonymousToken(now: Long): String? {
        return try {
            val request = Request.Builder()
                .url(TOKEN_URL)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept", "application/json")
                .header("Referer", "https://open.spotify.com/")
                .header("Origin", "https://open.spotify.com")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "拉取 Spotify 匿名 Token 失败，HTTP 码: ${response.code}")
                    return null
                }

                val bodyStr = response.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val token = json.optString("accessToken")
                val expiresAt = json.optLong("accessTokenExpirationTimestampMs", now + 3600_000L)

                if (token.isNotBlank()) {
                    cachedToken = token
                    tokenExpiresAtMs = expiresAt
                    AppLogger.i(TAG, "已成功获取 Spotify 匿名 Token")
                    token
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "请求 Spotify 匿名 Token 异常: ${e.message}")
            null
        }
    }
}


