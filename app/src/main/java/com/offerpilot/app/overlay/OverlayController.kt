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
 * Accessibility overlay hints (TYPE_ACCESSIBILITY_OVERLAY; no "draw over other apps" permission needed).
 * Not touchable or focusable: never covers or intercepts Uber's accept button (shown at the top of the screen by default,
 * while the accept area is usually at the bottom).
 */
class OverlayController(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { hide() }

    private var glow: EdgeGlowView? = null

    /**
     * Shows a verdict. [showText] controls the text pill, [showGlow] the edge glow (no glow when the verdict is unknown).
     */
    fun show(text: String, verdict: Verdict, position: OverlayPosition, seconds: Int, showText: Boolean = true, showGlow: Boolean = true) {
        main.post {
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, seconds.coerceIn(2, 60) * 1000L)
            if (showGlow && verdict != Verdict.UNKNOWN) {
                val g = glow ?: EdgeGlowView(service).also { v ->
                    runCatching { wm.addView(v, glowParams()) }.onFailure { return@also }
                    glow = v
                }
                g.setGlowColor(colorFor(verdict) or 0xFF000000.toInt())
                g.startPulse()
            } else {
                removeGlow()
            }
            if (!showText) { removeText(); return@post }
            val tv = view ?: createView().also { v ->
                runCatching { wm.addView(v, params(position)) }.onFailure { return@post }
                view = v
            }
            runCatching { wm.updateViewLayout(tv, params(position)) }
            tv.text = text
            (tv.background as GradientDrawable).setColor(colorFor(verdict))
            tv.setTextColor(if (verdict == Verdict.MIN_WAGE) Color.BLACK else Color.WHITE)
        }
    }

    fun hide() {
        main.post {
            main.removeCallbacks(hideRunnable)
            removeText()
            removeGlow()
        }
    }

    private fun removeText() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun removeGlow() {
        glow?.let { it.stopPulse(); runCatching { wm.removeView(it) } }
        glow = null
    }

    private fun glowParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private var badge: TextView? = null

    /** Small badge in the top-left corner during continuous capture (not touchable). */
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
        Verdict.GREAT -> Color.parseColor("#E61E88E5")
        Verdict.GOOD -> Color.parseColor("#E643A047")
        Verdict.MIN_WAGE -> Color.parseColor("#E6FBC02D")
        Verdict.LOSS -> Color.parseColor("#E6E53935")
        Verdict.UNKNOWN -> Color.parseColor("#E6616161")
    }
}
