package com.rawsmusic.module.online.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.source.qq.QQMusicSource
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * QQ 音乐“你的私荐歌单”数据仓库。
 *
 * 职责：
 * 1. 负责私荐歌单的拉取、换一批（刷新）轮转逻辑；
 * 2. 结合 MMKV 做本地离线持久化缓存，实现秒开；
 * 3. 配合主界面歌单列表，提供单例状态流驱动。
 */
class QQPrivateRecommendRepository private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = Gson()
    private val mmkv: MMKV? = try {
        MMKV.defaultMMKV()
    } catch (e: Throwable) {
        null
    }

    private val _playlists = MutableStateFlow<List<OnlinePlaylist>>(emptyList())
    val playlists: StateFlow<List<OnlinePlaylist>> = _playlists.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var refreshSeed: Int = 0

    init {
        loadFromCache()
        scope.launch {
            com.rawsmusic.module.online.auth.PlatformAccountManager.accounts.collect { accounts ->
                val qqAcc = accounts[com.rawsmusic.module.online.model.OnlinePlatform.QQ]
                val currentUin = qqAcc?.userId.orEmpty()
                val cachedUin = mmkv?.decodeString(KEY_CACHE_UIN).orEmpty()
                if (currentUin.isNotBlank() && currentUin != cachedUin && !_isRefreshing.value) {
                    AppLogger.i(TAG, "检测到 QQ 音乐账号登录态变更 (旧: $cachedUin -> 新: $currentUin)，自动拉取最新个性化私荐")
                    refresh(isUserAction = false)
                }
            }
        }
    }

    /**
     * 从本地持久化缓存中加载上一次的私荐歌单（保障秒开与离线可用）。
     */
    private fun loadFromCache() {
        try {
            val currentUin = com.rawsmusic.module.online.auth.PlatformAccountManager.getAccount(com.rawsmusic.module.online.model.OnlinePlatform.QQ)?.userId.orEmpty()
            val cachedUin = mmkv?.decodeString(KEY_CACHE_UIN).orEmpty()
            // 如果已登录但缓存是未登录时的，不加载通用缓存，留给网络静默拉取个性化数据
            if (currentUin.isNotBlank() && cachedUin.isBlank()) {
                AppLogger.d(TAG, "检测到已登录但本地仅有未登录通用缓存，跳过旧缓存加载")
                return
            }

            val json = mmkv?.decodeString(KEY_CACHE_PLAYLISTS).orEmpty()
            if (json.isNotBlank()) {
                val type = object : TypeToken<List<OnlinePlaylist>>() {}.type
                val cached = gson.fromJson<List<OnlinePlaylist>>(json, type).orEmpty()
                if (cached.isNotEmpty()) {
                    _playlists.value = cached
                    AppLogger.d(TAG, "从缓存加载私荐歌单成功，共 ${cached.size} 个 (归属: $cachedUin)")
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "读取私荐歌单缓存失败: ${e.message}")
        }
    }

    /**
     * 将获取到的私荐歌单写入持久化缓存。
     */
    private fun saveToCache(list: List<OnlinePlaylist>) {
        if (list.isEmpty()) return
        try {
            val currentUin = com.rawsmusic.module.online.auth.PlatformAccountManager.getAccount(com.rawsmusic.module.online.model.OnlinePlatform.QQ)?.userId.orEmpty()
            val json = gson.toJson(list)
            mmkv?.encode(KEY_CACHE_PLAYLISTS, json)
            mmkv?.encode(KEY_CACHE_UIN, currentUin)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "写入私荐歌单缓存失败: ${e.message}")
        }
    }

    /**
     * 若当前歌单为空或登录态发生变化，则自动拉取一次。
     */
    fun loadIfNeeded() {
        val currentUin = com.rawsmusic.module.online.auth.PlatformAccountManager.getAccount(com.rawsmusic.module.online.model.OnlinePlatform.QQ)?.userId.orEmpty()
        val cachedUin = mmkv?.decodeString(KEY_CACHE_UIN).orEmpty()
        val accountMismatch = currentUin.isNotBlank() && currentUin != cachedUin

        if ((_playlists.value.isEmpty() || accountMismatch) && !_isRefreshing.value) {
            refresh(isUserAction = false)
        }
    }

    /**
     * 刷新 / 换一批私荐歌单。
     *
     * @param isUserAction 是否为用户手动点击“换一批”按钮触发。
     */
    fun refresh(isUserAction: Boolean = true, onComplete: ((Boolean, String?) -> Unit)? = null) {
        scope.launch {
            if (_isRefreshing.value) return@launch
            mutex.withLock {
                _isRefreshing.value = true
                try {
                    if (isUserAction) {
                        refreshSeed++
                    }
                    val currentSeed = refreshSeed
                    val qqSource = OnlineMusicRepository.getInstance().getQQMusicSource()
                    val fetched = qqSource.getPrivateRecommendPlaylists(refreshSeed = currentSeed)
                    if (fetched.isNotEmpty()) {
                        _playlists.value = fetched
                        saveToCache(fetched)
                        AppLogger.i(TAG, "换一批私荐歌单成功(seed=$currentSeed)，共 ${fetched.size} 个")
                        onComplete?.invoke(true, null)
                    } else {
                        AppLogger.w(TAG, "私荐歌单返回为空，保持现有展示")
                        onComplete?.invoke(false, "暂未获取到新歌单")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "刷新私荐歌单异常", e)
                    onComplete?.invoke(false, e.message)
                } finally {
                    _isRefreshing.value = false
                }
            }
        }
    }

    companion object {
        private const val TAG = "QQPrivateRecommendRepo"
        private const val KEY_CACHE_PLAYLISTS = "KEY_QQ_PRIVATE_RECOMMEND_PLAYLISTS"
        private const val KEY_CACHE_UIN = "KEY_QQ_PRIVATE_RECOMMEND_UIN"

        @Volatile
        private var instance: QQPrivateRecommendRepository? = null

        fun getInstance(): QQPrivateRecommendRepository {
            return instance ?: synchronized(this) {
                instance ?: QQPrivateRecommendRepository().also { instance = it }
            }
        }
    }
}
