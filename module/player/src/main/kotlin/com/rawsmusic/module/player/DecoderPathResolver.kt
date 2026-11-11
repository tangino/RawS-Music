package com.rawsmusic.module.player

import android.content.Context
import android.os.ParcelFileDescriptor
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.scanner.webdav.WebDavPlaybackCredentialResolver
import java.io.File

/**
 * Owns SAF ParcelFileDescriptors used to feed content:// URIs into FFmpeg.
 *
 * A single playback session may have more than one decoder open at the same time
 * (main decoder + gapless/crossfade next decoder). Keep every PFD alive until
 * the session is retired instead of closing the previous one when resolving the
 * next content:// URI.
 */
internal class DecoderPathResolver(
    private val context: Context,
    private val tag: String
) {
    private val activePfds = ArrayList<ParcelFileDescriptor>()
    private val flacCompatibilityCache = FlacPlaybackCompatibilityCache(
        cacheDirectory = File(context.cacheDir, "flac_playback_compat"),
        tag = tag,
    )

    fun resolve(path: String): String {
        val sanitizedPath = WebDavPlaybackCredentialResolver.sanitizeUrlIfConfigured(path) ?: path
        val registeredTransport = RemoteHttpStreamRegistry.lookup(sanitizedPath)?.resolveUrl(sanitizedPath)
        val transportPath = registeredTransport ?: sanitizedPath
        com.rawsmusic.core.common.sacd.SacdIsoSourceRegistry.area(sanitizedPath)?.let { area ->
            if (transportPath.startsWith("content://")) {
                // FFmpegBridge scopes the original FD to native open; the SACD reader owns
                // its reopened file. Repeated ISO/CUE switching must not retain more PFDs.
                return com.rawsmusic.core.common.sacd.SacdIsoBridge.nativeAreaPath(area, transportPath)
            }
        }
        val resolvedPath = if (transportPath.startsWith("content://")) {
            resolveContentUri(transportPath)
        } else if (transportPath.startsWith("http://", true) || transportPath.startsWith("https://", true)) {
            val existingCached = WebDavPlaybackCredentialResolver.findCachedPath(context, sanitizedPath)
            if (!existingCached.isNullOrBlank()) {
                existingCached
            } else {
                WebDavPlaybackCredentialResolver.toSafeUrl(transportPath) ?: transportPath
            }
        } else {
            transportPath
        }
        return flacCompatibilityCache.resolve(
            sourcePath = resolvedPath,
            sourceIdentity = sanitizedPath,
        )
    }

    /**
     * Slow safety fallback for WebDAV origins that FFmpeg cannot consume directly.
     * WebDAV cache safety and deduplication is handled by WebDavPlaybackCache's fine-grained key lock.
     * Intentionally not synchronized on DecoderPathResolver to avoid blocking close() or resolve() during playback switching.
     */
    fun resolveWebDavCacheFallback(path: String): String? {
        val sanitizedPath = WebDavPlaybackCredentialResolver.sanitizeUrlIfConfigured(path) ?: return null
        return WebDavPlaybackCredentialResolver.cachePath(context, sanitizedPath)
    }

    private fun resolveContentUri(path: String): String {
        return try {
            val uri = android.net.Uri.parse(path)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return path
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val count = synchronized(activePfds) {
                activePfds.add(pfd)
                activePfds.size
            }
            AppLogger.d(tag, "SAF: resolved content:// to fd path: $fdPath activePfds=$count")
            fdPath
        } catch (e: Exception) {
            AppLogger.w(tag, "SAF: failed to resolve content:// URI: $path", e)
            path
        }
    }

    fun close(reason: String) {
        val pfds = synchronized(activePfds) {
            if (activePfds.isEmpty()) return
            val list = activePfds.toList()
            activePfds.clear()
            list
        }
        for (pfd in pfds) {
            runCatching { pfd.close() }
                .onFailure { AppLogger.w(tag, "SAF: close PFD failed reason=$reason", it) }
        }
        AppLogger.d(tag, "SAF: closed ${pfds.size} PFD(s), reason=$reason")
    }
}
