package com.rawsmusic.module.online.playback

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.OnlinePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 音频直链探活与完整性校验（逐字移植自 OrBitPlayer 的
 * `OnlineAudioSourceManager.probeAndValidateAudioUrl` 与 `resolveMiguDirectPlayUrl`）。
 *
 * 存在的意义：平台返回的"播放地址"经常是 **30s/45s 试听片段**、网页报错流或防盗链失效响应。
 * 直接把这种 URL 交给播放器，用户会听到一段就断。所以这里做四层拦截：
 * URL 关键字 → 响应状态与 Content-Type → 流长度下限 → 用 MediaMetadataRetriever 探真实时长。
 *
 * ⚠️ 各层阈值与 Referer 映射都是从实际反爬/试听行为反推出来的，**不要凭直觉调整**。
 */
object OnlinePlaybackProbe {

    private const val TAG = "OnlinePlayback"

    private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Mobile)"
    private const val UA_BROWSER = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 探活并校验音频直链。
     *
     * @return `(最终地址, 实际时长毫秒)`；校验不通过返回 null。
     */
    suspend fun probeAndValidateAudioUrl(
        rawUrl: String,
        expectedDurationMs: Long = 0L,
        targetPlatform: OnlinePlatform? = null,
    ): Pair<String, Long>? = withContext(Dispatchers.IO) {
        if (rawUrl.isBlank()) return@withContext null
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext null
        }

        // 1. URL 关键字检测：识别各大平台的试听/预览标记
        if (containsAuditionKeyword(rawUrl)) {
            AppLogger.w(TAG, "Audio probe rejected URL: raw URL contains preview/audition keyword for $rawUrl")
            return@withContext null
        }

        try {
            // 2. 用 Range 请求探前 8KB 与响应头（附加各平台合规防盗链 Header）
            val reqBuilder = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", UA_BROWSER)
                .header("Range", "bytes=0-8192")
                .header("Accept", "*/*")

            if (targetPlatform != null) {
                when (targetPlatform) {
                    OnlinePlatform.QQ -> reqBuilder.header("Referer", "https://y.qq.com/")
                    OnlinePlatform.NETEASE -> reqBuilder.header("Referer", "https://music.163.com/")
                    OnlinePlatform.KUGOU -> reqBuilder.header("Referer", "https://www.kugou.com/")
                    OnlinePlatform.KUWO -> reqBuilder.header("Referer", "https://www.kuwo.cn/")
                    OnlinePlatform.MIGU -> reqBuilder.header("Referer", "https://m.music.migu.cn/")
                }
            }

            var finalUrl: String? = null
            var totalContentLength = -1L
            var contentType = ""
            var httpSuccess = false

            try {
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    finalUrl = resp.request.url.toString()
                    contentType = resp.header("Content-Type", "")?.lowercase() ?: ""

                    // 返回网页报错或 JSON 错误文本，判定为无效音频直链
                    if (code >= 400 || contentType.contains("text/html") || contentType.contains("application/json")) {
                        AppLogger.d(TAG, "Audio probe rejected URL: status=$code, contentType=$contentType for $rawUrl")
                        return@withContext null
                    }

                    if (resp.isSuccessful || code == 206) {
                        httpSuccess = true
                        val contentRange = resp.header("Content-Range")
                        if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
                            val totalStr = contentRange.substringAfterLast("/").trim()
                            totalContentLength = totalStr.toLongOrNull() ?: -1L
                        }
                        if (totalContentLength <= 0L) {
                            totalContentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        }

                        // 文件过小（如小于 2KB 的假响应体）
                        if (code == 200 && totalContentLength in 0..2048) {
                            return@withContext null
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "Range probe exception for $rawUrl: ${e.message}")
            }

            // Range 请求被 CDN 限制时，退化成轻量 HEAD 确认连通性
            if (!httpSuccess) {
                try {
                    val headReqBuilder = Request.Builder()
                        .url(rawUrl)
                        .header("User-Agent", UA_MOBILE)
                        .head()
                    if (targetPlatform != null) {
                        when (targetPlatform) {
                            OnlinePlatform.QQ -> headReqBuilder.header("Referer", "https://y.qq.com/")
                            OnlinePlatform.NETEASE -> headReqBuilder.header("Referer", "https://music.163.com/")
                            OnlinePlatform.KUGOU -> headReqBuilder.header("Referer", "https://www.kugou.com/")
                            OnlinePlatform.KUWO -> headReqBuilder.header("Referer", "https://www.kuwo.cn/")
                            OnlinePlatform.MIGU -> headReqBuilder.header("Referer", "https://m.music.migu.cn/")
                        }
                    }
                    okHttpClient.newCall(headReqBuilder.build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            finalUrl = resp.request.url.toString()
                            contentType = resp.header("Content-Type", "")?.lowercase() ?: ""
                            totalContentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                            httpSuccess = true
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val validUrl = finalUrl ?: rawUrl

            // 跟随 302 重定向后的真实 CDN URL 复检试听关键字
            if (containsAuditionKeyword(validUrl)) {
                AppLogger.w(TAG, "Audio probe rejected URL: redirect URL contains preview/audition keyword for $validUrl")
                return@withContext null
            }

            // 3. 流长度下限：按 64kbps 极限低码率估算，每秒至少 8KB
            if (expectedDurationMs >= 30_000L && totalContentLength > 0L) {
                val minExpectedBytes = (expectedDurationMs / 1000L) * 8_000L
                if (totalContentLength < minExpectedBytes) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected URL: stream length too short ($totalContentLength bytes, " +
                            "min required $minExpectedBytes bytes for ${expectedDurationMs}ms) for $validUrl",
                    )
                    return@withContext null
                }
            }

            // 4. 用 MediaMetadataRetriever 探真实时长（超时设为 2200ms，对齐 OrBitPlayer 的轻量高效探活）
            var detectedDurationMs = 0L
            try {
                withTimeoutOrNull(2200L) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        val headers = mutableMapOf("User-Agent" to UA_MOBILE)
                        if (targetPlatform != null) {
                            when (targetPlatform) {
                                OnlinePlatform.QQ -> headers["Referer"] = "https://y.qq.com/"
                                OnlinePlatform.NETEASE -> headers["Referer"] = "https://music.163.com/"
                                OnlinePlatform.KUGOU -> headers["Referer"] = "https://www.kugou.com/"
                                OnlinePlatform.KUWO -> headers["Referer"] = "https://www.kuwo.cn/"
                                OnlinePlatform.MIGU -> headers["Referer"] = "https://m.music.migu.cn/"
                            }
                        }
                        retriever.setDataSource(validUrl, headers)
                        val durStr = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                        )
                        detectedDurationMs = durStr?.toLongOrNull() ?: 0L
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "MediaMetadataRetriever probe skipped for $validUrl: ${e.message}")
            }

            // 5. 严格杜绝试听截断短流 (28s/30s/45s 试听片段)、残缺音频（完全对齐 OrBitPlayer 的科学规则）
            if (detectedDurationMs > 0L) {
                // 若探测时长与预期时长相差过大（例如预期大于60秒但探测相差超过45秒且探测时长小于60秒），拦截试听
                if (expectedDurationMs >= 60_000L && detectedDurationMs <= 45_000L) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected URL: preview snippet detected ($detectedDurationMs ms vs expected $expectedDurationMs ms) for $validUrl",
                    )
                    return@withContext null
                }

                // 实际 ≤45s（各大平台典型 28s/30s/45s 试听截断），但预期 ≥50s 或未知时长 → 绝对试听截断，拦截
                if (detectedDurationMs <= 45_000L && (expectedDurationMs >= 50_000L || expectedDurationMs <= 0L)) {
                    AppLogger.w(TAG, "Audio probe rejected URL: preview snippet detected ($detectedDurationMs ms) for $validUrl")
                    return@withContext null
                }
                // 实际 <60s，但预期 ≥90s
                if (detectedDurationMs < 60_000L && expectedDurationMs >= 90_000L) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected URL: preview duration too short " +
                            "($detectedDurationMs ms vs expected $expectedDurationMs ms) for $validUrl",
                    )
                    return@withContext null
                }
                // 实际不足预期的 70% → 不完整流
                if (expectedDurationMs >= 45_000L && detectedDurationMs < (expectedDurationMs * 0.70f).toLong()) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected URL: incomplete audio duration " +
                            "($detectedDurationMs ms vs expected $expectedDurationMs ms) for $validUrl",
                    )
                    return@withContext null
                }
            } else {
                // 未探出真实时长时（超时或未解析出）的防试听防漏兜底防御：
                // 1) 预期长歌（>=60s），但流体积偏小（<1.5MB，28秒试听一般在 300KB~900KB）：坚决拦截
                if (expectedDurationMs >= 60_000L && totalContentLength in 1..1_500_000L) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected suspicious small stream without duration verification " +
                            "($totalContentLength bytes for expected ${expectedDurationMs}ms) for $validUrl",
                    )
                    return@withContext null
                }
                // 2) 未知时长但文件流过小（<1MB）：疑似试听片段
                if (expectedDurationMs <= 0L && totalContentLength in 1..1_000_000L) {
                    AppLogger.w(
                        TAG,
                        "Audio probe rejected suspicious small stream without duration verification " +
                            "($totalContentLength bytes) for $validUrl",
                    )
                    return@withContext null
                }
            }

            val finalDuration = if (detectedDurationMs > 0L) detectedDurationMs else expectedDurationMs
            Pair(validUrl, finalDuration)
        } catch (e: Exception) {
            AppLogger.d(TAG, "Audio probe exception for $rawUrl: ${e.message}")
            null
        }
    }

    private fun containsAuditionKeyword(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("audition") || lower.contains("preview") || lower.contains("trial") ||
            lower.contains("sample") || lower.contains("listen_part") || lower.contains("/short/") ||
            lower.contains("试听") || lower.contains("snippet")
    }

    /**
     * 咪咕官方音频直链解析：先按 copyrightId 查 18 位 contentId，
     * 再依次尝试 HQ / PQ 网关，跟随 302 拿到真实 CDN 直链。
     *
     * 全流程失败时兜底返回 HQ 网关地址（让播放器自己去跟随重定向）。
     */
    suspend fun resolveMiguDirectPlayUrl(copyrightId: String): String? = withContext(Dispatchers.IO) {
        if (copyrightId.isBlank()) return@withContext null
        try {
            // 1. 查 18 位 contentId
            val infoUrl =
                "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?copyrightId=$copyrightId&resourceType=2"
            val req = Request.Builder()
                .url(infoUrl)
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://m.music.migu.cn/")
                .build()
            // 原实现未关闭响应体；这里补上 use{}，行为不变但避免连接池泄漏。
            val resStr = okHttpClient.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val root = JSONObject(resStr)
            val resourceArr = root.optJSONArray("resource") ?: return@withContext null
            val resObj = resourceArr.optJSONObject(0) ?: return@withContext null
            val contentId = resObj.optString("contentId")
            if (contentId.isBlank()) return@withContext null

            // 2. 依次尝试 HQ / PQ 网关，拿 302 后的真实 CDN 直链
            val toneFlags = listOf("HQ", "PQ")
            for (tone in toneFlags) {
                val gateUrl = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do" +
                    "?toneFlag=$tone&netType=00&userId=1&ua=Android_migu&version=5.1" +
                    "&copyrightId=$copyrightId&contentId=$contentId&resourceType=2&channel=0"
                val gateReq = Request.Builder()
                    .url(gateUrl)
                    .header("User-Agent", UA_MOBILE)
                    .header("Referer", "https://m.music.migu.cn/")
                    .header("channel", "0")
                    .build()
                okHttpClient.newCall(gateReq).execute().use { resp ->
                    val finalUrl = resp.request.url.toString()
                    if (resp.isSuccessful && !finalUrl.contains("listenSong.do", ignoreCase = true)) {
                        return@withContext finalUrl
                    }
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        return@withContext location
                    }
                }
            }

            // 兜底返回默认网关 URL
            "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do" +
                "?toneFlag=HQ&netType=00&userId=1&ua=Android_migu&version=5.1" +
                "&copyrightId=$copyrightId&contentId=$contentId&resourceType=2&channel=0"
        } catch (e: Exception) {
            AppLogger.w(TAG, "resolveMiguDirectPlayUrl error for $copyrightId: ${e.message}")
            null
        }
    }
}
