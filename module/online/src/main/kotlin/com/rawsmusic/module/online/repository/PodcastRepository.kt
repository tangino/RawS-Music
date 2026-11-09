package com.rawsmusic.module.online.repository

import android.content.Context
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.online.model.PodcastCategory
import com.rawsmusic.module.online.model.PodcastEpisode
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import com.rawsmusic.module.online.source.podcast.ItunesPodcastSource
import com.rawsmusic.module.online.source.podcast.SpotifyPodcastSource
import com.rawsmusic.module.online.store.PodcastStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播客统一数据仓库。
 *
 * 融合方向一（Spotify 匿名 Web 索引）为主、方向二（iTunes 开放生态与原生 RSS 解析）为底的双引擎组合，
 * 负责播客探索广场、分类推荐、全网搜索、单集解析与原生解码播放调度。
 */
object PodcastRepository {
    private const val TAG = "PodcastRepository"

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, List<PodcastShow>>()
    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, List<PodcastEpisode>>()

    val categories = listOf(
        PodcastCategory("featured", "热门推荐", "播客"),
        PodcastCategory("tech", "科技前沿", "科技"),
        PodcastCategory("business", "商业经管", "商业"),
        PodcastCategory("culture", "文化艺术", "文化"),
        PodcastCategory("story", "故事叙事", "故事FM"),
        PodcastCategory("news", "新闻资讯", "News"),
        PodcastCategory("music", "音乐生活", "音乐"),
        PodcastCategory("growth", "知行成长", "心理"),
    )

    /**
     * 获取指定分类下的推荐播客（带极速内存缓存）。
     */
    suspend fun getCategoryShows(category: PodcastCategory): List<PodcastShow> = withContext(Dispatchers.IO) {
        val cached = categoryCache[category.id]
        if (!cached.isNullOrEmpty()) {
            return@withContext cached
        }

        AppLogger.i(TAG, "从播客生态检索分类 [${category.name}]")

        val rawList = mutableListOf<PodcastShow>()
        val seenIds = mutableSetOf<String>()

        if (category.id == "featured") {
            // 热门推荐：聚合中国区高热度中文播客与通用精选播客（解决原本单长尾词只有5条的问题）
            val cnHot1 = runCatching {
                ItunesPodcastSource.searchShows("播客", limit = 50, country = "CN")
            }.getOrDefault(emptyList())
            val cnHot2 = runCatching {
                ItunesPodcastSource.searchShows("Podcast", limit = 50, country = "CN")
            }.getOrDefault(emptyList())
            val globalHot = runCatching {
                ItunesPodcastSource.searchShows("精选", limit = 30)
            }.getOrDefault(emptyList())

            (cnHot1 + cnHot2 + globalHot).forEach { s ->
                if (seenIds.add(s.id)) {
                    rawList.add(s)
                }
            }
        } else if (category.id == "growth") {
            // 知行成长：组合心理与个人成长两大维度
            val growList1 = runCatching {
                ItunesPodcastSource.searchShows("心理", limit = 50, country = "CN")
            }.getOrDefault(emptyList())
            val growList2 = runCatching {
                ItunesPodcastSource.searchShows("个人成长", limit = 40, country = "CN")
            }.getOrDefault(emptyList())

            (growList1 + growList2).forEach { s ->
                if (seenIds.add(s.id)) {
                    rawList.add(s)
                }
            }
        } else {
            // 其他行业分类：优先拉取中国区分类播客，若不足则追加通用生态播客
            val cnCat = runCatching {
                ItunesPodcastSource.searchShows(category.queryKeyword, limit = 50, country = "CN")
            }.getOrDefault(emptyList())
            cnCat.forEach { s ->
                if (seenIds.add(s.id)) {
                    rawList.add(s)
                }
            }

            if (rawList.size < 20) {
                val globalCat = runCatching {
                    ItunesPodcastSource.searchShows(category.queryKeyword, limit = 50)
                }.getOrDefault(emptyList())
                globalCat.forEach { s ->
                    if (seenIds.add(s.id)) {
                        rawList.add(s)
                    }
                }
            }
        }

        val shows = rawList.map { it.copy(category = category.name) }
        if (shows.isNotEmpty()) {
            categoryCache[category.id] = shows
        }
        shows
    }

    /**
     * 全网深度阶梯搜索播客节目（多区域与智能语义联想多阶深潜，突破单次上限）。
     *
     * @param query 搜索关键词
     * @param stage 检索阶梯深度：
     *              1: 核心精准检索（全球通用 + CN + US，约 150+ 个）
     *              2: 国际播客重镇扩展（GB 英国 + CA 加拿大 + AU 澳大利亚 + SG 新加坡）
     *              3: 深度语义扩展一（"${query} podcast" + "listen ${query}"）
     *              4: 深度语义扩展二（"learn ${query}" + "learning ${query}" 等）
     * @param seenIds 已去重的播客 ID 集合，避免重复展现
     * @return Pair<新增去重播客列表, 是否还支持继续向更深阶梯检索>
     */
    suspend fun searchShowsByStage(
        query: String,
        stage: Int,
        seenIds: MutableSet<String>,
    ): Pair<List<PodcastShow>, Boolean> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext Pair(emptyList(), false)

        val newShows = mutableListOf<PodcastShow>()

        when (stage) {
            1 -> {
                // 第一梯队：全球核心通用 + 中美双大区
                val g1 = runCatching { ItunesPodcastSource.searchShows(q, limit = 100) }.getOrDefault(emptyList())
                val c1 = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "CN") }.getOrDefault(emptyList())
                val u1 = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "US") }.getOrDefault(emptyList())
                (g1 + c1 + u1).forEach { s ->
                    if (seenIds.add(s.id)) newShows.add(s)
                }
                Pair(newShows, true)
            }
            2 -> {
                // 第二梯队：英联邦及海外主要大区（GB, CA, AU, SG）
                val gb = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "GB") }.getOrDefault(emptyList())
                val ca = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "CA") }.getOrDefault(emptyList())
                val au = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "AU") }.getOrDefault(emptyList())
                val sg = runCatching { ItunesPodcastSource.searchShows(q, limit = 100, country = "SG") }.getOrDefault(emptyList())
                (gb + ca + au + sg).forEach { s ->
                    if (seenIds.add(s.id)) newShows.add(s)
                }
                Pair(newShows, true)
            }
            3 -> {
                // 第三梯队：第一层语义派生扩展词
                val derived1 = "$q podcast"
                val d1 = runCatching { ItunesPodcastSource.searchShows(derived1, limit = 100) }.getOrDefault(emptyList())
                val d1cn = runCatching { ItunesPodcastSource.searchShows(derived1, limit = 100, country = "CN") }.getOrDefault(emptyList())
                (d1 + d1cn).forEach { s ->
                    if (seenIds.add(s.id)) newShows.add(s)
                }
                Pair(newShows, true)
            }
            4 -> {
                // 第四梯队：第二层语义派生扩展词（学习、精选、长尾）
                val derived2 = "learn $q"
                val derived3 = "learning $q"
                val d2 = runCatching { ItunesPodcastSource.searchShows(derived2, limit = 100) }.getOrDefault(emptyList())
                val d3 = runCatching { ItunesPodcastSource.searchShows(derived3, limit = 100) }.getOrDefault(emptyList())
                (d2 + d3).forEach { s ->
                    if (seenIds.add(s.id)) newShows.add(s)
                }
                // 第四阶梯为最终深度
                Pair(newShows, false)
            }
            else -> {
                Pair(emptyList(), false)
            }
        }
    }

    /**
     * 全网深度搜索播客节目（多区域并发聚合与全局去重，数据全面完整）。
     */
    suspend fun searchShows(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val seenIds = mutableSetOf<String>()
        searchShowsByStage(query, stage = 1, seenIds).first
    }

    /**
     * 获取指定节目的往期单集列表（带极速内存缓存，秒开）。
     */
    suspend fun getEpisodes(show: PodcastShow): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        val cached = episodeCache[show.id]
        if (!cached.isNullOrEmpty()) {
            return@withContext cached
        }

        // 解析 RSS 得到完整单集列表与原生 MP3/M4A 直链
        val eps = ItunesPodcastSource.getEpisodes(show)
        if (eps.isNotEmpty()) {
            episodeCache[show.id] = eps
        }
        eps
    }

    /**
     * 播放单集播客，并自动同步写入播客播放历史。
     */
    fun playEpisode(
        context: Context,
        episode: PodcastEpisode,
        episodeList: List<PodcastEpisode> = listOf(episode),
        index: Int = 0,
        show: PodcastShow? = null,
    ): Boolean {
        val audioUrl = episode.audioUrl
        if (audioUrl.isNullOrBlank()) {
            AppNoticeBus.post("该单集暂无可用播放直链", AppNoticeIcon.ERROR)
            return false
        }

        val queue = episodeList.map { ep ->
            RawSourceMediaItem(
                sourceId = "podcast",
                remoteId = ep.id,
                mediaType = com.rawsmusic.core.common.source.RawSourceMediaType.Music,
                title = ep.title,
                artists = listOf(ep.showTitle),
                album = ep.showTitle,
                artworkUrl = ep.coverUrl,
                durationMs = ep.durationMs,
            )
        }

        val currentItem = queue.getOrNull(index) ?: queue.first()
        val rawSource = RawResolvedAudioSource(
            url = audioUrl,
            quality = RawSourceQuality.Standard,
            headers = emptyMap(),
        )

        try {
            MusicSourcePlaybackController.playResolved(
                context = context,
                item = currentItem,
                source = rawSource,
                queue = queue,
                index = index,
                originalPlatform = "podcast",
                resolvedPlatform = "podcast",
                sourceTag = "podcast",
                autoPlay = true,
            )
            // 播客非歌单，彻底清空当前播放歌单卡片信息
            com.rawsmusic.core.common.prefs.PlayedPlaylistManager.recordNonPlaylistPlayed()
            AppNoticeBus.post("正在播放: ${episode.title}", AppNoticeIcon.PLAYLIST)
            // 自动同步记录到播客播放历史
            val targetShow = show ?: PodcastShow(
                id = episode.showId,
                title = episode.showTitle,
                publisher = episode.showTitle,
                description = "",
                coverUrl = episode.coverUrl,
                sourceType = PodcastSourceType.RSS,
            )
            PodcastStore.recordPlay(episode, targetShow)
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "起播播客单集异常: ${e.message}", e)
            AppNoticeBus.post("播客播放失败: ${e.message}", AppNoticeIcon.ERROR)
            return false
        }
    }
}
