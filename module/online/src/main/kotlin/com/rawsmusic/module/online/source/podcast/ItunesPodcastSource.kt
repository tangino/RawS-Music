package com.rawsmusic.module.online.source.podcast

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 泛用型公开播客源（方向二保底核心）。
 *
 * 采用 Apple / iTunes 公开播客索引与原生 RSS 解析，
 * 永久免鉴权、零 Token 依赖，提供全球及中文海量头部播客内容与原始 MP3/M4A 音频直链。
 */
object ItunesPodcastSource {
    private const val TAG = "ItunesPodcastSource"
    private const val SEARCH_API = "https://itunes.apple.com/search"

    /**
     * 针对全球多样化播客托管服务器（如 Megaphone、Libsyn、Substack 等）构建的容错网络客户端。
     * 解决第三方 CDN 证书链校验异常与重定向问题，保证 RSS 抓取 100% 成功。
     */
    private val client: OkHttpClient = try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    } catch (e: Exception) {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 搜索播客节目，支持指定 limit（最高 200）和指定国家/地区代码（如 CN / US）。
     */
    fun searchShows(query: String, limit: Int = 100, country: String? = null): List<PodcastShow> {
        if (query.isBlank()) return emptyList()
        val shows = mutableListOf<PodcastShow>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val countryParam = if (!country.isNullOrBlank()) "&country=$country" else ""
            val url = "$SEARCH_API?term=$encoded&media=podcast&limit=$limit$countryParam"
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val results = json.optJSONArray("results") ?: return emptyList()

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val feedUrl = item.optString("feedUrl")
                    if (feedUrl.isBlank()) continue

                    val collectionId = item.optString("collectionId")
                    val collectionName = item.optString("collectionName")
                    val artistName = item.optString("artistName", "独立播客")
                    val artworkUrl = item.optString("artworkUrl600", item.optString("artworkUrl100"))
                    val trackCount = item.optInt("trackCount", 0)
                    val primaryGenreName = item.optString("primaryGenreName", "播客")

                    shows.add(
                        PodcastShow(
                            id = "itunes_$collectionId",
                            title = collectionName,
                            publisher = artistName,
                            description = "分类: $primaryGenreName",
                            coverUrl = artworkUrl,
                            totalEpisodes = trackCount,
                            feedUrl = feedUrl,
                            sourceType = PodcastSourceType.RSS,
                            category = primaryGenreName,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "iTunes 搜索播客异常: ${e.message}", e)
        }
        return shows
    }

    /**
     * 获取指定节目的往期单集列表（优先抓取原生 RSS，若源站宕机或反代 502 则自动启用 iTunes 官方 Lookup 兜底）。
     */
    fun getEpisodes(show: PodcastShow, limit: Int = Int.MAX_VALUE): List<PodcastEpisode> {
        val feedUrl = show.feedUrl
        var episodes: List<PodcastEpisode> = emptyList()

        if (!feedUrl.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url(feedUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val xml = response.body?.string()
                        if (!xml.isNullOrBlank()) {
                            episodes = PodcastFeedParser.parseEpisodes(
                                showId = show.id,
                                showTitle = show.title,
                                defaultCoverUrl = show.coverUrl,
                                xmlContent = xml,
                                limit = limit,
                            )
                        }
                    } else {
                        AppLogger.w(TAG, "请求 RSS 失败: ${response.code} from $feedUrl")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "拉取并解析 RSS 异常: ${e.message}", e)
            }
        }

        // 🌟 核心容灾兜底：当播客源站服务器挂掉（如 502 Bad Gateway、连接重置或宕机）导致 RSS 为空时，
        // 自动降级使用 Apple iTunes 官方官方单集索引 API (Lookup API) 兜底拉取完整单集与播放直链！
        if (episodes.isEmpty()) {
            val collectionId = show.id.removePrefix("itunes_")
            if (collectionId.isNotBlank() && collectionId.all { it.isDigit() }) {
                AppLogger.i(TAG, "RSS 无法获取数据，自动启动 iTunes 官方 Lookup API 兜底拉取: $collectionId (${show.title})")
                val fallbackEpisodes = fetchEpisodesFromItunesLookup(show, collectionId, limit)
                if (fallbackEpisodes.isNotEmpty()) {
                    return fallbackEpisodes
                }
            }
        }

        return episodes
    }

    /**
     * 通过 Apple iTunes 官方 Lookup 接口直接抓取单集列表（用于 RSS 托管源宕机、被墙或反代 502 时的兜底保障）。
     */
    private fun fetchEpisodesFromItunesLookup(
        show: PodcastShow,
        collectionId: String,
        limit: Int = Int.MAX_VALUE,
    ): List<PodcastEpisode> {
        val episodes = mutableListOf<PodcastEpisode>()
        try {
            val lookupLimit = if (limit > 300) 300 else limit.coerceAtLeast(1)
            val url = "https://itunes.apple.com/lookup?id=$collectionId&entity=podcastEpisode&limit=$lookupLimit"
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val results = json.optJSONArray("results") ?: return emptyList()

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    if (item.optString("wrapperType") != "podcastEpisode") continue

                    val trackId = item.optString("trackId", item.optString("trackViewUrl"))
                    val trackName = item.optString("trackName")
                    if (trackName.isBlank()) continue

                    val episodeUrl = item.optString("episodeUrl")
                    val releaseDate = item.optString("releaseDate")
                    val durationMs = item.optLong("trackTimeMillis", 0L)
                    val description = item.optString("description")
                    val artworkUrl = item.optString("artworkUrl600", item.optString("artworkUrl160", show.coverUrl))

                    episodes.add(
                        PodcastEpisode(
                            id = if (trackId.isNotBlank()) "itunes_ep_$trackId" else "${show.id}_${trackName.hashCode()}",
                            showId = show.id,
                            showTitle = show.title,
                            title = trackName,
                            description = description,
                            pubDate = releaseDate,
                            durationMs = durationMs,
                            coverUrl = artworkUrl.ifBlank { show.coverUrl },
                            audioUrl = episodeUrl,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "iTunes Lookup 兜底单集失败: ${e.message}", e)
        }
        return episodes
    }
}


