package com.rawsmusic.module.online.auth.model

import com.rawsmusic.module.online.model.OnlinePlatform

/**
 * 平台账号信息模型
 */
data class PlatformAccount(
    val platform: OnlinePlatform,
    val userId: String,
    val nickname: String,
    val avatarUrl: String = "",
    val isVip: Boolean = false,
    val vipLevel: String = "",
    val vipExpireTime: Long = 0L,
    val cookies: Map<String, String> = emptyMap(),
    val tokens: Map<String, String> = emptyMap(),
    val extraData: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取格式化的 Cookie 字符串 (自动补齐平台关键关联字段)
     */
    fun toCookieHeader(): String {
        val map = cookies.toMutableMap()
        if (platform == OnlinePlatform.QQ && userId.isNotBlank()) {
            val rawUin = userId.replace("^o0*".toRegex(), "")
            if (!map.containsKey("uin")) map["uin"] = rawUin
            if (!map.containsKey("p_uin")) map["p_uin"] = "o0$rawUin"
            if (!map.containsKey("login_uin")) map["login_uin"] = rawUin
            if (!map.containsKey("o_cookie")) map["o_cookie"] = rawUin
            if (!map.containsKey("euin")) map["euin"] = rawUin

            val key = map["qm_keyst"] ?: map["qqmusic_key"] ?: map["psrf_musickey_id"] ?: tokens["key"] ?: ""
            if (key.isNotBlank()) {
                map["qm_keyst"] = key
                map["qqmusic_key"] = key
                map["psrf_musickey_id"] = key
                map["authst"] = key
            }
        } else if (platform == OnlinePlatform.KUGOU && userId.isNotBlank()) {
            if (!map.containsKey("KugouID")) map["KugouID"] = userId
            if (!map.containsKey("KugooID")) map["KugooID"] = userId
            if (!map.containsKey("userid")) map["userid"] = userId
            val token = tokens["token"] ?: tokens["t"] ?: map["token"] ?: map["t"] ?: ""
            if (token.isNotBlank()) {
                map["token"] = token
                map["t"] = token
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 获取主要的认证 Token (如果有)
     */
    fun getPrimaryToken(): String {
        return tokens["token"] ?: tokens["t"] ?: tokens["access_token"] ?: cookies["token"] ?: cookies["t"] ?: ""
    }
}

/**
 * 二维码生成结果
 */
data class QrCodeData(
    val platform: OnlinePlatform,
    val key: String,
    val qrUrl: String? = null,
    val qrImageBytes: ByteArray? = null,
    val expireTimeMs: Long = 120_000L,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QrCodeData

        if (platform != other.platform) return false
        if (key != other.key) return false
        if (qrUrl != other.qrUrl) return false
        if (qrImageBytes != null) {
            if (other.qrImageBytes == null) return false
            if (!qrImageBytes.contentEquals(other.qrImageBytes)) return false
        } else if (other.qrImageBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = platform.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + (qrUrl?.hashCode() ?: 0)
        result = 31 * result + (qrImageBytes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 扫码状态枚举
 */
enum class QrStatus {
    WAITING,   // 等待扫码
    SCANNED,   // 已扫码，待在手机端确认
    SUCCESS,   // 登录成功
    EXPIRED,   // 二维码已过期失效
    ERROR      // 网络或未知错误
}

/**
 * 扫码轮询检查结果
 */
data class QrCheckResult(
    val status: QrStatus,
    val message: String = "",
    val account: PlatformAccount? = null
)
