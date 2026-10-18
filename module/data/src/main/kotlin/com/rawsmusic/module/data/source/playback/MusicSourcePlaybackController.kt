package com.rawsmusic.module.data.source.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.common.source.RawResolvedAudioSource
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        get() = queue.isNotEmpty() && (queue.size > 1 || status == MusicSourcePlaybackStatus.Playing)

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

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerGeneration = Long.MIN_VALUE
    private var mediaPlayerProgressJob: Job? = null
    private var mediaFallbackJob: Job? = null
    private var preloadJob: Job? = null
    private val preloadedResolvedMap = java.util.concurrent.ConcurrentHashMap<String, RawResolvedAudioSource>()
    private var plannedNextShuffleIndex: Int? = null
    private val playHistory = ArrayDeque<Int>()
    private const val MAX_HISTORY_SIZE = 50
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var backendReachedPlayingGeneration = Long.MIN_VALUE
    private var fallbackAttemptedGeneration = Long.MIN_VALUE
    private var activeResolvedSource = null as RawResolvedAudioSource?
    private var activeStartPositionMs = 0L
    private var activeAutoPlay = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    mediaPlayer?.setVolume(1f, 1f)
                    if (resumeOnFocusGain && isMediaPlayerPrepared()) {
                        resumeOnFocusGain = false
                        resumeMediaPlayer()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    mediaPlayer?.setVolume(0.22f, 0.22f)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnFocusGain = mutableSnapshot.value.isPlaying
                    pauseMediaPlayer()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocusGain = false
                    pauseMediaPlayer()
                    abandonAudioFocus()
                }
            }
        }
    }

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
        if (mediaPlayer == null) prepared = false
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
        val resolvedIndex = requestedItem?.let { selected ->
            playableQueue.indexOfFirst { it.stableIdentity == selected.stableIdentity }
        }?.takeIf { it >= 0 } ?: index.coerceIn(playableQueue.indices)
        startQueueIndex(playableQueue, resolvedIndex)
    }

    fun playItem(context: Context, item: RawSourceMediaItem) {
        play(context, listOf(item), 0)
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
        mediaFallbackJob?.cancel()
        mediaFallbackJob = null
        cacheJob?.cancel()
        cacheJob = null
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        backend?.stop()
        val activeQueue = if (queue.isNotEmpty() && queue.size > 1) {
            queue
        } else {
            val currentQueue = mutableSnapshot.value.queue
            if (currentQueue.size > 1) {
                val matchIndex = currentQueue.indexOfFirst {
                    it.stableIdentity == item.stableIdentity ||
                        (it.title.equals(item.title, ignoreCase = true) &&
                            it.artists == item.artists)
                }
                if (matchIndex >= 0) {
                    currentQueue.toMutableList().apply { set(matchIndex, item) }
                } else if (queue.isNotEmpty()) {
                    queue
                } else {
                    listOf(item)
                }
            } else if (queue.isNotEmpty()) {
                queue
            } else {
                listOf(item)
            }
        }
        val activeIndex = activeQueue.indexOfFirst { it.stableIdentity == item.stableIdentity }
            .takeIf { it >= 0 }
            ?: if (index in activeQueue.indices) index else 0
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            queue = activeQueue,
            currentIndex = activeIndex,
            currentItem = item,
            status = MusicSourcePlaybackStatus.Resolving,
            requestedQuality = source.quality,
            resolvedQuality = source.quality,
            originalPlatform = originalPlatform,
            resolvedPlatform = resolvedPlatform,
            sourceTag = sourceTag,
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
        if (!prepared) return
        val target = positionMs.coerceIn(0L, mutableSnapshot.value.durationMs.coerceAtLeast(0L))
        if (isMediaPlayerPrepared()) {
            val player = mediaPlayer ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                } else {
                    @Suppress("DEPRECATION")
                    player.seekTo(target.toInt())
                }
            }.onFailure {
                AppLogger.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_SEEK_FAIL generation=$mediaPlayerGeneration targetMs=$target", it)
            }
        } else {
            backend?.seekTo(target)
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = target)
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
        releaseMediaPlayer(abandonFocus = true)
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
        releaseMediaPlayer(abandonFocus = true)
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
        prepared = false
        completionHandledGeneration = Long.MIN_VALUE
        backendReachedPlayingGeneration = Long.MIN_VALUE
        fallbackAttemptedGeneration = Long.MIN_VALUE
        activeResolvedSource = null
        backend?.stop()
        releaseMediaPlayer(abandonFocus = false)
        val preloadedSource = preloadedResolvedMap.remove(item.stableIdentity)
        if (preloadedSource != null) {
            AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} PRELOAD_HIT title=${item.title} quality=${preloadedSource.quality}")
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                queue = queue,
                currentIndex = index,
                currentItem = item,
                status = MusicSourcePlaybackStatus.Preparing,
                requestedQuality = preloadedSource.quality,
                resolvedQuality = preloadedSource.quality,
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
        // 对齐 OrBitPlayer 核心机制：检查本地磁盘是否已存在有效缓存文件
        // 若已命中完整缓存，直接以离线本地文件起播，跳过 Resolving（正在寻源...）阶段，实现 0 延时离线秒开！
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
            )
            mutableSnapshot.value = MusicSourcePlaybackSnapshot(
                queue = queue,
                currentIndex = index,
                currentItem = item,
                status = MusicSourcePlaybackStatus.Preparing,
                requestedQuality = cachedQuality,
                resolvedQuality = cachedQuality,
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
        mutableSnapshot.value = MusicSourcePlaybackSnapshot(
            queue = queue,
            currentIndex = index,
            currentItem = item,
            status = MusicSourcePlaybackStatus.Resolving,
            requestedQuality = requestedQuality,
            resolvedQuality = requestedQuality,
            durationMs = item.durationMs,
        )

        resolveJob = scope.launch {
            try {
                val resolved = MusicSourceAudioResolver.resolve(context, item, requestedQuality)
                if (generation != generations.get()) return@launch
                prepareResolved(
                    generation = generation,
                    item = item,
                    source = resolved,
                    startPositionMs = startPositionMs.coerceAtLeast(0L),
                    autoPlay = autoPlay,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                fail(error.message.orEmpty().ifBlank { "在线播放失败" }.take(1_024))
            }
        }
    }

    private fun schedulePreloadNext() {
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

        // 若下一首在本地磁盘已拥有有效缓存，直接装载到 preloadedResolvedMap 并跳过网络预热
        val nextCached = OnlineAudioFileCache.findValidCachedFile(context, nextItem)
        if (nextCached != null) {
            val (cachedFile, cachedQuality) = nextCached
            preloadedResolvedMap[nextItem.stableIdentity] = RawResolvedAudioSource(
                url = cachedFile.absolutePath,
                quality = cachedQuality,
                headers = emptyMap(),
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
                } ?: MusicSourceAudioResolver.resolve(context, nextItem, preferredQuality)

                AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} PRELOAD_RESOLVED nextTitle=${nextItem.title} url=${OnlinePlaybackDiagnostics.safeUrl(resolved.url)}")

                // 🚀 核心对齐 OrBitPlayer 机制：拿到直链后立即在后台静默物化至本地磁盘缓存！
                // 不仅规避防盗链 Token/时间戳在数分钟后过期失效（403 Forbidden），更让切歌瞬间从本地磁盘 0 延迟秒开！
                if (resolved.url.startsWith("http://", ignoreCase = true) || resolved.url.startsWith("https://", ignoreCase = true)) {
                    val materializedResult = OnlineAudioFileCache.materialize(
                        context = context,
                        item = nextItem,
                        source = resolved,
                    ) { _, _, _ -> }
                    val localSource = resolved.copy(
                        url = materializedResult.file.absolutePath,
                        headers = emptyMap(),
                        userAgent = null,
                        expiresAtMs = null,
                    )
                    preloadedResolvedMap[nextItem.stableIdentity] = localSource
                    AppLogger.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} PRELOAD_MATERIALIZED nextTitle=${nextItem.title} " +
                            "bytes=${materializedResult.bytes} file=${materializedResult.file.name}"
                    )
                } else {
                    preloadedResolvedMap[nextItem.stableIdentity] = resolved
                }
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
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourcePlaybackStatus.Preparing,
            resolvedQuality = source.quality,
            error = "",
            positionMs = startPositionMs,
            bufferedPercent = 0,
        )

        val activeBackend = backend
        if (activeBackend == null) {
            prepareMediaPlayer(
                generation = generation,
                source = source,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                reason = "backend_unavailable",
            )
            return
        }

        if (!source.url.startsWith("http://", ignoreCase = true) &&
            !source.url.startsWith("https://", ignoreCase = true)
        ) {
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

        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }
        cacheJob?.cancel()
        cacheJob = scope.launch {
            try {
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_START generation=$generation " +
                        "quality=${source.quality} url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
                )
                val cached = OnlineAudioFileCache.materialize(
                    context = context,
                    item = item,
                    source = source,
                ) { percent, downloadedBytes, totalBytes ->
                    mainHandler.post {
                        if (generation != generations.get()) return@post
                        val current = mutableSnapshot.value
                        if (current.status == MusicSourcePlaybackStatus.Preparing) {
                            mutableSnapshot.value = current.copy(bufferedPercent = percent.coerceIn(0, 100))
                        }
                    }
                    if (percent == 0 || percent == 25 || percent == 50 || percent == 75 || percent == 100) {
                        AppLogger.i(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PROGRESS generation=$generation " +
                                "percent=$percent downloadedBytes=$downloadedBytes totalBytes=$totalBytes"
                        )
                    }
                }
                if (generation != generations.get()) return@launch
                val localSource = source.copy(
                    url = cached.file.absolutePath,
                    headers = emptyMap(),
                    userAgent = null,
                    expiresAtMs = null,
                )
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_END generation=$generation " +
                        "fromCache=${cached.fromCache} bytes=${cached.bytes} " +
                        "contentType=${cached.contentType.ifBlank { "unknown" }} " +
                        "file=${cached.file.name}"
                )
                playBackend(
                    backend = activeBackend,
                    generation = generation,
                    item = item,
                    source = localSource,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                AppLogger.e(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_PREPARE_FAIL generation=$generation " +
                        "message=${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}",
                    error,
                )
                prepareMediaPlayer(
                    generation = generation,
                    source = source,
                    startPositionMs = startPositionMs,
                    autoPlay = autoPlay,
                    reason = "cache_download_failed",
                )
            }
        }
    }

    private fun playBackend(
        backend: MusicSourcePlaybackBackend,
        generation: Long,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
    ) {
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
    }

    private fun pauseInternal() {
        if (!prepared) return
        if (isMediaPlayerPrepared()) pauseMediaPlayer() else backend?.pause()
    }

    private fun resumeInternal() {
        if (!prepared) return
        if (isMediaPlayerPrepared()) resumeMediaPlayer() else backend?.resume()
    }

    private fun onBackendSnapshot(backendSnapshot: MusicSourceBackendSnapshot) {
        val generation = generations.get()
        if (backendSnapshot.generation != generation) return
        if (mediaPlayerGeneration == generation) return
        val state = mutableSnapshot.value
        if (state.currentItem == null) return

        val duration = backendSnapshot.durationMs.takeIf { it > 0L } ?: state.durationMs
        when (backendSnapshot.status) {
            MusicSourceBackendStatus.Idle -> {
                if (state.status != MusicSourcePlaybackStatus.Error &&
                    state.status != MusicSourcePlaybackStatus.Completed
                ) {
                    prepared = false
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
                if (state.canGoNext) {
                    next(automatic = true)
                } else {
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Completed,
                        positionMs = duration.coerceAtLeast(state.positionMs),
                        durationMs = duration.coerceAtLeast(0L),
                        bufferedPercent = 100,
                    )
                }
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
                        prepareMediaPlayer(
                            generation = generation,
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
        if (isMediaPlayerPrepared()) {
            return runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        }
        return backend?.snapshot?.value?.positionMs
            ?.coerceAtLeast(0L)
            ?: mutableSnapshot.value.positionMs.coerceAtLeast(0L)
    }

    private fun fail(message: String) {
        prepared = false
        releaseMediaPlayer(abandonFocus = true)
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourcePlaybackStatus.Error,
            error = message.take(1_024),
        )
    }

    private fun prepareMediaPlayer(
        generation: Long,
        source: RawResolvedAudioSource,
        startPositionMs: Long,
        autoPlay: Boolean,
        reason: String,
    ) {
        val context = applicationContext ?: run {
            fail("在线播放上下文不可用")
            return
        }
        if (generation != generations.get()) return
        releaseMediaPlayer(abandonFocus = false)

        val player = MediaPlayer()
        mediaPlayer = player
        mediaPlayerGeneration = generation
        prepared = false
        val requestHeaders = LinkedHashMap(source.headers)
        source.userAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { userAgent ->
                if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    requestHeaders["User-Agent"] = userAgent
                }
            }

        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_OPEN_START generation=$generation reason=$reason " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(requestHeaders)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
        )
        try {
            player.setAudioAttributes(audioAttributes)
            player.setOnPreparedListener { preparedPlayer ->
                if (generation != generations.get() || mediaPlayer !== preparedPlayer) return@setOnPreparedListener
                prepared = true
                val reportedDuration = runCatching { preparedPlayer.duration.toLong() }
                    .getOrDefault(0L)
                    .coerceAtLeast(0L)
                val duration = reportedDuration.takeIf { it > 0L }
                    ?: mutableSnapshot.value.currentItem?.durationMs
                    ?: 0L
                val targetPosition = if (duration > 0L) {
                    startPositionMs.coerceIn(0L, duration)
                } else {
                    startPositionMs.coerceAtLeast(0L)
                }
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Preparing,
                    durationMs = duration,
                    positionMs = targetPosition,
                    error = "",
                )
                if (targetPosition > 0L) {
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            preparedPlayer.seekTo(targetPosition, MediaPlayer.SEEK_CLOSEST)
                        } else {
                            @Suppress("DEPRECATION")
                            preparedPlayer.seekTo(targetPosition.toInt())
                        }
                    }
                }
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_PREPARED generation=$generation " +
                        "durationMs=$duration startMs=$targetPosition autoPlay=$autoPlay"
                )
                if (!autoPlay) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        status = MusicSourcePlaybackStatus.Paused,
                        bufferedPercent = 100,
                    )
                    return@setOnPreparedListener
                }
                if (!requestAudioFocus(context)) {
                    fail("无法获取音频焦点")
                    return@setOnPreparedListener
                }
                runCatching { preparedPlayer.start() }
                    .onSuccess {
                        mutableSnapshot.value = mutableSnapshot.value.copy(
                            status = MusicSourcePlaybackStatus.Playing,
                            bufferedPercent = 100,
                            error = "",
                        )
                        AppLogger.i(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_PLAYING generation=$generation"
                        )
                        startMediaPlayerProgressUpdates(generation)
                        schedulePreloadNext()
                    }
                    .onFailure { error -> fail(error.message ?: "无法开始在线播放") }
            }
            player.setOnBufferingUpdateListener { current, percent ->
                if (generation == generations.get() && mediaPlayer === current) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        bufferedPercent = percent.coerceIn(0, 100),
                    )
                }
            }
            player.setOnCompletionListener { completedPlayer ->
                if (generation != generations.get() || mediaPlayer !== completedPlayer) return@setOnCompletionListener
                val state = mutableSnapshot.value
                prepared = false
                if (state.canGoNext) {
                    next(automatic = true)
                } else {
                    mutableSnapshot.value = state.copy(
                        status = MusicSourcePlaybackStatus.Completed,
                        positionMs = state.durationMs,
                        bufferedPercent = 100,
                    )
                    abandonAudioFocus()
                }
            }
            player.setOnErrorListener { failedPlayer, what, extra ->
                if (generation == generations.get() && mediaPlayer === failedPlayer) {
                    AppLogger.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_ERROR generation=$generation what=$what extra=$extra"
                    )
                    fail("在线播放器错误：$what/$extra")
                }
                true
            }
            player.setDataSource(context, Uri.parse(source.url), requestHeaders)
            player.prepareAsync()
        } catch (error: Throwable) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} MEDIAPLAYER_OPEN_FAIL generation=$generation " +
                    "message=${error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(512)}",
                error,
            )
            fail(error.message ?: "无法打开在线播放地址")
        }
    }

    private fun pauseMediaPlayer() {
        val player = mediaPlayer ?: return
        if (!isMediaPlayerPrepared()) return
        runCatching {
            if (player.isPlaying) player.pause()
        }.onSuccess {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                status = MusicSourcePlaybackStatus.Paused,
                positionMs = currentPosition(),
            )
        }
    }

    private fun resumeMediaPlayer() {
        val context = applicationContext ?: return
        val player = mediaPlayer ?: return
        if (!isMediaPlayerPrepared()) return
        if (!requestAudioFocus(context)) {
            fail("无法获取音频焦点")
            return
        }
        runCatching { player.start() }
            .onSuccess {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    status = MusicSourcePlaybackStatus.Playing,
                    error = "",
                )
                startMediaPlayerProgressUpdates(generations.get())
            }
            .onFailure { fail(it.message ?: "无法继续在线播放") }
    }

    private fun startMediaPlayerProgressUpdates(generation: Long) {
        mediaPlayerProgressJob?.cancel()
        mediaPlayerProgressJob = scope.launch {
            while (isActive && generation == generations.get() && mediaPlayerGeneration == generation) {
                val state = mutableSnapshot.value
                if (state.currentItem == null) break
                val duration = runCatching {
                    if (isMediaPlayerPrepared()) mediaPlayer?.duration?.toLong() ?: 0L else 0L
                }.getOrDefault(0L).takeIf { it > 0L } ?: state.durationMs
                mutableSnapshot.value = state.copy(
                    positionMs = currentPosition().coerceAtMost(duration.coerceAtLeast(0L)),
                    durationMs = duration.coerceAtLeast(0L),
                )
                delay(500L)
            }
        }
    }

    private fun isMediaPlayerPrepared(): Boolean =
        prepared && mediaPlayer != null && mediaPlayerGeneration == generations.get()

    private fun releaseMediaPlayer(abandonFocus: Boolean) {
        mediaPlayerProgressJob?.cancel()
        mediaPlayerProgressJob = null
        mediaPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.setOnBufferingUpdateListener(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        mediaPlayerGeneration = Long.MIN_VALUE
        prepared = false
        if (abandonFocus) abandonAudioFocus()
    }

    private fun requestAudioFocus(context: Context): Boolean {
        val manager = audioManager ?: (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also {
            audioManager = it
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.abandonAudioFocus(focusListener) }
        }
        resumeOnFocusGain = false
    }

    private fun loadPreferredQuality(): RawSourceQuality = runCatching {
        val rawName = AppPreferences.storage.decodeString(PREFERRED_QUALITY_KEY, null)
        if (rawName != null) {
            RawSourceQuality.valueOf(rawName)
        } else {
            when (AppPreferences.Source.preferredQuality) {
                "flac24bit" -> RawSourceQuality.HiRes
                "flac" -> RawSourceQuality.Lossless
                "320k" -> RawSourceQuality.Super
                else -> RawSourceQuality.Standard
            }
        }
    }.getOrDefault(RawSourceQuality.Standard)
}
