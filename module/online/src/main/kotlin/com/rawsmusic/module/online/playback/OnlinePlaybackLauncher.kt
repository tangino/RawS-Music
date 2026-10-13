package com.rawsmusic.module.online.playback

import android.content.Context
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.runtime.MusicSourceSearchCoordinator
import com.rawsmusic.module.online.model.OnlineSongItem
import kotlinx.coroutines.CancellationException

/** 在线歌曲起播的结果。文案映射交给 UI 层，数据层不产出用户可见字符串。 */
sealed interface OnlinePlayResult {
    /** 已交给 RawS 播放控制器，后续状态由它自己的快照发布。 */
    data object Started : OnlinePlayResult

    /** 用户一个音源都没装 —— 广场只有元数据，没有音源就无法解析出播放地址。 */
    data object NoSourceInstalled : OnlinePlayResult

    /** 音源里找不到与目标歌名/歌手/时长匹配的曲子。 */
    data object NoMatchFound : OnlinePlayResult

    data class Failed(val reason: String) : OnlinePlayResult
}

/**
 * 在线歌曲 → RawS 既有播放链路的接线。
 *
 * 这是 `AGENTS.MD` §6.1 第 5 条与 §9 D5 的落点：**不移植 `OnlineAudioSourceManager`，
 * 也不替换 `MusicSourceAudioResolver`**。广场歌曲只有平台 catalog 元数据、没有播放地址，
 * 所以流程是「用既有音源检索能力找候选 → 用我们移植的匹配算法挑对的那首 → 交给既有播放控制器」：
 *
 * 1. `MusicSourceSearchCoordinator` 检索用户已安装的 MusicFree / LX 音源（RawS 既有能力）
 * 2. [OnlinePlaybackBridge.selectBestCandidate] 用歌名 + 歌手 + 5 秒时长容差挑出真正匹配的那首
 *    （防止「歌名像就播了别人的版本」）
 * 3. `MusicSourcePlaybackController` 播放；它内部仍走 `MusicSourceAudioResolver`，
 *    我们**不插手**解析细节，只负责给对候选
 *
 * ⚠️ 注意不要在这里直接调 `MusicSourceAudioResolver.resolve` 再调播放：
 * 播放控制器内部会自己解析，重复解析等于白跑一次网络。
 */
object OnlinePlaybackLauncher {

    private const val TAG = "OnlineSource"

    /**
     * 为在线歌曲找出可播放的候选（不播放）。
     *
     * 分离出来是为了让调用方可以在真正起播前先做 UI 反馈或预校验。
     */
    suspend fun findPlayableCandidate(
        context: Context,
        item: OnlineSongItem,
    ): RawSourceMediaItem? {
        val musicFreeSources = MusicSourcePluginStore.sources.value
        val lxSources = LxSourcePluginStore.sources.value
        val hasEnabledSource = musicFreeSources.any { it.enabled } || lxSources.any { it.enabled }
        if (!hasEnabledSource) return null

        // 用「歌名 + 歌手」检索：只给歌名会淹在一堆翻唱/伴奏里，只给歌手必然跑偏。
        val query = listOf(item.title, item.artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (query.isEmpty()) return null

        val aggregated = MusicSourceSearchCoordinator.search(
            context = context,
            sources = musicFreeSources,
            lxSources = lxSources,
            query = query,
        )
        val candidates = aggregated.items
        if (candidates.isEmpty()) {
            AppLogger.d(TAG, "音源检索无结果：query=$query")
            return null
        }

        val best = OnlinePlaybackBridge.selectBestCandidate(item, candidates)
        if (best == null) {
            AppLogger.d(TAG, "音源有 ${candidates.size} 个候选，但无一通过匹配硬过滤：query=$query")
        }
        return best
    }

    /** 找到候选并交给 RawS 播放控制器起播。 */
    suspend fun play(context: Context, item: OnlineSongItem): OnlinePlayResult = try {
        val hasEnabledSource = MusicSourcePluginStore.sources.value.any { it.enabled } ||
            LxSourcePluginStore.sources.value.any { it.enabled }
        if (!hasEnabledSource) {
            OnlinePlayResult.NoSourceInstalled
        } else {
            val candidate = findPlayableCandidate(context, item)
            if (candidate == null) {
                OnlinePlayResult.NoMatchFound
            } else {
                MusicSourcePlaybackController.playItem(context = context, item = candidate)
                OnlinePlayResult.Started
            }
        }
    } catch (cancelled: CancellationException) {
        // 协程取消必须原样抛出，否则会把取消伪装成业务失败。
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "在线歌曲起播失败：${item.title}", error)
        OnlinePlayResult.Failed(error.message.orEmpty().ifBlank { "解析播放地址失败" })
    }
}
