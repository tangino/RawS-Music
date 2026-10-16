package com.rawsmusic.module.online.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.InstalledLxSource
import java.lang.reflect.Type

/**
 * `sources_json` 的一项（音源同步 DTO）。
 *
 * ## 字段名为什么这么怪
 *
 * ⚠️ **`docs/cloud-sync-contract.md` §4 ④ 列出的字段名与 OrBit 的真实实现不一致。**
 * 契约文档写的是 `enabled` / `installedAtMs` / `scriptSha256` / `updatedAtMs`，
 * 但 OrBit 的权威定义 `SourceScriptItem`（`app/.../data/online/model/SourceScriptItem.kt:8`）
 * 实际是：
 *
 * ```jsonc
 * { "id","name","version","author","description","scriptContent","sourceUrl",
 *   "isEnabled",                 // ← 不是 enabled
 *   "isPrimary",
 *   "supportPlatforms",          // ← 逗号分隔**字符串**（joinToString(",")），不是数组
 *   "importedAt" }               // ← 不是 installedAtMs
 * ```
 * 而且 OrBit **没有** `scriptSha256` / `updatedAtMs`。
 *
 * 这与之前发现的 `#EXT-ORBIT-*` 是同类的规格偏差：**以 OrBit 的真实实现为准**（铁律 3 的精神）。
 *
 * ## 本 DTO 的兼容策略
 *
 * - **写**：按 OrBit 的格式写（`isEnabled` / `importedAt` / `supportPlatforms` 用逗号字符串）。
 *   额外多写 `scriptSha256` / `updatedAtMs` —— OrBit 是按 key 取值的（`optString`/`optBoolean`），
 *   **多出来的键会被它忽略**，所以多写这两个是安全的，换来 RawS 侧能做完整性校验。
 * - **读**：靠 Gson 的 `alternate` 同时认两套命名；`supportPlatforms` 用
 *   [CloudPlatformListAdapter] 同时接受「逗号字符串」与「JSON 数组」两种写法。
 *   → 于是 **OrBit 写的能被我们读、我们写的能被 OrBit 读**。
 *
 * ## 不需要同步的字段
 *
 * 本地 `InstalledLxSource` 还有 `homepage` / `origin` / `format` / `platforms` / `actions` /
 * `lastError`。其中 **`platforms` / `actions` / `format` 都是安装时从脚本正文解析出来的**
 * （见 `LxSourcePluginStore` 里 `descriptor.platforms` / `metadata.format`），
 * 只要 `scriptContent` 在，还原后它们会被**自动重新推导**，所以**不往云端带**。
 * `platforms` 里唯一有跨端意义的是平台码 → 由 [supportPlatforms] 承载。
 */
data class CloudSourceEntry(
    @SerializedName("id")
    val id: String? = "",
    @SerializedName("name")
    val name: String? = "",
    @SerializedName("version")
    val version: String? = "",
    @SerializedName("author")
    val author: String? = "",
    @SerializedName("description")
    val description: String? = "",
    /** 脚本正文。**raws 只存路径，所以上传时必须读文件内联**；为空说明备份不完整。 */
    @SerializedName("scriptContent")
    val scriptContent: String? = "",
    @SerializedName("sourceUrl")
    val sourceUrl: String? = "",
    // alternate 让它同时认 OrBit 的 isEnabled 与契约文档写的 enabled。
    @SerializedName(value = "isEnabled", alternate = ["enabled"])
    val enabled: Boolean? = true,
    @SerializedName("isPrimary")
    val isPrimary: Boolean? = false,
    /** 平台码（`wy` / `tx` / `kg` / `kw` / `mg`）。线上是逗号分隔字符串，见 [CloudPlatformListAdapter]。 */
    @SerializedName("supportPlatforms")
    @JsonAdapter(CloudPlatformListAdapter::class)
    val supportPlatforms: List<String>? = emptyList(),
    @SerializedName(value = "importedAt", alternate = ["installedAtMs"])
    val importedAt: Long? = 0L,
    /** RawS 扩展：脚本 sha256，用于还原后校验完整性。OrBit 会忽略它。 */
    @SerializedName("scriptSha256")
    val scriptSha256: String? = "",
    /** RawS 扩展：OrBit 会忽略它。 */
    @SerializedName("updatedAtMs")
    val updatedAtMs: Long? = 0L,
) {
    val safeId: String get() = id.orEmpty()
    val safeName: String get() = name.orEmpty()
    val safeVersion: String get() = version.orEmpty()
    val safeAuthor: String get() = author.orEmpty()
    val safeDescription: String get() = description.orEmpty()
    val safeScriptContent: String get() = scriptContent.orEmpty()
    val safeSourceUrl: String get() = sourceUrl.orEmpty()
    val isEnabled: Boolean get() = enabled ?: true
    val isPrimarySource: Boolean get() = isPrimary ?: false
    val safeSupportPlatforms: List<String> get() = supportPlatforms.orEmpty()
    val safeImportedAt: Long get() = importedAt ?: 0L
    val safeScriptSha256: String get() = scriptSha256.orEmpty()
    val safeUpdatedAtMs: Long get() = updatedAtMs ?: 0L
}

/**
 * `supportPlatforms` 的双向适配：
 * - **写**：拼成逗号分隔字符串（OrBit `toJson` 的 `joinToString(",")`）——
 *   写成 JSON 数组会让 OrBit 的 `optString` 取不到值。
 * - **读**：字符串（按 `,` 切）与 JSON 数组**都接受**，容错两边都认。
 */
internal class CloudPlatformListAdapter :
    JsonSerializer<List<String>>,
    JsonDeserializer<List<String>> {

    override fun serialize(
        src: List<String>?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?,
    ): JsonElement = JsonPrimitive(src.orEmpty().joinToString(","))

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): List<String> {
        if (json == null || json.isJsonNull) return emptyList()
        // 数组写法（契约文档没写类型，容错）
        if (json.isJsonArray) {
            return json.asJsonArray
                .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        if (!json.isJsonPrimitive) return emptyList()
        return json.asString
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

object CloudSourceJson {

    private const val TAG = "CloudSourceSync"

    /** 与契约文档 §4 ④ 一致：`sources_json` 顶层是数组。 */
    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    // ------------------------------------------------------------------ 编码

    /**
     * `InstalledLxSource` + 脚本文本 → DTO。
     *
     * @param scriptContent **必须由调用方读好文件传入**（本对象不碰 Context/IO，便于单测）。
     *   为 null 或空白时记一条 warn 并把 `scriptContent` 留空 ——
     *   「只有路径没有正文」的备份是不完整的，**由调用方决定是报错还是照传**，
     *   本函数不静默编造内容。
     */
    fun fromLxSource(source: InstalledLxSource, scriptContent: String?): CloudSourceEntry {
        if (scriptContent.isNullOrBlank()) {
            AppLogger.w(
                TAG,
                "音源「${source.name}」的脚本正文为空（scriptPath=${source.scriptPath}），" +
                    "该备份还原后无法使用",
            )
        }
        return CloudSourceEntry(
            id = source.id,
            name = source.name,
            version = source.version,
            author = source.author,
            description = source.description,
            scriptContent = scriptContent.orEmpty(),
            sourceUrl = source.sourceUrl,
            enabled = source.enabled,
            isPrimary = source.isPrimary,
            // ⚠️ 取的是 Map 的 **key**（平台码）；value 是音质集合，OrBit 不认，别带过去。
            supportPlatforms = source.platforms.keys.toList(),
            importedAt = source.installedAtMs,
            scriptSha256 = source.scriptSha256,
            updatedAtMs = source.updatedAtMs,
        )
    }

    fun encode(entries: List<CloudSourceEntry>): String = gson.toJson(entries)

    // ------------------------------------------------------------------ 解码

    /**
     * 解析 `sources_json`。
     *
     * 容错：空串/null → 空列表；非法 JSON → 记日志并返回**空列表**（由调用方决定是否当失败）。
     *
     * ⚠️ 这里**不**因为 `scriptContent` 为空就丢掉条目 —— 丢掉了用户会以为备份里没有这个音源。
     * 是否可用由 [isUsable] 判断，调用方据此把「不完整」明确报给用户（AGENTS §6.0 红线：
     * 缺正文要明确报错，不静默失败）。
     */
    fun decode(json: String?): List<CloudSourceEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = gson.fromJson(json, Array<CloudSourceEntry>::class.java)?.toList().orEmpty()
            list.map { entry ->
                entry.copy(
                    id = entry.safeId,
                    name = entry.safeName,
                    version = entry.safeVersion,
                    author = entry.safeAuthor,
                    description = entry.safeDescription,
                    scriptContent = entry.safeScriptContent,
                    sourceUrl = entry.safeSourceUrl,
                    enabled = entry.isEnabled,
                    isPrimary = entry.isPrimarySource,
                    supportPlatforms = entry.safeSupportPlatforms,
                    importedAt = entry.safeImportedAt,
                    scriptSha256 = entry.safeScriptSha256,
                    updatedAtMs = entry.safeUpdatedAtMs,
                )
            }
        } catch (error: Throwable) {
            AppLogger.e(TAG, "解析 sources_json 失败（${json.length} 字节）", error)
            emptyList()
        }
    }

    /**
     * 该条目是否可还原：必须有脚本正文。
     *
     * 对应契约 §4 的「只有路径没有正文 → 明确报错『该备份不完整』」。
     */
    fun isUsable(entry: CloudSourceEntry): Boolean = entry.safeScriptContent.isNotBlank()

    /** 从条目里挑出不可用的那些，供 UI 提示用（不静默丢弃）。 */
    fun unusableEntries(entries: List<CloudSourceEntry>): List<CloudSourceEntry> =
        entries.filterNot(::isUsable)
}
