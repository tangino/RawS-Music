package com.rawsmusic.module.data.source.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 基于 Media3 SimpleCache + OkHttpDataSource 的在线音频边播边缓存存储层。
 *
 * 特点：
 * 1. 收到首包即可起播，同时按分块写入本地 LRU 磁盘缓存（默认上限 1GB），支持任意进度 Range Seek；
 * 2. 使用基于歌曲身份与音质的稳定 cacheKey，即使上游 CDN 临时直链过期或换域名，二次播放仍可直接命中本地缓存；
 * 3. 支持按 cacheKey 精准驱逐（例如酷我短音频校验不通过时立即清掉坏分块）。
 */
@OptIn(UnstableApi::class)
object OnlineMedia3Cache {
    private const val TAG = "OnlineMedia3Cache"
    private const val CACHE_DIR_NAME = "online_media3_cache_v1"
    private const val MAX_CACHE_BYTES = 1_024L * 1_024L * 1_024L // 1 GB
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val lock = Any()

    @Volatile
    private var simpleCache: SimpleCache? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun getCache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        synchronized(lock) {
            simpleCache?.let { return it }
            val appCtx = context.applicationContext
            val dir = File(appCtx.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
            val dbProvider = StandaloneDatabaseProvider(appCtx)
            return SimpleCache(dir, evictor, dbProvider).also {
                simpleCache = it
                AppLogger.i(TAG, "SimpleCache initialized at ${dir.absolutePath}, maxBytes=$MAX_CACHE_BYTES")
            }
        }
    }

    /**
     * 生成与上游临时直链解耦的稳定缓存键。
     */
    fun cacheKey(item: RawSourceMediaItem, quality: RawSourceQuality): String =
        sha256("${item.stableIdentity}|${quality.name}")

    /**
     * 创建支持自定义请求头（Referer / User-Agent / Cookie 等）的边播边缓存 DataSource.Factory。
     */
    fun createDataSourceFactory(
        context: Context,
        headers: Map<String, String> = emptyMap(),
        userAgent: String? = null,
    ): DataSource.Factory {
        val mergedHeaders = LinkedHashMap<String, String>()
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                mergedHeaders[k] = v
            }
        }
        val effectiveUa = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(effectiveUa)
            .apply {
                if (mergedHeaders.isNotEmpty()) {
                    setDefaultRequestProperties(mergedHeaders)
                }
            }
        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 精准驱逐某首歌在某个音质（或全部音质）下的分块缓存，用于拦截坏流/短试听后立即清理。
     */
    fun evict(context: Context, item: RawSourceMediaItem, quality: RawSourceQuality? = null) {
        val cache = runCatching { getCache(context) }.getOrNull() ?: return
        val targets = if (quality != null) listOf(quality) else RawSourceQuality.entries
        targets.forEach { q ->
            val key = cacheKey(item, q)
            runCatching { cache.removeResource(key) }
                .onFailure { AppLogger.w(TAG, "evict cache key=$key failed: ${it.message}") }
        }
    }

    /**
     * 清理全部 Media3 在线边播缓存。
     */
    fun clearAll(context: Context) {
        val cache = runCatching { getCache(context) }.getOrNull() ?: return
        runCatching {
            val keys = cache.keys.toList()
            keys.forEach { key -> runCatching { cache.removeResource(key) } }
            AppLogger.i(TAG, "Cleared ${keys.size} Media3 cache entries")
        }.onFailure {
            AppLogger.w(TAG, "clearAll failed: ${it.message}")
        }
    }

    fun cacheSizeBytes(context: Context): Long =
        runCatching { getCache(context).cacheSpace }.getOrDefault(0L)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
