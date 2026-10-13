package com.rawsmusic.module.online.playback

import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem

/**
 * 在线歌曲 → 可播放地址的**桥接算法**。
 *
 * 设计要点（对应根 `AGENTS.MD` §6.1 第 5 条、§9 D5）：
 * **不移植 OrBit 的 `OnlineAudioSourceManager`，也不替换 RawS 的解析器。**
 * RawS 的 `MusicSourceAudioResolver` 已经能从用户安装的 LX / MusicFree 音源解析地址；
 * 这里只做它**前后**的两件事：
 *
 * 1. **前**：广场歌曲的曲名/歌手未必与音源里的条目逐字相同 → 用 [OnlinePlaybackMatcher]
 *    从候选中挑出真正对得上的那首，避免「歌名像就播了别人的版本」。
 * 2. **后**：平台经常返回 30s/45s 试听片段或防盗链失效响应 → 用 [OnlinePlaybackProbe]
 *    探活校验，不合格就换下一个候选。
 *
 * 实际的「搜索用户音源 → 调 `MusicSourceAudioResolver.resolve`」由调用方（播放流程）完成，
 * 因为那需要 Context 与运行时服务；本对象保持无状态、可单测。
 */
object OnlinePlaybackBridge {

    private const val TAG = "OnlineSource"

    /**
     * 从候选里挑出与目标最匹配的一项。
     *
     * 先用 [OnlinePlaybackMatcher.isMatched] 做**硬过滤**（歌名 + 歌手 + 5 秒时长容差），
     * 再按 [OnlinePlaybackMatcher.calculateMatchScore] 降序取第一。
     * 硬过滤保证不会「像就播」，打分只在合格候选之间排序。
     */
    fun selectBestCandidate(
        target: OnlineSongItem,
        candidates: List<RawSourceMediaItem>,
    ): RawSourceMediaItem? {
        if (candidates.isEmpty()) return null

        val matched = candidates.filter { candidate ->
            OnlinePlaybackMatcher.isMatched(
                targetTitle = target.title,
                targetArtist = target.artist,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artists.joinToString("/"),
                targetDurationMs = target.durationMs,
                candidateDurationMs = candidate.durationMs,
            )
        }
        if (matched.isEmpty()) {
            AppLogger.d(TAG, "selectBestCandidate: ${candidates.size} 个候选全部未通过匹配硬过滤")
            return null
        }

        return matched.maxByOrNull { candidate ->
            OnlinePlaybackMatcher.calculateMatchScore(
                targetTitle = target.title,
                targetArtist = target.artist,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artists.joinToString("/"),
                targetDurationMs = target.durationMs,
                candidateDurationMs = candidate.durationMs,
            )
        }
    }

    /**
     * 探活校验一个已解析出的地址。
     *
     * @return 校验通过则返回（地址可能已被重定向改写）；不合格返回 null。
     */
    suspend fun validateResolved(
        resolved: RawResolvedAudioSource,
        platform: OnlinePlatform,
        expectedDurationMs: Long,
    ): RawResolvedAudioSource? {
        val (validatedUrl, _) = OnlinePlaybackProbe.probeAndValidateAudioUrl(
            rawUrl = resolved.url,
            expectedDurationMs = expectedDurationMs,
            targetPlatform = platform,
        ) ?: return null

        // 探活会跟随重定向，最终地址可能与入参不同，必须用校验过的那个。
        return resolved.copy(url = validatedUrl)
    }

    /**
     * 依次尝试候选，返回第一个能解析且通过校验的结果。
     *
     * @param resolveCandidate 由调用方注入，通常是
     *   `{ candidate, quality -> MusicSourceAudioResolver.resolve(context, candidate, quality) }`。
     *   这里不直接依赖 `:module:data` 的具体实现，保持可测与低耦合。
     */
    suspend fun resolveFirstPlayable(
        target: OnlineSongItem,
        candidates: List<RawSourceMediaItem>,
        preferredQuality: RawSourceQuality,
        resolveCandidate: suspend (RawSourceMediaItem, RawSourceQuality) -> RawResolvedAudioSource?,
    ): RawResolvedAudioSource? {
        val ordered = orderCandidatesByScore(target, candidates)
        if (ordered.isEmpty()) return null

        for (candidate in ordered) {
            try {
                val resolved = resolveCandidate(candidate, preferredQuality) ?: continue
                val validated = validateResolved(resolved, target.platform, target.durationMs)
                if (validated != null) return validated
                AppLogger.d(TAG, "候选解析结果未通过探活校验，尝试下一个：${candidate.title}")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.d(TAG, "候选解析失败，尝试下一个：${candidate.title} - ${error.message}")
            }
        }
        return null
    }

    /** 按匹配得分降序排列候选（不做硬过滤，供需要全量尝试的调用方使用）。 */
    fun orderCandidatesByScore(
        target: OnlineSongItem,
        candidates: List<RawSourceMediaItem>,
    ): List<RawSourceMediaItem> = candidates.sortedByDescending { candidate ->
        OnlinePlaybackMatcher.calculateMatchScore(
            targetTitle = target.title,
            targetArtist = target.artist,
            candidateTitle = candidate.title,
            candidateArtist = candidate.artists.joinToString("/"),
            targetDurationMs = target.durationMs,
            candidateDurationMs = candidate.durationMs,
        )
    }
}
