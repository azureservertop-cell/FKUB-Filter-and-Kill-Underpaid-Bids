package com.offerpilot.app.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.offerpilot.core.model.AdapterDebug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控件快照（Phase 0 可行性验证用，类似 GKD 快照）。
 *
 * - 只在用户主动触发（延时抓取 / 无障碍按钮）或用户开启"自动快照"时保存；
 * - 只导出目标应用窗口的控件树；其他窗口只记录包名和类型；
 * - 保存在本应用私有外部目录 Android/data/com.offerpilot.app/files/snapshots/，不会上传；
 * - 截图默认不保存。
 */
class SnapshotStore(private val service: AccessibilityService) {

    val dir: File get() = File(service.getExternalFilesDir(null), "snapshots").apply { mkdirs() }

    fun capture(targets: Set<String>, reason: String, adapterDebug: AdapterDebug?): File? {
        val json = JSONObject()
        json.put("app", "OfferPilot")
        json.put("format", 1)
        json.put("reason", reason)
        json.put("time", System.currentTimeMillis())
        json.put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("sdk", Build.VERSION.SDK_INT)
        })
        json.put("targetPackages", JSONArray(targets.toList()))
        adapterDebug?.let {
            json.put("adapter", JSONObject().apply {
                put("note", it.note)
                put("money", JSONArray(it.moneyCandidates)); put("distanceKm", JSONArray(it.distanceCandidates))
                put("durationMin", JSONArray(it.durationCandidates))
            })
        }
        val windows = JSONArray()
        var hasTarget = false
        for (w in runCatching { service.windows }.getOrNull().orEmpty()) {
            val root = runCatching { w.root }.getOrNull()
            val pkg = root?.packageName?.toString()
            val wj = JSONObject()
            wj.put("windowType", windowType(w.type))
            wj.put("title", w.title?.toString())
            wj.put("layer", w.layer)
            wj.put("active", w.isActive)
            wj.put("package", pkg)
            val r = Rect(); w.getBoundsInScreen(r); wj.put("bounds", r.flattenToString())
            if (root != null && pkg in targets) {
                hasTarget = true
                var count = 0
                wj.put("tree", dump(root, 0) { count++ < 3000 })
            }
            windows.put(wj)
        }
        json.put("windows", windows)
        json.put("hasTargetWindow", hasTarget)
        val name = "snapshot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "$name.json")
        file.writeText(json.toString(2))
        return file
    }

    /** 可选：保存整屏截图（用户在调试页显式开启时）。 */
    fun saveScreenshot(bitmap: Bitmap, jsonFile: File): File {
        val png = File(jsonFile.parentFile, jsonFile.nameWithoutExtension + ".png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return png
    }

    fun list(): List<File> = dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun dump(n: AccessibilityNodeInfo, depth: Int, budget: () -> Boolean): JSONObject {
        val o = JSONObject()
        val r = Rect(); n.getBoundsInScreen(r)
        o.put("depth", depth)
        o.put("class", n.className?.toString())
        n.viewIdResourceName?.let { o.put("id", it) }
        n.text?.let { o.put("text", it.toString()) }
        n.contentDescription?.let { o.put("desc", it.toString()) }
        o.put("bounds", r.flattenToString())
        o.put("visible", n.isVisibleToUser)
        if (n.isClickable) o.put("clickable", true)
        if (depth < 80) {
            val children = JSONArray()
            for (i in 0 until n.childCount) {
                if (!budget()) { o.put("truncated", true); break }
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                children.put(dump(c, depth + 1, budget))
            }
            if (children.length() > 0) o.put("children", children)
        }
        return o
    }

    private fun windowType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        else -> "type_$t"
    }
}
