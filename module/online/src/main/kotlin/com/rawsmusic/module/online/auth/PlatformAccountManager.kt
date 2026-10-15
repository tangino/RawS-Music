package com.rawsmusic.module.online.auth

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.online.auth.crypto.PlatformCredentialCipher
import com.rawsmusic.module.online.auth.model.PlatformAccount
import com.rawsmusic.module.online.auth.model.QrCheckResult
import com.rawsmusic.module.online.auth.model.QrCodeData
import com.rawsmusic.module.online.auth.model.QrStatus
import com.rawsmusic.module.online.auth.service.IPlatformAuthService
import com.rawsmusic.module.online.auth.service.KugouMusicAuthService
import com.rawsmusic.module.online.auth.service.QQMusicAuthService
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 平台账号（QQ / 酷狗）管理。
 *
 * ## 与 OrBit 版的区别（决策 D7）
 *
 * OrBit 把 cookie / token **明文**写进 `SharedPreferences`。这里改为：
 * `PlatformAccount` → JSON → [PlatformCredentialCipher] AES-GCM 加密 → 密文存
 * [AppPreferences.PlatformAuth]（MMKV）。**任何时候都不会退化成写明文** ——
 * 加密失败就放弃写入并记日志。
 *
 * ## 为什么没有 `getInstance(context)`
 *
 * OrBit 的构造函数只为了拿 `SharedPreferences`。这里底层是 MMKV（`AppPreferences` 是全局
 * `object`，自己惰性初始化 `MMKV.defaultMMKV()`），不需要 Context，所以直接用 `object`。
 *
 * ## 为什么懒加载而不是 init 里恢复
 *
 * MMKV 由 `CoreInit` 初始化。若本对象在初始化之前就被触碰，构造期读 MMKV 会出问题。
 * 因此改成「首次访问时恢复一次」，无论调用顺序如何都安全。
 */
object PlatformAccountManager {

    private const val TAG = "PlatformAuth"

    private val authServices: Map<OnlinePlatform, IPlatformAuthService> = mapOf(
        OnlinePlatform.QQ to QQMusicAuthService(),
        OnlinePlatform.KUGOU to KugouMusicAuthService(),
    )

    private val _accounts = MutableStateFlow<Map<OnlinePlatform, PlatformAccount?>>(emptyMap())
    /** 各平台当前登录的账号；未登录的平台值为 null 或不存在。 */
    val accounts: StateFlow<Map<OnlinePlatform, PlatformAccount?>> = _accounts.asStateFlow()

    @Volatile
    private var restored = false

    private fun ensureRestored() {
        if (restored) return
        synchronized(this) {
            if (restored) return
            restored = true
            authServices.keys.forEach { platform -> restorePlatform(platform) }
            // 恢复完成后立刻把账号接到音源请求上（P1-09 预留的 bindAuthProviders 钩子）。
            bindToRepository()
        }
    }

    /** 当前支持扫码登录的平台（酷我/咪咕/网易没有实现登录服务）。 */
    fun supportedPlatforms(): Set<OnlinePlatform> = authServices.keys

    fun getAuthService(platform: OnlinePlatform): IPlatformAuthService? = authServices[platform]

    fun getAccount(platform: OnlinePlatform): PlatformAccount? {
        ensureRestored()
        return _accounts.value[platform]
    }

    fun isLoggedIn(platform: OnlinePlatform): Boolean {
        ensureRestored()
        return _accounts.value[platform] != null
    }

    /** 供音源请求携带的 Cookie 头；未登录返回空串。 */
    fun getCookieHeader(platform: OnlinePlatform): String {
        ensureRestored()
        return _accounts.value[platform]?.toCookieHeader().orEmpty()
    }

    /**
     * 把账号提供者接到 [OnlineMusicRepository]，让音源的网络请求自动带上登录态。
     *
     * 注意方向：是 online 模块内部接线（`auth → repository`），不引入跨模块依赖。
     */
    fun bindToRepository() {
        OnlineMusicRepository.getInstance().bindAuthProviders(
            cookieProvider = { platform -> getCookieHeaderWithoutRestore(platform) },
            accountProvider = { platform -> _accounts.value[platform] },
        )
    }

    // 供 bindToRepository 内部用：避免 provider 在恢复过程中再次触发 ensureRestored（会死锁）。
    private fun getCookieHeaderWithoutRestore(platform: OnlinePlatform): String =
        _accounts.value[platform]?.toCookieHeader().orEmpty()

    suspend fun getQrCode(platform: OnlinePlatform): QrCodeData = withContext(Dispatchers.IO) {
        val service = authServices[platform]
            ?: throw IllegalArgumentException("暂不支持 ${platform.displayName} 登录服务")
        service.getQrCode()
    }

    /** 轮询扫码状态；一旦成功立刻落盘保存账号。 */
    suspend fun checkQrStatus(
        platform: OnlinePlatform,
        key: String,
        extra: Map<String, String> = emptyMap(),
    ): QrCheckResult = withContext(Dispatchers.IO) {
        val service = authServices[platform]
            ?: throw IllegalArgumentException("暂不支持 ${platform.displayName} 登录服务")
        val result = service.checkQrStatus(key, extra)
        if (result.status == QrStatus.SUCCESS) {
            result.account?.let { account ->
                saveAccount(account)
                AppPreferences.PlatformAuth.lastQrLoginAtMs = System.currentTimeMillis()
            }
        }
        result
    }

    /**
     * 保存账号（加密后落盘）。
     *
     * ⚠️ 加密失败时**不写入**：宁可不保存，也不能把明文凭据写进磁盘。
     */
    fun saveAccount(account: PlatformAccount) {
        ensureRestored()
        val encrypted = PlatformCredentialCipher.encrypt(serializeAccount(account))
        if (encrypted == null) {
            AppLogger.e(TAG, "凭据加密失败，已放弃保存 ${account.platform.displayName} 账号")
            return
        }
        AppPreferences.PlatformAuth.setEncryptedAccount(account.platform.id, encrypted)
        _accounts.value = _accounts.value.toMutableMap().apply { put(account.platform, account) }
        AppLogger.i(
            TAG,
            "已保存 ${account.platform.displayName} 账号：${account.nickname}（${account.userId}）",
        )
    }

    /** 退出登录：删除密文并清空内存态。 */
    fun logout(platform: OnlinePlatform) {
        ensureRestored()
        AppPreferences.PlatformAuth.clearAccount(platform.id)
        _accounts.value = _accounts.value.toMutableMap().apply { remove(platform) }
        AppLogger.i(TAG, "已退出 ${platform.displayName} 登录")
    }

    /** 刷新账号资料与 VIP 状态；失败时返回原账号（不把用户踢下线）。 */
    suspend fun refreshAccount(platform: OnlinePlatform): PlatformAccount? = withContext(Dispatchers.IO) {
        val current = getAccount(platform) ?: return@withContext null
        val service = authServices[platform] ?: return@withContext current
        try {
            val refreshed = service.refreshUserInfo(current)
            saveAccount(refreshed)
            refreshed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.e(TAG, "刷新 ${platform.displayName} 用户信息失败", error)
            current
        }
    }

    /**
     * 手动使用用户输入的 Cookie / Token 登录（对应 OrBitPlayer 的 Cookie 登录机制）。
     */
    suspend fun loginWithCookies(
        platform: OnlinePlatform,
        cookies: Map<String, String>,
    ): Result<PlatformAccount> = withContext(Dispatchers.IO) {
        val service = authServices[platform]
            ?: return@withContext Result.failure(IllegalStateException("暂不支持 ${platform.displayName} 登录"))
        try {
            val initialAccount = when (platform) {
                OnlinePlatform.QQ -> {
                    val uin = cookies["uin"] ?: cookies["login_uin"] ?: cookies["luin"] ?: ""
                    val cleanUin = uin.removePrefix("o").trim()
                    val token = cookies["qm_keyst"] ?: cookies["qqmusic_key"] ?: cookies["psrf_musickey_id"] ?: ""
                    PlatformAccount(
                        platform = platform,
                        userId = cleanUin.ifBlank { "qq_user" },
                        nickname = if (cleanUin.isNotBlank()) "QQ用户_$cleanUin" else "QQ音乐用户",
                        avatarUrl = "",
                        isVip = false,
                        vipLevel = "",
                        cookies = cookies,
                        tokens = if (token.isNotBlank()) mapOf("token" to token) else emptyMap(),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                OnlinePlatform.KUGOU -> {
                    val userId = cookies["userid"] ?: cookies["kg_mid"] ?: ""
                    val token = cookies["token"] ?: cookies["kg_token"] ?: ""
                    PlatformAccount(
                        platform = platform,
                        userId = userId.ifBlank { "kugou_user" },
                        nickname = if (userId.isNotBlank()) "酷狗用户_$userId" else "酷狗音乐用户",
                        avatarUrl = "",
                        isVip = false,
                        vipLevel = "",
                        cookies = cookies,
                        tokens = if (token.isNotBlank()) mapOf("token" to token) else emptyMap(),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                else -> return@withContext Result.failure(IllegalStateException("暂不支持 ${platform.displayName} 登录"))
            }

            // 尝试通过网络拉取真实的用户昵称、头像和 VIP 状态
            val validatedAccount = try {
                service.refreshUserInfo(initialAccount)
            } catch (e: Exception) {
                AppLogger.w(TAG, "刷新 Cookie 账号信息异常，使用初始凭据兜底: ${e.message}")
                initialAccount
            }
            saveAccount(validatedAccount)
            Result.success(validatedAccount)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Cookie 登录失败", e)
            Result.failure(e)
        }
    }

    /**
     * 拉取用户自建 / 收藏的歌单。
     *
     * 返回 [Result] 而不是裸 `List`：**「没有歌单」与「拉取失败」必须能区分**。
     * 原实现失败时返回 `emptyList()`，UI 只能显示「你没有歌单」—— 用户会以为歌单丢了，
     * 而实际是网络或平台接口的问题。这是本工程反复强调的「不要静默失败」。
     *
     * （此方法此前没有任何调用方，所以改成 Result 是安全的；`IPlatformAuthService.getUserPlaylists`
     * 的签名保持不动，避免动到已移植的平台实现。）
     *
     * 失败情形：未登录、平台无对应服务、平台接口异常。
     * **成功但列表为空**表示该账号确实没有歌单（正常结果，不是错误）。
     */
    suspend fun fetchUserPlaylists(platform: OnlinePlatform): Result<List<OnlinePlaylist>> =
        withContext(Dispatchers.IO) {
            val account = getAccount(platform)
                ?: return@withContext Result.failure(
                    IllegalStateException("${platform.displayName} 未登录"),
                )
            val service = authServices[platform]
                ?: return@withContext Result.failure(
                    IllegalStateException("${platform.displayName} 暂无可用登录服务"),
                )
            try {
                Result.success(service.getUserPlaylists(account))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "获取 ${platform.displayName} 用户歌单失败", error)
                Result.failure(error)
            }
        }

    private fun restorePlatform(platform: OnlinePlatform) {
        val encrypted = AppPreferences.PlatformAuth.encryptedAccount(platform.id)
        if (encrypted.isEmpty()) return
        val json = PlatformCredentialCipher.decrypt(encrypted)
        if (json == null) {
            AppLogger.w(TAG, "${platform.displayName} 的凭据无法解密，按未登录处理（需重新扫码）")
            return
        }
        val account = deserializeAccount(json)
        if (account == null) {
            AppLogger.w(TAG, "${platform.displayName} 的凭据内容无法解析，按未登录处理")
            return
        }
        _accounts.value = _accounts.value.toMutableMap().apply { put(platform, account) }
    }

    // ================= 序列化 =================
    // 字段名与 OrBit 版保持一致，便于将来云端同步互认。

    private fun serializeAccount(account: PlatformAccount): String = JSONObject().apply {
        put("platform", account.platform.id)
        put("userId", account.userId)
        put("nickname", account.nickname)
        put("avatarUrl", account.avatarUrl)
        put("isVip", account.isVip)
        put("vipLevel", account.vipLevel)
        put("vipExpireTime", account.vipExpireTime)
        put("updatedAt", account.updatedAt)
        put("cookies", JSONObject(account.cookies as Map<*, *>))
        put("tokens", JSONObject(account.tokens as Map<*, *>))
        put("extraData", JSONObject(account.extraData as Map<*, *>))
    }.toString()

    private fun deserializeAccount(json: String): PlatformAccount? = try {
        val obj = JSONObject(json)
        val platform = OnlinePlatform.fromId(obj.optString("platform")) ?: return null
        PlatformAccount(
            platform = platform,
            userId = obj.optString("userId"),
            nickname = obj.optString("nickname"),
            avatarUrl = obj.optString("avatarUrl"),
            isVip = obj.optBoolean("isVip", false),
            vipLevel = obj.optString("vipLevel"),
            vipExpireTime = obj.optLong("vipExpireTime", 0L),
            cookies = obj.optJSONObject("cookies").toStringMap(),
            tokens = obj.optJSONObject("tokens").toStringMap(),
            extraData = obj.optJSONObject("extraData").toStringMap(),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
        )
    } catch (error: Throwable) {
        AppLogger.e(TAG, "账号 JSON 解析失败", error)
        null
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { optString(it, "") }
    }
}
