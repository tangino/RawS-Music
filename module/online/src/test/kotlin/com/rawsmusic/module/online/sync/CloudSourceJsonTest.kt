package com.rawsmusic.module.online.sync

import com.google.gson.Gson
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.MusicSourceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音源同步 DTO 的契约测试。
 *
 * **最要紧的两件事**（写错不会编译报错、本地也不会失败，只有跨端同步时才暴露）：
 * 1. **字段名**必须是 OrBit `SourceScriptItem.toJson()` 用的那套（`isEnabled` / `importedAt`），
 *    而不是 `docs/cloud-sync-contract.md` §4 ④ 里写的 `enabled` / `installedAtMs`。
 * 2. **`supportPlatforms` 必须是逗号分隔的字符串**，不能是 JSON 数组 ——
 *    OrBit 用 `joinToString(",")` 写、用 `optString` 读，给它数组它取不到值。
 */
class CloudSourceJsonTest {

    private val gson = Gson()

    // ------------------------------------------------------------ 写入格式

    @Test
    fun `serialized json uses Orbit's field names, not the doc's`() {
        val json = CloudSourceJson.encode(listOf(entry()))

        // OrBit 真实使用的键
        assertTrue("缺 isEnabled", json.contains("\"isEnabled\""))
        assertTrue("缺 importedAt", json.contains("\"importedAt\""))
        assertTrue("缺 scriptContent", json.contains("\"scriptContent\""))
        assertTrue("缺 isPrimary", json.contains("\"isPrimary\""))
        assertTrue("缺 sourceUrl", json.contains("\"sourceUrl\""))

        // 契约文档里写错的那套键**不应该**出现在我们写出的 JSON 里
        assertFalse("不应出现 enabled（OrBit 用 isEnabled）", json.contains("\"enabled\""))
        assertFalse("不应出现 installedAtMs（OrBit 用 importedAt）", json.contains("\"installedAtMs\""))
    }

    @Test
    fun `supportPlatforms is written as a comma joined string`() {
        val json = CloudSourceJson.encode(
            listOf(entry(platforms = listOf("wy", "tx", "kg"))),
        )

        // 关键：是字符串 "wy,tx,kg"，不是数组 ["wy","tx","kg"]
        assertTrue(
            "supportPlatforms 必须写成逗号分隔字符串，实际 JSON：$json",
            json.contains("\"supportPlatforms\":\"wy,tx,kg\""),
        )
        assertFalse("不能写成 JSON 数组", json.contains("\"supportPlatforms\":["))
    }

    @Test
    fun `raws extension fields are written without breaking Orbit`() {
        val json = CloudSourceJson.encode(
            listOf(entry(sha256 = "abc123", updatedAt = 999L)),
        )
        // 多写的这两个键 OrBit 会忽略（它按 key 取值），换来 RawS 能做完整性校验。
        assertTrue(json.contains("\"scriptSha256\":\"abc123\""))
        assertTrue(json.contains("\"updatedAtMs\":999"))
    }

    // ------------------------------------------------------------ 读取兼容

    @Test
    fun `reads Orbit shaped json`() {
        val orbitJson = """
            [{"id":"s1","name":"音源甲","version":"1.0","author":"作者","description":"",
              "scriptContent":"/*js*/","sourceUrl":"https://x/y.js","isEnabled":true,
              "isPrimary":false,"supportPlatforms":"wy,tx","importedAt":1700000000000}]
        """.trimIndent()

        val entry = CloudSourceJson.decode(orbitJson).single()

        assertEquals("s1", entry.id)
        assertEquals("音源甲", entry.name)
        assertEquals("/*js*/", entry.scriptContent)
        assertTrue(entry.isEnabled)
        assertEquals(listOf("wy", "tx"), entry.supportPlatforms)
        assertEquals(1700000000000L, entry.importedAt)
        assertTrue(CloudSourceJson.isUsable(entry))
    }

    @Test
    fun `reads doc shaped json via alternate names`() {
        // 契约文档那套命名（enabled / installedAtMs）也要能读，容错两边都认。
        val docJson = """
            [{"id":"s2","name":"音源乙","enabled":false,"installedAtMs":123,"supportPlatforms":""}]
        """.trimIndent()

        val entry = CloudSourceJson.decode(docJson).single()

        assertEquals("s2", entry.id)
        assertFalse("应能读到 enabled 别名", entry.isEnabled)
        assertEquals(123L, entry.importedAt)
    }

    @Test
    fun `accepts supportPlatforms written as an array`() {
        // 契约文档没写 supportPlatforms 的类型，别的实现可能写成数组 —— 也要能读。
        val json = """[{"id":"s3","name":"n","supportPlatforms":["wy","kg"]}]"""

        assertEquals(listOf("wy", "kg"), CloudSourceJson.decode(json).single().supportPlatforms)
    }

    @Test
    fun `round trips through gson`() {
        val original = listOf(entry(platforms = listOf("wy", "mg")))
        val restored = CloudSourceJson.decode(CloudSourceJson.encode(original)).single()

        assertEquals(original.single().id, restored.id)
        assertEquals(original.single().scriptContent, restored.scriptContent)
        assertEquals(listOf("wy", "mg"), restored.supportPlatforms)
        assertEquals(original.single().scriptSha256, restored.scriptSha256)
    }

    // ------------------------------------------------------------ 容错与完整性

    @Test
    fun `decode tolerates blank and invalid json`() {
        assertTrue(CloudSourceJson.decode(null).isEmpty())
        assertTrue(CloudSourceJson.decode("").isEmpty())
        // 非法 JSON 不能抛异常出去（云端可能存了脏数据）
        assertTrue(CloudSourceJson.decode("{ 这不是 JSON").isEmpty())
    }

    @Test
    fun `entry without script content is reported as unusable, not silently dropped`() {
        val json = """
            [{"id":"ok","name":"有正文","scriptContent":"/*js*/"},
             {"id":"broken","name":"只有路径","sourceUrl":"https://x/y.js","scriptContent":""}]
        """.trimIndent()

        val entries = CloudSourceJson.decode(json)

        // 关键：**不能**因为缺正文就把条目丢掉 —— 用户会以为备份里没这个音源。
        assertEquals(2, entries.size)
        assertEquals(1, CloudSourceJson.unusableEntries(entries).size)
        assertEquals("broken", CloudSourceJson.unusableEntries(entries).single().id)
    }

    // ------------------------------------------------------------ 本地映射

    @Test
    fun `platforms map keys become supportPlatforms, qualities are dropped`() {
        val source = lxSource(
            // 本地是「平台 → 音质集合」；OrBit 只认平台码，所以只能取 key。
            platforms = mapOf("wy" to setOf("320k", "flac"), "tx" to setOf("128k")),
        )

        val entry = CloudSourceJson.fromLxSource(source, "/*js*/")

        assertEquals(setOf("wy", "tx"), entry.safeSupportPlatforms.toSet())
        // 音质不该混进来
        assertFalse(entry.safeSupportPlatforms.contains("320k"))
        assertFalse(entry.safeSupportPlatforms.contains("flac"))
    }

    @Test
    fun `local source is mapped with inlined script and Orbit compatible defaults`() {
        val source = lxSource()

        val entry = CloudSourceJson.fromLxSource(source, "/*js*/")

        assertEquals(source.id, entry.id)
        assertEquals(source.name, entry.name)
        assertEquals("/*js*/", entry.scriptContent)
        assertEquals(source.enabled, entry.isEnabled)
        assertEquals(source.installedAtMs, entry.importedAt)
        assertEquals(source.scriptSha256, entry.scriptSha256)
        // RawS 没有主音源概念 → 固定 false
        assertFalse(entry.isPrimarySource)
    }

    @Test
    fun `missing script file yields empty content instead of fabricating one`() {
        val entry = CloudSourceJson.fromLxSource(lxSource(), scriptContent = null)

        // 读不到文件时留空（由调用方决定报错），**绝不**编造内容。
        assertEquals("", entry.scriptContent)
        assertFalse(CloudSourceJson.isUsable(entry))
    }

    // ------------------------------------------------------------ 构造工具

    private fun entry(
        platforms: List<String> = listOf("wy"),
        sha256: String = "sha",
        updatedAt: Long = 0L,
    ) = CloudSourceEntry(
        id = "s1",
        name = "音源甲",
        version = "1.0",
        author = "作者",
        description = "说明",
        scriptContent = "/*js*/",
        sourceUrl = "https://example.com/a.js",
        enabled = true,
        supportPlatforms = platforms,
        importedAt = 1_700_000_000_000L,
        scriptSha256 = sha256,
        updatedAtMs = updatedAt,
    )

    private fun lxSource(
        platforms: Map<String, Set<String>> = mapOf("wy" to setOf("320k")),
    ) = InstalledLxSource(
        id = "lx-1",
        name = "音源甲",
        version = "1.0",
        author = "作者",
        description = "说明",
        sourceUrl = "https://example.com/a.js",
        scriptPath = "/data/app/files/plugins/abc.js",
        scriptSha256 = "sha-abc",
        origin = MusicSourceOrigin.RemoteUrl,
        enabled = true,
        platforms = platforms,
        installedAtMs = 1_600_000_000_000L,
        updatedAtMs = 1_600_000_000_001L,
    )
}
