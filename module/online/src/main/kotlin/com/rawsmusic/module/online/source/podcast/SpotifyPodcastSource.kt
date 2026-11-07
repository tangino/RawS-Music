package com.rawsmusic.module.online.source.podcast

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.auth.spotify.SpotifyAccountStore
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Spotify 官方播客接口层（方向一核心）。
 *
 * 优先采用用户登录授权生成的个人 Access Token（解除 403 限制并支持个人关注同步），
 * 兜底采用无感拉取的官方凭证查询。
 */
object SpotifyPodcastSource {
    private const val TAG = "SpotifyPodcastSource"
    private const val BASE_URL = "https://api.spotify.com/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var forbiddenCooldownUntilMs: Long = 0L

    /**
     * 判断 Spotify API 是否处于可用状态（登录用户不设熔断，未登录受匿名 403 熔断保护）。
     */
    fun isAvailable(): Boolean {
        if (SpotifyAccountStore.isLoggedIn()) return true
        return System.currentTimeMillis() > forbiddenCooldownUntilMs
    }

    /**
     * 获取当前可用的 Token（优先使用登录账号的授权 Token）。
     */
    fun getActiveToken(): String? {
        val userToken = SpotifyAccountStore.userProfile.value?.accessToken
        if (!userToken.isNullOrBlank()) {
            return userToken
        }
        return SpotifyAnonymousTokenProvider.getAccessToken()
    }

    /**
     * 在 Spotify 搜索播客节目。
     */
    fun searchShows(query: String, limit: Int = 20): List<PodcastShow> {
        if (!isAvailable()) return emptyList()
        val token = getActiveToken() ?: return emptyList()
        val shows = mutableListOf<PodcastShow>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?type=show&q=$encoded&limit=$limit"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 403) {
                        forbiddenCooldownUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
                        AppLogger.w(TAG, "Spotify API 返回 403（需开发者账号具备 Premium 订阅），已熔断 10 分钟并秒级直达高速播客 CDN")
                    } else {
                        AppLogger.w(TAG, "Spotify 搜索失败，HTTP: ${response.code}")
                    }
                    return emptyList()
                }
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val showsObj = json.optJSONObject("shows") ?: return emptyList()
                val items = showsObj.optJSONArray("items") ?: return emptyList()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val publisher = item.optString("publisher")
                    val description = item.optString("description")
                    val totalEpisodes = item.optInt("total_episodes", 0)

                    val images = item.optJSONArray("images")
                    var coverUrl = ""
                    if (images != null && images.length() > 0) {
                        coverUrl = images.optJSONObject(0)?.optString("url") ?: ""
                    }

                    shows.add(
                        PodcastShow(
                            id = "spotify_$id",
                            title = name,
                            publisher = publisher,
                            description = description,
                            coverUrl = coverUrl,
                            totalEpisodes = totalEpisodes,
                            feedUrl = null,
                            sourceType = PodcastSourceType.SPOTIFY,
                            category = "Spotify 精选",
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Spotify 搜索播客异常: ${e.message}")
        }
        return shows
    }

    /**
     * 获取 Spotify 节目下的单集列表（支持自动分页拉取完整单集）。
     */
    fun getEpisodes(show: PodcastShow, maxEpisodes: Int = 1000): List<PodcastEpisode> {
        if (!isAvailable()) return emptyList()
        val rawId = show.id.removePrefix("spotify_")
        val token = getActiveToken() ?: return emptyList()
        val episodes = mutableListOf<PodcastEpisode>()

        try {
            var nextUrl: String? = "$BASE_URL/shows/$rawId/episodes?limit=50"
            var pageCount = 0

            while (!nextUrl.isNullOrBlank() && episodes.size < maxEpisodes && pageCount < 20) {
                pageCount++
                val request = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .build()

                val pageNextUrl = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 403) {
                            forbiddenCooldownUntilMs = System.currentTimeMillis() + 10 * 60 * 1000L
                            AppLogger.w(TAG, "Spotify 获取单集 403，触发 10 分钟熔断并直连高速播客 CDN")
                        }
                        return@use null
                    }
                    val bodyStr = response.body?.string() ?: return@use null
                    val json = JSONObject(bodyStr)
                    val items = json.optJSONArray("items")

                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val epId = item.optString("id")
                            val name = item.optString("name")
                            val description = item.optString("description")
                            val releaseDate = item.optString("release_date")
                            val durationMs = item.optLong("duration_ms", 0L)
                            val audioPreviewUrl = item.optString("audio_preview_url").ifBlank { null }

                            val images = item.optJSONArray("images")
                            var coverUrl = show.coverUrl
                            if (images != null && images.length() > 0) {
                                val img = images.optJSONObject(0)?.optString("url")
                                if (!img.isNullOrBlank()) coverUrl = img
                            }

                            episodes.add(
                                PodcastEpisode(
                                    id = "spotify_ep_$epId",
                                    showId = show.id,
                                    showTitle = show.title,
                                    title = name,
                                    description = description,
                                    pubDate = releaseDate,
                                    durationMs = durationMs,
                                    coverUrl = coverUrl,
                                    audioUrl = audioPreviewUrl,
                                )
                            )
                        }
                    }
                    json.optString("next").ifBlank { null }
                }

                nextUrl = pageNextUrl
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Spotify 获取播客单集异常: ${e.message}")
        }
        return episodes
    }
}

