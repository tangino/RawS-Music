package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 经典柱状 + 悬浮缓降峰值物理运动管理器。
 *
 * 移植物理参数（对应 AGENTS §6.4 / OrBit PowerampSpectrumVisualizer）：
 * - Attack 极速响应（36 s⁻¹ / 约 0.82 响应）
 * - Decay 优雅阻尼（14 s⁻¹ / 约 0.68 响应）
 * - 峰值滞留：120ms（0.12s）
 * - 峰值自由落体：加速度 2.4 / 保持峰值不低于柱高
 */
internal class BarsWithPeaksMotion(private val capacity: Int = 128) {
    val displayBars = FloatArray(capacity)
    val displayPeaks = FloatArray(capacity)
    private val peakVelocities = FloatArray(capacity)
    private val peakHoldTimes = FloatArray(capacity)
    private var lastTimeSeconds = Float.NaN

    fun advance(
        timeSeconds: Float,
        sourceLevels: FloatArray,
        activeCount: Int,
        playing: Boolean,
    ) {
        val dt = if (lastTimeSeconds.isNaN() || timeSeconds < lastTimeSeconds) {
            1f / 60f
        } else {
            (timeSeconds - lastTimeSeconds).coerceIn(0.001f, 0.05f)
        }
        lastTimeSeconds = timeSeconds

        val attackFactor = (1f - exp((-36f * dt).toDouble())).toFloat().coerceIn(0f, 1f)
        val decayFactor = (1f - exp((-14f * dt).toDouble())).toFloat().coerceIn(0f, 1f)
        val sourceSize = sourceLevels.size

        for (i in 0 until activeCount.coerceAtMost(capacity)) {
            val targetMag = if (playing && sourceSize > 0) {
                val pos = (i.toFloat() / (activeCount - 1).coerceAtLeast(1)) * (sourceSize - 1)
                val i0 = pos.toInt().coerceIn(0, sourceSize - 1)
                val i1 = (i0 + 1).coerceIn(0, sourceSize - 1)
                val frac = pos - i0
                (sourceLevels[i0] * (1f - frac) + sourceLevels[i1] * frac).coerceIn(0f, 1f)
            } else 0f

            val currentBar = displayBars[i]
            if (targetMag > currentBar) {
                displayBars[i] = currentBar + (targetMag - currentBar) * attackFactor
            } else {
                displayBars[i] = currentBar + (targetMag - currentBar) * decayFactor
            }
            if (displayBars[i] < 0.002f) displayBars[i] = 0f

            val updatedBar = displayBars[i]
            if (updatedBar >= displayPeaks[i]) {
                displayPeaks[i] = updatedBar
                peakVelocities[i] = 0f
                peakHoldTimes[i] = 0.12f // 顶峰滞留 120ms
            } else {
                if (peakHoldTimes[i] > 0f) {
                    peakHoldTimes[i] -= dt
                } else {
                    peakVelocities[i] += 2.4f * dt // 自由落体重力加速度
                    val newPeak = displayPeaks[i] - peakVelocities[i] * dt
                    displayPeaks[i] = max(newPeak, updatedBar)
                }
            }
            if (displayPeaks[i] < 0.002f) displayPeaks[i] = 0f
        }
    }

    fun hasMotion(): Boolean {
        for (i in displayBars.indices) {
            if (displayBars[i] > 0.002f || displayPeaks[i] > 0.002f) return true
        }
        return false
    }
}

/**
 * 形态 6：经典柱状 + 悬浮缓降峰值 (BarsWithPeaks)。
 */
@Composable
fun BarsWithPeaksSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 240 else 170,
            easing = FastOutSlowInEasing,
        ),
        label = "bars-with-peaks-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val barsMotion = remember { BarsWithPeaksMotion(128) }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy() && !barsMotion.hasMotion()) {
        return
    }

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                visible = visible,
                playing = isPlaying,
                spectrumState = spectrumState,
                extraAnimationActive = barsMotion::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val barWidthDp = AppPreferences.UI.Visualizer.barWidthDp
        val barAlpha = AppPreferences.UI.Visualizer.barAlpha
        val borderWidthDp = AppPreferences.UI.Visualizer.borderWidthDp
        val borderColorArgb = AppPreferences.UI.Visualizer.borderColorArgb
        val borderAlpha = AppPreferences.UI.Visualizer.borderAlpha
        val borderOnly = AppPreferences.UI.Visualizer.borderOnly
        val singleColor = AppPreferences.UI.Visualizer.singleColor
        val customColor = AppPreferences.UI.Visualizer.customColorArgb
        val customColor2 = AppPreferences.UI.Visualizer.customColor2Argb
        val peakDecayEnabled = AppPreferences.UI.Visualizer.peakDecayEnabled
        val colorScheme = VisualizerColorScheme.fromId(AppPreferences.UI.Visualizer.colorSchemeId)

        val (primaryColor, secondaryColor, peakColor) = colorScheme.resolveColors(
            isSingleColor = singleColor,
            customColorArgb = customColor,
            customColor2Argb = customColor2,
        )

        val totalW = size.width
        val totalH = size.height

        val spacingDp = (barWidthDp * 0.35f).coerceIn(1.2f, 4.0f)
        val barStepDp = barWidthDp + spacingDp
        val availableWidthDp = totalW / density
        val count = if (availableWidthDp > 0f) {
            ((availableWidthDp + spacingDp) / barStepDp).toInt().coerceIn(16, 128)
        } else 32

        barsMotion.advance(
            timeSeconds = motion.animationTimeSeconds,
            sourceLevels = motion.levels,
            activeCount = count,
            playing = isPlaying,
        )

        val spacingPx = spacingDp.dp.toPx()
        val barWPx = ((totalW - (count - 1) * spacingPx) / count).coerceAtLeast(1.0f)
        val capHeightPx = 2.2.dp.toPx()
        val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

        val strokeWPx = if (borderOnly && borderWidthDp < 0.2f) 1.4.dp.toPx() else borderWidthDp.dp.toPx()
        val hasBorder = (strokeWPx > 0.05f && borderAlpha > 0.01f) || borderOnly
        val borderStrokeColor = if (hasBorder) {
            Color(borderColorArgb).copy(alpha = (borderAlpha * barAlpha).coerceIn(0f, 1f))
        } else Color.Transparent

        val effectiveBarAlpha = barAlpha.coerceIn(0.1f, 1f)

        for (i in 0 until count) {
            val mag = barsMotion.displayBars[i]
            val peak = barsMotion.displayPeaks[i]

            val barH = (mag * (totalH - capHeightPx - 4.dp.toPx())).coerceAtLeast(2.5f)
            val x = i * (barWPx + spacingPx)
            val y = totalH - barH

            // 1. 柱体填充
            if (!borderOnly) {
                if (singleColor) {
                    drawRoundRect(
                        color = primaryColor.copy(alpha = effectiveBarAlpha),
                        topLeft = Offset(x, y),
                        size = Size(barWPx, barH),
                        cornerRadius = corner,
                    )
                } else {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                secondaryColor.copy(alpha = effectiveBarAlpha),
                                primaryColor.copy(alpha = 0.95f * effectiveBarAlpha),
                                primaryColor.copy(alpha = effectiveBarAlpha),
                            ),
                            startY = y,
                            endY = totalH,
                        ),
                        topLeft = Offset(x, y),
                        size = Size(barWPx, barH),
                        cornerRadius = corner,
                    )
                }
            }

            // 2. 精致边框
            if (hasBorder) {
                val halfStroke = strokeWPx / 2f
                if (barWPx > strokeWPx && barH > strokeWPx) {
                    drawRoundRect(
                        color = borderStrokeColor,
                        topLeft = Offset(x + halfStroke, y + halfStroke),
                        size = Size(barWPx - strokeWPx, barH - strokeWPx),
                        cornerRadius = CornerRadius(
                            (corner.x - halfStroke).coerceAtLeast(0f),
                            (corner.y - halfStroke).coerceAtLeast(0f),
                        ),
                        style = Stroke(width = strokeWPx),
                    )
                } else {
                    drawRoundRect(
                        color = borderStrokeColor,
                        topLeft = Offset(x, y),
                        size = Size(barWPx, barH),
                        cornerRadius = corner,
                        style = Stroke(width = strokeWPx),
                    )
                }
            }

            // 3. 悬浮缓降峰值帽
            if (peakDecayEnabled && peak > 0.04f) {
                val peakH = (peak * (totalH - capHeightPx - 4.dp.toPx())).coerceAtLeast(barH)
                val peakY = (totalH - peakH - capHeightPx - 1.5.dp.toPx()).coerceAtLeast(0f)

                drawRoundRect(
                    color = peakColor.copy(alpha = effectiveBarAlpha),
                    topLeft = Offset(x, peakY),
                    size = Size(barWPx, capHeightPx),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/**
 * 形态 7：平滑极光山脉波形 (AuroraMountain)。
 * 基于三次贝塞尔平滑流光曲线拟合 + 底部渐变填充 + 峰值星点。
 */
@Composable
fun AuroraMountainSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 240 else 170,
            easing = FastOutSlowInEasing,
        ),
        label = "aurora-mountain-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val barsMotion = remember { BarsWithPeaksMotion(64) }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy() && !barsMotion.hasMotion()) {
        return
    }

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                visible = visible,
                playing = isPlaying,
                spectrumState = spectrumState,
                extraAnimationActive = barsMotion::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val barAlpha = AppPreferences.UI.Visualizer.barAlpha
        val singleColor = AppPreferences.UI.Visualizer.singleColor
        val customColor = AppPreferences.UI.Visualizer.customColorArgb
        val customColor2 = AppPreferences.UI.Visualizer.customColor2Argb
        val peakDecayEnabled = AppPreferences.UI.Visualizer.peakDecayEnabled
        val colorScheme = VisualizerColorScheme.fromId(AppPreferences.UI.Visualizer.colorSchemeId)

        val (primaryColor, secondaryColor, peakColor) = colorScheme.resolveColors(
            isSingleColor = singleColor,
            customColorArgb = customColor,
            customColor2Argb = customColor2,
        )

        val totalW = size.width
        val totalH = size.height
        val count = 48

        barsMotion.advance(
            timeSeconds = motion.animationTimeSeconds,
            sourceLevels = motion.levels,
            activeCount = count,
            playing = isPlaying,
        )

        if (count < 2) return@Canvas

        val stepX = totalW / (count - 1)
        val path = Path()
        val fillPath = Path()

        val points = Array(count) { i ->
            val mag = barsMotion.displayBars[i]
            val y = totalH - (mag * (totalH - 8.dp.toPx())).coerceAtLeast(3f)
            Offset(i * stepX, y)
        }

        path.moveTo(points[0].x, points[0].y)
        fillPath.moveTo(0f, totalH)
        fillPath.lineTo(points[0].x, points[0].y)

        for (i in 0 until count - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val cx = (p0.x + p1.x) / 2f
            path.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
        }

        fillPath.lineTo(totalW, totalH)
        fillPath.close()

        val effectiveAlpha = barAlpha.coerceIn(0.1f, 1f)

        // 1. 底部半透明垂直渐变填充
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = 0.50f * effectiveAlpha),
                    primaryColor.copy(alpha = 0.20f * effectiveAlpha),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = totalH,
            ),
        )

        // 2. 顶部平滑流光描边光带
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = effectiveAlpha),
                    peakColor.copy(alpha = effectiveAlpha),
                    secondaryColor.copy(alpha = effectiveAlpha),
                ),
            ),
            style = Stroke(
                width = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
            ),
        )

        // 3. 极值浮标星点 (Peak Points)
        if (peakDecayEnabled) {
            for (i in 0 until count step 2) {
                val peak = barsMotion.displayPeaks[i]
                if (peak > 0.05f) {
                    val peakY = (totalH - (peak * (totalH - 8.dp.toPx())) - 4.dp.toPx()).coerceAtLeast(2f)
                    drawCircle(
                        color = peakColor.copy(alpha = effectiveAlpha),
                        radius = 2.0.dp.toPx(),
                        center = Offset(points[i].x, peakY),
                    )
                }
            }
        }
    }
}

/**
 * 形态 8：镜面对称律动蝶形频谱 (MirroredBars)。
 * 中心对称上下展开 + 双色渐变发光柱体。
 */
@Composable
fun MirroredBarsSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 240 else 170,
            easing = FastOutSlowInEasing,
        ),
        label = "mirrored-bars-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val barsMotion = remember { BarsWithPeaksMotion(128) }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy() && !barsMotion.hasMotion()) {
        return
    }

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                visible = visible,
                playing = isPlaying,
                spectrumState = spectrumState,
                extraAnimationActive = barsMotion::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val barAlpha = AppPreferences.UI.Visualizer.barAlpha
        val borderWidthDp = AppPreferences.UI.Visualizer.borderWidthDp
        val borderColorArgb = AppPreferences.UI.Visualizer.borderColorArgb
        val borderAlpha = AppPreferences.UI.Visualizer.borderAlpha
        val borderOnly = AppPreferences.UI.Visualizer.borderOnly
        val singleColor = AppPreferences.UI.Visualizer.singleColor
        val customColor = AppPreferences.UI.Visualizer.customColorArgb
        val customColor2 = AppPreferences.UI.Visualizer.customColor2Argb
        val colorScheme = VisualizerColorScheme.fromId(AppPreferences.UI.Visualizer.colorSchemeId)

        val (primaryColor, secondaryColor, _) = colorScheme.resolveColors(
            isSingleColor = singleColor,
            customColorArgb = customColor,
            customColor2Argb = customColor2,
        )

        val totalW = size.width
        val totalH = size.height

        val spacingPx = 2.5.dp.toPx()
        val count = 48
        val barWPx = ((totalW - (count - 1) * spacingPx) / count).coerceAtLeast(1.5f)
        val centerY = totalH / 2f
        val capHeightPx = 2.0.dp.toPx()
        val corner = CornerRadius(1.2.dp.toPx(), 1.2.dp.toPx())

        val strokeWPx = if (borderOnly && borderWidthDp < 0.2f) 1.4.dp.toPx() else borderWidthDp.dp.toPx()
        val hasBorder = (strokeWPx > 0.05f && borderAlpha > 0.01f) || borderOnly
        val borderStrokeColor = if (hasBorder) {
            Color(borderColorArgb).copy(alpha = (borderAlpha * barAlpha).coerceIn(0f, 1f))
        } else Color.Transparent

        barsMotion.advance(
            timeSeconds = motion.animationTimeSeconds,
            sourceLevels = motion.levels,
            activeCount = count,
            playing = isPlaying,
        )

        val effectiveAlpha = barAlpha.coerceIn(0.1f, 1f)

        for (i in 0 until count) {
            val mag = barsMotion.displayBars[i]
            val halfH = (mag * (centerY - capHeightPx - 3.dp.toPx())).coerceAtLeast(2f)
            val x = i * (barWPx + spacingPx)
            val fullH = halfH * 2f
            val topY = centerY - halfH

            // 1. 上下对称发光柱体
            if (!borderOnly) {
                if (singleColor) {
                    drawRoundRect(
                        color = primaryColor.copy(alpha = effectiveAlpha),
                        topLeft = Offset(x, topY),
                        size = Size(barWPx, fullH),
                        cornerRadius = corner,
                    )
                } else {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                secondaryColor.copy(alpha = effectiveAlpha),
                                primaryColor.copy(alpha = effectiveAlpha),
                                secondaryColor.copy(alpha = effectiveAlpha),
                            ),
                            startY = topY,
                            endY = centerY + halfH,
                        ),
                        topLeft = Offset(x, topY),
                        size = Size(barWPx, fullH),
                        cornerRadius = corner,
                    )
                }
            }

            // 2. 轮廓边框
            if (hasBorder) {
                val halfStroke = strokeWPx / 2f
                if (barWPx > strokeWPx && fullH > strokeWPx) {
                    drawRoundRect(
                        color = borderStrokeColor,
                        topLeft = Offset(x + halfStroke, topY + halfStroke),
                        size = Size(barWPx - strokeWPx, fullH - strokeWPx),
                        cornerRadius = CornerRadius(
                            (corner.x - halfStroke).coerceAtLeast(0f),
                            (corner.y - halfStroke).coerceAtLeast(0f),
                        ),
                        style = Stroke(width = strokeWPx),
                    )
                } else {
                    drawRoundRect(
                        color = borderStrokeColor,
                        topLeft = Offset(x, topY),
                        size = Size(barWPx, fullH),
                        cornerRadius = corner,
                        style = Stroke(width = strokeWPx),
                    )
                }
            }
        }
    }
}
