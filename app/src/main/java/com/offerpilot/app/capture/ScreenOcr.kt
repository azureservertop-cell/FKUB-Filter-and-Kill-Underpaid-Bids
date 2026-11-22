package com.offerpilot.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import java.util.concurrent.Executor

/**
 * OCR fallback (PRD 2.2, second priority).
 *
 * Phase 0 real-device snapshots confirmed: while an offer popup is up, Uber Driver hides its whole UI layer from the
 * accessibility tree, so price / distance / time can't be read from nodes. In that case:
 *   AccessibilityService.takeScreenshot() → crop to the Uber window → ML Kit on-device text recognition
 *   → ScreenText(source = OCR) → the same UberDriverAdapter.
 *
 * Only called when the service suspects a hidden popup; rate-limited, never a tight screenshot loop.
 * Screenshots stay in memory, are recycled right after recognition and never written to disk. Fully local, no network.
 */
class ScreenOcr(
    private val service: AccessibilityService,
    private val executor: Executor,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * [onResult] is called on [executor]; null on failure.
     *
     * On Android 14+ takeScreenshotOfWindow captures only Uber's window, so our own overlay hint
     * (otherwise text like "$5 / 3km" would be read as offer content) and other apps' text are excluded.
     * Older versions take a full screenshot and crop it to the Uber window.
     */
    /**
     * [bandTop]..[bandBottom]: the part of the screen (screen y, pixels) the user chose to read; everything else is cut
     * off before recognition, which is faster and keeps map labels out.
     */
    fun recognize(
        region: Rect, windowId: Int, packageName: String,
        bandTop: Int = 0, bandBottom: Int = Int.MAX_VALUE,
        onResult: (ScreenText?) -> Unit,
    ) {
        val takenAt = System.currentTimeMillis()
        try {
            val windowOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windowId >= 0
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var full: Bitmap? = null
                    var crop: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        full = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        val src = full ?: return onResult(null)
                        // r: region of the screenshot to recognize; (ox, oy): screenshot → screen coordinate offset
                        val r: Rect
                        val ox: Int
                        val oy: Int
                        if (windowOnly) {
                            r = Rect(0, 0, src.width, src.height); ox = region.left; oy = region.top
                        } else {
                            r = Rect(region).apply { if (!intersect(0, 0, src.width, src.height)) set(0, 0, src.width, src.height) }
                            ox = 0; oy = 0
                        }
                        // Keep only the reading area the user set (bitmap y = screen y - oy)
                        r.top = maxOf(r.top, bandTop - oy)
                        r.bottom = minOf(r.bottom, if (bandBottom == Int.MAX_VALUE) r.bottom else bandBottom - oy)
                        if (r.height() <= 0) {
                            recycle(full, null)
                            onResult(ScreenText(packageName, emptyList(), takenAt, OfferSource.OCR))
                            return
                        }
                        val c = if (r.width() == src.width && r.height() == src.height) src
                        else Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                        crop = c
                        recognizer.process(InputImage.fromBitmap(c, 0))
                            .addOnSuccessListener(executor) { text ->
                                val nodes = text.textBlocks.flatMap { it.lines }.map { line ->
                                    val b = line.boundingBox
                                    TextNode(
                                        text = line.text,
                                        bounds = b?.let {
                                            Box(it.left + r.left + ox, it.top + r.top + oy, it.right + r.left + ox, it.bottom + r.top + oy)
                                        },
                                    )
                                }
                                recycle(full, crop)
                                onResult(ScreenText(packageName, nodes, takenAt, OfferSource.OCR))
                            }
                            .addOnFailureListener(executor) { e ->
                                Log.w(TAG, "ocr failed", e)
                                recycle(full, crop)
                                onResult(null)
                            }
                    } catch (t: Throwable) {
                        Log.w(TAG, "screenshot processing failed", t)
                        recycle(full, crop)
                        onResult(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed: $errorCode (windowOnly=$windowOnly)")
                    // Window screenshot failed (e.g. an unreadable overlay window) → fall back to full screenshot + crop
                    if (windowOnly) recognize(region, -1, packageName, bandTop, bandBottom, onResult) else onResult(null)
                }
            }
            if (windowOnly) service.takeScreenshotOfWindow(windowId, executor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot threw", t)
            executor.execute { onResult(null) }
        }
    }

    fun close() = recognizer.close()

    private fun recycle(vararg bitmaps: Bitmap?) {
        bitmaps.filterNotNull().distinct().forEach { if (!it.isRecycled) it.recycle() }
    }

    private companion object { const val TAG = "ScreenOcr" }
}
