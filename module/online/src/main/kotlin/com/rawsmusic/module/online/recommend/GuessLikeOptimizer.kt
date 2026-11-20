package com.rawsmusic.module.online.recommend

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.OnlineSongItem
import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 猜你喜欢本地推荐优化器与行为闭环引擎。
 * 1. 种子管理：记录用户完播与喜爱的正向反馈种子；
 * 2. 负反馈抑制：记录秒切/不感兴趣歌曲与歌手冷却；
 * 3. 本地重排：2000 首持久化历史去重、歌手防疲劳打散算法。
 */
object GuessLikeOptimizer {

    private const val TAG = "GuessLikeOptimizer"
    private const val MAX_EXPOSED_WINDOW = 2000
    private const val MAX_RECENT_RADIO_WINDOW = 500
    private const val MAX_POSITIVE_SEEDS = 100
    private const val KEY_EXPOSED_IDS = "guess_like_exposed_song_ids_v1"
    private const val KEY_EXPOSED_FPS = "guess_like_exposed_song_fps_v1"
    private const val KEY_POSITIVE_SEEDS = "guess_like_positive_seeds_v1"

    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawS-GuessLikePersist").apply { isDaemon = true }
    }

    private val kv: MMKV? by lazy {
        runCatching { MMKV.defaultMMKV() }.getOrNull()
    }

    // 正向种子池（最新完播或加喜欢的歌曲 ID）
    private val positiveSeedMids = LinkedHashSet<String>()

    // 负反馈歌曲抑制池（被跳过或标记不喜欢的歌曲 ID -> 记录时间戳）
    private val suppressedSongIds = ConcurrentHashMap<String, Long>()

    // 歌手跳过计数（歌手名 -> 跳过次数）与歌手冷却时间戳（歌手名 -> 解除冷却时间戳）
    private val artistSkipCount = ConcurrentHashMap<String, Int>()
    private val suppressedArtists = ConcurrentHashMap<String, Long>()

    // 最近在当前电台推荐流中排布播放过的歌曲 ID
    private val recentPlayedInRadio = LinkedHashSet<String>()

    // 全局已曝光展示歌曲 ID 滑动窗口（容量 2000 首，FIFO 轮转，并持久化到 MMKV 防止重启后重复推荐）
    private val exposedSongIds = LinkedHashSet<String>()

    // 全局已曝光歌曲特征指纹（"clean_title##clean_artist"，容量 2000 首，防止同曲不同版本重复推荐）
    private val exposedSongFingerprints = LinkedHashSet<String>()

    @Volatile
    private var loadedFromStorage = false

    private fun ensureLoaded() {
        if (loadedFromStorage) return
        synchronized(exposedSongIds) {
            if (loadedFromStorage) return
            loadedFromStorage = true
            val storage = kv ?: return
            runCatching {
                storage.decodeString(KEY_EXPOSED_IDS)
                    ?.split('\n')
                    ?.asSequence()
                    ?.filter { it.isNotBlank() }
                    ?.forEach { exposedSongIds.add(it) }
                storage.decodeString(KEY_EXPOSED_FPS)
                    ?.split('\n')
                    ?.asSequence()
                    ?.filter { it.isNotBlank() }
                    ?.forEach { exposedSongFingerprints.add(it) }
            }
            synchronized(positiveSeedMids) {
                runCatching {
                    storage.decodeString(KEY_POSITIVE_SEEDS)
                        ?.split('\n')
                        ?.asSequence()
                        ?.filter { it.isNotBlank() }
                        ?.forEach { positiveSeedMids.add(it) }
                }
            }
        }
    }

    private fun persistExposedAsync() {
        val idsSnapshot: List<String>
        val fpsSnapshot: List<String>
        synchronized(exposedSongIds) {
            idsSnapshot = exposedSongIds.toList()
            fpsSnapshot = exposedSongFingerprints.toList()
        }
        persistExecutor.execute {
            runCatching {
                kv?.encode(KEY_EXPOSED_IDS, idsSnapshot.joinToString("\n"))
                kv?.encode(KEY_EXPOSED_FPS, fpsSnapshot.joinToString("\n"))
            }
        }
    }

    private fun persistSeedsAsync() {
        val seedsSnapshot = synchronized(positiveSeedMids) { positiveSeedMids.toList() }
        persistExecutor.execute {
            runCatching {
                kv?.encode(KEY_POSITIVE_SEEDS, seedsSnapshot.joinToString("\n"))
            }
        }
    }

    /**
     * 记录正向反馈（例如单曲完播 >= 80% 或被收藏）。
     */
    fun recordPositiveFeedback(songId: String) {
        if (songId.isBlank()) return
        ensureLoaded()
        synchronized(positiveSeedMids) {
            positiveSeedMids.remove(songId)
            positiveSeedMids.add(songId)
            while (positiveSeedMids.size > MAX_POSITIVE_SEEDS) {
                val oldest = positiveSeedMids.iterator().next()
                positiveSeedMids.remove(oldest)
            }
        }
        persistSeedsAsync()
        AppLogger.d(TAG, "记录正向反馈种子: $songId (当前种子库大小: ${positiveSeedMids.size})")
    }

    /**
     * 记录负反馈（例如播放不足 10 秒即跳过，或用户主动点击“不喜欢/减少推荐”）。
     */
    fun recordNegativeFeedback(songId: String, artist: String) {
        val now = System.currentTimeMillis()
        if (songId.isNotBlank()) {
            suppressedSongIds[songId] = now + 24 * 3600 * 1000L // 抑制 24 小时
            synchronized(positiveSeedMids) {
                positiveSeedMids.remove(songId)
            }
            persistSeedsAsync()
        }

        if (artist.isNotBlank() && artist != "未知歌手") {
            val count = (artistSkipCount[artist] ?: 0) + 1
            artistSkipCount[artist] = count
            if (count >= 2) {
                // 连续跳过 2 次，该歌手冷却 1 小时
                suppressedArtists[artist] = now + 3600 * 1000L
                AppLogger.i(TAG, "歌手 [$artist] 短期内跳过 $count 次，触发 1 小时防疲劳冷却")
            }
        }
        AppLogger.i(TAG, "记录负反馈: $songId, 歌手: $artist")
    }

    /**
     * 标记某首歌曲已在电台播放。
     */
    fun recordSongPlayedInRadio(songId: String) {
        if (songId.isBlank()) return
        ensureLoaded()
        synchronized(recentPlayedInRadio) {
            recentPlayedInRadio.add(songId)
            while (recentPlayedInRadio.size > MAX_RECENT_RADIO_WINDOW) {
                val oldest = recentPlayedInRadio.iterator().next()
                recentPlayedInRadio.remove(oldest)
            }
        }
        // 播放的曲目同时记录到已曝光窗口
        synchronized(exposedSongIds) {
            exposedSongIds.remove(songId)
            exposedSongIds.add(songId)
            while (exposedSongIds.size > MAX_EXPOSED_WINDOW) {
                val oldest = exposedSongIds.iterator().next()
                exposedSongIds.remove(oldest)
            }
        }
        persistExposedAsync()
    }

    /**
     * 记录一组歌曲已在界面向用户曝光展示。
     * 建立 2000 首持久化滑动去重窗口，杜绝“换一批”与重启 App 后重复展示看过的歌曲。
     */
    fun recordSongsExposed(songs: Collection<OnlineSongItem>) {
        if (songs.isEmpty()) return
        ensureLoaded()
        synchronized(exposedSongIds) {
            for (song in songs) {
                if (song.id.isNotBlank()) {
                    exposedSongIds.remove(song.id)
                    exposedSongIds.add(song.id)
                }
                val fp = makeFingerprint(song.title, song.artist)
                if (fp.isNotBlank()) {
                    exposedSongFingerprints.remove(fp)
                    exposedSongFingerprints.add(fp)
                }
            }
            while (exposedSongIds.size > MAX_EXPOSED_WINDOW) {
                val oldest = exposedSongIds.iterator().next()
                exposedSongIds.remove(oldest)
            }
            while (exposedSongFingerprints.size > MAX_EXPOSED_WINDOW) {
                val oldestFp = exposedSongFingerprints.iterator().next()
                exposedSongFingerprints.remove(oldestFp)
            }
        }
        persistExposedAsync()
        AppLogger.d(TAG, "已记录曝光: ${songs.size} 首歌曲，当前曝光历史池大小: ${exposedSongIds.size}")
    }

    /**
     * 获取最近曝光的歌曲 mid 列表，传递给云端服务端以从召回源头剔除已曝光曲目。
     */
    fun getRecentExposedMids(limit: Int = 50): List<String> {
        ensureLoaded()
        synchronized(exposedSongIds) {
            return exposedSongIds.toList().takeLast(limit)
        }
    }

    /**
     * 提取歌曲特征指纹（去除版本后缀、小括号标记、副歌手修饰，进行小写归一化），
     * 避免同一首歌的不同 live 版本或翻唱版本在同一次推荐中重复出现。
     */
    private fun makeFingerprint(title: String, artist: String): String {
        val cleanTitle = title.trim().lowercase()
            .replace(Regex("""\(.*?\)|\[.*?\]|（.*?）|【.*?】"""), "")
            .replace(Regex("""-\s*(live|伴奏|remix|翻唱).*$"""), "")
            .trim()
        val cleanArtist = artist.trim().lowercase()
            .split(',', '/', '&', '、', '与')
            .firstOrNull()?.trim() ?: ""
        return if (cleanTitle.isNotBlank()) "$cleanTitle##$cleanArtist" else ""
    }

    /**
     * 获取供云端雷达请求使用的偏好种子歌曲列表。
     */
    fun getFavoriteSeeds(): List<String> = getDynamicFavoriteSeeds(5)

    // 外部收藏种子提供者回调（例如来自本地或平台收藏夹）
    @Volatile
    private var favoriteSeedsProvider: (() -> List<String>)? = null

    fun setFavoriteSeedsProvider(provider: () -> List<String>) {
        favoriteSeedsProvider = provider
    }

    /**
     * 借鉴 QTMusic 的动态种子扰动策略（Seed Perturbation）：
     * 每次推荐或“换一批”时，从正向偏好种子池与本地收藏曲库中进行动态抽样，
     * 避免固定传递最老的或相同的 5 首歌曲导致云端推荐算法产生静态死锁（Filter Bubble）。
     */
    fun getDynamicFavoriteSeeds(
        count: Int = 5,
        excludeMids: Set<String> = emptySet(),
    ): List<String> {
        ensureLoaded()
        val pool = mutableListOf<String>()
        synchronized(positiveSeedMids) {
            pool.addAll(positiveSeedMids.filterNot { it in excludeMids })
        }

        // 总是混入一部分外部收藏库随机种子，扩大兴趣探索半径，避免只围绕最近播放的几首歌打转
        try {
            val externalSeeds = favoriteSeedsProvider?.invoke().orEmpty()
            val candidateSeeds = externalSeeds.filterNot { it in excludeMids || it in pool }.shuffled()
            pool.addAll(candidateSeeds.take(count * 3))
        } catch (e: Exception) {
            AppLogger.w(TAG, "从外部收藏库提取种子失败: ${e.message}")
        }

        if (pool.isEmpty()) return emptyList()
        if (pool.size <= count) return pool.shuffled()

        // 全池洗牌随机抽取，彻底打破固定锚点导致的推荐死循环
        return pool.shuffled().take(count)
    }

    /**
     * 对候选曲目流进行本地严格优化与打散：
     * 1. 过滤已负反馈抑制的歌曲与冷却期内的歌手；
     * 2. 严格去重已曝光滑窗与已播电台歌曲，以及相同歌名指纹；
     * 3. 歌手防疲劳打散（确保同一歌手之间至少间隔 3 首以上）。
     *
     * @param filterExposed 是否严格过滤已曝光历史。在换一批与正常推荐时必须为 true，彻底打掉重复率。
     * @param allowRelaxFallback 当过滤后不足 3 首时是否允许立即放宽曝光限制；多轮召回中间轮次必须传 false，避免提前放回重复歌曲。
     */
    fun optimizeRecommendations(
        candidates: List<OnlineSongItem>,
        currentSeedId: String? = null,
        filterExposed: Boolean = true,
        allowRelaxFallback: Boolean = true,
    ): List<OnlineSongItem> {
        ensureLoaded()
        val now = System.currentTimeMillis()

        // 清理过期的抑制项
        suppressedSongIds.entries.removeIf { it.value < now }
        suppressedArtists.entries.removeIf { it.value < now }

        val filtered = synchronized(exposedSongIds) {
            candidates.filter { song ->
                if (song.id.isBlank()) return@filter false
                if (song.id == currentSeedId) return@filter false
                if (suppressedSongIds.containsKey(song.id)) return@filter false
                val artistCoolingUntil = suppressedArtists[song.artist]
                if (artistCoolingUntil != null && artistCoolingUntil > now) return@filter false

                if (filterExposed) {
                    // 1. 检查是否在已曝光滑窗或电台已播池中
                    if (song.id in exposedSongIds) return@filter false
                    if (song.id in recentPlayedInRadio) return@filter false

                    // 2. 检查歌名指纹，杜绝同曲不同版本重复
                    val fp = makeFingerprint(song.title, song.artist)
                    if (fp.isNotBlank() && fp in exposedSongFingerprints) return@filter false
                }
                true
            }
        }

        // 内部去重（同一批次内部去重）
        val deduplicated = mutableListOf<OnlineSongItem>()
        val seenIds = HashSet<String>()
        val seenFps = HashSet<String>()
        for (song in filtered) {
            val fp = makeFingerprint(song.title, song.artist)
            if (seenIds.add(song.id) && (fp.isBlank() || seenFps.add(fp))) {
                deduplicated.add(song)
            }
        }

        // 仅在允许兜底放宽时才退回未过滤已曝光的列表；多轮探索中保持严格空/少量返回，迫使上层换新种子继续召回
        if (allowRelaxFallback && deduplicated.size < 3 && filterExposed && candidates.isNotEmpty()) {
            AppLogger.w(TAG, "严格去重后候选歌曲较少(${deduplicated.size})，放宽曝光限制以防止流耗尽")
            return optimizeRecommendations(candidates, currentSeedId, filterExposed = false, allowRelaxFallback = false)
        }

        if (deduplicated.isEmpty()) {
            return if (allowRelaxFallback) candidates.distinctBy { it.id } else emptyList()
        }

        // 歌手防疲劳打散重排
        return scatterByArtist(deduplicated, minDistance = 3)
    }

    /**
     * 贪心间隔打散算法：使相同歌手之间的间距至少为 [minDistance] 首。
     */
    private fun scatterByArtist(
        items: List<OnlineSongItem>,
        minDistance: Int = 3,
    ): List<OnlineSongItem> {
        if (items.size <= minDistance) return items

        val result = mutableListOf<OnlineSongItem>()
        val remaining = items.toMutableList()

        while (remaining.isNotEmpty()) {
            // 找出最近 minDistance 个放置项里的所有歌手
            val recentArtists = result.takeLast(minDistance).map { it.artist }.toSet()

            // 优先挑一个与最近歌手不冲突的歌曲
            val candidateIndex = remaining.indexOfFirst { it.artist !in recentArtists }
            if (candidateIndex != -1) {
                result.add(remaining.removeAt(candidateIndex))
            } else {
                // 如果全都冲突了，挑一个离得最远的放入
                result.add(remaining.removeAt(0))
            }
        }

        return result
    }
}

