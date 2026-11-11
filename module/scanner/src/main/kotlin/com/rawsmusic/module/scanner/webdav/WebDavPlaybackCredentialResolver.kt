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

    fun toSafeUrl(url: String): String? = WebDavUrlTools.toSafeHttpUrl(url)?.toString()

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
