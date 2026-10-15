package com.rawsmusic.core.ui.widget.bitmaps

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 远程封面网络下载调度器。
 *
 * 参考 OrbitPlayer 的底层网络调度策略：
 * 1. 采用共享连接池与 HTTP/2 多路复用，避免为每个图片单独进行 TLS 握手；
 * 2. 针对国内各大音乐 CDN（网易云、QQ 音乐、酷我、咪咕等）配置相应的防盗链 Referer 与 User-Agent；
 * 3. 内存级 In-Flight 请求合并（Deduplication），避免同一封面被多个组件并发重复下载；
 * 4. 独立于本地解码单线程的并发网络 I/O 线程池，支持后台极速预下载；
 * 5. 失败自动安全重试与原子落盘，防止损坏缓存文件。
 */
object RemoteArtworkDownloader {
    private const val TAG = "RemoteArtworkDownloader"
    private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L // 32MB

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        })
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val prefetchExecutor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "RemoteArtworkPrefetch").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    private val inFlightDownloads = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    /**
     * 判断是否是网络远程图片 URL。
     */
    fun isRemoteUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("//")
    }

    /**
     * 规范化远程 URL。
     */
    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            else -> trimmed
        }
    }

    /**
     * 触发异步预下载。
     * 确保在主解码线程轮到该图片时，文件已经落盘到磁盘缓存中，瞬间完成解码。
     */
    fun prefetch(url: String, targetFile: File) {
        if (!isRemoteUrl(url) || (targetFile.isFile && targetFile.length() > 0L)) {
            return
        }
        prefetchExecutor.execute {
            downloadToFile(url, targetFile)
        }
    }

    /**
     * 同步或等待合并下载到目标文件。
     *
     * @return true 表示目标文件已就绪且内容有效；false 表示下载失败。
     */
    fun downloadToFile(url: String, targetFile: File): Boolean {
        if (targetFile.isFile && targetFile.length() > 0L) {
            return true
        }
        val normalized = normalizeUrl(url)
        if (!isRemoteUrl(normalized)) return false

        val future = inFlightDownloads.compute(normalized) { _, existingFuture ->
            if (existingFuture != null && !existingFuture.isDone) {
                existingFuture
            } else {
                CompletableFuture<Boolean>().also { newFuture ->
                    executeDownloadTask(normalized, targetFile, newFuture)
                }
            }
        } ?: return false

        return try {
            future.get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "downloadToFile timed out or failed for $normalized: ${e.message}")
            false
        } finally {
            inFlightDownloads.remove(normalized, future)
        }
    }

    private fun executeDownloadTask(
        url: String,
        targetFile: File,
        future: CompletableFuture<Boolean>
    ) {
        // 如果文件在竞争中已经被其他线程完成
        if (targetFile.isFile && targetFile.length() > 0L) {
            future.complete(true)
            return
        }

        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        // 尝试最多 2 次下载（含 1 次网络瞬态失败自动重试）
        var success = false
        var lastException: Throwable? = null

        for (attempt in 1..2) {
            val tempFile = File(parentDir ?: targetFile, "${targetFile.name}.tmp-${UUID.randomUUID()}")
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/*,*/*;q=0.8")

                // 根据音源 CDN 智能适配 Referer
                when {
                    url.contains("qq.com") || url.contains("gtimg.cn") -> {
                        requestBuilder.header("Referer", "https://y.qq.com/")
                    }
                    url.contains("163.com") || url.contains("126.net") -> {
                        requestBuilder.header("Referer", "https://music.163.com/")
                    }
                    url.contains("kugou.com") -> {
                        requestBuilder.header("Referer", "http://m.kugou.com/")
                    }
                    url.contains("kuwo.cn") -> {
                        requestBuilder.header("Referer", "http://www.kuwo.cn/")
                    }
                    url.contains("migu.cn") -> {
                        requestBuilder.header("Referer", "https://m.music.migu.cn/")
                    }
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val declaredLength = body.contentLength()
                    if (declaredLength > MAX_IMAGE_BYTES) {
                        throw IOException("Image too large: $declaredLength bytes")
                    }

                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        tempFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                totalBytes += read
                                if (totalBytes > MAX_IMAGE_BYTES) {
                                    throw IOException("Stream exceeded max bytes limit")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    if (totalBytes <= 0L) {
                        throw IOException("Downloaded 0 bytes")
                    }

                    // 原子落盘
                    if (targetFile.exists()) {
                        tempFile.delete()
                        success = true
                    } else if (tempFile.renameTo(targetFile) || (targetFile.isFile && targetFile.length() > 0L)) {
                        success = true
                    } else {
                        throw IOException("Failed to rename temp file to target file")
                    }
                }

                if (success) break
            } catch (t: Throwable) {
                lastException = t
                tempFile.delete()
                if (attempt < 2) {
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        if (success) {
            future.complete(true)
        } else {
            Log.e(TAG, "Download failed for $url: ${lastException?.message}")
            future.complete(false)
        }
    }
}
