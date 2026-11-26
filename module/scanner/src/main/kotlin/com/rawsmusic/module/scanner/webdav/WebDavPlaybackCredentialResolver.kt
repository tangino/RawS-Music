package com.rawsmusic.module.scanner.webdav

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Rehydrates WebDAV HTTP options for persisted queue items after the process-local stream registry
 * has been lost. It intentionally matches only URLs inside the configured WebDAV collection root,
 * so unrelated HTTP media never inherit WebDAV credentials.
 */
object WebDavPlaybackCredentialResolver {
    private const val TAG = "WebDavCredentialResolver"
    private val client by lazy { WebDavClient() }

    private fun findConfigForUrl(url: String): Pair<String, WebDavConfig>? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val stripped = WebDavUrlTools.stripUserInfo(url)
        val allDirs = AppPreferences.WebDav.getDirectories()
        for (dir in allDirs) {
            val root = dir.url.trim()
            if (root.isBlank()) continue
            val matches = runCatching { WebDavUrlTools.isWithinConfiguredRoot(root, stripped) }.getOrDefault(false)
            if (matches) {
                val cfg = WebDavConfig(
                    url = root,
                    username = dir.username,
                    password = dir.password,
                    authMode = when (dir.authMode) {
                        1 -> AuthMode.BASIC
                        2 -> AuthMode.DIGEST
                        else -> AuthMode.AUTO
                    },
                    extraHeaders = WebDavHeaderCodec.parseOrEmpty(dir.extraHeadersText),
                )
                return stripped to cfg
            }
        }
        val configuredUrl = AppPreferences.WebDav.url.trim()
        if (configuredUrl.isNotBlank()) {
            val matches = runCatching { WebDavUrlTools.isWithinConfiguredRoot(configuredUrl, stripped) }.getOrDefault(false)
            if (matches) {
                val cfg = WebDavConfig(
                    url = configuredUrl,
                    username = AppPreferences.WebDav.username,
                    password = AppPreferences.WebDav.password,
                    authMode = when (AppPreferences.WebDav.authMode) {
                        1 -> AuthMode.BASIC
                        2 -> AuthMode.DIGEST
                        else -> AuthMode.AUTO
                    },
                    extraHeaders = WebDavHeaderCodec.parseOrEmpty(AppPreferences.WebDav.extraHeadersText),
                )
                return stripped to cfg
            }
        }
        return null
    }

    fun sanitizeUrlIfConfigured(url: String): String? = findConfigForUrl(url)?.first

    fun isConfiguredWebDavUrl(url: String): Boolean = findConfigForUrl(url) != null

    fun toSafeUrl(url: String): String? = WebDavUrlTools.toSafeHttpUrl(url)?.toString()

    /**
     * 通过 HTTP Range 轻量提取 WebDAV 音频内嵌封面并写入目标文件（零全量下载、无需 FFmpeg）。
     */
    fun extractEmbeddedCoverToFile(url: String, outputFile: java.io.File): Boolean {
        val (sanitizedUrl, config) = findConfigForUrl(url) ?: return false
        val safeUrl = toSafeUrl(sanitizedUrl) ?: sanitizedUrl
        return runCatching {
            val bytes = WebDavHeaderTagReader.readEmbeddedCoverBytes(
                client = client,
                config = config,
                remoteUrl = safeUrl,
            ) ?: return false
            if (bytes.size <= 128) return false
            outputFile.parentFile?.mkdirs()
            val tmp = java.io.File(outputFile.parentFile, "${outputFile.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(outputFile)) {
                outputFile.writeBytes(bytes)
                tmp.delete()
            }
            outputFile.exists() && outputFile.length() > 128
        }.getOrDefault(false)
    }

    /**
     * 尝试读取 WebDAV 同目录同名 .lrc 侧车歌词（如 `xxx.flac` -> `xxx.lrc`）。
     * 仅支持已配置的 WebDAV 路径，上限 512KB，自动兼容 UTF-8 / GB18030 编码。
     */
    fun fetchSidecarLrc(audioUrl: String): String? {
        val (sanitizedUrl, config) = findConfigForUrl(audioUrl) ?: return null
        val noQuery = sanitizedUrl.substringBefore('?').substringBefore('#')
        val lastDot = noQuery.lastIndexOf('.')
        val lastSlash = noQuery.lastIndexOf('/')
        if (lastDot <= lastSlash || lastDot < 0) return null
        val lrcUrl = noQuery.substring(0, lastDot) + ".lrc"
        val safeLrcUrl = toSafeUrl(lrcUrl) ?: lrcUrl

        return runCatching {
            val call = client.openStreamingCall(
                config = config,
                remotePath = safeLrcUrl,
                rangeHeader = null,
                headOnly = false,
            )
            call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("text/html")) return null
                val stream = resp.body?.byteStream() ?: return null
                val maxBytes = 512 * 1024
                val out = java.io.ByteArrayOutputStream(8192)
                val buf = ByteArray(8192)
                var total = 0
                while (total < maxBytes) {
                    val read = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    total += read
                }
                val bytes = out.toByteArray()
                if (bytes.isEmpty()) return null
                decodeSidecarLrcBytes(bytes).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun decodeSidecarLrcBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8Strict = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8Strict != null) return utf8Strict
        return runCatching { String(bytes, java.nio.charset.Charset.forName("GB18030")) }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /**
     * 快速检查本地是否已存在该 WebDAV 歌曲的完整播放缓存文件。
     * 纯本地内存与文件检查，毫秒级返回，绝不产生网络请求或线程阻塞。
     */
    fun findCachedPath(context: Context, url: String, knownSize: Long = 0L): String? {
        val (sanitizedUrl, config) = findConfigForUrl(url) ?: return null
        return WebDavPlaybackCache.findCachedPath(
            context = context,
            config = config,
            remoteUrl = sanitizedUrl,
            knownSize = knownSize,
        )
    }

    fun resolve(url: String): WebDavPlaybackRequest? {
        val (sanitizedUrl, config) = findConfigForUrl(url) ?: return null
        return runCatching {
            val req = client.buildPlaybackRequestForUrl(
                config = config,
                targetUrl = sanitizedUrl,
                refreshAuthentication = true,
            )
            val headerProvider: (String) -> Map<String, String> = { target ->
                client.buildPlaybackHeaders(
                    config = config,
                    targetUrl = target,
                    refreshAuthentication = true,
                )
            }
            val allAliases = listOf(
                url,
                sanitizedUrl,
                req.url,
                toSafeUrl(url).orEmpty(),
                toSafeUrl(sanitizedUrl).orEmpty(),
                toSafeUrl(req.url).orEmpty(),
            ).filter { it.isNotBlank() }.distinct()

            allAliases.forEach { u ->
                RemoteHttpStreamRegistry.register(
                    url = u,
                    headers = req.headers,
                    userAgent = req.userAgent,
                    owner = "webdav-direct",
                    headerProvider = headerProvider,
                    urlProvider = { toSafeUrl(u) ?: u },
                )
            }
            req
        }.onFailure { error ->
            Log.w(TAG, "RESOLVE_FAILED url=${sanitizedUrl.safeLogUrl()}: ${error.message}", error)
        }.getOrNull()
    }

    /**
     * 如果 URL 属于已配置的 WebDAV 目录，预先注册 HTTP 选项与鉴权信息至 RemoteHttpStreamRegistry。
     */
    fun registerIfConfigured(url: String): Boolean {
        return resolve(url) != null
    }

    fun cachePath(context: Context, url: String, knownSize: Long = 0L): String? {
        val (sanitizedUrl, config) = findConfigForUrl(url) ?: return null
        return runCatching {
            val request = client.buildPlaybackRequestForUrl(
                config = config,
                targetUrl = sanitizedUrl,
                refreshAuthentication = true,
            )
            val path = WebDavPlaybackCache.resolve(
                context = context,
                client = client,
                config = config,
                remoteUrl = sanitizedUrl,
                knownSize = knownSize,
            )
            val headerProvider: (String) -> Map<String, String> = { target ->
                client.buildPlaybackHeaders(
                    config = config,
                    targetUrl = target,
                    refreshAuthentication = true,
                )
            }
            val registeredUrls = listOf(
                url,
                sanitizedUrl,
                request.url,
                toSafeUrl(url).orEmpty(),
                toSafeUrl(sanitizedUrl).orEmpty(),
            ).filter { it.isNotBlank() }.distinct()

            registeredUrls.forEach { u ->
                RemoteHttpStreamRegistry.register(
                    url = u,
                    headers = request.headers,
                    userAgent = request.userAgent,
                    owner = "webdav-persisted",
                    headerProvider = headerProvider,
                    urlProvider = { path },
                )
            }
            path
        }.onFailure { error ->
            Log.w(TAG, "CACHE_FALLBACK_FAILED url=${sanitizedUrl.safeLogUrl()}: ${error.message}", error)
        }.getOrNull()
    }

    private fun String.safeLogUrl(): String =
        substringBefore('?').replace(Regex("//[^/@]+@"), "//***@")
}
