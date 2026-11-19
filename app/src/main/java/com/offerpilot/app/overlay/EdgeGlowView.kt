package com.offerpilot.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View

/**
 * 全屏边缘荧光。
 *
 * 用一组同心圆角矩形描边叠出光晕：最外圈最亮，向内逐层变淡。圆角半径取自屏幕真实圆角
 * （Android 12+ RoundedCorner API），所以四角是顺滑的弧形，不会出现方块或亮斑。
 * 只在边缘绘制，中间完全透明；所在窗口不可触摸。呼吸动画只改 View 透明度，不重绘。
 */
class EdgeGlowView(context: Context) : View(context) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val thickness = dp(30f)
    private val layers = 16
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var color = Color.BLUE
    private var animator: ValueAnimator? = null

    fun setGlowColor(c: Int) {
        color = c
        invalidate()
    }

    fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.6f, 1f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    fun stopPulse() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    private fun screenCornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val r = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
            if (r != null && r > 0) return r.toFloat()
        }
        return dp(28f)
    }

    override fun onDraw(canvas: Canvas) {
        val outerRadius = screenCornerRadius()
        val step = thickness / layers
        paint.strokeWidth = step + 1f // 略重叠，避免层间缝隙
        for (i in 0 until layers) {
            // i = 0 在最外圈。透明度按平方衰减，外亮内淡，过渡柔和。
            val f = 1f - i.toFloat() / layers
            val a = (235 * f * f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
            val inset = step * i + step / 2
            rect.set(inset, inset, width - inset, height - inset)
            val r = (outerRadius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
