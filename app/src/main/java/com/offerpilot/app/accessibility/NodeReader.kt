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

    /** 屏幕上读不到 root 的大窗口（不含本应用的无障碍悬浮层和输入法）。 */
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

    /** 目标应用的一个窗口。 */
    data class TargetWindow(val id: Int, val bounds: Rect, val isApplication: Boolean)

    /** 一帧读取结果：文字 + 窗口信息（用于判断弹窗是否被隐藏、OCR 裁剪区域）。 */
    data class ScreenRead(
        val screen: ScreenText,
        val windows: List<TargetWindow>,
        val totalNodes: Int,
        /**
         * 读不到内容的大窗口（root 为 null，不知道属于哪个应用）。真机：Uber 在后台时，
         * 订单卡片以这种窗口弹在地图 / 桌面等其他应用上方。
         */
        val opaqueWindows: List<TargetWindow> = emptyList(),
    ) {
        /** 结构签名：文字没变但窗口/节点结构变了也能察觉（持续抓取用）。 */
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
