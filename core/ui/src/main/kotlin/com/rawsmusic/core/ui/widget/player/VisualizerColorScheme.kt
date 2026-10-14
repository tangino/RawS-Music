package com.rawsmusic.core.ui.widget.player

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.rawsmusic.core.ui.R

/**
 * 频谱发光配色方案（对应 P4-02 / OrBit VisualizerColorScheme）。
 *
 * 存储层存的是稳定的 [id] 字符串（见 `AppPreferences.UI.Visualizer.colorSchemeId`），
 * 避免追加/重排枚举时引发序号错位。
 */
enum class VisualizerColorScheme(
    val id: String,
    @param:StringRes val titleRes: Int,
    val primaryColor: Color,
    val secondaryColor: Color,
    val peakColor: Color,
) {
    FOLLOW_BACKGROUND(
        id = "follow_background",
        titleRes = R.string.visualizer_color_follow_background,
        primaryColor = Color(0xFF00E5FF),
        secondaryColor = Color(0xFF7C4DFF),
        peakColor = Color(0xFFFFFFFF),
    ),
    NEON_CYAN_PURPLE(
        id = "neon_cyan_purple",
        titleRes = R.string.visualizer_color_neon_cyan_purple,
        primaryColor = Color(0xFF00F0FF),
        secondaryColor = Color(0xFFB026FF),
        peakColor = Color(0xFFE0FFFF),
    ),
    FIRE_AMBER(
        id = "fire_amber",
        titleRes = R.string.visualizer_color_fire_amber,
        primaryColor = Color(0xFFFF9100),
        secondaryColor = Color(0xFFFF1744),
        peakColor = Color(0xFFFFD54F),
    ),
    ELECTRIC_GREEN(
        id = "electric_green",
        titleRes = R.string.visualizer_color_electric_green,
        primaryColor = Color(0xFF00E676),
        secondaryColor = Color(0xFF00B0FF),
        peakColor = Color(0xFFB9F6CA),
    ),
    CUSTOM(
        id = "custom",
        titleRes = R.string.visualizer_color_custom,
        primaryColor = Color(0xFF00E5FF),
        secondaryColor = Color(0xFF7C4DFF),
        peakColor = Color(0xFFFFFFFF),
    );

    companion object {
        fun fromId(id: String?): VisualizerColorScheme =
            entries.firstOrNull { it.id == id } ?: FOLLOW_BACKGROUND
    }
}

/**
 * 根据配色方案及相关参数，解析实际绘制所需的 (主色, 副色, 峰值色) 三色元组。
 * 逻辑与 OrBit 保持一致，包括跟随背景和 HSV 峰值亮度推导。
 */
fun VisualizerColorScheme.resolveColors(
    isSingleColor: Boolean = false,
    customColorArgb: Long = 0xFF00B8D4L,
    customColor2Argb: Long = 0xFF818CF8L,
    backgroundLightColor: Color? = null,
    backgroundDarkColor: Color? = null,
    fallbackPrimary: Color = Color(0xFF00E5FF),
    fallbackSecondary: Color = Color(0xFF7C4DFF),
): Triple<Color, Color, Color> {
    return when (this) {
        VisualizerColorScheme.FOLLOW_BACKGROUND -> {
            val light = backgroundLightColor ?: fallbackPrimary
            val dark = backgroundDarkColor ?: fallbackSecondary
            val refColor = if (isSingleColor) light else dark
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(refColor.toArgb(), hsv)
            val peakHsv = floatArrayOf(hsv[0], (hsv[1] * 0.20f).coerceIn(0f, 0.35f), 1.0f)
            val peakCol = Color(android.graphics.Color.HSVToColor(peakHsv))
            Triple(light, if (isSingleColor) light else dark, peakCol)
        }
        VisualizerColorScheme.CUSTOM -> {
            val base = Color(customColorArgb)
            val sec = if (isSingleColor) base else Color(customColor2Argb)
            val refColor = if (isSingleColor) customColorArgb else customColor2Argb
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV((refColor and 0xFFFFFFFFL).toInt(), hsv)
            val peakHsv = floatArrayOf(hsv[0], (hsv[1] * 0.20f).coerceIn(0f, 0.35f), 1.0f)
            val peakCol = Color(android.graphics.Color.HSVToColor(peakHsv))
            Triple(base, sec, peakCol)
        }
        else -> {
            Triple(
                primaryColor,
                if (isSingleColor) primaryColor else secondaryColor,
                peakColor,
            )
        }
    }
}
