package com.rawsmusic.ui.floating

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 与悬浮胶囊完全同形的虚线选择框。
 *
 * 圆角半径取控件高度的一半，因此无论用户把悬浮窗放大/缩小到多少，
 * 虚线框始终严丝合缝地贴合胶囊外轮廓（胶囊本身也是半高全圆角）。
 */
class PillDashFrameDrawable(
    private val strokeWidthPx: Float,
    private val dashLengthPx: Float,
    private val dashColor: Int = Color.WHITE
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = dashColor
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(
            floatArrayOf(dashLengthPx, dashLengthPx * 0.85f),
            0f
        )
    }

    private val path = Path()
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rebuildPath(bounds)
    }

    private fun rebuildPath(bounds: Rect) {
        val inset = strokeWidthPx / 2f
        rect.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        )
        val radius = (rect.height() / 2f).coerceAtLeast(1f)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (path.isEmpty) rebuildPath(bounds)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
