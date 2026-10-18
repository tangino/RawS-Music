package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.RawWindowWidthClass

/**
 * 根据设备屏幕窗口断点动态计算在线音乐卡片网格列数，
 * 默认在大平板横屏与车机大屏下展示高密度 8 列精致卡片布局。
 *
 * - Compact (<600dp，常规手机竖屏): 3 列
 * - Medium (600..839dp，小平板/折叠展开/手机横屏): 4 列
 * - Expanded (840..1199dp，常规平板竖屏): 6 列
 * - Large / ExtraLarge (>=1200dp，大平板横屏/中控车机): 8 列
 */
@Composable
internal fun resolveOnlineGridColumns(): Int {
    val profile = LocalRawWindowLayoutProfile.current
    return when (profile.widthClass) {
        RawWindowWidthClass.Compact -> 3
        RawWindowWidthClass.Medium -> 4
        RawWindowWidthClass.Expanded -> 6
        RawWindowWidthClass.Large,
        RawWindowWidthClass.ExtraLarge -> 8
    }
}
