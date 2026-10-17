package com.rawsmusic.core.common.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 界面与车机低 DPI 高分屏自适应缩放计算工具
 */
@Suppress("DEPRECATION")
object UiScaleHelper {

    const val MODE_AUTO = "auto"
    const val MODE_080 = "0.8"
    const val MODE_090 = "0.9"
    const val MODE_100 = "1.0"
    const val MODE_110 = "1.1"
    const val MODE_125 = "1.25"
    const val MODE_150 = "1.5"
    const val MODE_175 = "1.75"
    const val MODE_200 = "2.0"
    const val MODE_225 = "2.25"

    // 锁定缓存设备真实的硬件物理指标，避免被后续 updateConfiguration 污染导致基准漂移
    @Volatile
    private var rawMetricsInitialized = false
    private var rawWidthPixels: Int = 0
    private var rawHeightPixels: Int = 0
    private var rawDensityDpi: Int = 0
    private var rawDensity: Float = 0f

    /**
     * 获取设备真实的原始物理 DisplayMetrics（不随 Activity / Application 缩放配置改变）
     */
    fun getRawDisplayMetrics(context: Context): DisplayMetrics {
        if (!rawMetricsInitialized || rawDensityDpi == 0) {
            synchronized(this) {
                if (!rawMetricsInitialized || rawDensityDpi == 0) {
                    val metrics = DisplayMetrics()
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            context.display.getRealMetrics(metrics)
                        } catch (_: Exception) {
                            @Suppress("DEPRECATION")
                            wm?.defaultDisplay?.getRealMetrics(metrics)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        wm?.defaultDisplay?.getRealMetrics(metrics)
                    }
                    if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
                        val dm = context.resources.displayMetrics
                        metrics.widthPixels = dm.widthPixels
                        metrics.heightPixels = dm.heightPixels
                        metrics.density = dm.density
                        metrics.densityDpi = dm.densityDpi
                        metrics.scaledDensity = dm.scaledDensity
                    }
                    rawWidthPixels = metrics.widthPixels
                    rawHeightPixels = metrics.heightPixels
                    rawDensityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else 160
                    rawDensity = if (metrics.density > 0f) metrics.density else (rawDensityDpi / 160f)
                    rawMetricsInitialized = true
                }
            }
        }

        return DisplayMetrics().apply {
            widthPixels = rawWidthPixels
            heightPixels = rawHeightPixels
            densityDpi = rawDensityDpi
            density = rawDensity
            scaledDensity = rawDensity
        }
    }

    /**
     * 兼容方法：获取物理真实像素 DisplayMetrics
     */
    fun getRealDisplayMetrics(context: Context): DisplayMetrics {
        return getRawDisplayMetrics(context)
    }

    /**
     * 根据当前选择的缩放模式与屏幕物理指标，计算实际 UI 缩放比例因子
     *
     * @param mode 缩放模式: "auto", "0.8", "0.9", "1.0", "1.1", "1.25", "1.5", "1.75", "2.0", "2.25"
     * @param widthPixels 物理屏幕宽
     * @param heightPixels 物理屏幕高
     * @param systemDensityDpi 系统报告的原始屏幕密度 DPI
     * @return UI 缩放倍率 (例如 0.8f, 1.0f, 1.75f 等)
     */
    fun calculateScaleFactor(
        mode: String,
        widthPixels: Int,
        heightPixels: Int,
        systemDensityDpi: Int
    ): Float {
        if (mode != MODE_AUTO) {
            return mode.toFloatOrNull()?.coerceIn(0.6f, 3.0f) ?: 1.0f
        }

        val longEdge = maxOf(widthPixels, heightPixels)

        // 智能车机与低 DPI 高分屏检测算法:
        // 当系统物理密度配置为低 DPI (<= 175 DPI，如标准的 160mdpi 车机)，但屏幕物理像素较大时 (如 2560x1600, 2560x1440, 1920x1080 车机)
        if (systemDensityDpi <= 175) {
            return when {
                longEdge >= 3200 -> 2.25f // 4K 级别大屏 (等效 360 DPI)
                longEdge >= 2200 -> 1.75f // 2.5K / 2K 屏幕 (如 2560x1600/1440，等效 280 DPI，最佳车机触控尺寸)
                longEdge >= 1800 -> 1.40f // 1080P/1200P 屏幕 (等效 224 DPI)
                else -> 1.0f
            }
        } else if (systemDensityDpi <= 215) {
            // 中低密度 2K/4K 车机补偿
            return when {
                longEdge >= 3200 -> 1.80f
                longEdge >= 2400 -> 1.35f
                else -> 1.0f
            }
        }

        // 正常手机或已配置合理 DPI 的设备保持原生 1.0x
        return 1.0f
    }

    /**
     * 将缩放比例同步应用到 Activity 与 Application 的 Resources / DisplayMetrics 中，
     * 确保所有的系统级子窗口 (如 Dialog、DropdownMenu、PopupWindow、Toast 等) 均自动生效缩放
     */
    fun applyActivityDensity(activity: Activity, scaleFactor: Float) {
        try {
            val rawMetrics = getRawDisplayMetrics(activity)
            val baseDensityDpi = rawMetrics.densityDpi
            val targetDensityDpi = (baseDensityDpi * scaleFactor).toInt()
            val targetDensity = rawMetrics.density * scaleFactor
            val fontScale = activity.resources.configuration.fontScale

            val config = Configuration(activity.resources.configuration).apply {
                this.densityDpi = targetDensityDpi
                if (targetDensity > 0f) {
                    val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
                    val wPx = if (isPortrait) minOf(rawMetrics.widthPixels, rawMetrics.heightPixels) else maxOf(rawMetrics.widthPixels, rawMetrics.heightPixels)
                    val hPx = if (isPortrait) maxOf(rawMetrics.widthPixels, rawMetrics.heightPixels) else minOf(rawMetrics.widthPixels, rawMetrics.heightPixels)
                    screenWidthDp = (wPx / targetDensity).toInt()
                    screenHeightDp = (hPx / targetDensity).toInt()
                    smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
                }
            }

            val dm = activity.resources.displayMetrics
            dm.density = targetDensity
            dm.densityDpi = targetDensityDpi
            dm.scaledDensity = targetDensity * fontScale

            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, dm)

            // 同步 Application 级 Resources
            val appDm = activity.applicationContext.resources.displayMetrics
            appDm.density = targetDensity
            appDm.densityDpi = targetDensityDpi
            appDm.scaledDensity = targetDensity * fontScale
            val appConfig = Configuration(activity.applicationContext.resources.configuration).apply {
                this.densityDpi = targetDensityDpi
            }
            @Suppress("DEPRECATION")
            activity.applicationContext.resources.updateConfiguration(appConfig, appDm)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
