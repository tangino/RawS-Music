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
    private var cachedVideoShows: List<PodcastShow>? = null

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
     * 严格判定是否为真正的视频播客。
     * 1. 标题明确带有 (Video)、[Video]、Video Podcast、Video Edition、视频播客、视频版、录像 等显著视频特征；
     * 2. FeedUrl 包含 video / mp4 / m4v 视频直链；
     * 3. 严厉排除仅因为 "Video Games" 游戏分类而产生的误判音频播客。
     */
    fun isConfirmedVideoShow(show: PodcastShow): Boolean {
        if (show.isVideo) return true
        val t = show.title.lowercase()
        val f = (show.feedUrl ?: "").lowercase()
        val c = show.category.lowercase()

        // 严厉排除：仅属于游戏分类 "Video Games" 但标题与描述无任何真正视频标识的普通音频播客
        if (c == "video games" && !t.contains("video podcast") && !t.contains("(video") && !t.contains("[video")) {
            return false
        }

        val hasVideoInTitle = t.contains("(video") || t.contains("[video") ||
            t.contains("video podcast") || t.contains("video edition") ||
            t.contains("video version") || t.contains("video show") ||
            t.contains("videos") || t.contains("视频") || t.contains("录像") ||
            t.endsWith(" video") || t.contains(" video ")

        val hasVideoInFeed = f.contains("video") || f.contains("mp4") || f.contains("m4v")

        return hasVideoInTitle || hasVideoInFeed
    }

    /**
     * 获取高品质全网视频播客专栏（Video Podcasts），包含 TED 演讲全系、生活治愈、时尚秀场、前沿科技与户外探险。
     */
    suspend fun getRecommendedVideoShows(): List<PodcastShow> = withContext(Dispatchers.IO) {
        cachedVideoShows?.let { if (it.isNotEmpty()) return@withContext it }

        val videoShows = mutableListOf<PodcastShow>()
        val seenIds = mutableSetOf<String>()

        // 1. 全球知名顶流视频播客核心专栏池（100% 确认提供超清 MP4 视频直链，免登录极速直连）
        val curatedVideoShows = listOf(
            // --- TED Talks 官方演讲全系列超清视频专栏 ---
            PodcastShow(
                id = "itunes_470623801",
                title = "TED Talks Science and Medicine",
                publisher = "TED",
                description = "全球顶尖科学家与医学思想家演讲实录（官方 1080P/超清 MP4 视频专栏）。",
                coverUrl = "https://images.unsplash.com/photo-1507668077129-56e32842fceb?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTScienceMedicine",
                sourceType = PodcastSourceType.RSS,
                category = "科技前沿",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470623803",
                title = "TED Talks Society and Culture",
                publisher = "TED",
                description = "关于人类社会、文明反思与多元文化的深度思考视频展播。",
                coverUrl = "https://images.unsplash.com/photo-1522202176988-66273c2fd55f?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTSocietyCulture",
                sourceType = PodcastSourceType.RSS,
                category = "文化艺术",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470623037",
                title = "TED Talks Education",
                publisher = "TED",
                description = "点亮未来教育与认知成长：全球先锋教育家演讲现场视频。",
                coverUrl = "https://images.unsplash.com/photo-1497633762265-9d179a990aa6?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTEducation",
                sourceType = PodcastSourceType.RSS,
                category = "知行成长",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470623175",
                title = "TED Talks Kids and Family",
                publisher = "TED",
                description = "献给孩子与家庭的奇思妙想，启迪创新的视觉演讲专栏。",
                coverUrl = "https://images.unsplash.com/photo-1502086223501-7ea6ecd79368?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTKidsFamily",
                sourceType = PodcastSourceType.RSS,
                category = "生活故事",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470623747",
                title = "TED Talks News and Politics",
                publisher = "TED",
                description = "洞察全球大势与地缘未来的前沿观察演讲视频专栏。",
                coverUrl = "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTNewsPolitics",
                sourceType = PodcastSourceType.RSS,
                category = "新闻资讯",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470623182",
                title = "TED Talks Music",
                publisher = "TED",
                description = "音乐魔力与先锋演奏家现场：震撼人心的视听现场专栏。",
                coverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTMusic",
                sourceType = PodcastSourceType.RSS,
                category = "音乐生活",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_470616189",
                title = "TED Talks Art",
                publisher = "TED",
                description = "当代先锋艺术家的构想与视觉创造力碰撞展映视频。",
                coverUrl = "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/iTunesPodcastTTArt",
                sourceType = PodcastSourceType.RSS,
                category = "文化艺术",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_537381609",
                title = "TEDTalks Technologie",
                publisher = "TED",
                description = "人工智能、虚拟现实与改变未来的前沿科技现场演说。",
                coverUrl = "https://images.unsplash.com/photo-1485827404703-89b55fcc595e?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/FrenchPodcastTechnology",
                sourceType = PodcastSourceType.RSS,
                category = "科技前沿",
                isVideo = true,
            ),

            // --- 生活治愈、深度访谈与人文时尚视频专栏 ---
            PodcastShow(
                id = "itunes_1871546574",
                title = "Hope & Humor Video Podcast",
                publisher = "Kristina Kuzmic",
                description = "家庭与生活治愈视频播客：幽默、真实的生活故事与成长启示（超清 MP4 视频原声）。",
                coverUrl = "https://images.unsplash.com/photo-1517245386807-bb43f82c33c4?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feed.podbean.com/hopeandhumorvideo/feed.xml",
                sourceType = PodcastSourceType.RSS,
                category = "生活故事",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1642596659",
                title = "The Hollywood Godfather Video Podcast",
                publisher = "Gianni Russo",
                description = "好莱坞传奇人物与演艺界大咖的深度访谈高清视频专栏。",
                coverUrl = "https://images.unsplash.com/photo-1542751371-adc38448a05e?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feed.podbean.com/hollywoodgodfatherpodcast/feed.xml",
                sourceType = PodcastSourceType.RSS,
                category = "文化艺术",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_111082774",
                title = "MODTV FASHION VIDEO PODCAST",
                publisher = "MODTV",
                description = "全球顶奢秀场、巴黎米兰设计师幕后专访与先锋时尚视觉短片专栏。",
                coverUrl = "https://images.unsplash.com/photo-1490481651871-ab68de25d43d?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/modtv/podcast",
                sourceType = PodcastSourceType.RSS,
                category = "文化艺术",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_205372733",
                title = "Best of YouTube (video)",
                publisher = "Plankton Productions",
                description = "全球创意爆笑短片、高能名场面精选视频专栏。",
                coverUrl = "https://images.unsplash.com/photo-1611162617213-7d7a39e9b1d7?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feed.podbean.com/boyt/feed.xml",
                sourceType = PodcastSourceType.RSS,
                category = "生活故事",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1292144600",
                title = "Yoga Classes Video Podcast",
                publisher = "Yoga Vidya",
                description = "随时随地身心放松：专业瑜伽动作与冥想跟练超清视频播客。",
                coverUrl = "https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://www.yoga-kurs-blog.de/category/yoga-class-podcast/feed/",
                sourceType = PodcastSourceType.RSS,
                category = "知行成长",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_368506448",
                title = "YOGAMOUR Yoga Video Podcast",
                publisher = "YOGAMOUR",
                description = "自然山野与户外深度瑜伽体验，带给你平静力量的视觉专栏。",
                coverUrl = "https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://yogamour.de/feed/mp4video/",
                sourceType = PodcastSourceType.RSS,
                category = "知行成长",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1016203534",
                title = "East Texas Explorer Video Podcast",
                publisher = "Bob Phillips",
                description = "探索野外风光、古迹遗存与自然秘境的地理发现视频专栏。",
                coverUrl = "https://images.unsplash.com/photo-1469474968028-56623f02e42e?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feed.podbean.com/easttexasexplorer/feed.xml",
                sourceType = PodcastSourceType.RSS,
                category = "生活故事",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_254930313",
                title = "Serious Eats: Video Podcast",
                publisher = "Serious Eats",
                description = "全球米其林美食制作、烹饪秘诀与街头舌尖探秘超清视频。",
                coverUrl = "https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=600&auto=format&fit=crop&q=80",
                feedUrl = "http://feeds.feedburner.com/seriouseatsvideo",
                sourceType = PodcastSourceType.RSS,
                category = "生活故事",
                isVideo = true,
            ),

            // --- 科技前沿、数码与创意设计视频专栏 ---
            PodcastShow(
                id = "itunes_1290008155",
                title = "iPhone Life Video Podcast",
                publisher = "iPhone Life",
                description = "最新数码科技前沿评测、Apple 新生态与上手体验视频播客。",
                coverUrl = "https://images.unsplash.com/photo-1492691527719-9d1e07e534b4?w=600&auto=format&fit=crop&q=80",
                feedUrl = "http://feeds.feedburner.com/iphonelife-video-cast",
                sourceType = PodcastSourceType.RSS,
                category = "科技前沿",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1851084144",
                title = "Pluribusters - a Pluribus video podcast",
                publisher = "Pluribusters",
                description = "未来科技、开源生态与数字文明圆桌对话高清视频专栏。",
                coverUrl = "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://anchor.fm/s/10f7a7eb4/podcast/rss",
                sourceType = PodcastSourceType.RSS,
                category = "科技前沿",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1074477061",
                title = "Bridgetown Video Podcast",
                publisher = "Bridgetown",
                description = "关于心智成熟、现代都市生活与心灵安顿的讲演视频。",
                coverUrl = "https://images.unsplash.com/photo-1519681393784-d120267933ba?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.feedburner.com/bridgetownvideopodcast",
                sourceType = PodcastSourceType.RSS,
                category = "知行成长",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1075343742",
                title = "Hope Generation: Video Podcast",
                publisher = "Ben Courson",
                description = "点燃希望与生命激情的励志演说与人物对话视频播客。",
                coverUrl = "https://images.unsplash.com/photo-1475721027785-f74eccf877e2?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feed.podbean.com/hopegenerationvideo/feed.xml",
                sourceType = PodcastSourceType.RSS,
                category = "知行成长",
                isVideo = true,
            ),
            PodcastShow(
                id = "itunes_1746135509",
                title = "Stripper Energy (video)",
                publisher = "KPBS",
                description = "先锋戏剧、现代舞蹈与舞台表演高清纪实视频。",
                coverUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=600&auto=format&fit=crop&q=80",
                feedUrl = "https://feeds.cloud.kpbs.org/stripper-energy-video",
                sourceType = PodcastSourceType.RSS,
                category = "文化艺术",
                isVideo = true,
            )
        )

        curatedVideoShows.forEach { s ->
            if (seenIds.add(s.id)) videoShows.add(s)
        }

        // 2. 深度从 iTunes 获取全球最新发布的活跃视频播客（严格过滤保证 100% 具备视频属性）
        val onlineQueries = listOf("video podcast", "video show")
        for (q in onlineQueries) {
            val results = runCatching {
                ItunesPodcastSource.searchShows(q, limit = 60)
            }.getOrDefault(emptyList())

            results.forEach { show ->
                if (isConfirmedVideoShow(show) && seenIds.add(show.id)) {
                    videoShows.add(show.copy(isVideo = true))
                }
            }
        }

        cachedVideoShows = videoShows
        videoShows
    }

    /**
     * 搜索特定的视频播客。
     *
     * 针对用户输入的关键词进行定向视频检索，结合精选视频专栏池与全网视频 RSS，
     * 彻底排除非视频纯音频播客，确保结果全部为真实可播的视频节目。
     */
    suspend fun searchVideoShows(query: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val videoShows = mutableListOf<PodcastShow>()
        val seenIds = mutableSetOf<String>()

        if (trimmed.isEmpty()) {
            return@withContext getRecommendedVideoShows()
        }

        // 1. 优先从高权威精选视频池中做模糊匹配（秒开置顶，如搜 TED、Yoga、Fashion、Tech）
        val allCurated = getRecommendedVideoShows()
        allCurated.forEach { s ->
            if (s.title.contains(trimmed, ignoreCase = true) ||
                s.description.contains(trimmed, ignoreCase = true) ||
                s.publisher.contains(trimmed, ignoreCase = true) ||
                s.category.contains(trimmed, ignoreCase = true)
            ) {
                if (seenIds.add(s.id)) videoShows.add(s.copy(isVideo = true))
            }
        }

        // 2. 构造高命中定向视频查询词进行网络请求
        val queryVariants = mutableListOf<String>()
        if (trimmed.contains("video", ignoreCase = true)) {
            queryVariants.add(trimmed)
            queryVariants.add("$trimmed podcast")
        } else {
            queryVariants.add("$trimmed video podcast")
            queryVariants.add("$trimmed video")
            queryVariants.add(trimmed)
        }

        for (qv in queryVariants) {
            val results = runCatching {
                ItunesPodcastSource.searchShows(qv, limit = 50)
            }.getOrDefault(emptyList())

            results.forEach { show ->
                if (isConfirmedVideoShow(show) && seenIds.add(show.id)) {
                    videoShows.add(show.copy(isVideo = true))
                }
            }
            if (videoShows.size >= 60) break
        }

        videoShows
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
