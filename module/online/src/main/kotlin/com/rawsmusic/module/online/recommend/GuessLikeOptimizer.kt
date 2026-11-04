package com.rawsmusic.module.online.recommend

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.OnlineSongItem
import java.util.concurrent.ConcurrentHashMap

/**
 * 猜你喜欢本地推荐优化器与行为闭环引擎。
 * 1. 种子管理：记录用户完播与喜爱的正向反馈种子；
 * 2. 负反馈抑制：记录秒切/不感兴趣歌曲与歌手冷却；
 * 3. 本地重排：历史去重、歌手防疲劳打散算法。
 */
object GuessLikeOptimizer {

    private const val TAG = "GuessLikeOptimizer"

    // 正向种子池（最新完播或加喜欢的歌曲 ID）
    private val positiveSeedMids = LinkedHashSet<String>()

    // 负反馈歌曲抑制池（被跳过或标记不喜欢的歌曲 ID -> 记录时间戳）
    private val suppressedSongIds = ConcurrentHashMap<String, Long>()

    // 歌手跳过计数（歌手名 -> 跳过次数）与歌手冷却时间戳（歌手名 -> 解除冷却时间戳）
    private val artistSkipCount = ConcurrentHashMap<String, Int>()
    private val suppressedArtists = ConcurrentHashMap<String, Long>()

    // 最近在当前电台推荐流中排布过的歌曲 ID
    private val recentPlayedInRadio = LinkedHashSet<String>()

    /**
     * 记录正向反馈（例如单曲完播 >= 80% 或被收藏）。
     */
    fun recordPositiveFeedback(songId: String) {
        if (songId.isBlank()) return
        synchronized(positiveSeedMids) {
            positiveSeedMids.remove(songId)
            positiveSeedMids.add(songId)
            while (positiveSeedMids.size > 30) {
                val oldest = positiveSeedMids.iterator().next()
                positiveSeedMids.remove(oldest)
            }
        }
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
        synchronized(recentPlayedInRadio) {
            recentPlayedInRadio.add(songId)
            while (recentPlayedInRadio.size > 100) {
                val oldest = recentPlayedInRadio.iterator().next()
                recentPlayedInRadio.remove(oldest)
            }
        }
    }

    /**
     * 获取供云端雷达请求使用的偏好种子歌曲列表。
     */
    fun getFavoriteSeeds(): List<String> {
        synchronized(positiveSeedMids) {
            return positiveSeedMids.toList().takeLast(5).reversed()
        }
    }

    /**
     * 对云端拉取回来的候选曲目流进行本地智能优化：
     * 1. 过滤已抑制的歌曲与冷却期内的歌手；
     * 2. 过滤最近电台已播重复歌曲；
     * 3. 歌手防疲劳打散（确保同一歌手之间至少间隔 3 首以上）。
     */
    fun optimizeRecommendations(
        candidates: List<OnlineSongItem>,
        currentSeedId: String? = null,
    ): List<OnlineSongItem> {
        val now = System.currentTimeMillis()

        // 清理过期的抑制项
        suppressedSongIds.entries.removeIf { it.value < now }
        suppressedArtists.entries.removeIf { it.value < now }

        // 1. 过滤抑制歌曲、冷却歌手以及当前种子自身
        val filtered = candidates.filter { song ->
            if (song.id == currentSeedId) return@filter false
            if (suppressedSongIds.containsKey(song.id)) return@filter false
            val artistCoolingUntil = suppressedArtists[song.artist]
            if (artistCoolingUntil != null && artistCoolingUntil > now) return@filter false
            true
        }

        // 2. 去重过滤（若过滤后太少，则放宽去重限制）
        val deduplicated = mutableListOf<OnlineSongItem>()
        val seenIds = HashSet<String>()
        synchronized(recentPlayedInRadio) {
            for (song in filtered) {
                if (seenIds.add(song.id)) {
                    if (song.id !in recentPlayedInRadio || deduplicated.size < 5) {
                        deduplicated.add(song)
                    }
                }
            }
        }

        if (deduplicated.isEmpty()) {
            return candidates.distinctBy { it.id }
        }

        // 3. 歌手防疲劳打散重排（Greedy Distance-Spaced Scattering）
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

