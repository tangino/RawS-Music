package com.rawsmusic.module.online.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.online.auth.crypto.PlatformCredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 统一的云同步结果。UI 只依赖它，不直接看 HTTP 码。 */
sealed interface CloudSyncResult<out T> {
    data class Success<T>(
        val data: T,
        val message: String = "",
    ) : CloudSyncResult<T>

    /**
     * @param code HTTP 状态码；0 表示「还没发出请求就失败了」（比如未配置服务器地址）。
     */
    data class Failure(
        val message: String,
        val code: Int = 0,
    ) : CloudSyncResult<Nothing>
}

// ============================================================================
// 响应 DTO —— 字段名来自 docs/cloud-sync-contract.md §2（**响应是 snake_case**，
// 与请求的 camelCase 不同，很容易猜错）。只声明用得到的字段，其余 Gson 会忽略。
// ============================================================================

private data class AuthResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("username") val username: String = "",
    @SerializedName("isNew") val isNew: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String = "",
)

private data class PlaylistBackupResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("mode") val mode: String = "",
    @SerializedName("username") val username: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
    @SerializedName("error") val error: String = "",
)

private data class PlaylistRestoreData(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("playlists_json") val playlistsJson: String = "",
    @SerializedName("online_favorites_json") val onlineFavoritesJson: String? = null,
    @SerializedName("groups_json") val groupsJson: String? = null,
    @SerializedName("playlist_count") val playlistCount: Int = 0,
    @SerializedName("created_at") val createdAt: Long = 0L,
    @SerializedName("updated_at") val updatedAt: Long = 0L,
)

private data class PlaylistRestoreResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("username") val username: String = "",
    @SerializedName("data") val data: PlaylistRestoreData? = null,
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String = "",
)

private data class CountResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("error") val error: String = "",
)

/** 云端取回的完整备份。 */
data class CloudPlaylistBackup(
    val playlistsJson: String,
    val onlineFavoritesJson: String,
    val groupsJson: String,
    val playlistCount: Int,
    val updatedAt: Long,
)

private data class SourceBackupResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("username") val username: String = "",
    @SerializedName("count") val count: Int = 0,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
    @SerializedName("error") val error: String = "",
)

private data class SourceRestoreData(
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("sources_json") val sourcesJson: String = "",
    @SerializedName("source_count") val sourceCount: Int = 0,
    @SerializedName("created_at") val createdAt: Long = 0L,
    @SerializedName("updated_at") val updatedAt: Long = 0L,
)

private data class SourceRestoreResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("username") val username: String = "",
    @SerializedName("data") val data: SourceRestoreData? = null,
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String = "",
)

/** 云端取回的音源备份。 */
data class CloudSourceBackup(
    val sourcesJson: String,
    val sourceCount: Int,
    val updatedAt: Long,
)

/**
 * 云同步 HTTP 客户端（歌单部分）。
 *
 * ## 只有这一层碰 HTTP
 *
 * `P5-01` 会把这里的 HTTP 逻辑与 P3 的音源同步合并成统一客户端，所以**不要**把请求构造
 * 散落到别处 —— 需要新的云端能力就往这里加方法。
 *
 * ## 必须容错的服务端缺陷（见契约 §6）
 *
 * 1. `playlistsJson` / `onlineFavoritesJson` / `groupsJson` 的 `JSON.parse` 在服务端**未做保护**，
 *    非法 JSON 直接 500 → 调用方必须保证序列化合法（序列化在 [CloudPlaylistJson] 里，已测）。
 * 2. `POST /api/user/playlists/delete` 若**既不传 `deleteAll` 也不传两个数组**，会**清空全部云端歌单**。
 *    → 本类的 [deletePlaylists] **强制要求显式参数**（`deleteAll` 或非空数组），否则直接本地拒绝，
 *    **不发请求**。「忘传参数就清空用户全部云端数据」这种事故不值得靠调用方自觉来避免。
 * 3. 选择性删除**不回写 `groups_json`** → 云端分组可能残留，UI 应提示。
 * 4. `POST /api/user/data/clear` **不接受 `userId` 别名**，只能传 `username`。
 * 5. `restore` 在无备份时返回 **404**（不是空数据），要当作「云端还没有备份」而不是错误弹红。
 *
 * ## 密码
 *
 * 落盘的是密文（[AppPreferences.CloudSync]），加解密复用 [PlatformCredentialCipher]。
 * ⚠️ **不要改那个类的 Keystore 别名**：别名已在设备上生成，改了会让已存的平台账号凭据
 * 全部解不开（表现为「需要重新扫码登录」）。
 */
object CloudSyncClient {

    private const val TAG = "CloudSync"

    private val gson = Gson()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 配置是否齐全（地址 + 用户名 + 密码密文）。 */
    fun isConfigured(): Boolean =
        AppPreferences.CloudSync.serverUrl.isNotBlank() &&
            AppPreferences.CloudSync.username.isNotBlank() &&
            AppPreferences.CloudSync.encryptedPassword().isNotBlank()

    /**
     * 保存账号配置。密码**只以密文落盘**；加密失败时返回失败且**不写入**（绝不退化成明文）。
     */
    fun saveCredentials(
        serverUrl: String,
        username: String,
        password: String,
    ): CloudSyncResult<Unit> {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        if (normalizedUrl.isBlank()) {
            return CloudSyncResult.Failure("服务器地址不能为空")
        }
        val encrypted = if (password.isBlank()) {
            // 允许改用户名/地址而暂不提供密码：保留原密文。
            AppPreferences.CloudSync.encryptedPassword()
        } else {
            PlatformCredentialCipher.encrypt(password)
                ?: return CloudSyncResult.Failure("密码加密失败，已放弃保存")
        }
        AppPreferences.CloudSync.serverUrl = normalizedUrl
        AppPreferences.CloudSync.username = username.trim()
        if (password.isNotBlank()) AppPreferences.CloudSync.setEncryptedPassword(encrypted)
        return CloudSyncResult.Success(Unit)
    }

    /** 登录（服务端会在账号不存在时自动注册）。 */
    suspend fun authenticate(context: Context): CloudSyncResult<String> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/auth",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, AuthResponse::class.java)
                if (parsed.success) {
                    val tail = if (parsed.isNew) "（已自动注册）" else ""
                    CloudSyncResult.Success("${parsed.message.ifBlank { "登录成功" }}$tail")
                } else {
                    preferredFailure(parsed.error, parsed.message) { "登录失败" }
                }
            },
            fallbackError = "登录失败",
        )
    }

    /**
     * 上传歌单备份。
     *
     * @param mode `"merge"`（增量合并，默认）或 `"overwrite"`（全量覆盖）。
     */
    suspend fun backupPlaylists(
        context: Context,
        playlistsJson: String,
        onlineFavoritesJson: String,
        groupsJson: String,
        mode: String,
    ): CloudSyncResult<Int> {
        val config = resolveConfig() ?: return notConfigured()
        val safeMode = if (mode == MODE_OVERWRITE) MODE_OVERWRITE else MODE_MERGE
        return post(
            path = "/api/user/playlists/backup",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
                "playlistsJson" to playlistsJson,
                "onlineFavoritesJson" to onlineFavoritesJson,
                "groupsJson" to groupsJson,
                "mode" to safeMode,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, PlaylistBackupResponse::class.java)
                if (parsed.success) {
                    AppPreferences.CloudSync.lastBackupAtMs =
                        parsed.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    // 记住用户这次选的模式，下次默认沿用。
                    AppPreferences.CloudSync.uploadMode = safeMode
                    CloudSyncResult.Success(parsed.count, parsed.message)
                } else {
                    preferredFailure(parsed.error, parsed.message) { "上传失败" }
                }
            },
            fallbackError = "上传失败",
        )
    }

    /**
     * 下载云端备份。
     *
     * ⚠️ 服务端在**没有备份**时返回 404，这里映射成 [CloudSyncResult.Failure] 且 `code = 404`，
     * 由 UI 决定是提示「云端还没有备份」还是报错 —— 不要一律当失败弹红。
     */
    suspend fun restorePlaylists(context: Context): CloudSyncResult<CloudPlaylistBackup> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/playlists/restore",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, PlaylistRestoreResponse::class.java)
                val data = parsed.data
                if (parsed.success && data != null) {
                    AppPreferences.CloudSync.lastRestoreAtMs =
                        data.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    CloudSyncResult.Success(
                        CloudPlaylistBackup(
                            playlistsJson = data.playlistsJson,
                            onlineFavoritesJson = data.onlineFavoritesJson.orEmpty(),
                            groupsJson = data.groupsJson.orEmpty(),
                            playlistCount = data.playlistCount,
                            updatedAt = data.updatedAt,
                        ),
                    )
                } else {
                    preferredFailure(parsed.error, parsed.message) { "下载失败" }
                }
            },
            fallbackError = "下载失败",
            codeOverrides = mapOf(404 to "云端还没有备份"),
        )
    }

    /**
     * 删除云端歌单。
     *
     * **安全护栏**：服务端在「既不传 `deleteAll` 也不传任何数组」时会**清空全部云端歌单**。
     * 为了不可能误触发，这里要求调用方必须明确二选一：
     * - 清空 → `deleteAll = true`
     * - 选择性删除 → 至少一个数组非空
     *
     * 两者都不满足时**直接本地返回失败、不发请求**。
     */
    suspend fun deletePlaylists(
        context: Context,
        deleteAll: Boolean,
        localPlaylistNames: List<String> = emptyList(),
        onlinePlaylistIds: List<String> = emptyList(),
    ): CloudSyncResult<Int> {
        if (!deleteAll && localPlaylistNames.isEmpty() && onlinePlaylistIds.isEmpty()) {
            // 见类注释第 2 条：这种请求会把用户云端数据全清掉。
            return CloudSyncResult.Failure("必须显式指定要删除的内容（不传参数会清空全部云端歌单）")
        }
        val config = resolveConfig() ?: return notConfigured()
        val body = mutableMapOf<String, Any?>(
            "username" to config.username,
            "password" to config.password,
        )
        if (deleteAll) {
            body["deleteAll"] = true
        } else {
            if (localPlaylistNames.isNotEmpty()) body["localPlaylistNames"] = localPlaylistNames
            if (onlinePlaylistIds.isNotEmpty()) body["onlinePlaylistIds"] = onlinePlaylistIds
        }
        return post(
            path = "/api/user/playlists/delete",
            body = body,
            decode = { json ->
                val parsed = gson.fromJson(json, CountResponse::class.java)
                if (parsed.success) {
                    CloudSyncResult.Success(parsed.count, parsed.message)
                } else {
                    preferredFailure(parsed.error, parsed.message) { "删除失败" }
                }
            },
            fallbackError = "删除失败",
        )
    }

    /**
     * 清空该账号的全部云端数据（**不删 `users` 行**）。
     *
     * ⚠️ 契约明确：这个接口**不接受 `userId` 别名**，只认 `username`。所以这里只放 `username`。
     */
    suspend fun clearAllData(context: Context): CloudSyncResult<Unit> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/data/clear",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, CountResponse::class.java)
                if (parsed.success) {
                    CloudSyncResult.Success(Unit, parsed.message)
                } else {
                    preferredFailure(parsed.error, parsed.message) { "清空失败" }
                }
            },
            fallbackError = "清空失败",
        )
    }

    // ------------------------------------------------------------ 音源（洛雪 / LX）

    /**
     * 上传音源备份。服务端语义是**全量覆盖**（没有 `mode` 参数，与歌单不同）。
     *
     * ⚠️ 服务端对 `sourcesJson` 的 `JSON.parse` 同样**未做保护**，非法 JSON 直接 500 ——
     * 序列化一律走 [CloudSourceJson]，不要手拼。
     */
    suspend fun backupSources(
        context: Context,
        sourcesJson: String,
    ): CloudSyncResult<Int> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/sources/backup",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
                "sourcesJson" to sourcesJson,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, SourceBackupResponse::class.java)
                if (parsed.success) {
                    CloudSyncResult.Success(parsed.count, parsed.message)
                } else {
                    preferredFailure(parsed.error, parsed.message) { "音源上传失败" }
                }
            },
            fallbackError = "音源上传失败",
        )
    }

    /**
     * 下载音源备份。
     *
     * ⚠️ 与歌单一样，**无备份时服务端返回 404**，这里映射成「云端还没有音源备份」而不是错误。
     */
    suspend fun restoreSources(context: Context): CloudSyncResult<CloudSourceBackup> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/sources/restore",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, SourceRestoreResponse::class.java)
                val data = parsed.data
                if (parsed.success && data != null) {
                    CloudSyncResult.Success(
                        CloudSourceBackup(
                            sourcesJson = data.sourcesJson,
                            sourceCount = data.sourceCount,
                            updatedAt = data.updatedAt,
                        ),
                    )
                } else {
                    preferredFailure(parsed.error, parsed.message) { "音源下载失败" }
                }
            },
            fallbackError = "音源下载失败",
            codeOverrides = mapOf(404 to "云端还没有音源备份"),
        )
    }

    /**
     * 删除音源备份。
     *
     * 与 [deletePlaylists] 不同：这个路由**没有**「不传参数就清空」的陷阱
     * （服务端就是删掉该账号的整条音源备份），所以不需要额外护栏。
     */
    suspend fun deleteSourceBackup(context: Context): CloudSyncResult<Unit> {
        val config = resolveConfig() ?: return notConfigured()
        return post(
            path = "/api/user/sources/delete",
            body = mapOf(
                "username" to config.username,
                "password" to config.password,
            ),
            decode = { json ->
                val parsed = gson.fromJson(json, CountResponse::class.java)
                if (parsed.success) {
                    CloudSyncResult.Success(Unit, parsed.message)
                } else {
                    preferredFailure(parsed.error, parsed.message) { "音源备份删除失败" }
                }
            },
            fallbackError = "音源备份删除失败",
        )
    }

    const val MODE_MERGE = "merge"
    const val MODE_OVERWRITE = "overwrite"

    // ------------------------------------------------------------------ 内部

    private data class ResolvedConfig(val baseUrl: String, val username: String, val password: String)

    private fun resolveConfig(): ResolvedConfig? {
        val baseUrl = AppPreferences.CloudSync.serverUrl.trim().trimEnd('/')
        val username = AppPreferences.CloudSync.username.trim()
        if (baseUrl.isBlank() || username.isBlank()) return null
        val encrypted = AppPreferences.CloudSync.encryptedPassword()
        if (encrypted.isBlank()) return null
        val password = PlatformCredentialCipher.decrypt(encrypted) ?: return null
        return ResolvedConfig(baseUrl, username, password)
    }

    private fun notConfigured(): CloudSyncResult.Failure =
        CloudSyncResult.Failure("请先填写服务器地址与账号，并在设置里保存密码")

    private fun preferredFailure(
        error: String,
        message: String,
        fallback: () -> String,
    ): CloudSyncResult.Failure {
        val text = error.ifBlank { message }.ifBlank { fallback() }
        return CloudSyncResult.Failure(text)
    }

    /**
     * 发一个 JSON POST 并解析响应。
     *
     * @param decode 响应体 → 结果；**只在 HTTP 2xx 时调用**。
     * @param codeOverrides 特定 HTTP 码要覆盖成的用户可读文案（如 restore 的 404）。
     */
    private suspend fun <T> post(
        path: String,
        body: Map<String, Any?>,
        decode: (String) -> CloudSyncResult<T>,
        fallbackError: String,
        codeOverrides: Map<Int, String> = emptyMap(),
    ): CloudSyncResult<T> = withContext(Dispatchers.IO) {
        val config = resolveConfig() ?: return@withContext notConfigured()
        val url = "${config.baseUrl}$path"
        val payload = gson.toJson(body)
        AppLogger.d(TAG, "POST $path (${payload.length} bytes)")
        try {
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    decode(text)
                } else {
                    val override = codeOverrides[response.code]
                    if (override != null) {
                        CloudSyncResult.Failure(override, response.code)
                    } else {
                        // 服务端错误体是 {"success":false,"error":"..."} 或 {"success":false,"message":"..."}
                        val fromBody = extractError(text)
                        CloudSyncResult.Failure(
                            fromBody.ifBlank { "$fallbackError（HTTP ${response.code}）" },
                            response.code,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.e(TAG, "云同步请求失败：$path", error)
            CloudSyncResult.Failure(error.message.orEmpty().ifBlank { fallbackError })
        }
    }

    /** 从错误响应里抽 `error` 或 `message`；抽不到返回空串。 */
    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val parsed = gson.fromJson(body, CountResponse::class.java)
            parsed?.error?.ifBlank { parsed.message }.orEmpty()
        } catch (error: Throwable) {
            // 错误体不一定是那段 JSON（比如 502 的 HTML），解析失败就当没有。
            ""
        }
    }
}
