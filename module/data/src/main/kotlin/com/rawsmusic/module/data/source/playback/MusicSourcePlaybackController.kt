package com.rawsmusic.module.data.source.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.rawsmusic.core.common.prefs.PlaylistAutoDownloadStore
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Playback state owned only by the online-source portal. */
enum class MusicSourcePlaybackStatus {
    Idle,
    Resolving,
    Preparing,
    Playing,
    Paused,
    Completed,
    Error,
}

data class MusicSourcePlaybackSnapshot(
    val queue: List<RawSourceMediaItem> = emptyList(),
    val currentIndex: Int = -1,
    val currentItem: RawSourceMediaItem? = null,
    val status: MusicSourcePlaybackStatus = MusicSourcePlaybackStatus.Idle,
    val requestedQuality: RawSourceQuality = RawSourceQuality.Standard,
    val resolvedQuality: RawSourceQuality = RawSourceQuality.Standard,
    val originalPlatform: String? = null,
    val resolvedPlatform: String? = null,
    val sourceTag: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPercent: Int = 0,
    val error: String = "",
) {
    val isPlaying: Boolean
        get() = status == MusicSourcePlaybackStatus.Playing

    val isBusy: Boolean
        get() = status == MusicSourcePlaybackStatus.Resolving || status == MusicSourcePlaybackStatus.Preparing

    val isFallbackSource: Boolean
        get() = !originalPlatform.isNullOrBlank() && !resolvedPlatform.isNullOrBlank() && originalPlatform != resolvedPlatform

    val canGoPrevious: Boolean
        get() = queue.size > 1 || positionMs > 5_000L || currentIndex > 0

    val canGoNext: Boolean
        get() {
            if (queue.isEmpty()) return false
            val playMode = com.rawsmusic.module.data.prefs.AppPreferences.Player.playMode
            val isShuffle = com.rawsmusic.module.data.prefs.AppPreferences.Player.isShuffle
            val isLoopMode = playMode.repeatMode != com.rawsmusic.core.common.model.RepeatMode.OFF ||
                playMode == com.rawsmusic.core.common.model.PlayMode.SHUFFLE_ALL ||
                isShuffle
            return queue.size > 1 || isLoopMode || status == MusicSourcePlaybackStatus.Playing || status == MusicSourcePlaybackStatus.Completed
        }

    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Online-source queue and quality controller.
 *
 * Resolved HTTP(S) sources are materialized through Android's network stack as seekable cache
 * files, then played by PlayerController/FfmpegAudioPlayer so PCM enters the shared DSP path. The
 * independent MediaPlayer remains only as a last-resort fallback when download or local decode fails.
 */
object MusicSourcePlaybackController {
    private const val TAG = "MusicSourcePlayback"
    private const val PREFERRED_QUALITY_KEY = "music_source_playback_quality_v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generations = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(MusicSourcePlaybackSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    private var applicationContext: Context? = null
    private var resolveJob: Job? = null
    private var backendStateJob: Job? = null
    private var cacheJob: Job? = null
    private var backend: MusicSourcePlaybackBackend? = null
    private var prepared = false
    private var completionHandledGeneration = Long.MIN_VALUE

    @Volatile
    private var exoPlayer: ExoPlayer? = null
    @Volatile
    private var exoPlayerGeneration = Long.MIN_VALUE
    @Volatile
    var externalPcmDspHook: ExternalPcmDspHook? = null
    private var exoProgressJob: Job? = null
    @Volatile
    private var pendingSeekTargetMs: Long = -1L
    @Volatile
    private var pendingSeekUntilMs: Long = 0L
    private var mediaFallbackJob: Job? = null
    private var preloadJob: Job? = null
    private val preloadedResolvedMap = java.util.concurrent.ConcurrentHashMap<String, RawResolvedAudioSource>()
    private var plannedNextShuffleIndex: Int? = null
    private val playHistory = ArrayDeque<Int>()
    private const val MAX_HISTORY_SIZE = 50
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val PRELOAD_TIMEOUT_MS = 12_000L
    private const val EXO_PREPARE_TIMEOUT_MS = 20_000L
    private var backendReachedPlayingGeneration = Long.MIN_VALUE
    private var fallbackAttemptedGeneration = Long.MIN_VALUE
    private var activeResolvedSource = null as RawResolvedAudioSource?
    private var activeStartPositionMs = 0L
    private var activeAutoPlay = true

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 保证所有 ExoPlayer 访问（建实例/起播/暂停/恢复/seek/释放）都在 MainLooper 执行，杜绝跨线程异常导致旧音轨残留。 */
    private inline fun runOnMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
    private val media3AudioAttributes = Media3AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** 当前是否由 Media3 ExoPlayer 接管在线歌曲边播边缓存。 */
    fun isMedia3Active(): Boolean = exoPlayer != null && exoPlayerGeneration == generations.get()

    /** 供宿主 PlayerController（通知栏/锁屏/迷你播放器/蓝牙线控）转发暂停动作。 */
    fun pauseExternal() = pauseExoPlayer()

    /** 供宿主 PlayerController（通知栏/锁屏/迷你播放器/蓝牙线控）转发恢复播放动作。 */
    fun resumeExternal() = resumeExoPlayer()

    /** 供宿主在 FFmpeg 即将开播前做终极互斥：强制停掉并释放当前 ExoPlayer，杜绝双引擎同时出声。 */
    fun stopExoPlayerOnly() = releaseExoPlayer()

    @Synchronized
    fun installBackend(newBackend: MusicSourcePlaybackBackend) {
        if (backend === newBackend) return
        backendStateJob?.cancel()
        backend = newBackend
        backendStateJob = scope.launch {
            newBackend.snapshot.collectLatest(::onBackendSnapshot)
        }
    }

    @Synchronized
    fun uninstallBackend(expectedBackend: MusicSourcePlaybackBackend) {
        if (backend !== expectedBackend) return
        backendStateJob?.cancel()
        backendStateJob = null
        backend = null
        if (exoPlayer == null) prepared = false
    }

    fun play(
        context: Context,
        queue: List<RawSourceMediaItem>,
        index: Int,
    ) {
        applicationContext = context.applicationContext
        val playableQueue = queue.filter { it.mediaType == RawSourceMediaType.Music }
        if (playableQueue.isEmpty()) {
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                status = MusicSourcePlaybackStatus.Error,
                error = "当前结果中没有可播放歌曲",
            )
            return
        }
        val requestedItem = queue.getOrNull(index)
        // Prefer the caller's exact row. Some search platforms return several versions of the
        // same song with an identical remote id; indexOfFirst() would then pick version #1 even
        // when the user tapped version #3, making the player show the wrong metadata/artwork.
        val resolvedIndex = index
            .takeIf {
                it in playableQueue.indices &&
                    playableQueue[it].stableIdentity == requestedItem?.stableIdentity
            }
            ?: requestedItem?.let { selected ->
                playableQueue.indexOfFirst { it.stableIdentity == selected.stableIdentity }
            }?.takeIf { it >= 0 }
            ?: index.coerceIn(playableQueue.indices)
        startQueueIndex(playableQueue, resolvedIndex)
    }

    fun playItem(context: Context, item: RawSourceMediaItem) {
        play(context, listOf(item), 0)
    }

    /**
     * 向当前在线播放队列末尾追加歌曲（用于电台/推荐流静默续播）
     */
    fun appendQueue(newItems: List<RawSourceMediaItem>) {
        if (newItems.isEmpty()) return
        val currentSnap = mutableSnapshot.value
        val currentQueue = currentSnap.queue.toMutableList()
        val currentIds = currentQueue.map { it.stableIdentity }.toSet()
        val toAdd = newItems.filter { it.stableIdentity !in currentIds }
        if (toAdd.isNotEmpty()) {
            currentQueue.addAll(toAdd)
            mutableSnapshot.value = currentSnap.copy(queue = currentQueue)
            backend?.onQueueAppended(toAdd)
            com.rawsmusic.core.common.utils.AppLogger.d("MusicSourcePlayback", "已向播放队列静默追加 " + toAdd.size + " 首歌曲，当前队列共 " + currentQueue.size + " 首")
        }
    }

    /**
     * 用新批次曲目整体替换当前在线播放队列（保留当前播放曲目，位于索引 0）
     */
    fun replaceQueueRetainingCurrent(newItems: List<RawSourceMediaItem>) {
        if (newItems.isEmpty()) return
        val currentSnap = mutableSnapshot.value
        val currentTrack = currentSnap.currentItem
        val newQueue = mutableListOf<RawSourceMediaItem>()
        if (currentTrack != null) {
            newQueue.add(currentTrack)
            val filteredMore = newItems.filter { it.stableIdentity != currentTrack.stableIdentity }
            newQueue.addAll(filteredMore)
        } else {
            newQueue.addAll(newItems)
        }
        val targetIndex = if (newQueue.isNotEmpty()) 0 else -1
        mutableSnapshot.value = currentSnap.copy(
            queue = newQueue,
            currentIndex = targetIndex,
        )
        backend?.onQueueReplaced(newQueue, targetIndex)
        com.rawsmusic.core.common.utils.AppLogger.d("MusicSourcePlayback", "已用新批次替换播放队列，共 ${newQueue.size} 首歌曲，当前播放曲目保留在首位")
    }

    /**
     * 直接使用已通过寻源与探活校验的直链起播，避免重复触发二次解析与模糊检索。
     */
    fun playResolved(
        context: Context,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        queue: List<RawSourceMediaItem> = listOf(item),
        index: Int = 0,
        originalPlatform: String? = null,
        resolvedPlatform: String? = null,
        sourceTag: String? = null,
        autoPlay: Boolean = true,
    ) {
        applicationContext = context.applicationContext
        val generation = generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        // 切歌起播前必须同时卸载旧的 ExoPlayer 与底层 backend 会话，杜绝双引擎同时出声
        releaseExoPlayer()
        backend?.stop()
        val activeQueue = if (queue.isNotEmpty()) {
            queue
        } else {
            val currentQueue = mutableSnapshot.value.queue
            if (currentQueue.isNotEmpty()) {
                val matchIndex = currentQueue.indexOfFirst {
                    it.stableIdentity == item.stableIdentity ||
                        (it.title.equals(item.title, ignoreCase = true) &&
                            it.artists == item.artists)
                }
                if (matchIndex >= 0) {
                    currentQueue.toMutableList().apply { set(matchIndex, item) }
                } else {
                    listOf(item)
                }
            } else {
                listOf(item)
            }
        }
        // Same duplicate-identity trap as play(): the clicked queue position must win over the
        // first identical stableIdentity, otherwise currentItem is the clicked version but the
        // player/mini-player bind their metadata and artwork to queue[0] (the first result).
        val activeIndex = index
            .takeIf {
                it in activeQueue.indices &&
                    activeQueue[it].stableIdentity == item.stableIdentity
            }
            ?: activeQueue.indexOfFirst { it.stableIdentity == item.stableIdentity }
                .takeIf { it >= 0 }
            ?: if (index in activeQueue.indices) index else 0
        val origPlatform = resolvePlatformDisplayName(originalPlatform ?: item.sourceId)
        val resPlatform = resolvePlatformDisplayName(resolvedPlatform ?: source.platform ?: origPlatform)
        val tag = sourceTag ?: source.sourceName ?: "在线音源"
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            queue = activeQueue,
            currentIndex = activeIndex,
            currentItem = item,
            status = MusicSourcePlaybackStatus.Preparing,
            requestedQuality = source.quality,
            resolvedQuality = source.quality,
            originalPlatform = origPlatform,
            resolvedPlatform = resPlatform,
            sourceTag = tag,
            durationMs = item.durationMs,
        )
        prepareResolved(
            generation = generation,
            item = item,
            source = source,
            startPositionMs = 0L,
            autoPlay = autoPlay,
        )
    }

    /** Persists quality and re-resolves the current item without losing position. */
    fun setPreferredQuality(quality: RawSourceQuality) {
        AppPreferences.storage.encode(PREFERRED_QUALITY_KEY, quality.name)
        val orbitKey = when (quality) {
            RawSourceQuality.HiRes -> "flac24bit"
            RawSourceQuality.Lossless -> "flac"
            RawSourceQuality.Super, RawSourceQuality.High -> "320k"
            RawSourceQuality.Standard -> "128k"
        }
        AppPreferences.Source.preferredQuality = orbitKey
        val state = mutableSnapshot.value
        if (state.currentItem == null || state.currentIndex !in state.queue.indices) return
        if (state.requestedQuality == quality && state.status != MusicSourcePlaybackStatus.Error) return

        val resumePosition = currentPosition().takeIf { it > 0L } ?: state.positionMs
        val shouldAutoPlay = state.isPlaying || state.isBusy
        startQueueIndex(
            queue = state.queue,
            index = state.currentIndex,
            requestedQualityOverride = quality,
            startPositionMs = resumePosition,
            autoPlay = shouldAutoPlay,
        )
    }

    fun preferredQuality(): RawSourceQuality = loadPreferredQuality()

    fun playPause(context: Context? = applicationContext) {
        context?.let { applicationContext = it.applicationContext }
        when (mutableSnapshot.value.status) {
            MusicSourcePlaybackStatus.Playing -> pauseInternal()
            MusicSourcePlaybackStatus.Paused -> resumeInternal()
            MusicSourcePlaybackStatus.Completed,
            MusicSourcePlaybackStatus.Error -> replayCurrent()
            MusicSourcePlaybackStatus.Idle,
            MusicSourcePlaybackStatus.Resolving,
            MusicSourcePlaybackStatus.Preparing -> Unit
        }
    }

    fun calculateNextIndex(
        queue: List<RawSourceMediaItem>,
        currentIndex: Int,
        automatic: Boolean = false,
    ): Int {
        if (queue.isEmpty()) return -1
        if (queue.size == 1) return 0

        val isShuffle = AppPreferences.Player.isShuffle
        val playMode = AppPreferences.Player.playMode

        if (playMode == PlayMode.REPEAT_ONE && automatic) {
            return currentIndex
        }

        if (isShuffle || playMode == PlayMode.SHUFFLE_ALL || playMode == PlayMode.SHUFFLE_ONCE) {
            val planned = plannedNextShuffleIndex
            if (planned != null && planned in queue.indices && planned != currentIndex) {
                return planned
            }
            return pickNextShuffleIndex(queue.size, currentIndex)
        }

        return (currentIndex + 1) % queue.size
    }

    private fun pickNextShuffleIndex(queueSize: Int, currentIndex: Int): Int {
        if (queueSize <= 1) return 0
        val candidates = (0 until queueSize).filter { it != currentIndex }
        return if (candidates.isNotEmpty()) candidates.random() else 0
    }

    fun next(automatic: Boolean = false) {
        val state = mutableSnapshot.value
        val targetIndex = calculateNextIndex(state.queue, state.currentIndex, automatic)
        plannedNextShuffleIndex = null
        if (targetIndex in state.queue.indices) {
            startQueueIndex(state.queue, targetIndex)
        }
    }

    fun previous() {
        val state = mutableSnapshot.value
        if (state.positionMs > 5_000L && prepared) {
            seekTo(0L)
            return
        }
        while (playHistory.isNotEmpty()) {
            val prevIndex = playHistory.removeLast()
            if (prevIndex in state.queue.indices && prevIndex != state.currentIndex) {
                plannedNextShuffleIndex = null
                startQueueIndex(state.queue, prevIndex)
                return
            }
        }
        val prevIndex = if (state.queue.isNotEmpty()) {
            (state.currentIndex - 1 + state.queue.size) % state.queue.size
        } else -1
        if (prevIndex in state.queue.indices) {
            plannedNextShuffleIndex = null
            startQueueIndex(state.queue, prevIndex)
        } else if (prepared) {
            seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        if (!prepared && !isMedia3Active()) return
        val maxDur = mutableSnapshot.value.durationMs.coerceAtLeast(0L)
        val target = if (maxDur > 0L) positionMs.coerceIn(0L, maxDur) else positionMs.coerceAtLeast(0L)
        if (isMedia3Active()) {
            pendingSeekTargetMs = target
            pendingSeekUntilMs = SystemClock.elapsedRealtime() + 1_500L
            runOnMainThread {
                val player = exoPlayer ?: return@runOnMainThread
                runCatching { player.seekTo(target) }.onFailure {
                    pendingSeekTargetMs = -1L
                    AppLogger.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} EXO_SEEK_FAIL generation=$exoPlayerGeneration targetMs=$target", it)
                }
            }
        } else {
            backend?.seekTo(target)
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = target)
    }

    /**
     * 在线列表主动切换新歌曲、进入异步寻源前立即卸载上一首音频，避免寻源期间旧歌残留或与新歌重叠播放。
     * 保留队列与预加载映射（避免误删刚命中的下一首预加载），仅停止当前发声引擎与进行中的解析/缓存任务。
     */
    fun stopForSwitch() {
        generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        releaseExoPlayer()
        backend?.stop()
    }

    fun stop() {
        generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        preloadedResolvedMap.clear()
        plannedNextShuffleIndex = null
        playHistory.clear()
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        backend?.stop()
        releaseExoPlayer()
        mutableSnapshot.value = MusicSourcePlaybackSnapshot()
    }

    /**
     * 当本地歌曲开始播放或切歌时调用此方法。
     * 立即取消所有正在进行的异步网络寻源、文件缓存与预加载协程，
     * 并将快照状态重置为 Idle（isBusy = false），阻断任何网络寻源状态对本地歌曲 UI 的污染。
     * 注意：不调用 backend?.stop()，避免打断当前正准备起播的本地音频引擎。
     */
    fun resetForLocalPlayback() {
        generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        preloadedResolvedMap.clear()
        plannedNextShuffleIndex = null
        playHistory.clear()
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        releaseExoPlayer()
        mutableSnapshot.value = MusicSourcePlaybackSnapshot()
    }

    private fun replayCurrent() {
        val state = mutableSnapshot.value
        if (state.currentIndex in state.queue.indices) {
            startQueueIndex(state.queue, state.currentIndex)
        }
    }

    private fun startQueueIndex(
        queue: List<RawSourceMediaItem>,
        index: Int,
        requestedQualityOverride: RawSourceQuality? = null,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
    ) {
        val context = applicationContext ?: return
        if (index !in queue.indices) return
        val item = queue[index]

        val currentState = mutableSnapshot.value
        if (currentState.currentIndex in currentState.queue.indices && currentState.currentIndex != index) {
            if (playHistory.peekLast() != currentState.currentIndex) {
                playHistory.addLast(currentState.currentIndex)
                if (playHistory.size > MAX_HISTORY_SIZE) {
                    playHistory.removeFirst()
                }
            }
        }
        plannedNextShuffleIndex = null

        val generation = generations.incrementAndGet()
        resolveJob?.cancel()
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        preloadJob?.cancel()
        preloadJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        releaseExoPlayer()
        backend?.stop()

        val origPlatform = resolvePlatformDisplayName(item.sourceId)

        // 1. 宽容多级检索预加载映射（精确 stableIdentity -> remoteId 后缀 -> title+artist 元数据）
        val preloadedSource = findAndConsumePreloadedSource(item)
        if (preloadedSource != null) {
            val resPlatform = resolvePlatformDisplayName(preloadedSource.platform ?: item.sourceId)
            AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} PRELOAD_HIT title=${item.title} quality=${preloadedSource.quality} platform=$resPlatform")
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                queue = queue,
                currentIndex = index,
                currentItem = item,
                status = MusicSourcePlaybackStatus.Preparing,
                requestedQuality = preloadedSource.quality,
                resolvedQuality = preloadedSource.quality,
                originalPlatform = origPlatform,
                resolvedPlatform = resPlatform,
                sourceTag = preloadedSource.sourceName ?: "预加载直链",
                durationMs = item.durationMs,
            )
            prepareResolved(
                generation = generation,
                item = item,
                source = preloadedSource,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = autoPlay,
            )
            return
        }

        val targetPreferredQuality = requestedQualityOverride ?: loadPreferredQuality()

        // 2. 同步探测内存 LRU 直链缓存 (0ms 秒开起播，杜绝切歌时闪现 Resolving)
        val memoryHit = MusicSourceAudioResolver.peekMemoryCache(item, targetPreferredQuality)
        if (memoryHit != null) {
            val resPlatform = resolvePlatformDisplayName(memoryHit.platform ?: item.sourceId)
            AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} MEMORY_PEEK_HIT title=${item.title} quality=${memoryHit.quality} platform=$resPlatform")
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                queue = queue,
                currentIndex = index,
                currentItem = item,
                status = MusicSourcePlaybackStatus.Preparing,
                requestedQuality = memoryHit.quality,
                resolvedQuality = memoryHit.quality,
                originalPlatform = origPlatform,
                resolvedPlatform = resPlatform,
                sourceTag = memoryHit.sourceName ?: "内存直链",
                durationMs = item.durationMs,
            )
            prepareResolved(
                generation = generation,
                item = item,
                source = memoryHit,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = autoPlay,
            )
            return
        }

        // 3. 对齐 OrBitPlayer 核心机制：检查本地磁盘是否已存在有效缓存文件 (0ms 秒开起播)
        val cachedHit = OnlineAudioFileCache.findValidCachedFile(
            context = context,
            item = item,
            preferredQuality = targetPreferredQuality,
        )
        if (cachedHit != null) {
            val (cachedFile, cachedQuality) = cachedHit
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} CACHE_DIRECT_HIT title=${item.title} " +
                    "quality=$cachedQuality bytes=${cachedFile.length()} file=${cachedFile.name}"
            )
            val localResolved = RawResolvedAudioSource(
                url = cachedFile.absolutePath,
                quality = cachedQuality,
                headers = emptyMap(),
                platform = origPlatform,
                sourceName = "本地缓存",
            )
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                queue = queue,
                currentIndex = index,
                currentItem = item,
                status = MusicSourcePlaybackStatus.Preparing,
                requestedQuality = cachedQuality,
                resolvedQuality = cachedQuality,
                originalPlatform = origPlatform,
                resolvedPlatform = origPlatform,
                sourceTag = "本地缓存",
                durationMs = item.durationMs,
            )
            prepareResolved(
                generation = generation,
                item = item,
                source = localResolved,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                autoPlay = autoPlay,
            )
            return
        }

        val requestedQuality = MusicSourceAudioResolver.preferredQuality(
            item = item,
            preferred = targetPreferredQuality,
        )
        // 初始设为 Preparing（正在缓冲），让普通切歌展现为平滑缓冲；若后台网络寻源耗时超过 800ms，再显示“正在寻源...”
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            queue = queue,
            currentIndex = index,
            currentItem = item,
            status = MusicSourcePlaybackStatus.Preparing,
            requestedQuality = requestedQuality,
            resolvedQuality = requestedQuality,
            originalPlatform = origPlatform,
            resolvedPlatform = origPlatform,
            sourceTag = "正在寻源",
            durationMs = item.durationMs,
        )

        resolveJob = scope.launch {
            val delayResolvingNotification = launch {
                delay(800L)
                if (isActive && generation == generations.get() &&
                    mutableSnapshot.value.status == MusicSourcePlaybackStatus.Preparing
                ) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        status = MusicSourcePlaybackStatus.Resolving
                    )
                }
            }
            try {
                val resolved = withTimeout(RESOLVE_TIMEOUT_MS) {
                    MusicSourceAudioResolver.resolve(context, item, requestedQuality)
                }
                delayResolvingNotification.cancel()
                if (generation != generations.get()) return@launch
                prepareResolved(
                    generation = generation,
                    item = item,
                    source = resolved,
                    startPositionMs = startPositionMs.coerceAtLeast(0L),
                    autoPlay = autoPlay,
                )
            } catch (timeout: TimeoutCancellationException) {
                delayResolvingNotification.cancel()
                if (generation != generations.get()) return@launch
                AppLogger.w(TAG, "ONLINE_RESOLVE_TIMEOUT for ${item.title} after ${RESOLVE_TIMEOUT_MS}ms")
                fail("网络寻源超时，请尝试更换音源或稍后重试")
            } catch (cancelled: CancellationException) {
                delayResolvingNotification.cancel()
                throw cancelled
            } catch (error: Throwable) {
                delayResolvingNotification.cancel()
                if (generation != generations.get()) return@launch
                fail(error.message.orEmpty().ifBlank { "在线播放失败" }.take(1_024))
            }
        }
    }

    fun resolvePlatformDisplayName(rawPlatformOrSourceId: String?): String {
        if (rawPlatformOrSourceId.isNullOrBlank()) return "在线音源"
        val p = rawPlatformOrSourceId.trim().lowercase()
        return when {
            p == "kuwo_car" || p.contains("酷我车载") -> "酷我车载"
            p == "netease" || p == "wy" || p.contains("163") || p.contains("网易") -> "网易云音乐"
            p == "qq" || p == "tx" || p.contains("tencent") || p.contains("qq") -> "QQ音乐"
            p == "kugou" || p == "kg" || p.contains("酷狗") -> "酷狗音乐"
            p == "kuwo" || p == "kw" || p.contains("酷我") -> "酷我音乐"
            p == "migu" || p == "mg" || p.contains("咪咕") -> "咪咕音乐"
            else -> rawPlatformOrSourceId
        }
    }

    private fun findAndConsumePreloadedSource(item: RawSourceMediaItem): RawResolvedAudioSource? {
        // 1. 精确匹配 stableIdentity
        preloadedResolvedMap.remove(item.stableIdentity)?.let { return it }
        // 2. 匹配以 remoteId 结尾的 key
        val remoteIdMatch = preloadedResolvedMap.keys().toList().firstOrNull { key ->
            key.endsWith("/${item.remoteId}") || key.substringAfterLast('/') == item.remoteId
        }
        if (remoteIdMatch != null) {
            preloadedResolvedMap.remove(remoteIdMatch)?.let { return it }
        }
        // 3. 基于曲目名称和歌手兜底匹配
        val queue = mutableSnapshot.value.queue
        val itemTitle = item.title.trim()
        val itemArtist = item.artists.firstOrNull()?.trim() ?: ""
        val metaMatch = preloadedResolvedMap.keys().toList().firstOrNull { key ->
            val matchedInQueue = queue.firstOrNull { it.stableIdentity == key }
            matchedInQueue != null &&
                matchedInQueue.title.equals(itemTitle, ignoreCase = true) &&
                (itemArtist.isBlank() || matchedInQueue.artists.contains(itemArtist))
        }
        if (metaMatch != null) {
            preloadedResolvedMap.remove(metaMatch)?.let { return it }
        }
        return null
    }

    /**
     * 动态开关下一首歌曲预加载：
     * - 关闭时立即取消后台预加载任务并清理未消费的预加载直链；
     * - 开启时若当前处于在线播放态且队列有后续曲目，立即触发下一首预加载。
     */
    fun setPreloadEnabled(enabled: Boolean) {
        AppPreferences.Source.preloadNextSongEnabled = enabled
        if (!enabled) {
            preloadJob?.cancel()
            preloadJob = null
            preloadedResolvedMap.clear()
            AppLogger.i(TAG, "Song preload disabled; active preload job canceled")
        } else {
            AppLogger.i(TAG, "Song preload enabled")
            val snap = mutableSnapshot.value
            if (snap.status == MusicSourcePlaybackStatus.Playing || snap.status == MusicSourcePlaybackStatus.Paused) {
                schedulePreloadNext()
            }
        }
    }

    private fun schedulePreloadNext() {
        if (!AppPreferences.Source.preloadNextSongEnabled) {
            preloadJob?.cancel()
            preloadJob = null
            return
        }
        if (preloadJob?.isActive == true) {
            // 已有预加载任务正在后台平稳寻源或下载，避免被打断
            return
        }
        val context = applicationContext ?: return
        val state = mutableSnapshot.value
        val queue = state.queue
        if (queue.isEmpty() || state.currentIndex !in queue.indices) return

        val isShuffle = AppPreferences.Player.isShuffle
        val playMode = AppPreferences.Player.playMode

        if (playMode == PlayMode.REPEAT_ONE) {
            // 单曲循环无需跨曲目预加载
            return
        }

        // 优先对齐宿主 PlayerController 规划的下一首（包含确定性的随机洗牌序列与队列策略）
        val backendNext = backend?.peekNextQueueIndex()
        val nextIndex = if (backendNext != null && backendNext in queue.indices && backendNext != state.currentIndex) {
            plannedNextShuffleIndex = backendNext
            backendNext
        } else if (isShuffle || playMode == PlayMode.SHUFFLE_ALL || playMode == PlayMode.SHUFFLE_ONCE) {
            val next = pickNextShuffleIndex(queue.size, state.currentIndex)
            plannedNextShuffleIndex = next
            next
        } else {
            plannedNextShuffleIndex = null
            if (queue.size > 1) (state.currentIndex + 1) % queue.size else -1
        }

        if (nextIndex !in queue.indices || nextIndex == state.currentIndex) return
        val nextItem = queue[nextIndex]
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} SCHEDULE_PRELOAD started. currentIndex=${state.currentIndex} " +
                "nextIndex=$nextIndex nextTitle=${nextItem.title} nextSourceId=${nextItem.sourceId}"
        )

        // 若下一首在本地磁盘已拥有有效缓存，直接装载到 preloadedResolvedMap 并跳过网络预热
        val nextCached = OnlineAudioFileCache.findValidCachedFile(context, nextItem)
        if (nextCached != null) {
            val (cachedFile, cachedQuality) = nextCached
            val origPlatform = resolvePlatformDisplayName(nextItem.sourceId)
            preloadedResolvedMap[nextItem.stableIdentity] = RawResolvedAudioSource(
                url = cachedFile.absolutePath,
                quality = cachedQuality,
                headers = emptyMap(),
                platform = origPlatform,
                sourceName = "本地缓存",
            )
            AppLogger.d(TAG, "下一首【${nextItem.title}】已在本地完整缓存(${cachedQuality})，跳过网络预热")
            return
        }

        preloadJob = scope.launch(Dispatchers.IO) {
            delay(500L) // 缩短起播后等待延迟，让下一首更早进入预解析
            if (!isActive) return@launch
            try {
                val preferredQuality = MusicSourceAudioResolver.preferredQuality(
                    item = nextItem,
                    preferred = loadPreferredQuality(),
                )
                val resolved = preloadedResolvedMap[nextItem.stableIdentity]?.takeIf {
                    !it.url.startsWith("http", ignoreCase = true)
                } ?: withTimeoutOrNull(PRELOAD_TIMEOUT_MS) {
                    MusicSourceAudioResolver.resolve(context, nextItem, preferredQuality)
                } ?: return@launch

                AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} PRELOAD_RESOLVED nextTitle=${nextItem.title} url=${OnlinePlaybackDiagnostics.safeUrl(resolved.url)}")

                // 直链解析成功即写入预加载映射，切歌时 0ms 跳过寻源，直接交给 Media3 边播边缓存秒开（不再后台整曲全量下载）
                val platformName = resolved.platform ?: resolvePlatformDisplayName(nextItem.sourceId)
                val resolvedWithPlatform = resolved.copy(platform = platformName)
                preloadedResolvedMap[nextItem.stableIdentity] = resolvedWithPlatform
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                AppLogger.w(TAG, "预加载下一首【${nextItem.title}】失败: ${e.message}")
            }
        }
    }

    private fun prepareResolved(
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        activeResolvedSource = source
        activeStartPositionMs = startPositionMs
        activeAutoPlay = autoPlay
        val currentSnapshot = mutableSnapshot.value
        val origPlatform = currentSnapshot.originalPlatform ?: resolvePlatformDisplayName(item.sourceId)
        val resPlatform = resolvePlatformDisplayName(source.platform ?: currentSnapshot.resolvedPlatform ?: origPlatform)
        val tag = source.sourceName ?: currentSnapshot.sourceTag ?: "官方直链"
        mutableSnapshot.value = currentSnapshot.copy(
            status = MusicSourcePlaybackStatus.Preparing,
            resolvedQuality = source.quality,
            originalPlatform = origPlatform,
            resolvedPlatform = resPlatform,
            sourceTag = tag,
            error = "",
            positionMs = startPositionMs,
            bufferedPercent = 0,
        )

        val isHttpSource = source.url.startsWith("http://", ignoreCase = true) ||
            source.url.startsWith("https://", ignoreCase = true)

        // 本地文件路径继续走宿主 FFmpeg 管线（保留 USB DAC 独占、Native DSP/EQ、DSD）；
        // 在线 HTTP(S) 直链统一走 Media3 ExoPlayer + SimpleCache 实现首包秒开与真正边播边缓存。
        if (!isHttpSource) {
            val activeBackend = backend
            if (activeBackend != null) {
                playBackend(
                    backend = activeBackend,
                    generation = generation,
                    item = item,
                    source = source,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
                return
            }
        }

        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }

        // 若本地磁盘已有历史完整离线缓存（如旧版缓存），依然可以直接秒开（并保留酷我时长核验）
        if (isHttpSource) {
            val cachedHit = OnlineAudioFileCache.findValidCachedFile(context, item, source.quality)
            val activeBackend = backend
            if (cachedHit != null && activeBackend != null) {
                val (cachedFile, _) = cachedHit
                if (isKuwoCarSource(source)) {
                    val actualDurMs = probeLocalAudioDurationMs(cachedFile)
                    if (isDurationMismatched(actualDurMs, item.durationMs)) {
                        AppLogger.w(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} KUWO_DURATION_MISMATCH_CACHE actual=${actualDurMs}ms " +
                                "expected=${item.durationMs}ms title=${item.title}, deleting cache & falling back to origin"
                        )
                        runCatching { cachedFile.delete() }
                        fallbackFromKuwoMismatch(
                            context = context,
                            generation = generation,
                            item = item,
                            requestedQuality = source.quality,
                            startPositionMs = startPositionMs,
                            autoPlay = autoPlay,
                        )
                        return
                    }
                }
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_DIRECT_HIT bytes=${cachedFile.length()} " +
                        "file=${cachedFile.name} title=${item.title}"
                )
                val localSource = source.copy(
                    url = cachedFile.absolutePath,
                    headers = emptyMap(),
                    userAgent = null,
                    expiresAtMs = null,
                )
                playBackend(
                    backend = activeBackend,
                    generation = generation,
                    item = item,
                    source = localSource,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
                return
            }
        }

        cacheJob?.cancel()
        cacheJob = null
        prepareExoPlayer(
            generation = generation,
            item = item,
            source = source,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay,
            reason = if (isHttpSource) "media3_stream_cache" else "media3_local_fallback",
        )
    }

    private fun playBackend(
        backend: MusicSourcePlaybackBackend,
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        if (generation != generations.get()) return
        // 确保交由宿主底层播放前，内部 ExoPlayer 已彻底释放，杜绝双路同时出声
        releaseExoPlayer()
        val state = mutableSnapshot.value
        backend.play(
            MusicSourcePlaybackRequest(
                generation = generation,
                item = item,
                source = source,
                queue = state.queue,
                queueIndex = state.currentIndex,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
            )
        )
        // 🌟 核心保底：只要起播指令已交由底层渲染，立即调度下一首预加载与静默全量缓存物化
        schedulePreloadNext()
    }

    private fun pauseInternal() {
        if (isMedia3Active()) {
            pauseExoPlayer()
        } else if (prepared) {
            backend?.pause()
        }
    }

    private fun resumeInternal() {
        if (isMedia3Active()) {
            resumeExoPlayer()
        } else if (prepared) {
            backend?.resume()
        }
    }

    private fun onBackendSnapshot(backendSnapshot: MusicSourceBackendSnapshot) {
        val generation = generations.get()
        if (backendSnapshot.generation != generation) return
        if (exoPlayerGeneration == generation) return
        val state = mutableSnapshot.value
        if (state.currentItem == null) return

        val duration = backendSnapshot.durationMs.takeIf { it > 0L } ?: state.durationMs
        when (backendSnapshot.status) {
            MusicSourceBackendStatus.Idle -> {
                if (state.status != MusicSourcePlaybackStatus.Error &&
                    state.status != MusicSourcePlaybackStatus.Completed
                ) {
                    prepared = false
                    // 若底层渲染器在 Playing 或 Preparing（非缓存下载阶段）意外停止为 Idle，
                    // 必须同步退出，防止按钮或“正在缓冲”永久卡死。
                    if (state.status == MusicSourcePlaybackStatus.Playing ||
                        (state.status == MusicSourcePlaybackStatus.Preparing && cacheJob?.isActive != true)
                    ) {
                        mutableSnapshot.value = state.copy(
                            status = MusicSourcePlaybackStatus.Idle,
                        )
                    }
                }
            }
            MusicSourceBackendStatus.Preparing -> {
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Preparing,
                    positionMs = backendSnapshot.positionMs.coerceAtLeast(0L),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 0,
                    error = "",
                )
            }
            MusicSourceBackendStatus.Playing -> {
                val isNewPlayingGeneration = backendReachedPlayingGeneration != generation
                backendReachedPlayingGeneration = generation
                prepared = true
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Playing,
                    positionMs = backendSnapshot.positionMs.coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 100,
                    error = "",
                )
                if (isNewPlayingGeneration) {
                    schedulePreloadNext()
                }
            }
            MusicSourceBackendStatus.Paused -> {
                prepared = true
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Paused,
                    positionMs = backendSnapshot.positionMs.coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 100,
                    error = "",
                )
            }
            MusicSourceBackendStatus.Completed -> {
                if (completionHandledGeneration == generation) return
                completionHandledGeneration = generation
                prepared = false
                val completedPos = maxOf(state.positionMs, backendSnapshot.positionMs)
                val completedDur = duration.coerceAtLeast(state.durationMs)
                maybeAutoSaveOnTrackCompleted(
                    item = state.currentItem,
                    positionMs = completedPos,
                    durationMs = completedDur,
                )
                // 通过 PlayerController 宿主播放时，播放完成由 PlayerController 统一自动切歌推进（遵循全局循环/随机模式），
                // 严禁在此处重复触发 next(automatic = true) 导致双跳竞争。
                mutableSnapshot.value = state.copy(
                    status = MusicSourcePlaybackStatus.Completed,
                    positionMs = duration.coerceAtLeast(state.positionMs),
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = 100,
                )
            }
            MusicSourceBackendStatus.Error -> {
                if (fallbackAttemptedGeneration == generation) return
                val source = activeResolvedSource
                val canFallback = source != null &&
                    source.url.startsWith("http", ignoreCase = true) &&
                    backendReachedPlayingGeneration != generation &&
                    fallbackAttemptedGeneration != generation
                if (canFallback) {
                    fallbackAttemptedGeneration = generation
                    AppLogger.w(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_FALLBACK_START generation=$generation " +
                            "reason=${backendSnapshot.error.replace('\n', ' ').replace('\r', ' ').take(256)} " +
                            "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
                    )
                    mediaFallbackJob?.cancel()
                    mediaFallbackJob = scope.launch {
                        backend?.stop()
                        // PlayerController teardown is asynchronous. Give the failed AudioTrack/
                        // decoder session a short window to release audio focus and native output
                        // before the independent MediaPlayer requests the same route.
                        delay(160L)
                        if (generation != generations.get()) return@launch
                        val currentItem = state.currentItem ?: return@launch
                        prepareExoPlayer(
                            generation = generation,
                            item = currentItem,
                            source = source,
                            startPositionMs = activeStartPositionMs,
                            autoPlay = activeAutoPlay,
                            reason = "ffmpeg_open_failed",
                        )
                    }
                } else {
                    prepared = false
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Error,
                        positionMs = backendSnapshot.positionMs.coerceAtLeast(0L),
                        durationMs = duration.coerceAtLeast(0L),
                        error = backendSnapshot.error.ifBlank { "在线播放失败" }.take(1_024),
                    )
                }
            }
        }
    }

    private fun currentPosition(): Long {
        if (isMedia3Active()) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return runCatching { exoPlayer?.currentPosition ?: 0L }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
            }
            return mutableSnapshot.value.positionMs.coerceAtLeast(0L)
        }
        return backend?.snapshot?.value?.positionMs
            ?.coerceAtLeast(0L)
            ?: mutableSnapshot.value.positionMs.coerceAtLeast(0L)
    }

    private fun fail(message: String) {
        prepared = false
        releaseExoPlayer()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourcePlaybackStatus.Error,
            error = message.take(1_024),
        )
    }

    /**
     * 供已持有直链与请求头的远端流（如 WebDAV Hi-Res FLAC）直接接入 Media3 边播边下 + 原生 DSP 均衡器管线，
     * 无需走插件寻源，也不覆盖宿主 PlayerController 的混合播放队列。
     */
    fun playDirectStream(
        context: Context,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        reason: String = "webdav_direct_stream",
    ) {
        applicationContext = context.applicationContext
        val generation = generations.incrementAndGet()
        resolveJob?.cancel()
        resolveJob = null
        cacheJob?.cancel()
        cacheJob = null
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        releaseExoPlayer()
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = source
        activeStartPositionMs = startPositionMs.coerceAtLeast(0L)
        activeAutoPlay = autoPlay
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            status = MusicSourcePlaybackStatus.Preparing,
            queue = listOf(item),
            currentIndex = 0,
            currentItem = item,
            requestedQuality = source.quality,
            resolvedQuality = source.quality,
            originalPlatform = source.platform ?: item.sourceId,
            resolvedPlatform = source.platform ?: item.sourceId,
            sourceTag = reason,
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = item.durationMs.coerceAtLeast(0L),
            bufferedPercent = 0,
            error = "",
        )
        prepareExoPlayer(
            generation = generation,
            item = item,
            source = source,
            startPositionMs = startPositionMs.coerceAtLeast(0L),
            autoPlay = autoPlay,
            reason = reason,
            syncBackend = false,
        )
    }

    @OptIn(UnstableApi::class)
    private fun prepareExoPlayer(
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
        reason: String,
        syncBackend: Boolean = true,
    ) {
        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                prepareExoPlayer(generation, item, source, startPositionMs, autoPlay, reason, syncBackend)
            }
            return
        }
        if (generation != generations.get()) return
        releaseExoPlayer()

        // 通知宿主 PlayerController 同步当前曲目/队列/前台保活，并停止底层 FFmpeg 引擎，杜绝双路发声
        if (syncBackend) {
            val currentState = mutableSnapshot.value
            backend?.syncExternalPlayback(
                MusicSourcePlaybackRequest(
                    generation = generation,
                    item = item,
                    source = source,
                    queue = currentState.queue,
                    queueIndex = currentState.currentIndex,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
            )
        }

        val requestHeaders = LinkedHashMap(source.headers)
        source.userAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { userAgent ->
                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    requestHeaders["User-Agent"] = userAgent
                }
            }

        val cacheKey = OnlineMedia3Cache.cacheKey(item, source.quality)
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} EXO_OPEN_START generation=$generation reason=$reason " +
                "cacheKey=${cacheKey.take(12)} headers=${OnlinePlaybackDiagnostics.headerNames(requestHeaders)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
        )

        try {
            val dataSourceFactory = OnlineMedia3Cache.createDataSourceFactory(
                context = context,
                headers = requestHeaders,
                userAgent = source.userAgent,
            )
            val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
            // 挂载高精度 32-bit Float AudioProcessor，单次直连 RawS 原生 NativeDSPEngine（避免 16-bit 截断与叠跑失真）
            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(OnlineMedia3DspAudioProcessor { externalPcmDspHook }))
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                }
            }
            // 音频焦点由宿主 PlayerService/PlayerController 的 AndroidAudioInterrupt 统一持有与调度；
            // 若 ExoPlayer 再开启 handleAudioFocus=true，会在同进程内与 PlayerService 互抢焦点导致 ExoPlayer 收到 AUDIOFOCUS_LOSS(-1) 秒停。
            val player = ExoPlayer.Builder(context, renderersFactory)
                .setLooper(Looper.getMainLooper())
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(media3AudioAttributes, false)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

            exoPlayer = player
            exoPlayerGeneration = generation
            prepared = false
            var preloadScheduledForGen = false
            var kuwoCheckedForGen = false

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (generation != generations.get() || exoPlayer !== player) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            mutableSnapshot.value = mutableSnapshot.value.copy(
                                bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
                            )
                        }
                        Player.STATE_READY -> {
                            mediaFallbackJob?.cancel()
                            mediaFallbackJob = null
                            val rawDur = player.duration
                            val reportedDuration = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else 0L
                            if (!kuwoCheckedForGen) {
                                kuwoCheckedForGen = true
                                val currentItem = mutableSnapshot.value.currentItem
                                if (currentItem != null &&
                                    isKuwoCarSource(source) &&
                                    isDurationMismatched(reportedDuration, currentItem.durationMs)
                                ) {
                                    AppLogger.w(
                                        TAG,
                                        "${OnlinePlaybackDiagnostics.PREFIX} KUWO_DURATION_MISMATCH_EXO actual=${reportedDuration}ms " +
                                            "expected=${currentItem.durationMs}ms title=${currentItem.title}, evicting cache & falling back to origin"
                                    )
                                    prepared = false
                                    releaseExoPlayer()
                                    OnlineMedia3Cache.evict(context, currentItem, source.quality)
                                    fallbackFromKuwoMismatch(
                                        context = context,
                                        generation = generation,
                                        item = currentItem,
                                        requestedQuality = source.quality,
                                        startPositionMs = startPositionMs,
                                        autoPlay = autoPlay,
                                    )
                                    return
                                }
                            }
                            prepared = true
                            val duration = reportedDuration.takeIf { it > 0L }
                                ?: mutableSnapshot.value.currentItem?.durationMs
                                ?: 0L
                            val nextStatus = if (player.playWhenReady) {
                                MusicSourcePlaybackStatus.Playing
                            } else {
                                MusicSourcePlaybackStatus.Paused
                            }
                            mutableSnapshot.value = mutableSnapshot.value.copy(
                                status = nextStatus,
                                durationMs = duration,
                                positionMs = player.currentPosition.coerceAtLeast(0L),
                                bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
                                error = "",
                            )
                            AppLogger.i(
                                TAG,
                                "${OnlinePlaybackDiagnostics.PREFIX} EXO_READY generation=$generation " +
                                    "durationMs=$duration buffered=${player.bufferedPercentage}% playWhenReady=${player.playWhenReady}"
                            )
                            startExoProgressUpdates(generation)
                            if (!preloadScheduledForGen && player.playWhenReady) {
                                preloadScheduledForGen = true
                                schedulePreloadNext()
                            }
                        }
                        Player.STATE_ENDED -> {
                            if (completionHandledGeneration == generation) return
                            completionHandledGeneration = generation
                            prepared = false
                            exoProgressJob?.cancel()
                            exoProgressJob = null
                            val state = mutableSnapshot.value
                            val completedPos = maxOf(state.positionMs, player.currentPosition.coerceAtLeast(0L))
                            val completedDur = state.durationMs.coerceAtLeast(
                                player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                            )
                            maybeAutoSaveOnTrackCompleted(
                                item = state.currentItem,
                                positionMs = completedPos,
                                durationMs = completedDur,
                            )
                            if (backend != null) {
                                mutableSnapshot.value = state.copy(
                                    status = MusicSourcePlaybackStatus.Completed,
                                    positionMs = completedDur,
                                    durationMs = completedDur,
                                    bufferedPercent = 100,
                                )
                                backend?.onExternalTrackCompleted()
                            } else if (state.canGoNext) {
                                next(automatic = true)
                            } else {
                                mutableSnapshot.value = state.copy(
                                    status = MusicSourcePlaybackStatus.Completed,
                                    positionMs = completedDur,
                                    durationMs = completedDur,
                                    bufferedPercent = 100,
                                )
                            }
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (generation != generations.get() || exoPlayer !== player) return
                    if (!prepared) return
                    val current = mutableSnapshot.value
                    if (current.status == MusicSourcePlaybackStatus.Completed ||
                        current.status == MusicSourcePlaybackStatus.Error
                    ) {
                        return
                    }
                    val targetStatus = if (isPlaying) {
                        MusicSourcePlaybackStatus.Playing
                    } else if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                        MusicSourcePlaybackStatus.Playing
                    } else {
                        MusicSourcePlaybackStatus.Paused
                    }
                    mutableSnapshot.value = current.copy(
                        status = targetStatus,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (generation != generations.get() || exoPlayer !== player) return
                    mediaFallbackJob?.cancel()
                    mediaFallbackJob = null
                    AppLogger.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} EXO_ERROR generation=$generation " +
                            "code=${error.errorCodeName} msg=${error.message}",
                        error,
                    )
                    fail("在线播放器错误：${error.errorCodeName}")
                }
            })

            val mediaItem = MediaItem.Builder()
                .setUri(source.url)
                .setCustomCacheKey(cacheKey)
                .build()
            player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
            player.playWhenReady = autoPlay
            mediaFallbackJob?.cancel()
            mediaFallbackJob = scope.launch {
                delay(EXO_PREPARE_TIMEOUT_MS)
                if (generation != generations.get()) return@launch
                if (!prepared && mutableSnapshot.value.status == MusicSourcePlaybackStatus.Preparing) {
                    AppLogger.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} EXO_PREPARE_TIMEOUT generation=$generation")
                    fail("在线媒体准备超时")
                }
            }
            player.prepare()
        } catch (error: Throwable) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} EXO_OPEN_FAIL generation=$generation " +
                    "message=${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)}",
                error,
            )
            fail(error.message ?: "无法打开在线播放地址")
        }
    }

    private fun pauseExoPlayer() {
        runOnMainThread {
            val player = exoPlayer ?: return@runOnMainThread
            if (exoPlayerGeneration != generations.get()) return@runOnMainThread
            runCatching { player.pause() }.onSuccess {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Paused,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                )
            }
        }
    }

    private fun resumeExoPlayer() {
        runOnMainThread {
            val player = exoPlayer ?: return@runOnMainThread
            if (exoPlayerGeneration != generations.get()) return@runOnMainThread
            runCatching { player.play() }.onSuccess {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Playing,
                    error = "",
                )
                startExoProgressUpdates(generations.get())
            }.onFailure { fail(it.message ?: "无法继续在线播放") }
        }
    }

    private fun startExoProgressUpdates(generation: Long) {
        exoProgressJob?.cancel()
        exoProgressJob = scope.launch {
            while (isActive && generation == generations.get() && exoPlayerGeneration == generation) {
                val player = exoPlayer ?: break
                val state = mutableSnapshot.value
                if (state.currentItem == null) break
                val rawDur = player.duration
                val duration = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else state.durationMs
                val rawPos = player.currentPosition.coerceAtLeast(0L)
                val pos = resolveStableExoPosition(rawPos, state.positionMs)
                mutableSnapshot.value = state.copy(
                    positionMs = if (duration > 0L) pos.coerceAtMost(duration) else pos,
                    durationMs = duration.coerceAtLeast(0L),
                    bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
                )
                delay(250L)
            }
        }
    }

    /** 过滤 ExoPlayer 异步 seek 尚未落地前的滞后旧位置，避免拖动进度条松手瞬间进度条闪回旧位置抽搐。 */
    private fun resolveStableExoPosition(rawPosMs: Long, fallbackPosMs: Long): Long {
        val target = pendingSeekTargetMs
        if (target < 0L) return rawPosMs
        if (kotlin.math.abs(rawPosMs - target) <= 1_500L || SystemClock.elapsedRealtime() >= pendingSeekUntilMs) {
            pendingSeekTargetMs = -1L
            return rawPosMs
        }
        return fallbackPosMs
    }

    private fun releaseExoPlayer() {
        exoProgressJob?.cancel()
        exoProgressJob = null
        pendingSeekTargetMs = -1L
        pendingSeekUntilMs = 0L
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        val playerToRelease = exoPlayer
        exoPlayer = null
        exoPlayerGeneration = Long.MIN_VALUE
        prepared = false
        if (playerToRelease != null) {
            runOnMainThread {
                runCatching { playerToRelease.playWhenReady = false }
                runCatching { playerToRelease.stop() }
                runCatching { playerToRelease.release() }
                    .onFailure { AppLogger.w(TAG, "releaseExoPlayer failed: ${it.message}") }
            }
        }
    }

    /**
     * 歌曲自然播放完成后，若当前播放歌单开启了「播完自动保存（下载）」选项，
     * 则自动通过 [MusicSourceDownloadController] 将该网络歌曲下载保存到与手动下载一致的目录。
     */
    private fun maybeAutoSaveOnTrackCompleted(
        item: RawSourceMediaItem?,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (item == null || item.mediaType != RawSourceMediaType.Music) return
        if (!PlaylistAutoDownloadStore.isEnabledForCurrentPlaylist()) return
        // 避免中途网络断流提前 EOF 触发误下载：需达到 80% 进度或距结尾不足 15 秒（无时长元数据时放行）
        val reachedTail = durationMs <= 0L ||
            positionMs >= (durationMs * 0.8f).toLong() ||
            (durationMs >= 5_000L && positionMs >= durationMs - 15_000L)
        if (!reachedTail) {
            AppLogger.d(TAG, "AutoSave skipped (premature completion): title=${item.title} pos=$positionMs dur=$durationMs")
            return
        }
        val context = applicationContext ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val taskId = MusicSourceDownloadController.enqueueAutoSaveIfNeeded(context, item)
                if (taskId != null) {
                    AppNoticeBus.post(
                        message = "正在自动保存「${item.title}」",
                        icon = AppNoticeIcon.DOWNLOAD,
                    )
                }
            }.onFailure { error ->
                AppLogger.w(TAG, "AutoSave on complete failed for '${item.title}': ${error.message}")
            }
        }
    }

    @Volatile
    private var kuwoMismatchFallbackGeneration: Long = Long.MIN_VALUE

    private fun isKuwoCarSource(source: RawResolvedAudioSource): Boolean =
        source.platform.equals("kuwo_car", ignoreCase = true) ||
            source.sourceName == "酷我车载"

    private fun probeLocalAudioDurationMs(file: java.io.File): Long {
        if (!file.isFile || file.length() <= 0L) return 0L
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isDurationMismatched(actualDurationMs: Long, expectedDurationMs: Long): Boolean {
        if (actualDurationMs <= 0L) return false
        // 典型 30s / 45s / 60s 试听短片段
        if (actualDurationMs <= 62_000L && (expectedDurationMs >= 90_000L || expectedDurationMs <= 0L)) {
            return true
        }
        // 与标签预期时长相差过大（超过 20 秒且超过 15% 误差）
        if (expectedDurationMs >= 30_000L) {
            val diffMs = kotlin.math.abs(actualDurationMs - expectedDurationMs)
            val maxAllowedDiffMs = maxOf(20_000L, (expectedDurationMs * 0.15f).toLong())
            if (diffMs > maxAllowedDiffMs) return true
        }
        return false
    }

    /**
     * 酷我车载返回的音频在落盘/准备阶段被证实为短试听或错版时长时，
     * 驱逐缓存并跳过酷我车载，自动回退至原平台/多源重新寻源播放。
     */
    private fun fallbackFromKuwoMismatch(
        context: Context,
        generation: Long,
        item: RawSourceMediaItem,
        requestedQuality: RawSourceQuality,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
        if (kuwoMismatchFallbackGeneration == generation) {
            fail("酷我返回短音频且原平台回退已尝试过")
            return
        }
        kuwoMismatchFallbackGeneration = generation
        MusicSourceAudioResolver.evictCache(item)
        resolveJob?.cancel()
        resolveJob = scope.launch {
            try {
                if (generation != generations.get()) return@launch
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Resolving,
                    bufferedPercent = 0,
                    error = "",
                )
                val fallbackResolved = withTimeout(RESOLVE_TIMEOUT_MS) {
                    MusicSourceAudioResolver.resolve(
                        context = context,
                        item = item,
                        requestedQuality = requestedQuality,
                        skipKuwoCar = true,
                    )
                }
                if (generation != generations.get()) return@launch
                val resPlatform = resolvePlatformDisplayName(fallbackResolved.platform ?: item.sourceId)
                val tag = fallbackResolved.sourceName ?: "原平台"
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Preparing,
                    resolvedQuality = fallbackResolved.quality,
                    resolvedPlatform = resPlatform,
                    sourceTag = tag,
                    error = "",
                )
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} KUWO_MISMATCH_FALLBACK_OK title=${item.title} " +
                        "platform=$resPlatform tag=$tag quality=${fallbackResolved.quality}"
                )
                prepareResolved(
                    generation = generation,
                    item = item,
                    source = fallbackResolved,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                AppLogger.e(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} KUWO_MISMATCH_FALLBACK_FAIL title=${item.title} " +
                        "error=${OnlinePlaybackDiagnostics.errorSummary(error)}",
                    error,
                )
                fail(error.message.orEmpty().ifBlank { "原平台寻源失败" })
            }
        }
    }

    private fun loadPreferredQuality(): RawSourceQuality = runCatching {
        com.rawsmusic.module.data.source.kuwo.KuwoCarAudioEngine.qualityFromSourcePreference()
    }.getOrDefault(RawSourceQuality.Standard)
}
