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
            RemoteHttpStreamRegistry.register(
                url = sanitizedUrl,
                headers = req.headers,
                userAgent = req.userAgent,
                owner = "webdav-direct",
                headerProvider = headerProvider,
                urlProvider = { sanitizedUrl },
            )
            if (req.url != sanitizedUrl) {
                RemoteHttpStreamRegistry.register(
                    url = req.url,
                    headers = req.headers,
                    userAgent = req.userAgent,
                    owner = "webdav-direct",
                    headerProvider = headerProvider,
                    urlProvider = { req.url },
                )
            }
            if (url != sanitizedUrl) {
                RemoteHttpStreamRegistry.register(
                    url = url,
                    headers = req.headers,
                    userAgent = req.userAgent,
                    owner = "webdav-direct",
                    headerProvider = headerProvider,
                    urlProvider = { sanitizedUrl },
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
            RemoteHttpStreamRegistry.register(
                url = sanitizedUrl,
                headers = request.headers,
                userAgent = request.userAgent,
                owner = "webdav-persisted",
                headerProvider = headerProvider,
                urlProvider = { path },
            )
            if (request.url != sanitizedUrl) {
                RemoteHttpStreamRegistry.register(
                    url = request.url,
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
