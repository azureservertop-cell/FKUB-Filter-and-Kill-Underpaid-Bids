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
 * Full-screen edge glow.
 *
 * Built from concentric rounded-rect strokes: brightest at the outer edge, fading inward. The corner radius comes from
 * the display's real rounded corners (Android 12+ RoundedCorner API), so the corners are smooth arcs without blocks or hot spots.
 * Draws only along the edges, the middle is fully transparent, and the window is not touchable. The pulse animates View alpha only, no redraw.
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
        paint.strokeWidth = step + 1f // slight overlap to avoid gaps between layers
        for (i in 0 until layers) {
            // i = 0 is the outermost ring. Alpha falls off quadratically: bright outside, soft inside.
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
