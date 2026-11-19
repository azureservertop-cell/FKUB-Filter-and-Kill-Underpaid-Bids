package com.offerpilot.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.View

/**
 * 全屏边缘荧光：四条边向内渐隐的彩色光带 + 四角径向光斑，轻微呼吸动画。
 * 只画在边缘，中间完全透明；所在窗口不可触摸，不影响操作 Uber。
 */
class EdgeGlowView(context: Context) : View(context) {
    private val thickness = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Color.BLUE
    private var animator: ValueAnimator? = null

    fun setGlowColor(c: Int) {
        color = c
        invalidate()
    }

    fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 700
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = thickness
        val solid = Color.argb(235, Color.red(color), Color.green(color), Color.blue(color))
        val clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))

        fun band(x0: Float, y0: Float, x1: Float, y1: Float, l: Float, tp: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, solid, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(l, tp, r, b, paint)
        }
        band(0f, 0f, 0f, t, t, 0f, w - t, t)               // 上
        band(0f, h, 0f, h - t, t, h - t, w - t, h)         // 下
        band(0f, 0f, t, 0f, 0f, t, t, h - t)               // 左
        band(w, 0f, w - t, 0f, w - t, t, w, h - t)         // 右

        // 四角
        listOf(0f to 0f, w to 0f, 0f to h, w to h).forEach { (cx, cy) ->
            paint.shader = RadialGradient(cx, cy, t, solid, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(
                if (cx == 0f) 0f else w - t, if (cy == 0f) 0f else h - t,
                if (cx == 0f) t else w, if (cy == 0f) t else h, paint,
            )
        }
        paint.shader = null
    }
}
