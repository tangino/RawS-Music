package com.rawsmusic.core.ui.widget.player.gl

import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rawsmusic.core.ui.widget.player.VisualizerColorScheme
import com.rawsmusic.core.ui.widget.player.resolveColors
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlin.math.max
import kotlin.math.min

/**
 * 3D Raymarching 音频可视化渲染器通用接口。
 */
interface IAudioRaymarchingRenderer : GLSurfaceView.Renderer {
    var isPlaying: Boolean
    var targetBass: Float
    var targetMid: Float
    var targetTreble: Float
    var color1R: Float
    var color1G: Float
    var color1B: Float
    var color2R: Float
    var color2G: Float
    var color2B: Float
    fun release()
}

/**
 * 承载 OpenGL ES 2.0 / 3.0 Raymarching 渲染器的通用 Compose 包装组件。
 *
 * 核心特性（对应 AGENTS §6.4）：
 * 1. 移动端 3D Raymarching 光线步进黄金渲染分辨率：短边限制在 400px，维持屏幕宽高比，
 *    通过 GPU 硬件 Scaler 全屏拉伸，消除 85% 片元负荷，保证 60FPS。
 * 2. 严格的生命周期绑定：后台或页面不可见时调用 `onPause()` 停止渲染，恢复时 `onResume()`；dispose 时释放 GL 资源。
 * 3. 实时从 112 频点提取低/中/高频能量，并同步颜色状态。
 */
@Composable
fun <T : IAudioRaymarchingRenderer> GLVisualizerSurface(
    rendererFactory: () -> T,
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember(rendererFactory)

    val colorScheme = VisualizerColorScheme.fromId(AppPreferences.UI.Visualizer.colorSchemeId)
    val singleColor = AppPreferences.UI.Visualizer.singleColor
    val customColor = AppPreferences.UI.Visualizer.customColorArgb
    val customColor2 = AppPreferences.UI.Visualizer.customColor2Argb

    val (primaryColor, secondaryColor, _) = colorScheme.resolveColors(
        isSingleColor = singleColor,
        customColorArgb = customColor,
        customColor2Argb = customColor2,
    )

    // 实时同步颜色与播放状态
    LaunchedEffect(primaryColor, secondaryColor, isPlaying) {
        renderer.color1R = primaryColor.red
        renderer.color1G = primaryColor.green
        renderer.color1B = primaryColor.blue
        renderer.color2R = secondaryColor.red
        renderer.color2G = secondaryColor.green
        renderer.color2B = secondaryColor.blue
        renderer.isPlaying = isPlaying
    }

    // 实时从频点提取低频(Bass)、中频(Mid)、高频(Treble)能量
    val currentSpectrum = spectrumState?.value ?: spectrum
    LaunchedEffect(currentSpectrum) {
        if (currentSpectrum.isEmpty()) {
            renderer.targetBass = 0f
            renderer.targetMid = 0f
            renderer.targetTreble = 0f
            return@LaunchedEffect
        }

        val size = currentSpectrum.size
        // 1. 低频段 (Bass / Kick，前 1/8 频段)
        val bassEnd = (size / 8).coerceAtLeast(1)
        var bassSum = 0f
        for (i in 0 until bassEnd) {
            bassSum += currentSpectrum[i]
        }
        val bass = (bassSum / bassEnd).coerceIn(0f, 1.5f)

        // 2. 中频段 (Mid / Vocal，中间 3/8 频段)
        val midEnd = (size / 2).coerceAtLeast(bassEnd + 1)
        var midSum = 0f
        var midCount = 0
        for (i in bassEnd until midEnd.coerceAtMost(size)) {
            midSum += currentSpectrum[i]
            midCount++
        }
        val mid = if (midCount > 0) (midSum / midCount).coerceIn(0f, 1.5f) else 0f

        // 3. 高频段 (Treble / Air，后 1/2 频段)
        var trebleSum = 0f
        var trebleCount = 0
        for (i in midEnd until size) {
            trebleSum += currentSpectrum[i]
            trebleCount++
        }
        val treble = if (trebleCount > 0) (trebleSum / trebleCount).coerceIn(0f, 1.5f) else 0f

        renderer.targetBass = bass
        renderer.targetMid = mid
        renderer.targetTreble = treble
    }

    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner, visible) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (visible) glSurfaceViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> glSurfaceViewRef?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceViewRef?.onPause()
            renderer.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx: Context ->
                object : GLSurfaceView(ctx) {
                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        if (w > 0 && h > 0) {
                            // 移动端 3D Raymarching 光线步进黄金渲染分辨率 (短边限制在 400px，维持屏幕宽高比)
                            val maxShortEdge = 400f
                            val shortEdge = min(w, h).toFloat()
                            val scale = if (shortEdge > maxShortEdge) maxShortEdge / shortEdge else 1.0f
                            val targetW = max(1, (w * scale).toInt())
                            val targetH = max(1, (h * scale).toInt())
                            holder.setFixedSize(targetW, targetH)
                        }
                    }
                }.apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    glSurfaceViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
