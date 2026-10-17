package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 专供场景内部组件（如常驻侧栏 PersistentLibraryRail）采样的纯底层背景 Backdrop。
 * 仅包含底层的渐变与动态壁纸，不包含当前场景/页面节点自身，从而根绝 RenderNode 树自循环引用崩溃。
 */
val LocalAppBackgroundBackdrop = staticCompositionLocalOf<Backdrop?> { null }
