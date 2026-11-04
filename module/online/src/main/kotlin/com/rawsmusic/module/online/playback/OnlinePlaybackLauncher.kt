package com.rawsmusic.module.online.playback

import android.content.Context
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.OnlineAudioFileCache
import com.rawsmusic.module.data.source.runtime.MusicSourceSearchCoordinator
import com.rawsmusic.module.online.model.OnlineSongItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /** 找到候选并交给 RawS 播放控制器起播。优先照搬 OrBitPlayer 的全套寻源与探活算法。 */
    suspend fun play(
        context: Context,
        item: OnlineSongItem,
        queue: List<OnlineSongItem> = listOf(item),
        index: Int = 0,
    ): OnlinePlayResult = try {
        val rawQueue = if (queue.isNotEmpty() && queue.size > 1) {
            queue.map { OnlineSourceMediaMapper.toRawSourceMediaItem(it) }
        } else {
            val existingQueue = MusicSourcePlaybackController.snapshot.value.queue
            if (existingQueue.size > 1) {
                val matchIdx = existingQueue.indexOfFirst {
                    it.remoteId == item.id ||
                        (it.title.equals(item.title, ignoreCase = true) &&
                            it.artists.contains(item.artist))
                }
                if (matchIdx >= 0) {
                    val targetMedia = OnlineSourceMediaMapper.toRawSourceMediaItem(item)
                    existingQueue.toMutableList().apply { set(matchIdx, targetMedia) }
                } else if (queue.isNotEmpty()) {
                    queue.map { OnlineSourceMediaMapper.toRawSourceMediaItem(it) }
                } else {
                    listOf(OnlineSourceMediaMapper.toRawSourceMediaItem(item))
                }
            } else if (queue.isNotEmpty()) {
                queue.map { OnlineSourceMediaMapper.toRawSourceMediaItem(it) }
            } else {
                listOf(OnlineSourceMediaMapper.toRawSourceMediaItem(item))
            }
        }
        val targetMediaItem = OnlineSourceMediaMapper.toRawSourceMediaItem(item)
        // The caller's index is the exact row the user tapped. Prefer it when it still points at
        // that item: some search platforms return the same remote id for multiple versions of a
        // song, and indexOfFirst() would then silently select the first version (and its cover).
        val resolvedIndex = index
            .takeIf { it in rawQueue.indices && rawQueue[it].stableIdentity == targetMediaItem.stableIdentity }
            ?: rawQueue.indexOfFirst { it.stableIdentity == targetMediaItem.stableIdentity }
                .takeIf { it >= 0 }
            ?: if (index in rawQueue.indices) index else 0

        // 0. 🌟 对齐 OrBitPlayer 核心机制：检查本地磁盘是否已存在有效缓存文件
        // 若已命中完整缓存，直接以离线本地文件起播，跳过寻源（正在寻源...）阶段，实现 0 延时离线秒开！
        val cachedHit = OnlineAudioFileCache.findValidCachedFile(
            context = context,
            item = targetMediaItem,
            preferredQuality = OnlineAudioSourceResolver.stringToRawSourceQuality(AppPreferences.Source.preferredQuality),
        )
        if (cachedHit != null) {
            val (cachedFile, cachedQuality) = cachedHit
            AppLogger.i(
                TAG,
                "[OnlinePlaybackLauncher] 命中本地完整缓存：${item.title} -> ${cachedFile.name} ($cachedQuality)"
            )
            val rawMediaItem = targetMediaItem.copy(
                durationMs = item.durationMs,
            )
            val rawResolved = com.rawsmusic.core.common.source.RawResolvedAudioSource(
                url = cachedFile.absolutePath,
                quality = cachedQuality,
                headers = emptyMap(),
            )
            MusicSourcePlaybackController.playResolved(
                context = context,
                item = rawMediaItem,
                source = rawResolved,
                queue = rawQueue,
                index = resolvedIndex,
                originalPlatform = item.platform.displayName,
                resolvedPlatform = item.platform.displayName,
                sourceTag = "本地缓存",
            )
            return OnlinePlayResult.Started
        }

        // 1. 优先走 OrBitPlayer 寻源核心（LRU 缓存 -> 原平台优先通道 -> 跨平台并发搜救 -> 官方兜底直链 -> 探活）
        val resolved = OnlineAudioSourceResolver.resolvePlayableSource(
            context = context,
            platform = item.platform,
            songId = item.id,
            title = item.title,
            artist = item.artist,
            album = item.album,
            expectedDurationMs = item.durationMs,
            explicitQuality = AppPreferences.Source.preferredQuality,
        )

        if (resolved != null) {
            val quality = OnlineAudioSourceResolver.stringToRawSourceQuality(resolved.quality)
            val rawMediaItem = OnlineSourceMediaMapper.toRawSourceMediaItem(item).copy(
                durationMs = if (resolved.durationMs > 0L) resolved.durationMs else item.durationMs,
            )
            val rawResolved = com.rawsmusic.core.common.source.RawResolvedAudioSource(
                url = resolved.url,
                quality = quality,
                headers = emptyMap(),
            )
            MusicSourcePlaybackController.playResolved(
                context = context,
                item = rawMediaItem,
                source = rawResolved,
                queue = rawQueue,
                index = resolvedIndex,
                originalPlatform = item.platform.displayName,
                resolvedPlatform = resolved.platform.displayName,
                sourceTag = resolved.sourceName,
            )
            OnlinePlayResult.Started
        } else {
            // 2. 补充兜底：若全平台多源搜救未果，尝试从用户安装的 MusicFree 插件库检索
            val candidate = findPlayableCandidate(context, item)
            if (candidate != null) {
                val updatedQueue = rawQueue.toMutableList()
                if (resolvedIndex in updatedQueue.indices) {
                    updatedQueue[resolvedIndex] = candidate
                }
                MusicSourcePlaybackController.play(
                    context = context,
                    queue = updatedQueue,
                    index = resolvedIndex,
                )
                OnlinePlayResult.Started
            } else {
                val hasEnabledSource = MusicSourcePluginStore.sources.value.any { it.enabled } ||
                    LxSourcePluginStore.sources.value.any { it.enabled }
                if (!hasEnabledSource) {
                    OnlinePlayResult.NoSourceInstalled
                } else {
                    OnlinePlayResult.NoMatchFound
                }
            }
        }
    } catch (cancelled: CancellationException) {
        // 协程取消必须原样抛出，否则会把取消伪装成业务失败。
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.e(TAG, "在线歌曲起播失败：${item.title}", error)
        OnlinePlayResult.Failed(error.message.orEmpty().ifBlank { "解析播放地址失败" })
    }

    suspend fun playQueue(
        context: Context,
        songs: List<OnlineSongItem>,
        startIndex: Int = 0,
    ): OnlinePlayResult {
        if (songs.isEmpty()) return OnlinePlayResult.Failed("队列为空")
        val targetIndex = startIndex.coerceIn(songs.indices)
        val item = songs[targetIndex]
        return play(context = context, item = item, queue = songs, index = targetIndex)
    }

    // ====== 电台 / 猜你喜欢无限流状态管理 ======
    @Volatile
    private var isRadioMode: Boolean = false
    val isRadioActive: Boolean
        get() = isRadioMode

    private val _currentRadioSongs = kotlinx.coroutines.flow.MutableStateFlow<List<OnlineSongItem>>(emptyList())
    val currentRadioSongs: kotlinx.coroutines.flow.StateFlow<List<OnlineSongItem>> = _currentRadioSongs
    @Volatile
    private var radioPage: Int = 1
    @Volatile
    private var radioPlatform: com.rawsmusic.module.online.model.OnlinePlatform = com.rawsmusic.module.online.model.OnlinePlatform.QQ
    private var radioMonitorJob: Job? = null
    private val radioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 一键开启猜你喜欢 / 私人雷达电台
     * @param context Android 上下文
     * @param platform 平台（默认 QQ 音乐）
     */
    suspend fun launchGuessLikeRadio(
        context: Context,
        platform: com.rawsmusic.module.online.model.OnlinePlatform = com.rawsmusic.module.online.model.OnlinePlatform.QQ,
        seedSong: OnlineSongItem? = null,
        onSongsLoaded: ((List<OnlineSongItem>) -> Unit)? = null,
    ): OnlinePlayResult {
        isRadioMode = false
        radioMonitorJob?.cancel()
        radioPage = 1
        radioPlatform = platform

        val repo = com.rawsmusic.module.online.repository.OnlineMusicRepository.getInstance()
        val seedMids = if (seedSong != null) listOf(seedSong.id) else emptyList()
        val songsResult = repo.getRecommendSongs(
            page = 1,
            pageSize = 20,
            platform = platform,
            seedSongMids = seedMids,
        )
        val candidateSongs = songsResult.getOrNull().orEmpty()
        val songs = if (seedSong != null && candidateSongs.none { it.id == seedSong.id }) {
            listOf(seedSong) + candidateSongs
        } else {
            candidateSongs
        }

        if (songs.isEmpty()) {
            return OnlinePlayResult.Failed("获取个性推荐曲目为空")
        }

        _currentRadioSongs.value = songs
        onSongsLoaded?.invoke(songs)

        // 标记第一首歌曲已播
        com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordSongPlayedInRadio(songs[0].id)

        val playResult = playQueue(context, songs, startIndex = 0)
        if (playResult is OnlinePlayResult.Started) {
            isRadioMode = true
            startRadioWatermarkMonitor(context.applicationContext)
        }
        return playResult
    }

    /**
     * 用户主动负反馈（减少此类推荐，并切到下一首）
     */
    fun dislikeCurrentRadioSong(context: Context) {
        val snapshot = MusicSourcePlaybackController.snapshot.value
        val item = snapshot.currentItem
        if (item != null) {
            com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordNegativeFeedback(
                songId = item.remoteId,
                artist = item.artists.joinToString(", "),
            )
            com.rawsmusic.core.common.ui.AppNoticeBus.post(
                message = "已为您减少此类推荐",
                icon = com.rawsmusic.core.common.ui.AppNoticeIcon.INFO,
            )
            MusicSourcePlaybackController.next()
        }
    }

    /**
     * 电台队列低水位线监控：队列剩余不足 3 首时，自动静默获取下一批追加入队
     */
    private fun startRadioWatermarkMonitor(appContext: Context) {
        radioMonitorJob?.cancel()
        radioMonitorJob = radioScope.launch {
            var isLoadingMore = false
            var lastTrackId: String? = null
            var lastTrackArtist: String? = null
            var trackStartTimeMs: Long = System.currentTimeMillis()

            MusicSourcePlaybackController.snapshot.collect { snapshot ->
                if (!isRadioMode) return@collect
                val queue = snapshot.queue
                val curIdx = snapshot.currentIndex
                val currentTrack = snapshot.currentItem

                // 1. 行为监听闭环：检测切歌时长，判定正负反馈
                if (currentTrack != null && currentTrack.remoteId != lastTrackId) {
                    val playedDurationSec = (System.currentTimeMillis() - trackStartTimeMs) / 1000
                    if (lastTrackId != null && lastTrackArtist != null) {
                        if (playedDurationSec in 1..9) {
                            // 听不足 10 秒即切歌：记录负反馈抑制
                            com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordNegativeFeedback(lastTrackId!!, lastTrackArtist!!)
                        } else if (playedDurationSec >= 40) {
                            // 听满 40 秒以上：记录正向偏好种子
                            com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordPositiveFeedback(lastTrackId!!)
                        }
                    }
                    lastTrackId = currentTrack.remoteId
                    lastTrackArtist = currentTrack.artists.joinToString(", ")
                    trackStartTimeMs = System.currentTimeMillis()
                    com.rawsmusic.module.online.recommend.GuessLikeOptimizer.recordSongPlayedInRadio(currentTrack.remoteId)
                }

                // 2. 低水位线自动无限续杯（还剩 3 首以下自动拉取）
                if (queue.isNotEmpty() && curIdx >= queue.size - 3 && !isLoadingMore) {
                    isLoadingMore = true
                    try {
                        val nextPage = radioPage + 1
                        val seedMid = currentTrack?.remoteId.orEmpty()
                        AppLogger.d(TAG, "[OnlineRadio] 电台低水位触发自动续杯，拉取第 $nextPage 页 (种子: $seedMid)...")
                        val repo = com.rawsmusic.module.online.repository.OnlineMusicRepository.getInstance()
                        val moreResult = repo.getRecommendSongs(
                            page = nextPage,
                            pageSize = 20,
                            platform = radioPlatform,
                            seedSongMids = if (seedMid.isNotBlank()) listOf(seedMid) else emptyList(),
                        )
                        val moreSongs = moreResult.getOrNull().orEmpty()
                        if (moreSongs.isNotEmpty()) {
                            val rawItems = moreSongs.map { OnlineSourceMediaMapper.toRawSourceMediaItem(it) }
                            MusicSourcePlaybackController.appendQueue(rawItems)
                            _currentRadioSongs.value = _currentRadioSongs.value + moreSongs
                            radioPage = nextPage
                            AppLogger.i(TAG, "[OnlineRadio] 电台第 $nextPage 页续杯成功，已追加 ${rawItems.size} 首歌曲")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "[OnlineRadio] 电台静默续杯异常: ${e.message}")
                    } finally {
                        isLoadingMore = false
                    }
                }
            }
        }
    }
}
