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
 * 读取无障碍节点。只读取目标应用窗口的文字；其他应用只看包名。
 * 有节点数和深度上限，避免拖慢 Uber Driver。
 */
class NodeReader(private val service: AccessibilityService) {

    companion object {
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 60
    }

    /** 当前屏幕上属于目标应用的窗口根节点。 */
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

    fun readScreen(targets: Set<String>, now: Long): ScreenText? {
        val roots = targetRoots(targets)
        if (roots.isEmpty()) return null
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
        return ScreenText(pkg, nodes, now, OfferSource.ACCESSIBILITY)
    }
}
