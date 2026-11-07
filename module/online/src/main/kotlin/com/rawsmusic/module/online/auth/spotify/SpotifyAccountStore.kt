package com.rawsmusic.module.online.auth.spotify

import com.google.gson.Gson
import com.rawsmusic.core.common.utils.AppLogger
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify 账号状态持久化管理器。
 */
object SpotifyAccountStore {
    private const val TAG = "SpotifyAccountStore"
    private const val KEY_PROFILE = "spotify_user_profile_v1"

    private val gson by lazy { Gson() }
    private val mmkv by lazy { MMKV.defaultMMKV() }

    private val _userProfile = MutableStateFlow<SpotifyUserProfile?>(null)
    val userProfile: StateFlow<SpotifyUserProfile?> = _userProfile.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        runCatching {
            val json = mmkv.decodeString(KEY_PROFILE)
            if (!json.isNullOrBlank()) {
                val profile = gson.fromJson(json, SpotifyUserProfile::class.java)
                _userProfile.value = profile
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "加载 Spotify 账号数据失败: ${e.message}", e)
        }
    }

    fun isLoggedIn(): Boolean = _userProfile.value != null

    fun saveProfile(profile: SpotifyUserProfile) {
        _userProfile.value = profile
        runCatching {
            val json = gson.toJson(profile)
            mmkv.encode(KEY_PROFILE, json)
            AppLogger.i(TAG, "已保存 Spotify 账号: ${profile.displayName} (${profile.id})")
        }.onFailure { e ->
            AppLogger.e(TAG, "保存 Spotify 账号失败: ${e.message}", e)
        }
    }

    fun logout() {
        _userProfile.value = null
        mmkv.remove(KEY_PROFILE)
        AppLogger.i(TAG, "Spotify 账号已退出登录")
    }

    /**
     * 在后台静默异步刷新补充真实用户资料（头像、昵称、国家）。
     */
    fun refreshProfileSilently() {
        val current = _userProfile.value ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val fresh = SpotifyAuthService.fetchUserProfile(current.accessToken, current.refreshToken, current.spDcCookie)
            if (fresh != null) {
                saveProfile(fresh)
                AppLogger.i(TAG, "静默更新 Spotify 用户资料成功: ${fresh.displayName}")
            }
        }
    }

    /**
     * 获取有效的 Access Token（若临近过期或被要求强制刷新，则自动尝试使用 Refresh Token 续期）。
     * @param forceRefresh 是否强制忽略当前有效期进行刷新（用于 401 Unauthorized 重试）
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        val current = _userProfile.value ?: return null
        val now = System.currentTimeMillis()

        // 未要求强制刷新且还有 2 分钟以上有效期，直接使用
        if (!forceRefresh && now < current.tokenExpiresAtMs - 120_000L && current.accessToken.isNotBlank()) {
            return current.accessToken
        }

        // 尝试用 Refresh Token 刷新
        if (current.refreshToken.isNotBlank()) {
            val refreshed = SpotifyAuthService.refreshAccessToken(current.refreshToken)
            if (refreshed != null) {
                val updated = current.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken?.ifBlank { null } ?: current.refreshToken,
                    tokenExpiresAtMs = now + (refreshed.expiresInSec * 1000L),
                )
                saveProfile(updated)
                AppLogger.i(TAG, "已成功刷新并保存最新 Spotify Token (有效期 ${refreshed.expiresInSec}s)")
                return refreshed.accessToken
            }
        }

        return if (!forceRefresh) current.accessToken.ifBlank { null } else null
    }
}

