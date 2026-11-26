package com.rawsmusic.helper

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.core.ui.theme.ColorThemeMode
import com.rawsmusic.core.ui.theme.getCurrentColorThemeMode

class SystemBarsHelper(
    private val activity: Activity
) {
    fun setStatusBarHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (hidden) {
            // Treat a top-edge swipe as a temporary system reveal rather than a permanent policy
            // change. This matches the immersive activities in the app: a peek can expose the
            // status bar/notification affordance, then the controller restores the requested
            // hidden state automatically when the transient system UI is dismissed.
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * 切换大屏沉浸式全屏显示：
     * - 开启时同时隐藏状态栏与导航栏（边缘滑动可临时呼出并自动隐藏），并延伸至异形屏边缘；
     * - 关闭时恢复导航栏，状态栏遵循用户原有的 [statusBarHidden] 偏好。
     */
    fun setFullscreenMode(fullscreen: Boolean, statusBarHidden: Boolean = false) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            }
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
            setStatusBarHidden(statusBarHidden)
        }
    }

    fun setupEdgeToEdge(isDarkMode: Boolean) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // Project-style edge-to-edge: the system gesture/navigation handle stays visible,
        // but the app surface is allowed to draw behind it. Do not paint a scrim here.
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
    }

    fun updateForScene(isMainScene: Boolean, isDarkMode: Boolean) {
        val window = activity.window
        val colorTheme = getCurrentColorThemeMode()

        // Monet 模式下不覆盖状态栏颜色，让 RawMonetSystemBars 管理
        if (colorTheme != ColorThemeMode.MIUIX) {
            WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = !isDarkMode
            return
        }

        // MIUIX 模式：状态栏/导航栏都保持透明，具体页面自己绘制背景。
        // This mirrors the design: content goes edge-to-edge, controls opt into safe insets.
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isMainScene && !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }
    }
}
