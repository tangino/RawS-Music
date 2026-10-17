package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.RawWindowWidthClass

/**
 * 根据设备屏幕窗口断点动态计算在线音乐卡片网格列数，
 * 保证在手机、折叠屏、平板、车机横屏大屏设备下卡片大小匀称、自适应呈现。
 *
 * - Compact (<600dp，常规手机竖屏): 2 列
 * - Medium (600..839dp，小平板/折叠展开/手机横屏): 3 列
 * - Expanded (840..1199dp，常规平板竖屏/大折叠展开): 4 列
 * - Large (1200..1599dp，大平板横屏/中控车机): 5 列
 * - ExtraLarge (>=1600dp，超宽大屏/4K/超大中控): 6 列
 */
@Composable
internal fun resolveOnlineGridColumns(): Int {
    val profile = LocalRawWindowLayoutProfile.current
    return when (profile.widthClass) {
        RawWindowWidthClass.Compact -> 2
        RawWindowWidthClass.Medium -> 3
        RawWindowWidthClass.Expanded -> 4
        RawWindowWidthClass.Large -> 5
        RawWindowWidthClass.ExtraLarge -> 6
    }
}
