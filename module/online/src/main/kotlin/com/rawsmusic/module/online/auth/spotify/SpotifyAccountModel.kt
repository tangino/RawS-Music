package com.rawsmusic.module.online.auth.spotify

/**
 * Spotify 用户资料与授权凭据模型。
 */
data class SpotifyUserProfile(
    val id: String,
    val displayName: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val product: String = "free", // "premium" 或 "free"
    val country: String = "",
    val accessToken: String,
    val refreshToken: String = "",
    val tokenExpiresAtMs: Long = 0L,
    val spDcCookie: String? = null,
)

