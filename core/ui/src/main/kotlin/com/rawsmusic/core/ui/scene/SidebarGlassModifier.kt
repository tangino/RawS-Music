package com.rawsmusic.core.ui.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.module.data.prefs.BottomBarMaterial
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 侧边栏/常驻导航栏的背景修饰符，深度接入并跟随应用的外观材质系统（BottomBarMaterial 与 GlobalLiquidGlassSettings）。
 * 支持液态玻璃（透镜折射 + 高光边缘 + 内阴影）、亚克力毛玻璃与纯色兜底。
 */
@Composable
internal fun Modifier.sidebarGlassModifier(
    shape: Shape,
    customMaterial: BottomBarMaterial? = null,
): Modifier {
    // 场景内部组件必须采样 LocalAppBackgroundBackdrop（纯底层壁纸图层），
    // 严禁采样包含当前场景自身的 LocalAppBackdrop，以防止 Skia 遍历渲染树时递归自引用引发 RenderThread SIGSEGV。
    val backdrop = LocalAppBackgroundBackdrop.current
    val bottomBarMaterial by PersonalizationPreferences.bottomBarMaterial.collectAsState()
    val material = customMaterial ?: bottomBarMaterial
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val isLight = !isSystemInDarkTheme() && !ThemeManager.albumColorForcesDark(context)

    // 当材质为纯色，或底层背景图层未就绪时，平滑降级为经典半透明卡片
    if (material == BottomBarMaterial.SOLID || backdrop == null) {
        return this.background(
            color = colors.surfaceContainer.copy(alpha = if (isLight) 0.88f else 0.80f),
            shape = shape,
        )
    }

    val effectiveBlurRadiusDp = if (performanceMode) {
        glassSettings.blurRadiusDp.coerceAtMost(10f)
    } else {
        glassSettings.blurRadiusDp
    }

    return when (material) {
        BottomBarMaterial.SOLID -> this
        BottomBarMaterial.ACRYLIC -> this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy(0.72f * glassSettings.vibrancyStrength)
                blur((effectiveBlurRadiusDp * 1.55f).dp.toPx())
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = (0.22f * glassSettings.highlightStrength).coerceIn(0f, 0.6f),
                )
            },
            shadow = {
                Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(
                        alpha = (0.12f * glassSettings.shadowStrength).coerceIn(0f, 0.35f),
                    ),
                )
            },
            innerShadow = {
                InnerShadow(
                    radius = 5.dp,
                    alpha = (0.10f * glassSettings.shadowStrength).coerceIn(0f, 0.35f),
                )
            },
            onDrawSurface = {
                drawRect(colors.surfaceContainer.copy(alpha = if (isLight) 0.72f else 0.58f))
            },
        )
        BottomBarMaterial.LIQUID_GLASS -> this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                val minDimension = size.minDimension
                val liquidBlurDp = (effectiveBlurRadiusDp * 0.25f)
                    .coerceAtMost(if (performanceMode) 2f else 8f)
                vibrancy(glassSettings.vibrancyStrength)
                blur(liquidBlurDp.dp.toPx())
                lens(
                    refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f,
                    refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.25f,
                    depthEffect = true,
                    chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                )
            },
            highlight = {
                Highlight.Default.copy(
                    alpha = (0.34f * glassSettings.highlightStrength).coerceIn(0f, 1f),
                )
            },
            shadow = {
                Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(
                        alpha = (0.12f * glassSettings.shadowStrength).coerceIn(0f, 0.4f),
                    ),
                )
            },
            innerShadow = {
                InnerShadow(
                    radius = 6.dp,
                    alpha = (0.22f * glassSettings.shadowStrength).coerceIn(0f, 1f),
                )
            },
            onDrawSurface = {
                drawRect(colors.surfaceContainer.copy(alpha = if (isLight) 0.26f else 0.16f))
            },
        )
    }
}
