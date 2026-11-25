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
 * Node snapshots (for the Phase 0 feasibility check, similar to GKD snapshots).
 *
 * - Saved only when the user triggers it (delayed capture / accessibility button) or turns on "auto snapshot";
 * - Only the target app's node tree is exported; other windows record just package name and type;
 * - Stored in the app's private external dir Android/data/com.offerpilot.app/files/snapshots/, never uploaded;
 * - Screenshots are not saved by default.
 */
class SnapshotStore(private val service: AccessibilityService) {

    val dir: File get() = File(service.getExternalFilesDir(null), "snapshots").apply { mkdirs() }

    fun capture(
        targets: Set<String>,
        reason: String,
        adapterDebug: AdapterDebug?,
        into: File = dir,
        ocrLines: List<String>? = null,
        evaluation: JSONObject? = null,
    ): File? {
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
        ocrLines?.let { json.put("ocrLines", JSONArray(it)) }
        evaluation?.let { json.put("evaluation", it) }
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
        val file = File(into.apply { mkdirs() }, "$name.json")
        file.writeText(json.toString(2))
        return file
    }

    /** Optional: save a full-screen screenshot (only when explicitly enabled on the debug page). */
    fun saveScreenshot(bitmap: Bitmap, jsonFile: File): File {
        val png = File(jsonFile.parentFile, jsonFile.nameWithoutExtension + ".png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return png
    }

    /** Log of offers that couldn't be judged ("Unreadable"), kept to the newest [UNREADABLE_MAX] entries. */
    val unreadableDir: File get() = File(dir, UNREADABLE_DIR).apply { mkdirs() }

    fun trimUnreadable() {
        val files = unreadableDir.listFiles()?.sortedByDescending { it.name }.orEmpty()
        val keepNames = files.filter { it.extension == "json" }.take(UNREADABLE_MAX).map { it.nameWithoutExtension }.toSet()
        files.filter { it.nameWithoutExtension !in keepNames }.forEach { it.delete() }
    }

    fun newSessionDir(): File =
        File(dir, "session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }

    fun list(): List<File> = dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()

    companion object {
        const val UNREADABLE_DIR = "session-unreadable"
        const val UNREADABLE_MAX = 100

        /**
         * Packs a session directory into a zip of the same name (under snapshots/) for one-tap sharing. [textOnly] leaves
         * the screenshots out ("<name>-text.zip"): the trace and the JSON files are enough to see what was read, and a long
         * session with screenshots runs to hundreds of MB.
         */
        fun zipSession(sessionDir: File, textOnly: Boolean = false): File {
            val zip = File(sessionDir.parentFile, sessionDir.name + (if (textOnly) "-text" else "") + ".zip")
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                sessionDir.listFiles()?.filter { !textOnly || it.extension != "png" }?.sortedBy { it.name }?.forEach { f ->
                    out.putNextEntry(java.util.zip.ZipEntry(sessionDir.name + "/" + f.name))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            return zip
        }
    }

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
