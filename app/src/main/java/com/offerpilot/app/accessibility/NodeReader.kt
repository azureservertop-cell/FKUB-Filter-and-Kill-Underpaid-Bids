package com.offerpilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode

/**
 * Reads accessibility nodes. Only text from target-app windows is read; other apps are identified by package name only.
 * Node count and depth are capped so Uber Driver is never slowed down.
 */
class NodeReader(private val service: AccessibilityService) {

    companion object {
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 60
    }

    /** Root nodes of the target app's windows currently on screen. */
    fun targetRoots(targets: Set<String>): List<Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<AccessibilityWindowInfo?, AccessibilityNodeInfo>>()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() in targets) out += w to root
        }
        if (out.isEmpty()) {
            val active = runCatching { service.rootInActiveWindow }.getOrNull()
            if (active != null && active.packageName?.toString() in targets) out += null to active
        }
        return out
    }

    /** Large on-screen windows whose root can't be read (excluding our own accessibility overlays and the IME). */
    private fun opaqueWindows(): List<TargetWindow> {
        val dm = service.resources.displayMetrics
        return runCatching { service.windows }.getOrNull().orEmpty().mapNotNull { w ->
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                w.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
            ) return@mapNotNull null
            val root = runCatching { w.root }.getOrNull()
            if (root?.packageName != null) return@mapNotNull null
            val r = Rect(); w.getBoundsInScreen(r)
            if (r.width() < dm.widthPixels / 2 || r.height() < dm.heightPixels / 4) return@mapNotNull null
            TargetWindow(w.id, r, w.type == AccessibilityWindowInfo.TYPE_APPLICATION)
        }
    }

    /** One window of the target app. */
    data class TargetWindow(val id: Int, val bounds: Rect, val isApplication: Boolean)

    /** One frame: text + window info (used to detect a hidden popup and to choose the OCR crop). */
    data class ScreenRead(
        val screen: ScreenText,
        val windows: List<TargetWindow>,
        val totalNodes: Int,
        /**
         * Large windows with no readable content (null root, owning app unknown). On a real device, when Uber is
         * in the background its offer card appears as such a window on top of maps, the launcher or other apps.
         */
        val opaqueWindows: List<TargetWindow> = emptyList(),
    ) {
        /** Structural signature: detects window/node changes even when the text is unchanged (used by continuous capture). */
        val signature: Int
            get() = screen.nodes.fold((windows.hashCode() * 31 + opaqueWindows.hashCode()) * 31 + totalNodes) { h, n ->
                31 * h + n.text.hashCode() + 7 * (n.bounds?.hashCode() ?: 0)
            }
    }

    fun read(targets: Set<String>, now: Long): ScreenRead? {
        val roots = targetRoots(targets)
        if (roots.isEmpty()) return null
        val windows = roots.mapNotNull { (w, _) ->
            w?.let { val r = Rect(); it.getBoundsInScreen(r); TargetWindow(it.id, r, it.type == AccessibilityWindowInfo.TYPE_APPLICATION) }
        }
        val nodes = ArrayList<TextNode>(128)
        var count = 0
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                val box = Box(rect.left, rect.top, rect.right, rect.bottom)
                val id = n.viewIdResourceName
                val cls = n.className?.toString()
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { nodes += TextNode(it, id, cls, box, depth) }
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it != n.text?.toString() }
                    ?.let { nodes += TextNode(it, id, cls, box, depth, fromContentDescription = true) }
            }
            for (i in 0 until n.childCount) {
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                walk(c, depth + 1)
            }
        }
        for ((_, root) in roots) walk(root, 0)
        val pkg = roots.first().second.packageName?.toString() ?: return null
        return ScreenRead(ScreenText(pkg, nodes, now, OfferSource.ACCESSIBILITY), windows, count, opaqueWindows())
    }
}
