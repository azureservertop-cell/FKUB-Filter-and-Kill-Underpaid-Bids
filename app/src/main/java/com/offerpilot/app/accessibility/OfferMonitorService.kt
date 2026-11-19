package com.offerpilot.app.accessibility

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.offerpilot.app.capture.ScreenOcr
import com.offerpilot.app.debug.SnapshotStore
import com.offerpilot.app.feedback.Haptics
import com.offerpilot.app.overlay.OverlayController
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.speech.Speaker
import com.offerpilot.core.OfferPipeline
import com.offerpilot.core.PipelineOutput
import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.parser.AdapterResult
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
        /** 持续抓取：两份快照之间的最短间隔，以及每个会话的上限。 */
        private const val RECORD_MIN_INTERVAL_MS = 1000L
        /** 两次 OCR 截图之间的最小间隔（系统对 takeScreenshot 也有频率限制）。 */
        private const val OCR_MIN_INTERVAL_MS = 800L
        private const val OPAQUE_MAX_MISSES = 3
        const val RECORD_MAX_COUNT = 1000

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
    private lateinit var ocr: ScreenOcr
    private val actionHandler: OfferActionHandler = NoOpOfferActionHandler

    // 仅在 worker 线程访问
    private var adapter = UberDriverAdapter()
    private var pipeline = OfferPipeline(adapter, OfferEvaluator())
    private var readScheduled = false
    private var lastAutoSnapshotKey: String? = null
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    // 持续抓取会话（仅 worker 线程访问）
    private var recordDir: java.io.File? = null
    private var recordCount = 0
    private var recordLastHash = 0
    private var recordLastAt = 0L
    private var recordStartedAt = 0L

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
        ocr = ScreenOcr(this) { r -> workHandler.post(r) }
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
        if (event == null || !(settings.monitoringEnabled || recording)) return
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
        if (!s.monitoringEnabled && recordDir == null) return
        val now = System.currentTimeMillis()
        try {
            val read = reader.read(s.targetPackages, now)
            MonitorState.update { it.copy(targetInForeground = read != null) }
            if (recordDir != null && read != null) recordFrame(read, now)
            if (!s.monitoringEnabled) return

            if (read == null) {
                frameGeneration++
                handle(pipeline.processResult(AdapterResult.NoOffer, now), s, now)
                return
            }
            val adapted = adapter.extract(read.screen)
            val nodeOfferComplete = adapted is AdapterResult.Offer && adapted.complete
            val region = if (!nodeOfferComplete && s.ocrEnabled) hiddenOfferRegion(read) else null
            if (region == null) {
                frameGeneration++
                MonitorState.update { it.copy(lastAdapterNote = adapter.lastDebug?.note) }
                handle(pipeline.processResult(adapted, now), s, now)
            } else {
                // 节点被隐藏：不把"空帧"喂给跟踪器（否则会误判订单消失），改走 OCR。
                requestOcr(region, read.screen.packageName)
            }
        } catch (t: Throwable) {
            // 安全降级：任何解析/读取异常都只记录，不输出结论、不重试轰炸
            Log.w(TAG, "frame failed", t)
            clearOutputs()
        }
    }

    /**
     * 判断是否"疑似有订单弹窗但节点读不到"，返回需要 OCR 的屏幕区域，否则 null。
     *
     * 真机快照（Android 15）显示：Uber 在前台弹出订单时，整个界面层从无障碍树里消失，只剩地图。
     * 另外，Uber 在后台时会在其他应用上方显示自己的窗口（非 application 类型）。
     */
    private fun hiddenOfferRegion(read: NodeReader.ScreenRead): NodeReader.TargetWindow? {
        val dm = resources.displayMetrics
        val bigOverlay = read.windows.firstOrNull {
            !it.isApplication && it.bounds.width() >= dm.widthPixels / 2 && it.bounds.height() >= dm.heightPixels / 6
        }
        if (bigOverlay != null) return bigOverlay
        val app = read.windows.firstOrNull { it.isApplication }
        if (app != null && read.screen.nodes.isEmpty()) return app
        // Uber 在线（有 Uber 窗口，如悬浮气泡）时，读不到内容的大窗口很可能就是弹在其他应用上的订单卡片。
        // 同一个窗口连续几次识别不到订单就不再截图，避免对其他应用的悬浮窗反复 OCR。
        return read.opaqueWindows.firstOrNull { (opaqueMisses[it.id] ?: 0) < OPAQUE_MAX_MISSES }
    }

    /** 读不到内容的窗口 id → 连续 OCR 未识别到订单的次数。 */
    private val opaqueMisses = HashMap<Int, Int>()

    // ---------------- OCR ----------------

    private var ocrInFlight = false
    private var lastOcrAt = 0L
    private var frameGeneration = 0L
    private var lastOcrLines: List<String> = emptyList()
    private var lastOcrLinesAt = 0L

    /** worker 线程。最小间隔 [OCR_MIN_INTERVAL_MS]，同一时间只跑一个。 */
    private fun requestOcr(window: NodeReader.TargetWindow, pkg: String) {
        if (ocrInFlight) return
        val wait = OCR_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastOcrAt)
        if (wait > 0) { scheduleRead(wait); return }
        ocrInFlight = true
        lastOcrAt = SystemClock.elapsedRealtime()
        val gen = frameGeneration
        val started = SystemClock.elapsedRealtime()
        ocr.recognize(window.bounds, window.id, pkg) { screen ->
            ocrInFlight = false
            val s = settings
            try {
                if (screen != null) {
                    val found = adapter.extract(screen) is AdapterResult.Offer
                    if (found) opaqueMisses.remove(window.id) else opaqueMisses[window.id] = (opaqueMisses[window.id] ?: 0) + 1
                    if (opaqueMisses.size > 50) opaqueMisses.clear()
                }
                // 识别期间弹窗已消失（出现过正常节点帧）→ 丢弃，避免输出过期结论
                if (screen != null && gen == frameGeneration && s.monitoringEnabled) {
                    lastOcrLines = screen.nodes.map { it.text }
                    lastOcrLinesAt = System.currentTimeMillis()
                    val adapted = adapter.extract(screen)
                    MonitorState.update { it.copy(lastAdapterNote = "OCR：" + (adapter.lastDebug?.note ?: "")) }
                    val now = System.currentTimeMillis()
                    handle(pipeline.processResult(adapted, now), s, now)
                    Log.d(TAG, "ocr frame in ${SystemClock.elapsedRealtime() - started}ms, ${screen.nodes.size} lines")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ocr handling failed", t)
            }
            // 弹窗期间 Uber 不一定持续发事件：主动再看一次，以便发现弹窗消失或内容变化
            scheduleRead(OCR_MIN_INTERVAL_MS)
        }
    }

    private fun handle(output: PipelineOutput, s: AppSettings, now: Long) {
        when (output) {
            is PipelineOutput.Show -> {
                present(output, s)
                maybeAutoSnapshot(s, output)
                Log.d(TAG, "offer ${output.offer.source}: ${output.result.verdict}")
            }
            PipelineOutput.Clear -> clearOutputs()
            is PipelineOutput.RecheckLater -> scheduleRead(output.delayMs)
            PipelineOutput.None -> Unit
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
        if (s.overlayEnabled || s.edgeGlowEnabled) {
            overlay.show(
                overlayText, out.result.verdict, s.overlayPosition, s.overlaySeconds,
                showText = s.overlayEnabled, showGlow = s.edgeGlowEnabled,
            )
        }
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

    // ---------------- 持续抓取 ----------------

    @Volatile private var recording = false

    /** UI 调用：开始持续抓取。目标应用画面文字每变化一次保存一份快照（至少间隔 1 秒）。 */
    fun startRecording() {
        workHandler.post {
            if (recordDir != null) return@post
            val dir = snapshots.newSessionDir()
            recordDir = dir; recordCount = 0; recordLastHash = 0; recordLastAt = 0L
            recordStartedAt = System.currentTimeMillis()
            recording = true
            publishRecording()
            scheduleRead(0)
        }
    }

    /** UI 调用：结束持续抓取并打包成 zip。 */
    fun stopRecording() {
        workHandler.post {
            val dir = recordDir ?: return@post
            recordDir = null
            recording = false
            overlay.hideBadge()
            val zip = runCatching { if (recordCount > 0) SnapshotStore.zipSession(dir) else null }
                .onFailure { Log.w(TAG, "zip failed", it) }.getOrNull()
            if (recordCount == 0) dir.deleteRecursively()
            MonitorState.update { it.copy(recording = null, lastSnapshotPath = zip?.absolutePath ?: it.lastSnapshotPath) }
        }
    }

    /** worker 线程：画面文字有变化才保存，避免地图动画导致的重复。 */
    private fun recordFrame(read: NodeReader.ScreenRead, now: Long) {
        val dir = recordDir ?: return
        val hash = read.signature
        if (hash == recordLastHash) return
        if (now - recordLastAt < RECORD_MIN_INTERVAL_MS) {
            // 稍后再读一次，保证变化后的最终画面也能被保存
            scheduleRead(RECORD_MIN_INTERVAL_MS - (now - recordLastAt))
            return
        }
        val file = runCatching {
            snapshots.capture(settings.targetPackages, "record", adapter.lastDebug, into = dir, ocrLines = recentOcrLines())
        }
            .onFailure { Log.w(TAG, "record failed", it) }.getOrNull() ?: return
        if (settings.snapshotScreenshot) captureScreenshot(file)
        recordLastHash = hash
        recordLastAt = now
        recordCount++
        publishRecording()
        if (recordCount >= RECORD_MAX_COUNT) stopRecording()
    }

    private fun recentOcrLines(): List<String>? =
        lastOcrLines.takeIf { System.currentTimeMillis() - lastOcrLinesAt < 3_000 }

    private fun publishRecording() {
        val dir = recordDir ?: return
        MonitorState.update {
            it.copy(recording = RecordingStatus(dir.name, recordStartedAt, recordCount, RECORD_MAX_COUNT))
        }
        overlay.showBadge("● 抓取中 $recordCount")
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
        val file = runCatching { snapshots.capture(s.targetPackages, reason, adapter.lastDebug, ocrLines = recentOcrLines()) }
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
        if (::overlay.isInitialized) overlay.hideBadge()
        MonitorState.update { it.copy(recording = null) }
        if (::speaker.isInitialized) speaker.shutdown()
        if (::ocr.isInitialized) runCatching { ocr.close() }
        if (::worker.isInitialized) worker.quitSafely()
        scope.cancel()
        MonitorState.update { it.copy(serviceConnected = false, targetInForeground = false) }
        instanceRef = WeakReference(null)
        super.onDestroy()
    }
}
