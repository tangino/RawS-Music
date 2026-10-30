package com.rawsmusic.ui.floating

import android.animation.ObjectAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 桌面与全局悬浮窗管理器 (遵循 RawS-Music 规范，完整保留原版高精质感布局与交互逻辑)
 * 1. 呈现当前播放歌曲信息（黑胶唱片自转封面、歌名、歌手、实时双行智能歌词）
 * 2. 极简播控：播放/暂停按键、微型关闭键
 * 3. 灵动手势：左右滑动标题文本切歌，轻触点击唤起播放器主界面
 * 4. 任意拖动：全屏防越界保护，位置自动记忆
 * 5. 全面自适应设备：车机大屏黄金大尺寸 360x92dp，平板 285x74dp，手机 205x54dp
 * 6. 双指捏合无级缩放 (Pinch-to-Scale) 与长按四角把手自由拉伸，持久化至 MMKV
 */
class FloatingWindowManager private constructor(private val context: Context) {

    /**
     * 悬浮窗尺寸档位枚举
     */
    enum class DeviceTier {
        PHONE,        // 手机尺寸（紧凑灵动胶囊 205x54dp）
        TABLET,       // 平板尺寸（大触控面积，舒适视距 285x74dp）
        CAR_OR_LARGE  // 车机/超大屏幕（驾驶视距黄金尺寸 360x92dp）
    }

    companion object {
        private const val TAG = "FloatingWindowManager"

        /** 用户可自由缩放范围：最小 60%，最大 220%（会再按屏幕尺寸二次收敛） */
        private const val MIN_USER_SCALE = 0.6f
        private const val MAX_USER_SCALE = 2.2f
        /** 单独封面状态允许再放大一倍 (比展开态上限大一倍) */
        private const val MAX_COLLAPSED_SCALE = 8.8f
        /** 尺寸调整框长时间无操作自动收起，避免误触后一直停留在调整状态 */
        private const val RESIZE_IDLE_TIMEOUT_MS = 7000L

        private const val POSITION_POLL_MS = 100L
        private const val PAUSED_POLL_MS = 600L

        /** 折叠/展开：两段动画之间的停顿（用户指定 0.5 秒） */
        private const val STAGE_HOLD_MS = 500L
        /** 歌词折叠/铺开段时长 */
        private const val LYRICS_FOLD_MS = 300L
        /** 封面放大/缩小段时长 */
        private const val COVER_ZOOM_MS = 340L

        @Volatile
        private var instance: FloatingWindowManager? = null

        fun getInstance(context: Context): FloatingWindowManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingWindowManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * 用户自定义缩放因子 (默认 1.0f，可长按悬浮窗后拖动四角圆点自由调节)
     */
    var userScaleFactor: Float = AppPreferences.FloatingWindow.userScale
        .coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)
        private set

    /**
     * 折叠状态下的缩放因子 (默认 1.0f，长按封面可进入尺寸调整模式自由调节)
     */
    var collapsedScaleFactor: Float = AppPreferences.FloatingWindow.collapsedScale
        .coerceIn(MIN_USER_SCALE, MAX_COLLAPSED_SCALE)
        private set

    /**
     * 当前是否处于折叠歌词状态（仅显示唱片封面）
     */
    var isCollapsed: Boolean = AppPreferences.FloatingWindow.isCollapsed
        private set

    /**
     * 当前应用程序是否处于后台
     */
    var isAppInBackground: Boolean = false
        private set

    private var startedActivityCount = 0

    // 屏幕横竖屏与系统配置监听器
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handleConfigurationChanged(newConfig)
        }
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    init {
        (context.applicationContext as? Application)?.let { app ->
            app.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(activity: Activity) {
                        startedActivityCount++
                        if (startedActivityCount == 1) {
                            isAppInBackground = false
                            AppLogger.d(TAG, "Application entered FOREGROUND: auto hiding floating window")
                            hide()
                        }
                    }

                    override fun onActivityStopped(activity: Activity) {
                        startedActivityCount--
                        if (startedActivityCount <= 0) {
                            startedActivityCount = 0
                            isAppInBackground = true
                            AppLogger.d(TAG, "Application entered BACKGROUND: checking auto show floating window")
                            checkAndAutoShow()
                        }
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
            )
            app.registerComponentCallbacks(componentCallbacks)
        }
    }

    private var floatingView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    var isShowing: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playbackPollingJob: Job? = null
    private var lyricsCollectJob: Job? = null
    private var coverLoadJob: Job? = null

    private var currentSongIdentity: String? = null
    private var currentLyricLines: List<LyricLine> = emptyList()
    private var lastLyricStateKey: String = ""
    private var currentLineSplitPart2StartMs: Long = Long.MAX_VALUE
    private var isCurrentSplitSecondPartActive: Boolean = false

    // 控件引用
    private var rootCard: View? = null
    private var capsuleContainer: LinearLayout? = null
    private var coverContainer: FrameLayout? = null
    private var ivCover: ImageView? = null
    private var btnPlayPause: ImageButton? = null

    private var infoContainer: LinearLayout? = null
    private var tvTitle: TextView? = null
    private var tvTitleDot: TextView? = null
    private var tvArtist: TextView? = null
    private var tvLyric: TextView? = null
    private var tvLyricNext: TextView? = null

    private var btnClose: ImageButton? = null
    private var coverRotateAnimator: ObjectAnimator? = null
    private var collapseTransitionAnimator: ValueAnimator? = null

    // 尺寸调整模式 (长按唤起) 相关控件与状态
    private var resizeOverlay: FrameLayout? = null
    private var resizeFrame: View? = null
    private var resizeHint: TextView? = null
    private val resizeHandles = mutableListOf<ResizeHandle>()

    /** 单个拖动圆点：dirX/dirY 表示向外拖动时该角对应的缩放增长方向 */
    private data class ResizeHandle(val view: View, val dirX: Int, val dirY: Int)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var resizeIdleRunnable: Runnable? = null

    /** 当前是否处于"拖动四角圆点调整大小"模式 */
    var isResizeMode: Boolean = false
        private set

    private var resizeStartScale = 1f
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartCenterX = 0f
    private var resizeStartCenterY = 0f
    private var suppressExitOnNextUp = false
    private var longPressTriggered = false
    private var infoLongPressRunnable: Runnable? = null

    private fun tryExitOnTouchUp(): Boolean {
        if (suppressExitOnNextUp) {
            suppressExitOnNextUp = false
            return false
        }
        exitResizeMode()
        return true
    }

    fun isEnabled(): Boolean = AppPreferences.FloatingWindow.isEnabled

    fun getThemeMode(): Int = AppPreferences.FloatingWindow.themeMode

    fun setThemeMode(mode: Int) {
        val normalized = mode.coerceIn(
            AppPreferences.FloatingWindow.THEME_SYSTEM,
            AppPreferences.FloatingWindow.THEME_DARK
        )
        AppPreferences.FloatingWindow.themeMode = normalized
        floatingView?.let { view ->
            applyTheme(view)
            updateResizeHintText(dragging = false)
        }
    }

    fun cycleThemeMode(): Int {
        val next = when (getThemeMode()) {
            AppPreferences.FloatingWindow.THEME_SYSTEM -> AppPreferences.FloatingWindow.THEME_LIGHT
            AppPreferences.FloatingWindow.THEME_LIGHT -> AppPreferences.FloatingWindow.THEME_DARK
            else -> AppPreferences.FloatingWindow.THEME_SYSTEM
        }
        setThemeMode(next)
        return next
    }

    fun isDarkTheme(config: Configuration = context.resources.configuration): Boolean {
        return when (AppPreferences.FloatingWindow.themeMode) {
            AppPreferences.FloatingWindow.THEME_LIGHT -> false
            AppPreferences.FloatingWindow.THEME_DARK -> true
            else -> (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun currentThemeLabel(): String {
        return when (getThemeMode()) {
            AppPreferences.FloatingWindow.THEME_LIGHT -> context.getString(R.string.settings_theme_light)
            AppPreferences.FloatingWindow.THEME_DARK -> context.getString(R.string.settings_theme_dark)
            else -> context.getString(R.string.settings_theme_system)
        }
    }

    private fun primaryLyricColor(isDark: Boolean = isDarkTheme()): Int =
        if (isDark) Color.WHITE else Color.parseColor("#0F172A")

    private fun activeSplitSecondaryLyricColor(isDark: Boolean = isDarkTheme()): Int =
        if (isDark) Color.parseColor("#B8F1F5F9") else Color.parseColor("#B81E293B")

    private fun secondaryLyricColor(isDark: Boolean = isDarkTheme()): Int =
        if (isDark) Color.parseColor("#80FFFFFF") else Color.parseColor("#8A475569")

    fun applyTheme(root: View? = floatingView, config: Configuration = context.resources.configuration) {
        val view = root ?: return
        val isDark = isDarkTheme(config)
        val density = context.resources.displayMetrics.density

        val capsuleH = (capsuleContainer?.layoutParams?.height ?: 0).takeIf { it > 0 }
            ?: capsuleContainer?.height?.takeIf { it > 0 }
            ?: (baseCapsuleSizeDp().second * userScaleFactor * density).toInt()
        val capsuleRadius = capsuleH / 2f

        val capsuleStrokeWidth = if (isCollapsed) {
            (0.75f * density).toInt().coerceAtLeast(1)
        } else {
            (1.5f * density).toInt().coerceAtLeast(2)
        }
        val coverStrokeWidth = if (isCollapsed) {
            (0.8f * density).toInt().coerceAtLeast(1)
        } else {
            (1.6f * density).toInt().coerceAtLeast(2)
        }

        val capsuleBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            if (isDark) {
                intArrayOf(
                    Color.parseColor("#E60E121A"),
                    Color.parseColor("#D9090C12"),
                    Color.parseColor("#CC06080D")
                )
            } else {
                intArrayOf(
                    Color.parseColor("#F5FFFFFF"),
                    Color.parseColor("#EEF4F6FA"),
                    Color.parseColor("#E8EBF0F7")
                )
            }
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = capsuleRadius
            setStroke(
                capsuleStrokeWidth,
                if (isDark) {
                    if (isCollapsed) Color.parseColor("#26FFFFFF") else Color.parseColor("#33FFFFFF")
                } else {
                    if (isCollapsed) Color.parseColor("#1A0F172A") else Color.parseColor("#240F172A")
                }
            )
        }
        capsuleContainer?.background = capsuleBg

        val vinylBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
            if (isDark) {
                intArrayOf(
                    Color.parseColor("#1A1C24"),
                    Color.parseColor("#0F1117"),
                    Color.parseColor("#14171F")
                )
            } else {
                intArrayOf(
                    Color.parseColor("#E2E8F0"),
                    Color.parseColor("#CBD5E1"),
                    Color.parseColor("#D8E0EB")
                )
            }
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(
                coverStrokeWidth,
                if (isDark) {
                    if (isCollapsed) Color.parseColor("#2BFFFFFF") else Color.parseColor("#3DFFFFFF")
                } else {
                    if (isCollapsed) Color.parseColor("#1C0F172A") else Color.parseColor("#260F172A")
                }
            )
        }
        coverContainer?.background = vinylBg

        tvTitle?.setTextColor(if (isDark) Color.WHITE else Color.parseColor("#0F172A"))
        tvTitleDot?.setTextColor(if (isDark) Color.parseColor("#66FFFFFF") else Color.parseColor("#660F172A"))
        tvArtist?.setTextColor(if (isDark) Color.parseColor("#80FFFFFF") else Color.parseColor("#99334155"))

        val shadowColor = if (isDark) Color.parseColor("#99000000") else Color.parseColor("#4DFFFFFF")
        val shadowRadius = if (isDark) 2f else 1.5f
        tvLyric?.setShadowLayer(shadowRadius, 0f, 1f, shadowColor)
        tvLyricNext?.setShadowLayer(shadowRadius, 0f, 1f, shadowColor)

        if (currentLineSplitPart2StartMs != Long.MAX_VALUE) {
            if (isCurrentSplitSecondPartActive) {
                tvLyric?.setTextColor(activeSplitSecondaryLyricColor(isDark))
                tvLyricNext?.setTextColor(primaryLyricColor(isDark))
            } else {
                tvLyric?.setTextColor(primaryLyricColor(isDark))
                tvLyricNext?.setTextColor(activeSplitSecondaryLyricColor(isDark))
            }
        } else {
            tvLyric?.setTextColor(primaryLyricColor(isDark))
            tvLyricNext?.setTextColor(secondaryLyricColor(isDark))
        }

        val iconTint = android.content.res.ColorStateList.valueOf(
            if (isDark) Color.WHITE else Color.parseColor("#0F172A")
        )
        btnPlayPause?.imageTintList = iconTint
        btnClose?.imageTintList = iconTint

        val dashStroke = (if (isCollapsed) 0.8f else 1.6f) * density
        resizeFrame?.background = PillDashFrameDrawable(
            strokeWidthPx = dashStroke,
            dashLengthPx = 4.5f * density,
            dashColor = if (isDark) Color.argb(215, 255, 255, 255) else Color.argb(190, 15, 23, 42)
        )
        val hintBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 11f * density
            setColor(if (isDark) Color.parseColor("#B3000000") else Color.parseColor("#E6FFFFFF"))
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
                if (isDark) Color.parseColor("#3DFFFFFF") else Color.parseColor("#290F172A")
            )
        }
        resizeHint?.background = hintBg
        resizeHint?.setTextColor(if (isDark) Color.WHITE else Color.parseColor("#0F172A"))
    }

    fun setEnabled(enabled: Boolean) {
        AppPreferences.FloatingWindow.isEnabled = enabled
        if (!enabled) {
            hide()
        } else if (isAppInBackground) {
            checkAndAutoShow()
        }
    }

    fun setUserScale(scale: Float) {
        val clamped = clampScale(scale, forCollapsed = false)
        if (abs(userScaleFactor - clamped) > 0.01f) {
            userScaleFactor = clamped
            AppPreferences.FloatingWindow.userScale = clamped
            floatingView?.let { view ->
                applyAdaptiveDimensions(view)
            applyTheme(view)
                windowParams?.let { params ->
                    val density = context.resources.displayMetrics.density
                    val (baseW, baseH) = baseCapsuleSizeDp()
                    val padPx = (2 * density).toInt() * 2
                    params.width = (baseW * clamped * density + padPx).toInt()
                    params.height = (baseH * clamped * density + padPx).toInt()
                    clampCoordinates(params, view)
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun setCollapsedScale(scale: Float) {
        val clamped = clampScale(scale, forCollapsed = true)
        if (abs(collapsedScaleFactor - clamped) > 0.01f) {
            collapsedScaleFactor = clamped
            AppPreferences.FloatingWindow.collapsedScale = clamped
            floatingView?.let { view ->
                applyAdaptiveDimensions(view)
                applyTheme(view)
                windowParams?.let { params ->
                    val density = context.resources.displayMetrics.density
                    val (_, baseH) = baseCapsuleSizeDp()
                    val padPx = (2 * density).toInt() * 2
                    val sizePx = (baseH * clamped * density).toInt() + padPx
                    params.width = sizePx
                    params.height = sizePx
                    clampCoordinates(params, view)
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun isCarEnvironment(ctx: Context = context): Boolean {
        val uiMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val hasAutomotive = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val isCarBuild = Build.DEVICE.contains("car", ignoreCase = true) ||
                Build.MODEL.contains("car", ignoreCase = true) ||
                Build.PRODUCT.contains("car", ignoreCase = true) ||
                Build.FINGERPRINT.contains("car", ignoreCase = true)
        return uiMode == Configuration.UI_MODE_TYPE_CAR || hasAutomotive || isCarBuild
    }

    fun getDeviceTier(): DeviceTier {
        if (isCarEnvironment()) {
            return DeviceTier.CAR_OR_LARGE
        }

        val bounds = getScreenBounds()
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val screenWDp = bounds.width() / density
        val screenHDp = bounds.height() / density
        val smallestWidthDp = minOf(screenWDp, screenHDp)
        val isLandscape = screenWDp > screenHDp

        return when {
            smallestWidthDp >= 700 || (isLandscape && screenWDp >= 800 && screenHDp >= 480) -> DeviceTier.CAR_OR_LARGE
            smallestWidthDp >= 600 || (isLandscape && screenWDp >= 660 && screenHDp >= 440) -> DeviceTier.TABLET
            else -> DeviceTier.PHONE
        }
    }

    private fun baseCapsuleSizeDp(tier: DeviceTier = getDeviceTier()): Pair<Float, Float> = when (tier) {
        DeviceTier.CAR_OR_LARGE -> Pair(360f, 92f)
        DeviceTier.TABLET -> Pair(285f, 74f)
        DeviceTier.PHONE -> Pair(205f, 54f)
    }

    /** 展开态胶囊内边距（start / end / 垂直），折叠过渡动画必须与这里保持完全一致 */
    private fun capsulePaddingDp(tier: DeviceTier = getDeviceTier()): Triple<Float, Float, Float> = when (tier) {
        DeviceTier.CAR_OR_LARGE -> Triple(8f, 8f, 8f)
        DeviceTier.TABLET -> Triple(7f, 6f, 6f)
        DeviceTier.PHONE -> Triple(5f, 5f, 4.5f)
    }

    /** 展开态黑胶封面基准尺寸，折叠过渡动画必须与这里保持完全一致 */
    private fun coverBaseSizeDp(tier: DeviceTier = getDeviceTier()): Float = when (tier) {
        DeviceTier.CAR_OR_LARGE -> 76f
        DeviceTier.TABLET -> 60f
        DeviceTier.PHONE -> 42f
    }

    private fun effectiveMaxScale(forCollapsed: Boolean = isCollapsed): Float {
        val bounds = getScreenBounds()
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val (baseW, baseH) = baseCapsuleSizeDp()
        val hardMax = if (forCollapsed) MAX_COLLAPSED_SCALE else MAX_USER_SCALE
        val baseWPx = if (forCollapsed) baseH * density else baseW * density
        val baseHPx = baseH * density
        return FloatingResizeMath.maxScaleForBounds(
            screenWidthPx = bounds.width(),
            screenHeightPx = bounds.height(),
            baseWidthPx = baseWPx,
            baseHeightPx = baseHPx,
            hardMaxScale = hardMax,
            minScale = MIN_USER_SCALE
        )
    }

    private fun clampScale(scale: Float, forCollapsed: Boolean = isCollapsed): Float =
        FloatingResizeMath.clampScale(scale, MIN_USER_SCALE, effectiveMaxScale(forCollapsed))

    fun checkAndAutoShow() {
        if (!isEnabled()) return
        if (!isAppInBackground) {
            AppLogger.d(TAG, "Application is in foreground, floating window will stay hidden")
            return
        }
        if (Settings.canDrawOverlays(context)) {
            show()
        }
    }

    private fun getScreenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            Rect(metrics.bounds)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun clampCoordinates(
        params: WindowManager.LayoutParams,
        targetView: View? = floatingView,
        expectedWidthPx: Int = 0,
        expectedHeightPx: Int = 0
    ) {
        val bounds = getScreenBounds()
        val density = context.resources.displayMetrics.density

        val (baseCapsuleW, baseCapsuleH) = baseCapsuleSizeDp()
        val baseW = if (isCollapsed) baseCapsuleH else baseCapsuleW
        val baseH = baseCapsuleH
        val expectedWidth = if (expectedWidthPx > 0) expectedWidthPx
        else (baseW * userScaleFactor * density).toInt()
        val expectedHeight = if (expectedHeightPx > 0) expectedHeightPx
        else (baseH * userScaleFactor * density).toInt()

        val vWidth = targetView?.width?.takeIf { it > 0 }
            ?: capsuleContainer?.width?.takeIf { it > 0 }
            ?: expectedWidth
        val vHeight = targetView?.height?.takeIf { it > 0 }
            ?: capsuleContainer?.height?.takeIf { it > 0 }
            ?: expectedHeight

        val maxX = (bounds.width() - vWidth).coerceAtLeast(0)
        val maxY = (bounds.height() - vHeight).coerceAtLeast(0)

        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show(force: Boolean = false) {
        if (!force && !isAppInBackground) {
            AppLogger.d(TAG, "Skip showing floating window because app is in foreground")
            return
        }
        if (isShowing && floatingView != null) return
        if (!Settings.canDrawOverlays(context)) {
            AppLogger.w(TAG, "Cannot show floating window: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val defaultX = (32 * context.resources.displayMetrics.density).toInt()
            val defaultY = (160 * context.resources.displayMetrics.density).toInt()
            val savedX = AppPreferences.FloatingWindow.posX.takeIf { it >= 0 } ?: defaultX
            val savedY = AppPreferences.FloatingWindow.posY.takeIf { it >= 0 } ?: defaultY

            val density = context.resources.displayMetrics.density
            val (baseCapsuleW, baseCapsuleH) = baseCapsuleSizeDp()
            val baseW = if (isCollapsed) baseCapsuleH else baseCapsuleW
            val baseH = baseCapsuleH
            val padPx = (2 * density).toInt() * 2
            val initScale = if (isCollapsed) collapsedScaleFactor else userScaleFactor
            val initW = if (isCollapsed) (baseH * initScale * density + padPx).toInt() else (baseW * initScale * density + padPx).toInt()
            val initH = (baseH * initScale * density + padPx).toInt()

            val params = WindowManager.LayoutParams(
                initW,
                initH,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }
            windowParams = params

            val view = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)
            floatingView = view

            bindViews(view)
            userScaleFactor = clampScale(userScaleFactor)
            applyAdaptiveDimensions(view)
            applyTheme(view)
            clampCoordinates(params, view)
            setupTouchAndDrag(view, params)
            setupResizeHandles()
            setupClickListeners()

            windowManager.addView(view, params)
            isShowing = true

            currentSongIdentity = null
            startObservingPlayback()
            AppLogger.i(TAG, "Floating window displayed successfully at (${params.x}, ${params.y}) tier=${getDeviceTier()} scale=$userScaleFactor")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show floating window: ${e.message}", e)
            isShowing = false
            floatingView = null
        }
    }

    fun hide() {
        if (!isShowing && floatingView == null) return

        if (isResizeMode) exitResizeMode()
        cancelInfoLongPress()
        cancelResizeIdleExit()

        playbackPollingJob?.cancel()
        playbackPollingJob = null
        lyricsCollectJob?.cancel()
        lyricsCollectJob = null
        coverLoadJob?.cancel()
        coverLoadJob = null

        coverRotateAnimator?.cancel()
        coverRotateAnimator = null

        collapseTransitionAnimator?.cancel()
        collapseTransitionAnimator = null

        try {
            floatingView?.let {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to remove floating window: ${e.message}", e)
        } finally {
            floatingView = null
            windowParams = null
            isShowing = false
            currentSongIdentity = null
            currentLyricLines = emptyList()
            lastLyricStateKey = ""
            currentLineSplitPart2StartMs = Long.MAX_VALUE
            isCurrentSplitSecondPartActive = false
        }
    }

    private fun bindViews(root: View) {
        rootCard = root.findViewById(R.id.floating_root_card)
        capsuleContainer = root.findViewById(R.id.floating_capsule_container)
        coverContainer = root.findViewById(R.id.floating_cover_container)
        ivCover = root.findViewById(R.id.floating_cover)
        btnPlayPause = root.findViewById(R.id.floating_btn_play_pause)

        infoContainer = root.findViewById(R.id.floating_info_container)
        tvTitle = root.findViewById(R.id.floating_title)
        tvTitleDot = root.findViewById(R.id.floating_title_dot)
        tvArtist = root.findViewById(R.id.floating_artist)
        tvLyric = root.findViewById(R.id.floating_lyric)
        tvLyricNext = root.findViewById(R.id.floating_lyric_next)

        btnClose = root.findViewById(R.id.floating_btn_close)

        resizeOverlay = root.findViewById(R.id.floating_resize_overlay)
        resizeFrame = root.findViewById(R.id.floating_resize_frame)
        resizeHint = root.findViewById(R.id.floating_resize_hint)
        resizeHint?.isClickable = true
        resizeHint?.setOnClickListener {
            cancelResizeIdleExit()
            cycleThemeMode()
            scheduleResizeIdleExit()
        }
        resizeHandles.clear()
        listOf(
            Triple(R.id.floating_resize_handle_tl, -1, -1),
            Triple(R.id.floating_resize_handle_tr, 1, -1),
            Triple(R.id.floating_resize_handle_bl, -1, 1),
            Triple(R.id.floating_resize_handle_br, 1, 1)
        ).forEach { (id, dirX, dirY) ->
            root.findViewById<View>(id)?.let { resizeHandles.add(ResizeHandle(it, dirX, dirY)) }
        }

        val density = context.resources.displayMetrics.density
        resizeFrame?.background = PillDashFrameDrawable(
            strokeWidthPx = 1.6f * density,
            dashLengthPx = 4.5f * density,
            dashColor = Color.argb(215, 255, 255, 255)
        )

        var overlayStartX = 0f
        var overlayStartY = 0f
        var overlayMoved = false
        var overlayStartDist = 0f
        var overlayStartScale = 1.0f
        var overlayIsResizing = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        resizeOverlay?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelResizeIdleExit()
                    overlayStartX = event.rawX
                    overlayStartY = event.rawY
                    overlayMoved = false
                    overlayIsResizing = false
                    suppressExitOnNextUp = false
                    if (isCollapsed) {
                        val (_, baseH) = baseCapsuleSizeDp()
                        val density = context.resources.displayMetrics.density
                        val padPx = (2 * density).toInt() * 2
                        val curH = baseH * density * collapsedScaleFactor
                        val centerX = (windowParams?.x ?: 0) + padPx / 2f + curH / 2f
                        val centerY = (windowParams?.y ?: 0) + padPx / 2f + curH / 2f
                        resizeStartCenterX = centerX
                        resizeStartCenterY = centerY
                        overlayStartDist = Math.hypot((event.rawX - centerX).toDouble(), (event.rawY - centerY).toDouble()).toFloat()
                        overlayStartScale = collapsedScaleFactor
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.rawX - overlayStartX)
                    val dy = abs(event.rawY - overlayStartY)
                    if (dx > touchSlop || dy > touchSlop) {
                        overlayMoved = true
                    }
                    if (isCollapsed && overlayStartDist > 0f) {
                        val (_, baseH) = baseCapsuleSizeDp()
                        val density = context.resources.displayMetrics.density
                        val baseHpx = (baseH * density).coerceAtLeast(1f)
                        val centerX = resizeStartCenterX
                        val centerY = resizeStartCenterY
                        val curDist = Math.hypot((event.rawX - centerX).toDouble(), (event.rawY - centerY).toDouble()).toFloat()
                        val distDelta = curDist - overlayStartDist
                        if (!overlayIsResizing && abs(distDelta) > touchSlop) {
                            overlayIsResizing = true
                        }
                        if (overlayIsResizing) {
                            val scaleDelta = (distDelta * 2.0f) / baseHpx
                            applyScaleAroundCenter(overlayStartScale + scaleDelta)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (overlayIsResizing && isCollapsed) {
                        AppPreferences.FloatingWindow.collapsedScale = collapsedScaleFactor
                        AppLogger.i(TAG, "折叠悬浮球拖动缩放完成: scale=$collapsedScaleFactor")
                    }
                    if (!overlayMoved && !overlayIsResizing) {
                        tryExitOnTouchUp()
                    } else {
                        scheduleResizeIdleExit()
                    }
                    overlayIsResizing = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    overlayIsResizing = false
                    scheduleResizeIdleExit()
                    true
                }
                else -> true
            }
        }

        ivCover?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        ivCover?.clipToOutline = true

        tvTitle?.isSelected = true
        tvLyric?.isSelected = true
        tvLyricNext?.isSelected = true
    }

    private fun handleConfigurationChanged(newConfig: Configuration) {
        val view = floatingView ?: return
        val params = windowParams ?: return
        if (!isShowing || !view.isAttachedToWindow) return

        AppLogger.d(TAG, "onConfigurationChanged orientation=${newConfig.orientation}, updating adaptive dimensions")

        view.post {
            userScaleFactor = clampScale(userScaleFactor)
            applyAdaptiveDimensions(view)
            applyTheme(view, newConfig)
            clampCoordinates(params, view)
            try {
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, params)
                    AppPreferences.FloatingWindow.posX = params.x
                    AppPreferences.FloatingWindow.posY = params.y
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to update floating window on config change: ${e.message}")
            }
        }
    }

    private fun applyAdaptiveDimensions(root: View) {
        val tier = getDeviceTier()
        val density = context.resources.displayMetrics.density
        val scale = if (isCollapsed) collapsedScaleFactor else userScaleFactor

        val (baseCapsuleWidthDp, baseCapsuleHeightDp) = baseCapsuleSizeDp(tier)

        val baseCoverSizeDp = coverBaseSizeDp(tier)

        val (basePlaySizeDp, basePlayPadDp, basePlayMarginEndDp) = when (tier) {
            DeviceTier.CAR_OR_LARGE -> Triple(36f, 6f, 8f)
            DeviceTier.TABLET -> Triple(28f, 4f, 6f)
            DeviceTier.PHONE -> Triple(22f, 3f, 4f)
        }

        val (baseTitleSizeSp, baseArtistSizeSp, baseLyricSizeSp) = when (tier) {
            DeviceTier.CAR_OR_LARGE -> Triple(16.5f, 13.5f, 15f)
            DeviceTier.TABLET -> Triple(13.5f, 11.5f, 12.5f)
            DeviceTier.PHONE -> Triple(10.5f, 9f, 9.8f)
        }

        val (baseCloseSizeDp, baseClosePadDp) = when (tier) {
            DeviceTier.CAR_OR_LARGE -> Pair(32f, 5f)
            DeviceTier.TABLET -> Pair(26f, 4f)
            DeviceTier.PHONE -> Pair(18f, 2f)
        }

        val (basePadStartDp, basePadEndDp, basePadVDp) = capsulePaddingDp(tier)

        val (baseInfoMarginStartDp, baseInfoMarginEndDp) = when (tier) {
            DeviceTier.CAR_OR_LARGE -> Pair(10f, 6f)
            DeviceTier.TABLET -> Pair(8f, 5f)
            DeviceTier.PHONE -> Pair(6f, 4f)
        }

        val baseTitleMaxWDp = when (tier) {
            DeviceTier.CAR_OR_LARGE -> 150f
            DeviceTier.TABLET -> 115f
            DeviceTier.PHONE -> 80f
        }

        val capsuleH = (baseCapsuleHeightDp * scale * density).toInt()
        val capsuleW = if (isCollapsed) capsuleH else (baseCapsuleWidthDp * scale * density).toInt()

        // 折叠状态下边界厚度减半：外圈留白边距由原先减半，黑胶唱片相应饱满
        val borderPad = if (isCollapsed) {
            (basePadVDp * 0.5f * scale * density).toInt().coerceAtLeast(1)
        } else {
            (basePadVDp * scale * density).toInt()
        }
        val coverSize = if (isCollapsed) {
            (capsuleH - borderPad * 2).coerceAtLeast(0)
        } else {
            (baseCoverSizeDp * scale * density).toInt()
        }

        capsuleContainer?.let { container ->
            val lp = container.layoutParams
            lp.width = capsuleW
            lp.height = capsuleH
            container.layoutParams = lp
            if (isCollapsed) {
                container.setPadding(borderPad, borderPad, borderPad, borderPad)
            } else {
                container.setPadding(
                    (basePadStartDp * scale * density).toInt(),
                    borderPad,
                    (basePadEndDp * scale * density).toInt(),
                    borderPad
                )
            }
        }

        infoContainer?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        btnPlayPause?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        btnClose?.visibility = if (isCollapsed) View.GONE else View.VISIBLE

        coverContainer?.let { container ->
            val lp = container.layoutParams
            lp.width = coverSize
            lp.height = coverSize
            container.layoutParams = lp
        }

        infoContainer?.let { info ->
            (info.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.marginStart = (baseInfoMarginStartDp * scale * density).toInt()
                lp.marginEnd = (baseInfoMarginEndDp * scale * density).toInt()
                info.layoutParams = lp
            }
        }

        tvTitle?.let {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSizeSp * scale)
            it.maxWidth = (baseTitleMaxWDp * scale * density).toInt()
        }
        tvTitleDot?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTitleSizeSp * scale * 0.8f)
        tvArtist?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseArtistSizeSp * scale)
        tvLyric?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseLyricSizeSp * scale)
        tvLyricNext?.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseLyricSizeSp * scale * 0.92f)

        btnPlayPause?.let { btn ->
            val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: LinearLayout.LayoutParams(0, 0)
            val pSize = (basePlaySizeDp * scale * density).toInt()
            lp.width = pSize
            lp.height = pSize
            lp.marginEnd = (basePlayMarginEndDp * scale * density).toInt()
            btn.layoutParams = lp
            val p = (basePlayPadDp * scale * density).toInt()
            btn.setPadding(p, p, p, p)
        }

        btnClose?.let {
            val lp = it.layoutParams
            val s = (baseCloseSizeDp * scale * density).toInt()
            lp.width = s
            lp.height = s
            it.layoutParams = lp
            val p = (baseClosePadDp * scale * density).toInt()
            it.setPadding(p, p, p, p)
        }

        val capsuleRadius = capsuleH / 2f
        val isDark = isDarkTheme()
        val capsuleStrokeWidth = if (isCollapsed) {
            (0.75f * density).toInt().coerceAtLeast(1)
        } else {
            (1.5f * density).toInt().coerceAtLeast(2)
        }
        val coverStrokeWidth = if (isCollapsed) {
            (0.8f * density).toInt().coerceAtLeast(1)
        } else {
            (1.6f * density).toInt().coerceAtLeast(2)
        }

        (capsuleContainer?.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.let { gd ->
            gd.cornerRadius = capsuleRadius
            val sColor = if (isDark) {
                if (isCollapsed) Color.parseColor("#26FFFFFF") else Color.parseColor("#33FFFFFF")
            } else {
                if (isCollapsed) Color.parseColor("#1A0F172A") else Color.parseColor("#240F172A")
            }
            gd.setStroke(capsuleStrokeWidth, sColor)
        }
        (coverContainer?.background?.mutate() as? android.graphics.drawable.GradientDrawable)?.let { gd ->
            val sColor = if (isDark) {
                if (isCollapsed) Color.parseColor("#2BFFFFFF") else Color.parseColor("#3DFFFFFF")
            } else {
                if (isCollapsed) Color.parseColor("#1C0F172A") else Color.parseColor("#260F172A")
            }
            gd.setStroke(coverStrokeWidth, sColor)
        }

        capsuleContainer?.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = if (view.height > 0) view.height / 2f else capsuleRadius
                outline.setRoundRect(0, 0, view.width, view.height, r.coerceAtLeast(1f))
            }
        }
        capsuleContainer?.clipToOutline = true
        capsuleContainer?.invalidateOutline()

        rootCard?.clipToOutline = false
        val padPx = (2 * density).toInt() * 2
        windowParams?.let { wp ->
            wp.width = capsuleW + padPx
            wp.height = capsuleH + padPx
            clampCoordinates(wp, root, expectedWidthPx = wp.width, expectedHeightPx = wp.height)
            if (root.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(root, wp)
                } catch (_: Exception) {}
            }
        }
        updateResizeOverlayGeometry(root)
    }

    private fun enterResizeMode() {
        if (isResizeMode) return
        val view = floatingView ?: return
        isResizeMode = true
        suppressExitOnNextUp = true

        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Exception) {}

        rootCard?.clipToOutline = false

        val density = context.resources.displayMetrics.density
        val dashStroke = (if (isCollapsed) 0.8f else 1.6f) * density
        resizeFrame?.background = PillDashFrameDrawable(
            strokeWidthPx = dashStroke,
            dashLengthPx = 4.5f * density,
            dashColor = if (isDarkTheme()) Color.argb(215, 255, 255, 255) else Color.argb(190, 15, 23, 42)
        )
        resizeOverlay?.visibility = View.VISIBLE
        updateResizeOverlayGeometry(view)
        updateResizeHintText(dragging = false)
        // 确保手柄处于顶层，不被遮挡
        resizeHandles.forEach { it.view.bringToFront() }
        if (isCollapsed) {
            // 折叠态空间小，避免中心 hint 遮盖四角拖动手柄
            resizeHint?.visibility = View.GONE
        } else {
            resizeHint?.visibility = View.VISIBLE
        }

        val fadeViews = listOfNotNull(resizeFrame) + resizeHandles.map { it.view }
        fadeViews.forEach { target ->
            target.alpha = 0f
            target.animate().alpha(1f).setDuration(180).start()
        }
        scheduleResizeIdleExit()
        val (baseW, baseH) = baseCapsuleSizeDp()
        AppLogger.i(
            TAG,
            "进入尺寸调整模式: scale=$userScaleFactor " +
                    "size=${(baseW * userScaleFactor).toInt()}x${(baseH * userScaleFactor).toInt()}dp"
        )
    }

    private fun exitResizeMode() {
        if (!isResizeMode) return
        isResizeMode = false
        suppressExitOnNextUp = false
        longPressTriggered = false
        cancelResizeIdleExit()
        infoContainer?.animate()?.translationX(0f)?.setDuration(100)?.start()
        resizeOverlay?.visibility = View.GONE

        capsuleContainer?.invalidateOutline()

        if (isCollapsed) {
            AppPreferences.FloatingWindow.collapsedScale = collapsedScaleFactor
            AppLogger.i(TAG, "退出尺寸调整模式(折叠): scale=$collapsedScaleFactor")
        } else {
            AppPreferences.FloatingWindow.userScale = userScaleFactor
            AppLogger.i(TAG, "退出尺寸调整模式(展开): scale=$userScaleFactor")
        }
    }

    private fun scheduleResizeIdleExit() {
        cancelResizeIdleExit()
        val runnable = Runnable { exitResizeMode() }
        resizeIdleRunnable = runnable
        uiHandler.postDelayed(runnable, RESIZE_IDLE_TIMEOUT_MS)
    }

    private fun cancelResizeIdleExit() {
        resizeIdleRunnable?.let { uiHandler.removeCallbacks(it) }
        resizeIdleRunnable = null
    }

    private fun scheduleInfoLongPress() {
        cancelInfoLongPress()
        longPressTriggered = false
        val runnable = Runnable {
            if (!isResizeMode) {
                longPressTriggered = true
                try {
                    infoContainer?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } catch (_: Exception) {}
                infoContainer?.animate()?.translationX(0f)?.setDuration(100)?.start()
                enterResizeMode()
            }
        }
        infoLongPressRunnable = runnable
        uiHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelInfoLongPress() {
        infoLongPressRunnable?.let { uiHandler.removeCallbacks(it) }
        infoLongPressRunnable = null
    }

    private fun updateResizeOverlayGeometry(root: View) {
        val container = capsuleContainer ?: return
        val overlay = resizeOverlay ?: return

        val capsuleW = (container.layoutParams?.width ?: 0).takeIf { it > 0 } ?: root.width
        val capsuleH = (container.layoutParams?.height ?: 0).takeIf { it > 0 } ?: root.height
        if (capsuleW <= 0 || capsuleH <= 0) return

        val overlayLp = overlay.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(capsuleW, capsuleH)
        overlayLp.width = capsuleW
        overlayLp.height = capsuleH
        overlayLp.gravity = Gravity.CENTER
        overlay.layoutParams = overlayLp

        resizeFrame?.let { frame ->
            val lp = frame.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(capsuleW, capsuleH)
            lp.width = capsuleW
            lp.height = capsuleH
            frame.layoutParams = lp
        }

        val radius = capsuleH / 2f
        val offset = radius * (1f - 0.70710678f)
        val density = context.resources.displayMetrics.density
        val handleSize = (capsuleH * 0.30f).coerceIn(18f * density, 26f * density)
        val sizePx = handleSize.toInt().coerceAtLeast(1)
        val margin = (offset - handleSize / 2f).coerceAtLeast(0f).toInt()

        resizeHandles.forEach { handle ->
            val lp = handle.view.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(sizePx, sizePx)
            lp.width = sizePx
            lp.height = sizePx
            lp.leftMargin = if (handle.dirX < 0) margin else 0
            lp.rightMargin = if (handle.dirX > 0) margin else 0
            lp.topMargin = if (handle.dirY < 0) margin else 0
            lp.bottomMargin = if (handle.dirY > 0) margin else 0
            handle.view.layoutParams = lp
        }
    }

    private fun updateResizeHintText(dragging: Boolean) {
        val hint = resizeHint ?: return
        val (baseW, baseH) = baseCapsuleSizeDp()
        val scale = if (isCollapsed) collapsedScaleFactor else userScaleFactor
        hint.text = if (dragging) {
            val percent = (scale * 100f).toInt()
            val hDp = (baseH * scale).toInt()
            val wDp = if (isCollapsed) hDp else (baseW * scale).toInt()
            "$percent% · ${wDp}×${hDp}dp"
        } else {
            context.getString(R.string.floating_window_resize_hint_with_theme, currentThemeLabel())
        }
        hint.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (10.5f * scale).coerceIn(9f, 14f)
        )
    }

    private fun applyScaleAroundCenter(targetScale: Float) {
        val view = floatingView ?: return
        val params = windowParams ?: return
        val newScale = clampScale(targetScale, forCollapsed = isCollapsed)
        val current = if (isCollapsed) collapsedScaleFactor else userScaleFactor

        if (abs(newScale - current) < 0.002f) {
            updateResizeHintText(dragging = true)
            return
        }

        val density = context.resources.displayMetrics.density
        val (baseW, baseH) = baseCapsuleSizeDp()

        val padPx = (2 * density).toInt() * 2
        if (isCollapsed) {
            collapsedScaleFactor = newScale
            val newH = baseH * density * newScale
            val newW = newH
            applyAdaptiveDimensions(view)
            params.x = (resizeStartCenterX - padPx / 2f - newW / 2f).toInt()
            params.y = (resizeStartCenterY - padPx / 2f - newH / 2f).toInt()
            params.width = (newW + padPx).toInt()
            params.height = (newH + padPx).toInt()
            clampCoordinates(params, view, newW.toInt(), newH.toInt())
        } else {
            userScaleFactor = newScale
            val newW = baseW * density * newScale
            val newH = baseH * density * newScale
            applyAdaptiveDimensions(view)
            params.x = (resizeStartCenterX - padPx / 2f - newH / 2f).toInt()
            params.y = (resizeStartCenterY - padPx / 2f - newH / 2f).toInt()
            params.width = (newW + padPx).toInt()
            params.height = (newH + padPx).toInt()
            clampCoordinates(params, view, newW.toInt(), newH.toInt())
        }

        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Update layout while resizing failed: ${e.message}")
        }
        updateResizeHintText(dragging = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandles() {
        val density = context.resources.displayMetrics.density
        val (baseW, baseH) = baseCapsuleSizeDp()
        val baseWpx = (baseW * density).coerceAtLeast(1f)
        val baseHpx = (baseH * density).coerceAtLeast(1f)

        resizeHandles.forEach { handle ->
            handle.view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handle.view.parent?.requestDisallowInterceptTouchEvent(true)
                        cancelResizeIdleExit()
                        suppressExitOnNextUp = false
                        resizeStartScale = if (isCollapsed) collapsedScaleFactor else userScaleFactor
                        resizeStartRawX = event.rawX
                        resizeStartRawY = event.rawY
                        val curScale = if (isCollapsed) collapsedScaleFactor else userScaleFactor
                        val curH = baseH * density * curScale
                        val padPx = (2 * density).toInt() * 2
                        windowParams?.let { p ->
                            resizeStartCenterX = p.x + padPx / 2f + curH / 2f
                            resizeStartCenterY = p.y + padPx / 2f + curH / 2f
                        }
                        handle.view.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - resizeStartRawX
                        val dy = event.rawY - resizeStartRawY
                        val delta = FloatingResizeMath.scaleDelta(
                            dxPx = dx,
                            dyPx = dy,
                            dirX = handle.dirX,
                            dirY = handle.dirY,
                            baseWidthPx = if (isCollapsed) baseHpx else baseWpx,
                            baseHeightPx = baseHpx
                        )
                        applyScaleAroundCenter(resizeStartScale + delta)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handle.view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        if (isCollapsed) {
                            AppPreferences.FloatingWindow.collapsedScale = collapsedScaleFactor
                        } else {
                            AppPreferences.FloatingWindow.userScale = userScaleFactor
                        }
                        AppLogger.i(TAG, "拖动圆点结束: isCollapsed=$isCollapsed scale=${if (isCollapsed) collapsedScaleFactor else userScaleFactor}")
                        scheduleResizeIdleExit()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun setupClickListeners() {
        val onCoverLongClickListener = View.OnLongClickListener {
            if (!isResizeMode) {
                enterResizeMode()
                true
            } else {
                false
            }
        }
        coverContainer?.setOnLongClickListener(onCoverLongClickListener)
        ivCover?.setOnLongClickListener(onCoverLongClickListener)

        coverContainer?.setOnClickListener {
            toggleCollapsed()
        }
        ivCover?.setOnClickListener {
            toggleCollapsed()
        }
        btnPlayPause?.setOnClickListener {
            sendPlayerAction(PlayerService.ACTION_TOGGLE_PLAYBACK)
        }
        btnClose?.setOnClickListener {
            hide()
        }
    }

    fun setCollapsed(collapsed: Boolean, animate: Boolean = true) {
        if (isCollapsed == collapsed) return
        isCollapsed = collapsed
        AppPreferences.FloatingWindow.isCollapsed = collapsed
        val view = floatingView ?: return
        if (!view.isAttachedToWindow) return

        if (animate) {
            animateCollapseTransition(view, collapsed)
        } else {
            collapseTransitionAnimator?.cancel()
            collapseTransitionAnimator = null
            applyAdaptiveDimensions(view)
        }
    }

    fun toggleCollapsed() {
        setCollapsed(!isCollapsed, animate = true)
    }

    /** 折叠过渡中某一帧的胶囊几何参数（像素） */
    private data class CapsuleTransitionFrame(
        val capsuleW: Int,
        val capsuleH: Int,
        val coverSize: Int,
    )

    private fun lerpTransitionFrame(
        from: CapsuleTransitionFrame,
        to: CapsuleTransitionFrame,
        t: Float,
    ): CapsuleTransitionFrame {
        fun lerp(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt()
        return CapsuleTransitionFrame(
            capsuleW = lerp(from.capsuleW, to.capsuleW),
            capsuleH = lerp(from.capsuleH, to.capsuleH),
            coverSize = lerp(from.coverSize, to.coverSize),
        )
    }

    private fun lerpArgb(from: Int, to: Int, t: Float): Int {
        fun lerpChannel(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt()
        return Color.argb(
            lerpChannel(Color.alpha(from), Color.alpha(to)),
            lerpChannel(Color.red(from), Color.red(to)),
            lerpChannel(Color.green(from), Color.green(to)),
            lerpChannel(Color.blue(from), Color.blue(to)),
        )
    }

    /**
     * 描边宽度/颜色也跟随过渡插值：动画结束时 applyTheme() 会重建背景，
     * 如果颜色是最后一次性切换的，就会看到边框“跳一下”。两段各走一半、停顿期间静止，
     * 插值到与 applyTheme 完全一致的终值后，这次重建在视觉上是连续的。
     *
     * @param progress 0 = 展开态样式，1 = 折叠态样式
     */
    private fun applyCollapseVisualStyle(progress: Float, isDark: Boolean = isDarkTheme()) {
        val density = context.resources.displayMetrics.density
        val f = progress.coerceIn(0f, 1f)

        val expandedCapsuleStroke = (1.5f * density).toInt().coerceAtLeast(2).toFloat()
        val collapsedCapsuleStroke = (0.75f * density).toInt().coerceAtLeast(1).toFloat()
        val expandedCoverStroke = (1.6f * density).toInt().coerceAtLeast(2).toFloat()
        val collapsedCoverStroke = (0.8f * density).toInt().coerceAtLeast(1).toFloat()
        val capsuleStroke = (expandedCapsuleStroke + (collapsedCapsuleStroke - expandedCapsuleStroke) * f)
            .roundToInt().coerceAtLeast(1)
        val coverStroke = (expandedCoverStroke + (collapsedCoverStroke - expandedCoverStroke) * f)
            .roundToInt().coerceAtLeast(1)

        val capsuleColor = lerpArgb(
            if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#240F172A"),
            if (isDark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1A0F172A"),
            f,
        )
        val coverColor = lerpArgb(
            if (isDark) Color.parseColor("#3DFFFFFF") else Color.parseColor("#260F172A"),
            if (isDark) Color.parseColor("#2BFFFFFF") else Color.parseColor("#1C0F172A"),
            f,
        )

        (capsuleContainer?.background as? android.graphics.drawable.GradientDrawable)?.let { gd ->
            gd.setStroke(capsuleStroke, capsuleColor)
        }
        (coverContainer?.background as? android.graphics.drawable.GradientDrawable)?.let { gd ->
            gd.setStroke(coverStroke, coverColor)
        }
        capsuleContainer?.invalidate()
        coverContainer?.invalidate()
    }

    /**
     * 应用一帧过渡几何。
     *
     * 三条硬性约束（就是之前“抖动”的根因）：
     * 1. 过渡期间**不改封面的 layout 尺寸**，封面放大/缩小完全由浮点 scaleX/scaleY 驱动。
     *    逐帧改 layout 会让圆形位图每帧按新的整数尺寸重新采样，视觉上就是亚像素抖动/闪烁。
     * 2. 胶囊内边距在过渡期间固定为展开态，封面用浮点 translationX 摆到正确位置，
     *    因此歌词信息栏不会被逐帧重排。
     * 3. 窗口只有在宽高/坐标的**整数**结果真的变化时才 updateViewLayout；
     *    默认配置下「封面缩放」这段窗口完全不动，绝不会出现整窗 1px 跳格。
     *
     * 圆心锚定：封面视觉圆心 = windowX + 左内边距 + 封面 layout 中心 + translationX，
     * 反推 windowX 即可保证过渡全程封面圆心钉在 anchorX/anchorY 上。
     */
    private fun applyCapsuleTransitionFrame(
        root: View,
        anchorX: Float,
        anchorY: Float,
        bounds: Rect,
        frame: CapsuleTransitionFrame,
        coverLayoutSize: Int,
        coverLayoutCenterX: Float,
        coverTranslationX: Float,
    ) {
        capsuleContainer?.let { capsule ->
            val lp = capsule.layoutParams
            if (lp.width != frame.capsuleW || lp.height != frame.capsuleH) {
                lp.width = frame.capsuleW
                lp.height = frame.capsuleH
                capsule.layoutParams = lp
            }
            (capsule.background as? android.graphics.drawable.GradientDrawable)?.let { gd ->
                gd.cornerRadius = frame.capsuleH / 2f
            }
            capsule.invalidate()
        }

        coverContainer?.let { cover ->
            val scale = if (coverLayoutSize > 0) frame.coverSize.toFloat() / coverLayoutSize else 1f
            if (cover.scaleX != scale) {
                cover.scaleX = scale
                cover.scaleY = scale
            }
            if (cover.translationX != coverTranslationX) {
                cover.translationX = coverTranslationX
            }
        }

        val params = windowParams ?: return
        val width = frame.capsuleW + root.paddingLeft + root.paddingRight
        val height = frame.capsuleH + root.paddingTop + root.paddingBottom
        val x = (anchorX - root.paddingLeft - coverLayoutCenterX - coverTranslationX).roundToInt()
        val y = (anchorY - root.paddingTop - frame.capsuleH / 2f).roundToInt()
        val targetX = x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
        val targetY = y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
        if (params.width == width && params.height == height &&
            params.x == targetX && params.y == targetY
        ) {
            return
        }
        params.width = width
        params.height = height
        params.x = targetX
        params.y = targetY
        if (root.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 折叠/展开过渡：严格分成两段，中间停顿 [STAGE_HOLD_MS]。
     *
     * - 折叠：歌词收窄淡出 -> 停顿 -> 封面（黑胶唱片）放大铺满圆球
     * - 展开：封面缩小回展开尺寸 -> 停顿 -> 歌词向右铺开淡入
     *
     * 两段共用同一个中间态（宽度已收成正圆、仍保持展开高度与展开封面）。
     * 封面始终以自身圆心为锚点：窗口坐标由「左内边距 + 封面 layout 中心 + 浮点平移」反推，
     * 圆心在任何一帧都不会跳。
     */
    private fun animateCollapseTransition(root: View, collapsed: Boolean) {
        val capsule = capsuleContainer ?: return
        val cover = coverContainer ?: return

        collapseTransitionAnimator?.cancel()
        collapseTransitionAnimator = null
        if (windowParams == null) return

        val density = context.resources.displayMetrics.density
        val tier = getDeviceTier()
        val (baseW, baseH) = baseCapsuleSizeDp(tier)
        val (padStartDp, padEndDp, padVDp) = capsulePaddingDp(tier)
        val coverDp = coverBaseSizeDp(tier)

        val expandedScale = userScaleFactor
        val expanded = CapsuleTransitionFrame(
            capsuleW = (baseW * expandedScale * density).toInt(),
            capsuleH = (baseH * expandedScale * density).toInt(),
            coverSize = (coverDp * expandedScale * density).toInt(),
        )
        val collapsedH = (baseH * collapsedScaleFactor * density).toInt()
        val collapsedPad = (padVDp * 0.5f * collapsedScaleFactor * density).toInt().coerceAtLeast(1)
        val collapsedFrame = CapsuleTransitionFrame(
            capsuleW = collapsedH,
            capsuleH = collapsedH,
            coverSize = (collapsedH - collapsedPad * 2).coerceAtLeast(0),
        )
        val circle = expanded.copy(capsuleW = expanded.capsuleH)

        val expandedPadL = (padStartDp * expandedScale * density).toInt()
        val expandedPadT = (padVDp * expandedScale * density).toInt()
        val expandedPadR = (padEndDp * expandedScale * density).toInt()
        val expandedPadB = (padVDp * expandedScale * density).toInt()

        // 过渡期间封面的 layout 尺寸 / 胶囊内边距都固定不变，只靠浮点变换表现大小与位置，
        // 这样位图不会被逐帧重新布局采样，歌词栏也不会跟着重排。
        val coverLayoutSize = expanded.coverSize.coerceAtLeast(1)
        val coverLayoutCenterX = expandedPadL + coverLayoutSize / 2f

        // 记录当前真实视觉状态（可能是一次被打断的过渡）
        val anchorLocation = IntArray(2)
        cover.getLocationOnScreen(anchorLocation)
        val startScale = cover.scaleX.takeIf { it > 0f } ?: 1f
        val startCoverVisualPx = ((cover.width.takeIf { it > 0 } ?: expanded.coverSize) * startScale)
            .roundToInt()
            .coerceAtLeast(1)
        val oldPadL = capsule.paddingLeft
        val oldCoverLayout = cover.width.takeIf { it > 0 } ?: expanded.coverSize
        val oldCoverCenterX = oldPadL + oldCoverLayout / 2f + cover.translationX

        val startFrame = CapsuleTransitionFrame(
            capsuleW = capsule.width.takeIf { it > 0 }
                ?: (if (collapsed) expanded.capsuleW else collapsedFrame.capsuleW),
            capsuleH = capsule.height.takeIf { it > 0 }
                ?: (if (collapsed) expanded.capsuleH else collapsedFrame.capsuleH),
            coverSize = startCoverVisualPx,
        )

        // 圆心锚点（屏幕坐标），必须在改 padding/layout 之前取
        val anchorCenterX = anchorLocation[0] + cover.width * startScale / 2f
        val anchorCenterY = anchorLocation[1] + cover.height * startScale / 2f

        // 统一使用展开态内边距 + 固定封面 layout；视觉由 scale/translation 无缝接续
        if (capsule.paddingLeft != expandedPadL || capsule.paddingTop != expandedPadT ||
            capsule.paddingRight != expandedPadR || capsule.paddingBottom != expandedPadB
        ) {
            capsule.setPadding(expandedPadL, expandedPadT, expandedPadR, expandedPadB)
        }
        val coverParams = cover.layoutParams
        if (coverParams.width != coverLayoutSize || coverParams.height != coverLayoutSize) {
            coverParams.width = coverLayoutSize
            coverParams.height = coverLayoutSize
            cover.layoutParams = coverParams
        }
        val startTranslationX = oldCoverCenterX - coverLayoutCenterX
        cover.scaleX = startCoverVisualPx.toFloat() / coverLayoutSize
        cover.scaleY = cover.scaleX
        cover.translationX = startTranslationX

        // 「圆盘居中于悬浮球」时需要的水平平移量
        fun centeredTranslation(capsuleHeight: Int): Float =
            capsuleHeight / 2f - coverLayoutCenterX

        val circleTranslation = centeredTranslation(expanded.capsuleH)
        val collapsedTranslation = centeredTranslation(collapsedFrame.capsuleH)
        val endFrame = if (collapsed) collapsedFrame else expanded
        val endTranslation = if (collapsed) collapsedTranslation else 0f

        val bounds = getScreenBounds()
        val rightViews = listOfNotNull(infoContainer, btnPlayPause, btnClose)
        val startAlpha = if (infoContainer?.visibility == View.VISIBLE) (infoContainer?.alpha ?: 1f) else 0f
        val endAlpha = if (collapsed) 0f else 1f

        // 第一段：折叠 = 歌词收窄淡出；展开 = 封面放大回展开尺寸
        val firstStageMs = (if (collapsed) LYRICS_FOLD_MS else COVER_ZOOM_MS).toFloat()
        // 第二段：折叠 = 封面缩放成悬浮球；展开 = 歌词向右铺开淡入
        val secondStageMs = (if (collapsed) COVER_ZOOM_MS else LYRICS_FOLD_MS).toFloat()
        val totalMs = firstStageMs + STAGE_HOLD_MS.toFloat() + secondStageMs
        val easing = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val isDark = isDarkTheme()

        var lastFrame: CapsuleTransitionFrame? = null
        var lastTranslation = Float.NaN
        var lastStyleProgress = Float.NaN
        var rightVisibilitySettled = false

        val animator = ValueAnimator.ofFloat(0f, totalMs).apply {
            duration = totalMs.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val elapsed = va.animatedValue as Float
                val frame: CapsuleTransitionFrame
                var translation: Float
                var alpha: Float
                val styleProgress: Float
                when {
                    elapsed <= firstStageMs -> {
                        val t = easing.getInterpolation((elapsed / firstStageMs).coerceIn(0f, 1f))
                        frame = lerpTransitionFrame(startFrame, circle, t)
                        translation = startTranslationX + (circleTranslation - startTranslationX) * t
                        alpha = startAlpha + (0f - startAlpha) * t
                        styleProgress = if (collapsed) 0.5f * t else 1f - 0.5f * t
                    }
                    elapsed <= firstStageMs + STAGE_HOLD_MS -> {
                        frame = circle
                        translation = circleTranslation
                        alpha = 0f
                        styleProgress = 0.5f
                        if (!rightVisibilitySettled) {
                            rightVisibilitySettled = true
                            // 折叠：歌词已完全淡出，直接撤掉，避免继续参与布局；
                            // 展开：为第二段的淡入提前挂上（alpha 仍为 0，不会闪出来）
                            rightViews.forEach {
                                it.visibility = if (collapsed) View.GONE else View.VISIBLE
                            }
                        }
                    }
                    else -> {
                        val t = easing.getInterpolation(
                            ((elapsed - firstStageMs - STAGE_HOLD_MS.toFloat()) / secondStageMs).coerceIn(0f, 1f)
                        )
                        frame = lerpTransitionFrame(circle, endFrame, t)
                        translation = circleTranslation + (endTranslation - circleTranslation) * t
                        alpha = endAlpha * t
                        styleProgress = if (collapsed) 0.5f + 0.5f * t else 0.5f - 0.5f * t
                    }
                }

                if (frame != lastFrame || translation != lastTranslation) {
                    lastFrame = frame
                    lastTranslation = translation
                    applyCapsuleTransitionFrame(
                        root = root,
                        anchorX = anchorCenterX,
                        anchorY = anchorCenterY,
                        bounds = bounds,
                        frame = frame,
                        coverLayoutSize = coverLayoutSize,
                        coverLayoutCenterX = coverLayoutCenterX,
                        coverTranslationX = translation,
                    )
                }
                rightViews.forEach { view -> if (view.alpha != alpha) view.alpha = alpha }
                if (styleProgress != lastStyleProgress) {
                    lastStyleProgress = styleProgress
                    applyCollapseVisualStyle(styleProgress, isDark)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    collapseTransitionAnimator = null
                    if (cancelled) return
                    // 先复位浮点变换，再对齐正式布局：两者视觉等价，不会有跳变
                    cover.scaleX = 1f
                    cover.scaleY = 1f
                    cover.translationX = 0f
                    applyAdaptiveDimensions(root)
                    applyTheme(root)
                    rightViews.forEach { view ->
                        view.visibility = if (collapsed) View.GONE else View.VISIBLE
                        view.alpha = 1f
                    }
                    windowParams?.let { wp ->
                        AppPreferences.FloatingWindow.posX = wp.x
                        AppPreferences.FloatingWindow.posY = wp.y
                    }
                }
            })
        }
        collapseTransitionAnimator = animator
        // 结构切换后立刻落一帧，避免「新 layout + 旧变换」被绘制一帧
        applyCapsuleTransitionFrame(
            root = root,
            anchorX = anchorCenterX,
            anchorY = anchorCenterY,
            bounds = bounds,
            frame = startFrame,
            coverLayoutSize = coverLayoutSize,
            coverLayoutCenterX = coverLayoutCenterX,
            coverTranslationX = startTranslationX,
        )
        animator.start()
    }

    private fun sendPlayerAction(action: String) {
        val controller = PlayerService.currentRuntimeController()
        if (controller != null) {
            when (action) {
                PlayerService.ACTION_TOGGLE_PLAYBACK -> {
                    if (controller.playState.value == PlayState.PLAYING) {
                        controller.pause()
                    } else {
                        controller.resume()
                    }
                    return
                }
                PlayerService.ACTION_NEXT -> {
                    controller.next()
                    return
                }
                PlayerService.ACTION_PREVIOUS -> {
                    controller.previous()
                    return
                }
            }
        }

        runCatching {
            val intent = Intent(context, PlayerService::class.java).setAction(action)
            context.startService(intent)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndDrag(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialTouchXInView = 0f
        var isDragging = false
        var isScaling = false

        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                val factor = detector.scaleFactor
                if (isCollapsed) {
                    collapsedScaleFactor = clampScale(collapsedScaleFactor * factor, forCollapsed = true)
                } else {
                    userScaleFactor = clampScale(userScaleFactor * factor, forCollapsed = false)
                }
                applyAdaptiveDimensions(view)
                applyTheme(view)
                clampCoordinates(params, view)
                try {
                    if (view.isAttachedToWindow) {
                        windowManager.updateViewLayout(view, params)
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Update layout on scale failed: ${e.message}")
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (isCollapsed) {
                    AppPreferences.FloatingWindow.collapsedScale = collapsedScaleFactor
                } else {
                    AppPreferences.FloatingWindow.userScale = userScaleFactor
                }
            }
        })

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isResizeMode) return true
                if (isCollapsed) {
                    val targetScale = if (collapsedScaleFactor < 1.2f) 1.35f else 1.0f
                    setCollapsedScale(targetScale)
                } else {
                    val targetScale = if (userScaleFactor < 1.2f) 1.35f else 1.0f
                    setUserScale(targetScale)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isResizeMode) {
                    enterResizeMode()
                }
            }
        })

        infoContainer?.let { container ->
            setupInfoTouchListener(container)
        }

        view.setOnTouchListener { _, event ->
            if (isResizeMode) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> suppressExitOnNextUp = false
                    MotionEvent.ACTION_UP -> tryExitOnTouchUp()
                }
                return@setOnTouchListener true
            }

            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)

            if (event.pointerCount > 1 || isScaling) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isScaling = false
                    isDragging = false
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialTouchXInView = event.x
                    isDragging = false
                    isScaling = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging && !isScaling) {
                        val bounds = getScreenBounds()
                        val vWidth = view.width.takeIf { it > 0 } ?: 0
                        val vHeight = view.height.takeIf { it > 0 } ?: 0
                        val maxX = (bounds.width() - vWidth).coerceAtLeast(0)
                        val maxY = (bounds.height() - vHeight).coerceAtLeast(0)

                        params.x = (initialX + dx).coerceIn(0, maxX)
                        params.y = (initialY + dy).coerceIn(0, maxY)

                        try {
                            if (view.isAttachedToWindow) {
                                windowManager.updateViewLayout(view, params)
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Update floating window position failed: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        clampCoordinates(params, view)
                        AppPreferences.FloatingWindow.posX = params.x
                        AppPreferences.FloatingWindow.posY = params.y
                    } else if (!isScaling && !isResizeMode) {
                        if (isCollapsed) {
                            toggleCollapsed()
                        } else {
                            val density = context.resources.displayMetrics.density
                            val coverRight = coverContainer?.right ?: 0
                            if (coverRight > 0 && initialTouchXInView <= coverRight + 12 * density) {
                                toggleCollapsed()
                            } else {
                                openMainActivity()
                            }
                        }
                    }
                    isDragging = false
                    isScaling = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInfoTouchListener(container: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val density = context.resources.displayMetrics.density
        val swipeThreshold = 36 * density
        var startX = 0f
        var startY = 0f
        var isHorizontalSwiping = false

        container.setOnTouchListener { v, event ->
            if (isResizeMode) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> suppressExitOnNextUp = false
                    MotionEvent.ACTION_UP -> tryExitOnTouchUp()
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isHorizontalSwiping = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    scheduleInfoLongPress()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        cancelInfoLongPress()
                    }
                    if (!isHorizontalSwiping && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                        isHorizontalSwiping = true
                    }
                    if (isHorizontalSwiping) {
                        v.translationX = dx * 0.45f
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelInfoLongPress()
                    if (longPressTriggered) {
                        longPressTriggered = false
                        v.animate().translationX(0f).alpha(1f).setDuration(120).start()
                        isHorizontalSwiping = false
                        return@setOnTouchListener true
                    }
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val totalMove = abs(dx) + abs(dy)

                    if (isHorizontalSwiping) {
                        if (dx < -swipeThreshold) {
                            v.animate().translationX(-45f * density).alpha(0.3f).setDuration(120).withEndAction {
                                v.translationX = 45f * density
                                v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                            }.start()
                            sendPlayerAction(PlayerService.ACTION_NEXT)
                        } else if (dx > swipeThreshold) {
                            v.animate().translationX(45f * density).alpha(0.3f).setDuration(120).withEndAction {
                                v.translationX = -45f * density
                                v.animate().translationX(0f).alpha(1f).setDuration(160).start()
                            }.start()
                            sendPlayerAction(PlayerService.ACTION_PREVIOUS)
                        } else {
                            v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                        }
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                        if (totalMove <= touchSlop) {
                            openMainActivity()
                        }
                    }
                    isHorizontalSwiping = false
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainActivity() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch MainActivity: ${e.message}", e)
        }
    }

    private fun startObservingPlayback() {
        lyricsCollectJob?.cancel()
        lyricsCollectJob = scope.launch {
            PlayerService.currentLyrics.collectLatest { data ->
                if (data != null && !data.isEmpty) {
                    currentLyricLines = data.lines
                    updateLyricLine(getCurrentPlaybackPositionMs())
                }
            }
        }

        playbackPollingJob?.cancel()
        playbackPollingJob = scope.launch {
            while (isActive) {
                var pollDelayMs = PAUSED_POLL_MS
                val controller = PlayerService.currentRuntimeController()
                if (controller != null) {
                    val song = controller.currentSong.value
                    val isPlaying = controller.playState.value == PlayState.PLAYING
                    val pos = controller.position.value
                    updatePlaybackInfo(song, isPlaying, pos)
                    pollDelayMs = if (isPlaying) POSITION_POLL_MS else PAUSED_POLL_MS
                } else {
                    updatePlaybackInfo(null, false, 0L)
                }
                delay(pollDelayMs)
            }
        }
    }

    private fun getCurrentPlaybackPositionMs(): Long {
        return PlayerService.currentRuntimeController()?.position?.value ?: 0L
    }

    private fun updatePlaybackInfo(song: AudioFile?, isPlaying: Boolean, currentPositionMs: Long) {
        if (!isShowing || floatingView == null) return

        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_float_pause else R.drawable.ic_float_play
        )
        updateCoverRotation(isPlaying)

        if (song != null) {
            val identity = "${song.path}|${song.cueTrackIndex}|${song.cueOffsetMs}"
            if (identity != currentSongIdentity) {
                currentSongIdentity = identity
                tvTitle?.text = song.title.ifBlank { "RawS-Music" }
                tvArtist?.text = song.artist.ifBlank { "未知艺术家" }
                tvLyric?.text = "正在同步歌词..."
                tvLyric?.setTextColor(primaryLyricColor())
                tvLyricNext?.text = ""
                lastLyricStateKey = ""
                currentLineSplitPart2StartMs = Long.MAX_VALUE
                isCurrentSplitSecondPartActive = false

                loadSongCover(song)

                val lyrics = PlayerService.currentLyrics.value
                if (lyrics != null && !lyrics.isEmpty) {
                    currentLyricLines = lyrics.lines
                    updateLyricLine(currentPositionMs)
                } else {
                    currentLyricLines = emptyList()
                    updateLyricLine(currentPositionMs)
                }
            } else {
                updateLyricLine(currentPositionMs)
            }
        } else {
            currentSongIdentity = null
            currentLyricLines = emptyList()
            lastLyricStateKey = ""
            currentLineSplitPart2StartMs = Long.MAX_VALUE
            isCurrentSplitSecondPartActive = false
            tvTitle?.text = "RawS-Music"
            tvArtist?.text = "未在播放音乐"
            tvLyric?.text = "享受美好旋律"
            tvLyricNext?.text = ""
            ivCover?.setImageResource(R.drawable.ic_default_album_art)
        }
    }

    private fun updateCoverRotation(isPlaying: Boolean) {
        val cover = ivCover ?: return
        if (coverRotateAnimator == null) {
            coverRotateAnimator = ObjectAnimator.ofFloat(cover, View.ROTATION, 0f, 360f).apply {
                duration = 18000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
        }
        val anim = coverRotateAnimator ?: return
        if (isPlaying) {
            if (anim.isPaused) {
                anim.resume()
            } else if (!anim.isStarted) {
                anim.start()
            }
        } else {
            if (anim.isRunning) {
                anim.pause()
            }
        }
    }

    private fun loadSongCover(song: AudioFile) {
        coverLoadJob?.cancel()
        ivCover?.setImageResource(R.drawable.ic_default_album_art)

        coverLoadJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    BitmapProvider.execute(
                        key = song.coverKey,
                        targetWidth = 512,
                        targetHeight = 512,
                        surface = ArtworkSurface.Widget
                    )
                }.getOrNull()
            }

            if (isActive && isShowing && song.path == PlayerService.currentRuntimeController()?.currentSong?.value?.path) {
                if (bitmap != null && !bitmap.isRecycled) {
                    ivCover?.setImageBitmap(bitmap)
                } else {
                    ivCover?.setImageResource(R.drawable.ic_default_album_art)
                }
            }
        }
    }

    private val lyricDecelerateInterpolator = DecelerateInterpolator(1.6f)

    private fun isSequentialLyricState(prev: String, curr: String): Boolean {
        if (prev.isEmpty() || prev == "empty" || prev == "none") return false
        if (prev == "intro" && curr.startsWith("line_")) return true

        val prevIdx = parseStateIndex(prev)
        val currIdx = parseStateIndex(curr)
        if (prevIdx == null || currIdx == null) return false

        if (prev == "line_$prevIdx" && curr == "interlude_$prevIdx") return true
        if (prev == "interlude_$prevIdx" && curr == "line_${prevIdx + 1}") return true
        if (prev == "line_$prevIdx" && curr == "line_${prevIdx + 1}") return true
        if (prev == "line_$prevIdx" && curr == "outro") return true

        return false
    }

    private fun parseStateIndex(key: String): Int? {
        return when {
            key.startsWith("line_") -> key.removePrefix("line_").toIntOrNull()
            key.startsWith("interlude_") -> key.removePrefix("interlude_").toIntOrNull()
            key == "outro" -> 999999
            else -> null
        }
    }

    private fun updateLyricLine(currentPositionMs: Long) {
        val lyricPositionMs = com.rawsmusic.core.common.utils.LyricPlaybackTimeline.toLyricTime(currentPositionMs)
        if (currentLyricLines.isEmpty()) {
            if (lastLyricStateKey != "empty") {
                lastLyricStateKey = "empty"
                currentLineSplitPart2StartMs = Long.MAX_VALUE
                isCurrentSplitSecondPartActive = false
                setLyricDirectly("♪ 享受音乐旋律 ♪", "", primaryLyricColor(), secondaryLyricColor())
            }
            return
        }

        val state = FloatingLyricHelper.resolveLyricState(currentLyricLines, lyricPositionMs)
        if (state.stateKey != lastLyricStateKey) {
            val prevStateKey = lastLyricStateKey
            lastLyricStateKey = state.stateKey

            val targetText1: String
            val targetText2: String
            val targetColor1: Int
            val targetColor2: Int

            if (state.isInstrumental) {
                currentLineSplitPart2StartMs = Long.MAX_VALUE
                isCurrentSplitSecondPartActive = false
                targetText1 = state.text1
                targetText2 = state.text2
                targetColor1 = primaryLyricColor()
                targetColor2 = secondaryLyricColor()
            } else {
                val currentText = state.text1
                val nextText = state.text2
                val paint = tvLyric?.paint
                val availableWidth = if ((tvLyric?.width ?: 0) > 0) {
                    tvLyric!!.width.toFloat()
                } else {
                    val density = context.resources.displayMetrics.density
                    102f * userScaleFactor * density
                }
                val textWidth = paint?.measureText(currentText) ?: 0f

                if (textWidth > availableWidth && currentText.length >= 7) {
                    val split = FloatingLyricHelper.splitLongLyric(
                        line = state.activeLine,
                        displayText = currentText,
                        currentPositionMs = lyricPositionMs,
                        nextLine = state.nextLine
                    )
                    currentLineSplitPart2StartMs = split.part2StartMs
                    isCurrentSplitSecondPartActive = split.isSecondPartActive
                    targetText1 = split.part1
                    targetText2 = split.part2
                    if (split.isSecondPartActive) {
                        targetColor1 = activeSplitSecondaryLyricColor()
                        targetColor2 = primaryLyricColor()
                    } else {
                        targetColor1 = primaryLyricColor()
                        targetColor2 = activeSplitSecondaryLyricColor()
                    }
                } else {
                    currentLineSplitPart2StartMs = Long.MAX_VALUE
                    isCurrentSplitSecondPartActive = false
                    targetText1 = currentText
                    targetText2 = nextText
                    targetColor1 = primaryLyricColor()
                    targetColor2 = secondaryLyricColor()
                }
            }

            if (isSequentialLyricState(prevStateKey, state.stateKey) && isShowing) {
                animateLyricTransition(targetText1, targetText2, targetColor1, targetColor2)
            } else {
                setLyricDirectly(targetText1, targetText2, targetColor1, targetColor2)
            }
        } else if (currentLineSplitPart2StartMs != Long.MAX_VALUE) {
            val secondPartNowActive = lyricPositionMs >= currentLineSplitPart2StartMs
            if (secondPartNowActive != isCurrentSplitSecondPartActive) {
                isCurrentSplitSecondPartActive = secondPartNowActive
                if (secondPartNowActive) {
                    tvLyric?.setTextColor(activeSplitSecondaryLyricColor())
                    tvLyricNext?.setTextColor(primaryLyricColor())
                } else {
                    tvLyric?.setTextColor(primaryLyricColor())
                    tvLyricNext?.setTextColor(activeSplitSecondaryLyricColor())
                }
            }
        }
    }

    private fun animateLyricTransition(
        targetText1: String,
        targetText2: String,
        targetColor1: Int,
        targetColor2: Int
    ) {
        val l1 = tvLyric ?: return
        val l2 = tvLyricNext ?: return

        l1.animate().cancel()
        l2.animate().cancel()

        val shiftDistance = 9f * context.resources.displayMetrics.density

        l1.animate()
            .translationY(-shiftDistance)
            .alpha(0f)
            .setDuration(120L)
            .setInterpolator(lyricDecelerateInterpolator)
            .withEndAction {
                l1.text = targetText1
                l1.setTextColor(targetColor1)
                l2.text = targetText2
                l2.setTextColor(targetColor2)

                l1.translationY = shiftDistance * 0.7f
                l1.alpha = 0f
                l1.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(lyricDecelerateInterpolator)
                    .start()

                l2.translationY = shiftDistance * 0.7f
                l2.alpha = 0f
                l2.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(lyricDecelerateInterpolator)
                    .start()
            }
            .start()

        l2.animate()
            .translationY(-shiftDistance)
            .alpha(0f)
            .setDuration(120L)
            .setInterpolator(lyricDecelerateInterpolator)
            .start()
    }

    private fun setLyricDirectly(
        targetText1: String,
        targetText2: String,
        targetColor1: Int,
        targetColor2: Int
    ) {
        val l1 = tvLyric ?: return
        val l2 = tvLyricNext ?: return
        l1.animate().cancel()
        l2.animate().cancel()
        l1.translationY = 0f
        l1.alpha = 1f
        l1.text = targetText1
        l1.setTextColor(targetColor1)
        l2.translationY = 0f
        l2.alpha = 1f
        l2.text = targetText2
        l2.setTextColor(targetColor2)
    }
}
