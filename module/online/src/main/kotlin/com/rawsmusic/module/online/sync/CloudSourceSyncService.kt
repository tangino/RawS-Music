package com.rawsmusic.module.online.sync

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.LxSourceInstallResult
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourceOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 上传结果：`incomplete` 里的名字是**脚本文件读不到**的（备份里条目不可用），必须报给用户。 */
data class CloudSourceUploadReport(
    val uploaded: Int,
    val incomplete: List<String>,
)

/** 还原结果。字段够 UI 把「装了几个 / 失败几个 / 校验不过几个 / 备份本身不完整几个」都讲清楚。 */
data class CloudSourceRestoreReport(
    val installed: Int,
    /** 备份里缺脚本正文、**根本没尝试安装**的条目（AGENTS §6.0：要明确报错，不静默失败）。 */
    val skippedIncomplete: List<String>,
    /** 安装成功但 sha256 与备份记录**对不上**的条目名（脚本可能被改过）。 */
    val hashMismatched: List<String>,
    /** 安装失败的条目，形如 `名字: 原因`。 */
    val failures: List<String>,
    val updatedAt: Long,
) {
    val attempted: Int get() = installed + hashMismatched.size + failures.size
}

/**
 * 洛雪（LX）音源云同步的编排层。
 *
 * 分工与歌单那套一致：[CloudSourceJson] 管**格式**，[CloudSyncClient] 管**传输**，
 * 这里管**装配**（读脚本文件内联 / 安装并校验）。
 *
 * ## 本任务只做 LX
 *
 * 目标里写的是「**洛雪**歌源同步」，而契约 §4 ④ 的 `sources_json` 字段（`scriptContent`、
 * `supportPlatforms` 用 `wy/tx/kg/kw/mg` 平台码）本身就是 LX 的形状 —— OrBit 也只有 LX。
 * RawS 还有 MusicFree（`MusicSourcePluginStore`），**不在本任务范围**，不要顺手扩进去。
 *
 * ## 上传为什么必须读文件
 *
 * `InstalledLxSource` 只存 `scriptPath` + `scriptSha256`，**不存正文**。所以要把文件内容
 * 读出来内联进 `scriptContent`，否则云端备份还原后是个空壳（契约 §4 末尾明确要求）。
 * 读不到文件时**不编造内容**，留空并记进 [CloudSourceUploadReport.incomplete] 让 UI 报出来。
 *
 * ## 还原为什么必须走安装 API
 *
 * 契约 §4 末尾：写回脚本文件 → 校验 sha256 → 走**既有安装 API** 注册。
 * 这里用 [LxSourcePluginStore.installFromScript]（正是为此补的入口），
 * 它会按 sha256 内容寻址落盘、解析元数据、发布 StateFlow —— **不要**绕过它自己写文件。
 */
object CloudSourceSyncService {

    private const val TAG = "CloudSourceSync"

    // ------------------------------------------------------------------ 上传

    /** 组装 `sources_json` 并上传。 */
    suspend fun upload(context: Context): CloudSyncResult<CloudSourceUploadReport> = try {
        val report = buildPayload(context)
        if (report.uploaded == 0 && report.incomplete.isEmpty()) {
            // 本机一个音源都没装：仍然上传空数组（等价于「清空云端音源备份」），
            // 但要如实告诉调用方，避免 UI 显示「上传成功」却让人以为备份了东西。
            AppLogger.i(TAG, "本机没有已安装的 LX 音源，将上传空备份")
        }
        when (val result = CloudSyncClient.backupSources(context, report.json)) {
            is CloudSyncResult.Failure -> result
            is CloudSyncResult.Success -> CloudSyncResult.Success(
                CloudSourceUploadReport(
                    uploaded = report.uploaded,
                    incomplete = report.incomplete,
                ),
                result.message,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "组装音源上传载荷失败", error)
        CloudSyncResult.Failure(error.message.orEmpty().ifBlank { "音源上传失败" })
    }

    private data class Payload(val json: String, val uploaded: Int, val incomplete: List<String>)

    /** 读全部已安装音源的脚本正文并序列化。读不到正文的**不编造**，记进 `incomplete`。 */
    private suspend fun buildPayload(context: Context): Payload = withContext(Dispatchers.IO) {
        val installed = LxSourcePluginStore.sources.value
        val incomplete = mutableListOf<String>()
        val entries = installed.map { source ->
            val content = readScriptOrNull(source)
            if (content.isNullOrBlank()) incomplete += source.name
            CloudSourceJson.fromLxSource(source, content)
        }
        if (incomplete.isNotEmpty()) {
            AppLogger.w(TAG, "有 ${incomplete.size} 个音源的脚本正文读不到：${incomplete.joinToString()}")
        }
        Payload(
            json = CloudSourceJson.encode(entries),
            uploaded = entries.size,
            incomplete = incomplete,
        )
    }

    /** 读脚本文件；失败返回 null（**不要**吞成空串假装成功）。 */
    private fun readScriptOrNull(source: InstalledLxSource): String? = try {
        val file = File(source.scriptPath)
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (error: Throwable) {
        AppLogger.e(TAG, "读取音源脚本失败：${source.name} (${source.scriptPath})", error)
        null
    }

    // ------------------------------------------------------------------ 还原

    /** 从云端下载并安装音源。 */
    suspend fun restore(context: Context): CloudSyncResult<CloudSourceRestoreReport> = try {
        when (val downloaded = CloudSyncClient.restoreSources(context)) {
            is CloudSyncResult.Failure -> downloaded
            is CloudSyncResult.Success -> {
                val report = applyBackup(context, downloaded.data)
                AppLogger.i(
                    TAG,
                    "音源还原完成：安装 ${report.installed}、跳过（缺正文）${report.skippedIncomplete.size}、" +
                        "哈希不匹配 ${report.hashMismatched.size}、失败 ${report.failures.size}",
                )
                CloudSyncResult.Success(report, downloaded.message)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "音源还原失败", error)
        CloudSyncResult.Failure(error.message.orEmpty().ifBlank { "音源还原失败" })
    }

    private suspend fun applyBackup(
        context: Context,
        backup: CloudSourceBackup,
    ): CloudSourceRestoreReport {
        val entries = CloudSourceJson.decode(backup.sourcesJson)

        var installed = 0
        val skippedIncomplete = mutableListOf<String>()
        val hashMismatched = mutableListOf<String>()
        val failures = mutableListOf<String>()

        entries.forEach { entry ->
            // AGENTS §6.0：缺正文的备份要**明确报错**，不静默失败。
            // 这里跳过而不是「当空脚本安装」——空脚本会被解析器拒绝，报错信息还更难懂。
            if (!CloudSourceJson.isUsable(entry)) {
                skippedIncomplete += entry.name
                return@forEach
            }

            val result = LxSourcePluginStore.installFromScript(
                context = context,
                script = entry.scriptContent,
                sourceUrl = entry.sourceUrl,
                // 云端不带 origin（OrBit 也没这个字段）。用 sourceUrl 推断：
                // 有地址的当初就是从 URL 装的，没地址的当作本地文件导入。
                origin = if (entry.sourceUrl.isBlank()) {
                    MusicSourceOrigin.LocalFile
                } else {
                    MusicSourceOrigin.RemoteUrl
                },
            )

            when (result) {
                is LxSourceInstallResult.Failure -> {
                    failures += "${entry.name}: ${result.message}"
                }

                is LxSourceInstallResult.Success -> {
                    val source = result.source
                    // sha256 校验：备份里的值若为空（例如 OrBit 写的，它没有这个字段）就无从比对，
                    // 只做「装上了」的计数，不算不匹配。
                    val expected = entry.scriptSha256
                    if (expected.isNotBlank() && expected != source.scriptSha256) {
                        hashMismatched += entry.name
                        AppLogger.w(
                            TAG,
                            "音源「${entry.name}」安装后 sha256 与备份不一致：" +
                                "备份=$expected 实际=${source.scriptSha256}",
                        )
                    } else {
                        installed++
                    }
                    // installFromScript 对新装的一律给 enabled = true，
                    // 云端记录「已停用」的要补一次，否则还原后会被意外启用。
                    if (source.enabled != entry.enabled) {
                        LxSourcePluginStore.setEnabled(source.id, entry.enabled)
                    }
                }
            }
        }

        return CloudSourceRestoreReport(
            installed = installed,
            skippedIncomplete = skippedIncomplete,
            hashMismatched = hashMismatched,
            failures = failures,
            updatedAt = backup.updatedAt,
        )
    }
}
