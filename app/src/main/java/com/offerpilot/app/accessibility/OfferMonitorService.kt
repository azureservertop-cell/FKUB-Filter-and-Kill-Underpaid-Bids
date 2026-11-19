package com.offerpilot.app.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.offerpilot.app.OfferPilotApp
import com.offerpilot.app.action.NoOpOfferActionHandler
import com.offerpilot.app.action.OfferActionHandler
import com.offerpilot.app.debug.SnapshotStore
import com.offerpilot.app.feedback.Haptics
import com.offerpilot.app.overlay.OverlayController
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.speech.Speaker
import com.offerpilot.core.OfferPipeline
import com.offerpilot.core.PipelineOutput
import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.tracking.OfferTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * 订单监测服务（PRD 2.2 / 4.4）。
 *
 * 监听窗口事件 → 节流读取目标应用的文字 → OfferPipeline（适配器 + 去重 + 计算）
 * → 语音 / 悬浮 / 震动。识别、计算、显示、动作彼此解耦；动作模块是 NoOp。
 *
 * 性能：事件节流到每 [READ_INTERVAL_MS] 最多读一次；在后台线程读取节点；
 * 有节点上限；任何异常都被吞掉并降级为"无订单"，绝不影响 Uber Driver。
 */
class OfferMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "OfferMonitor"
        private const val READ_INTERVAL_MS = 150L
        private const val MAX_AUTO_SNAPSHOTS = 30

        private var instanceRef = WeakReference<OfferMonitorService>(null)
        val instance: OfferMonitorService? get() = instanceRef.get()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var worker: HandlerThread
    private lateinit var workHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var settings = AppSettings()
    private lateinit var reader: NodeReader
    private lateinit var overlay: OverlayController
    private lateinit var speaker: Speaker
    private lateinit var haptics: Haptics
    private lateinit var snapshots: SnapshotStore
    private val actionHandler: OfferActionHandler = NoOpOfferActionHandler

    // 仅在 worker 线程访问
    private var adapter = UberDriverAdapter()
    private var pipeline = OfferPipeline(adapter, OfferEvaluator())
    private var readScheduled = false
    private var lastAutoSnapshotKey: String? = null
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    private val readRunnable = Runnable { readScheduled = false; processFrame() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        worker = HandlerThread("offer-monitor").apply { start() }
        workHandler = Handler(worker.looper)
        reader = NodeReader(this)
        overlay = OverlayController(this)
        speaker = Speaker(this)
        haptics = Haptics(this)
        snapshots = SnapshotStore(this)
        MonitorState.update { it.copy(serviceConnected = true) }

        val repo = (application as OfferPilotApp).settings
        scope.launch {
            repo.settings.collect { s ->
                val old = settings
                settings = s
                workHandler.post { rebuildPipeline(s) }
                if (old.debugMode != s.debugMode || buttonCallback == null) updateAccessibilityButton(s.debugMode)
                if (!s.monitoringEnabled) clearOutputs()
            }
        }
    }

    private fun rebuildPipeline(s: AppSettings) {
        adapter = UberDriverAdapter(UberAdapterConfig(targetPackages = s.targetPackages))
        pipeline = OfferPipeline(adapter, OfferEvaluator(s.evaluator), OfferTracker())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !settings.monitoringEnabled) return
        if (!::workHandler.isInitialized) return
        // 非目标应用的事件只用于"用户离开 Uber"的判断：不读取其内容。
        val relevant = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> event.packageName?.toString() in settings.targetPackages
            else -> false
        }
        if (!relevant) return
        scheduleRead(READ_INTERVAL_MS)
    }

    private fun scheduleRead(delayMs: Long) {
        workHandler.post {
            if (!readScheduled) {
                readScheduled = true
                workHandler.postDelayed(readRunnable, delayMs)
            }
        }
    }

    /** worker 线程。 */
    private fun processFrame() {
        val s = settings
        if (!s.monitoringEnabled) return
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        try {
            val screen = reader.readScreen(s.targetPackages, now)
            MonitorState.update { it.copy(targetInForeground = screen != null) }
            val output = if (screen == null) {
                pipeline.tracker.onFrame(com.offerpilot.core.parser.AdapterResult.NoOffer, now)
                    .let { if (it is com.offerpilot.core.tracking.TrackerEvent.OfferGone) PipelineOutput.Clear else PipelineOutput.None }
            } else {
                pipeline.process(screen, now)
            }
            MonitorState.update { it.copy(lastAdapterNote = adapter.lastDebug?.note) }
            when (output) {
                is PipelineOutput.Show -> {
                    present(output, s)
                    maybeAutoSnapshot(s, output)
                    Log.d(TAG, "offer handled in ${SystemClock.elapsedRealtime() - started}ms: ${output.result.verdict}")
                }
                PipelineOutput.Clear -> clearOutputs()
                is PipelineOutput.RecheckLater -> scheduleRead(output.delayMs)
                PipelineOutput.None -> Unit
            }
        } catch (t: Throwable) {
            // 安全降级：任何解析/读取异常都只记录，不输出结论、不重试轰炸
            Log.w(TAG, "frame failed", t)
            clearOutputs()
        }
    }

    private fun present(out: PipelineOutput.Show, s: AppSettings) {
        val overlayText = OfferMessages.overlay(out.offer, out.result)
        MonitorState.update {
            it.copy(
                lastRecognition = LastRecognition(
                    timeMs = out.offer.timestamp,
                    success = out.result.isConclusive,
                    summary = overlayText,
                    detail = OfferMessages.detail(out.offer, out.result),
                )
            )
        }
        if (s.overlayEnabled) overlay.show(overlayText, out.result.verdict, s.overlayPosition, s.overlaySeconds)
        if (out.announce) {
            if (s.ttsEnabled) speaker.speak(OfferMessages.speech(out.offer, out.result), s.ttsVolume)
            if (s.vibrationEnabled) mainHandler.post { runCatching { haptics.play(out.result.verdict) } }
        }
        actionHandler.onEvaluated(out.offer, out.result) // NoOp：第一版不执行任何操作
    }

    /** 模拟页调用：用真实的悬浮层 / 语音 / 震动预览一条模拟订单。 */
    fun preview(offer: com.offerpilot.core.model.DeliveryOffer, result: com.offerpilot.core.model.EvaluationResult) {
        present(PipelineOutput.Show(offer, result, announce = true), settings)
    }

    private fun clearOutputs() {
        if (!::overlay.isInitialized) return
        overlay.hide()
        speaker.stop()
    }

    // ---------------- 调试快照 ----------------

    private fun maybeAutoSnapshot(s: AppSettings, out: PipelineOutput.Show) {
        if (!s.debugMode || !s.autoSnapshot) return
        val key = OfferTracker.dedupKey(out.offer)
        if (key == lastAutoSnapshotKey) return
        if (snapshots.list().size >= MAX_AUTO_SNAPSHOTS) return
        lastAutoSnapshotKey = key
        takeSnapshotNow("auto:${out.result.verdict}")
    }

    /** UI 调用：延时 [delayMs] 后抓取当前屏幕（留时间切到 Uber）。 */
    fun scheduleSnapshot(delayMs: Long) {
        MonitorState.update { it.copy(snapshotCountdownUntil = System.currentTimeMillis() + delayMs) }
        workHandler.postDelayed({
            MonitorState.update { it.copy(snapshotCountdownUntil = null) }
            takeSnapshotNow("delayed")
        }, delayMs)
    }

    /** worker 线程。 */
    private fun takeSnapshotNow(reason: String) {
        val s = settings
        val file = runCatching { snapshots.capture(s.targetPackages, reason, adapter.lastDebug) }
            .onFailure { Log.w(TAG, "snapshot failed", it) }.getOrNull() ?: return
        MonitorState.update { it.copy(lastSnapshotPath = file.absolutePath) }
        if (s.snapshotScreenshot) captureScreenshot(file)
    }

    private fun captureScreenshot(jsonFile: java.io.File) {
        val exec = Executors.newSingleThreadExecutor()
        takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bmp != null) snapshots.saveScreenshot(bmp, jsonFile)
                    result.hardwareBuffer.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "screenshot save failed", t)
                } finally {
                    exec.shutdown()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed: $errorCode")
                exec.shutdown()
            }
        })
    }

    /** 调试模式下在导航栏显示无障碍按钮：在 Uber 弹出订单时点一下即可抓取快照。 */
    private fun updateAccessibilityButton(enabled: Boolean) {
        val info = serviceInfo ?: return
        info.flags = if (enabled) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON.inv()
        serviceInfo = info
        val controller = accessibilityButtonController
        buttonCallback?.let { controller.unregisterAccessibilityButtonCallback(it) }
        buttonCallback = null
        if (enabled) {
            val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    workHandler.post { takeSnapshotNow("button") }
                }
            }
            controller.registerAccessibilityButtonCallback(cb)
            buttonCallback = cb
        }
    }

    override fun onInterrupt() {
        clearOutputs()
    }

    override fun onDestroy() {
        // 权限被撤销或服务被关闭：撤下所有提示并安全退出
        clearOutputs()
        if (::speaker.isInitialized) speaker.shutdown()
        if (::worker.isInitialized) worker.quitSafely()
        scope.cancel()
        MonitorState.update { it.copy(serviceConnected = false, targetInForeground = false) }
        instanceRef = WeakReference(null)
        super.onDestroy()
    }
}
