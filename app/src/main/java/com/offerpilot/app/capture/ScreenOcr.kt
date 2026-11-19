package com.offerpilot.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
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
 * OCR 兜底（PRD 2.2 第二优先级）。
 *
 * Phase 0 真机快照证实：订单弹窗出现时，Uber Driver 把整个界面层从无障碍树中隐藏，
 * 报价 / 距离 / 时间无法从节点读取。此时：
 *   AccessibilityService.takeScreenshot() → 裁剪到 Uber 窗口区域 → ML Kit 本地文字识别
 *   → ScreenText(source = OCR) → 同一个 UberDriverAdapter。
 *
 * 只在服务判断"疑似弹窗且节点读不到"时调用，有最小间隔，不做无间隔循环截图。
 * 截图只在内存中使用，识别完立即回收，不落盘。全程本地，不联网。
 */
class ScreenOcr(
    private val service: AccessibilityService,
    private val executor: Executor,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** [onResult] 在 [executor] 上回调；失败时传 null。 */
    fun recognize(region: Rect, packageName: String, onResult: (ScreenText?) -> Unit) {
        val takenAt = System.currentTimeMillis()
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var full: Bitmap? = null
                    var crop: Bitmap? = null
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        full = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                        val src = full ?: return onResult(null)
                        val r = Rect(region).apply { if (!intersect(0, 0, src.width, src.height)) set(0, 0, src.width, src.height) }
                        val c = if (r.width() == src.width && r.height() == src.height) src
                        else Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                        crop = c
                        recognizer.process(InputImage.fromBitmap(c, 0))
                            .addOnSuccessListener(executor) { text ->
                                val nodes = text.textBlocks.flatMap { it.lines }.map { line ->
                                    val b = line.boundingBox
                                    TextNode(
                                        text = line.text,
                                        bounds = b?.let { Box(it.left + r.left, it.top + r.top, it.right + r.left, it.bottom + r.top) },
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
                    Log.w(TAG, "screenshot failed: $errorCode")
                    onResult(null)
                }
            })
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
