package com.rawsmusic.ui.floating

/**
 * 悬浮窗"自由调整大小"的纯计算逻辑，独立成无 UI 依赖的对象以便单元测试。
 */
internal object FloatingResizeMath {

    /**
     * 由拖动位移换算等比缩放增量。
     *
     * 等比缩放时，四角圆点无法同时精确跟随水平与垂直位移（宽高比固定），
     * 这里把位移投影到"等比放大"的对角方向，得到最小二乘意义下的最优增量：
     * 向外拖为正（放大）、向内拖为负（缩小），且不会因垂直位移过大而瞬间冲满。
     *
     * @param dirX 该圆点水平方向的向外朝向 (-1 = 左侧, +1 = 右侧)
     * @param dirY 该圆点垂直方向的向外朝向 (-1 = 上侧, +1 = 下侧)
     */
    fun scaleDelta(
        dxPx: Float,
        dyPx: Float,
        dirX: Int,
        dirY: Int,
        baseWidthPx: Float,
        baseHeightPx: Float
    ): Float {
        val denominator = baseWidthPx * baseWidthPx + baseHeightPx * baseHeightPx
        if (denominator <= 0f) return 0f
        // 悬浮窗是以中心为视觉锚点对称向两侧扩展的。
        // 手指在屏幕上向外位移 dxPx，若要四角圆点精准贴合手指移动，整体尺寸增量须为单边位移的 2 倍。
        return 2.0f * (dxPx * dirX * baseWidthPx + dyPx * dirY * baseHeightPx) / denominator
    }

    /**
     * 缩放因子统一钳制
     */
    fun clampScale(scale: Float, minScale: Float, maxScale: Float): Float =
        scale.coerceIn(minScale, maxScale.coerceAtLeast(minScale))

    /**
     * 当前屏幕允许的最大缩放比例：既受全局上限约束，也要保证放大后仍完整落在屏幕内
     */
    fun maxScaleForBounds(
        screenWidthPx: Int,
        screenHeightPx: Int,
        baseWidthPx: Float,
        baseHeightPx: Float,
        hardMaxScale: Float,
        minScale: Float
    ): Float {
        if (baseWidthPx <= 0f || baseHeightPx <= 0f) return hardMaxScale
        val maxByWidth = screenWidthPx * 0.98f / baseWidthPx
        val maxByHeight = screenHeightPx * 0.85f / baseHeightPx
        return minOf(hardMaxScale, maxByWidth, maxByHeight).coerceAtLeast(minScale)
    }
}
