package com.offerpilot.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.offerpilot.app.settings.OverlayPosition
import com.offerpilot.core.model.Verdict

/**
 * 无障碍悬浮提示（TYPE_ACCESSIBILITY_OVERLAY，无需"悬浮窗"权限）。
 * 不可触摸、不可聚焦：不会挡住或拦截 Uber 的接单按钮（默认显示在屏幕顶部，
 * 接单区域通常在底部——待真机确认后可在设置里改位置）。
 */
class OverlayController(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    fun show(text: String, verdict: Verdict, position: OverlayPosition, seconds: Int) {
        main.post {
            val tv = view ?: createView().also { v ->
                runCatching { wm.addView(v, params(position)) }.onFailure { return@post }
                view = v
            }
            runCatching { wm.updateViewLayout(tv, params(position)) }
            tv.text = text
            (tv.background as GradientDrawable).setColor(colorFor(verdict))
            tv.setTextColor(if (verdict == Verdict.MARGINAL) Color.BLACK else Color.WHITE)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, seconds.coerceIn(2, 60) * 1000L)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(hideRunnable)
            view?.let { runCatching { wm.removeView(it) } }
            view = null
        }
    }

    private var badge: TextView? = null

    /** 持续抓取时左上角的小标记（不可触摸）。 */
    fun showBadge(text: String) {
        main.post {
            val tv = badge ?: createView().also { v ->
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                v.setTextColor(Color.WHITE)
                (v.background as GradientDrawable).setColor(Color.parseColor("#CCC62828"))
                val p = params(OverlayPosition.TOP).apply { gravity = Gravity.TOP or Gravity.START; x = 16 }
                runCatching { wm.addView(v, p) }.onFailure { return@post }
                badge = v
            }
            tv.text = text
        }
    }

    fun hideBadge() {
        main.post {
            badge?.let { runCatching { wm.removeView(it) } }
            badge = null
        }
    }

    private fun createView(): TextView {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics) }
        return TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(16f) }
            elevation = dp(6f)
            maxLines = 2
        }
    }

    private fun params(position: OverlayPosition) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        val h = service.resources.displayMetrics.heightPixels
        when (position) {
            OverlayPosition.TOP -> { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (h * 0.06).toInt() }
            OverlayPosition.CENTER -> { gravity = Gravity.CENTER; y = 0 }
        }
    }

    private fun colorFor(v: Verdict) = when (v) {
        Verdict.RECOMMENDED -> Color.parseColor("#E61B5E20")
        Verdict.MARGINAL -> Color.parseColor("#E6FBC02D")
        Verdict.NOT_RECOMMENDED -> Color.parseColor("#E6B71C1C")
        Verdict.UNKNOWN -> Color.parseColor("#E6616161")
    }
}
