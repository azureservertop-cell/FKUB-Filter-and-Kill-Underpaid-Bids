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
import com.offerpilot.app.feedback.SoundPlayer
import com.offerpilot.app.overlay.OverlayController
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.fuel.FuelPriceUpdater
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
 * Offer monitoring service (PRD 2.2 / 4.4).
 *
 * Window events → throttled read of the target app's text → OfferPipeline (adapter + dedupe + evaluation)
 * → speech / overlay / vibration. Recognition, evaluation, display and actions are decoupled; the action module is a no-op.
 *
 * Performance: events are throttled to at most one read per [READ_INTERVAL_MS]; nodes are read on a worker thread
 * with a node cap; every exception is swallowed and degrades to "no offer", never affecting Uber Driver.
 */
class OfferMonitorService : AccessibilityService() {

    companion object {
        private const val TAG = "OfferMonitor"
        private const val READ_INTERVAL_MS = 150L
        private const val MAX_AUTO_SNAPSHOTS = 30
        /** Continuous capture: minimum gap between two snapshots, and the per-session limit. */
        private const val RECORD_MIN_INTERVAL_MS = 1000L
        /** Minimum gap between two OCR screenshots (the system also rate-limits takeScreenshot). */
        private const val OCR_MIN_INTERVAL_MS = 800L
        /** Wait after a scroll before reading the list again. */
        private const val LIST_SETTLE_MS = 300L
        /** How often an offer list on screen is re-read so badges stay in place. */
        private const val LIST_REFRESH_MS = 1_000L
        private const val OPAQUE_MAX_MISSES = 3
        /** OCR interval for an Uber window that has repeatedly shown no offer. */
        private const val OCR_IDLE_INTERVAL_MS = 2_000L
        /** An Uber window with this few readable lines is treated as hiding its text (OCR is used). */
        private const val SPARSE_NODES = 8
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
    private lateinit var sounds: SoundPlayer
    private var pendingSpeech: Runnable? = null
    private lateinit var snapshots: SnapshotStore
    private lateinit var ocr: ScreenOcr
    private val actionHandler: OfferActionHandler = NoOpOfferActionHandler

    // Worker thread only
    private var adapter = UberDriverAdapter()
    private var evaluator = OfferEvaluator()
    private var pipeline = OfferPipeline(adapter, evaluator)
    /** When the target app last scrolled; list badges read before this are stale. */
    @Volatile private var lastScrollAt = 0L
    private var readScheduled = false
    private var lastAutoSnapshotKey: String? = null
    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null

    // Continuous capture session (worker thread only)
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
        sounds = SoundPlayer(this)
        snapshots = SnapshotStore(this)
        ocr = ScreenOcr(this) { r -> workHandler.post(r) }
        MonitorState.update { it.copy(serviceConnected = true) }

        val repo = (application as OfferPilotApp).settings
        // Auto fuel price: check hourly; the updater itself refreshes at most every 12 h.
        scope.launch {
            while (true) {
                runCatching { FuelPriceUpdater.refreshIfDue(repo) }
                kotlinx.coroutines.delay(60 * 60 * 1000L)
            }
        }
        scope.launch {
            repo.settings.collect { s ->
                val old = settings
                settings = s
                if (::sounds.isInitialized) sounds.prepare(s.tierSounds)
                if (::speaker.isInitialized) speaker.setLanguage(s.language)
                workHandler.post { rebuildPipeline(s) }
                if (old.debugMode != s.debugMode || buttonCallback == null) updateAccessibilityButton(s.debugMode)
                if (!s.monitoringEnabled) clearOutputs()
            }
        }
    }

    private fun rebuildPipeline(s: AppSettings) {
        adapter = UberDriverAdapter(UberAdapterConfig(targetPackages = s.targetPackages))
        evaluator = OfferEvaluator(s.evaluator.copy(homeCities = s.homeCities.ifBlank { s.fuel.region }))
        pipeline = OfferPipeline(adapter, evaluator, OfferTracker())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !(settings.monitoringEnabled || recording)) return
        if (settings.targetPackages.isEmpty() || !settings.consentAccepted) return // no app picked or no consent yet: read nothing
        if (!::workHandler.isInitialized) return
        // Events from other apps are only used to notice that the user left Uber; their content is never read.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (event.packageName?.toString() !in settings.targetPackages) return
            // The list is moving: drop the per-card badges now and read again once it settles.
            lastScrollAt = SystemClock.elapsedRealtime()
            if (::overlay.isInitialized) overlay.hideCards()
            scheduleRead(LIST_SETTLE_MS)
            return
        }
        val relevant = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // A window appeared or changed (e.g. a "Match" card sliding up over Uber's map): forget the "no offer here"
                // back-off so the next OCR runs right away instead of after the idle interval.
                if (event.packageName?.toString() in settings.targetPackages) workHandler.post { opaqueMisses.clear() }
                true
            }
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

    /** Worker thread. */
    private fun processFrame() {
        val s = settings
        if (!s.monitoringEnabled && recordDir == null) return
        if (s.targetPackages.isEmpty() || !s.consentAccepted) return
        val now = System.currentTimeMillis()
        try {
            val read = reader.read(s.targetPackages, now)
            MonitorState.update { it.copy(targetInForeground = read != null) }
            if (recordDir != null && read != null) recordFrame(read, now)
            if (!s.monitoringEnabled) return

            if (read == null) {
                frameGeneration++
                overlay.hideCards()
                handle(pipeline.processResult(AdapterResult.NoOffer, now), s, now)
                return
            }
            val adapted = adapter.extract(read.screen)
            val nodeOfferComplete = (adapted is AdapterResult.Offer && adapted.complete) ||
                (adapted is AdapterResult.OfferList && adapted.cards.any { it.complete })
            val candidate = if (!nodeOfferComplete && s.ocrEnabled) hiddenOfferRegion(read) else null
            if (candidate != null && !ocrAllowed(candidate.id)) {
                // Still Uber's hidden screen, but OCR'd it recently with no offer: look again when the back-off ends.
                // Don't treat this as a normal frame: that used to drop the OCR in flight, and if Uber sent no further
                // events (it often doesn't while an offer is up), a popup that appeared now was never read.
                val since = SystemClock.elapsedRealtime() - (lastOcrAtFor[candidate.id] ?: 0L)
                scheduleRead((OCR_IDLE_INTERVAL_MS - since).coerceAtLeast(READ_INTERVAL_MS))
                return
            }
            val region = candidate
            if (region == null) {
                frameGeneration++
                MonitorState.update { it.copy(lastAdapterNote = adapter.lastDebug?.note) }
                dispatch(adapted, s, now)
            } else {
                // Nodes are hidden: don't feed an "empty" frame to the tracker (it would think the offer vanished); use OCR instead.
                requestOcr(region, read.screen.packageName)
            }
        } catch (t: Throwable) {
            // Fail safe: any parse/read error is only logged; no verdict is shown and nothing is retried in a loop
            Log.w(TAG, "frame failed", t)
            clearOutputs()
        }
    }

    /**
     * Decides whether an offer popup is probably on screen but unreadable via nodes; returns the screen region to OCR, or null.
     * The caller applies the OCR back-off ([ocrAllowed]).
     *
     * Real-device snapshots (Android 15): when Uber shows an offer in the foreground, its whole UI layer disappears from the accessibility tree, leaving only the map.
     * When Uber is in the background it draws its own (non-application) windows on top of other apps.
     */
    private fun hiddenOfferRegion(read: NodeReader.ScreenRead): NodeReader.TargetWindow? {
        val dm = resources.displayMetrics
        val bigOverlay = read.windows.firstOrNull {
            !it.isApplication && it.bounds.width() >= dm.widthPixels / 2 && it.bounds.height() >= dm.heightPixels / 6
        }
        if (bigOverlay != null) return bigOverlay
        val app = read.windows.firstOrNull { it.isApplication }
        // Uber's own screen with its text hidden. Besides a fully empty tree, allow a sparse one: on the map, a few labels
        // (e.g. the "Trip Radar" chip) can stay readable while the offer card itself is hidden.
        if (app != null && read.screen.nodes.size <= SPARSE_NODES) return app
        // While Uber is online (an Uber window such as the floating bubble exists), a large unreadable window is most likely the offer card shown over another app.
        // If several OCR passes on the same window find no offer, it is only OCR'd every [OCR_IDLE_INTERVAL_MS] (the
        // caller's back-off). It used to be dropped for good, which missed an offer card that later appeared in a window
        // seen before (e.g. over the recent-apps screen or a photo picker).
        return read.opaqueWindows.firstOrNull()
    }

    /** Unreadable window id → number of consecutive OCR passes that found no offer. */
    private val opaqueMisses = HashMap<Int, Int>()
    /** Window id → when it was last OCR'd. */
    private val lastOcrAtFor = HashMap<Int, Long>()

    /**
     * After [OPAQUE_MAX_MISSES] OCR passes on the same Uber window found no offer (e.g. a screen that hides its text but isn't
     * an offer), OCR that window at most every [OCR_IDLE_INTERVAL_MS] instead of every [OCR_MIN_INTERVAL_MS].
     */
    private fun ocrAllowed(windowId: Int): Boolean =
        (opaqueMisses[windowId] ?: 0) < OPAQUE_MAX_MISSES ||
            SystemClock.elapsedRealtime() - (lastOcrAtFor[windowId] ?: 0L) >= OCR_IDLE_INTERVAL_MS

    // ---------------- OCR ----------------

    private var ocrInFlight = false
    private var lastOcrAt = 0L
    private var frameGeneration = 0L
    private var lastOcrLines: List<String> = emptyList()
    private var lastOcrLinesAt = 0L

    /** Worker thread. At least [OCR_MIN_INTERVAL_MS] apart, one at a time. */
    private fun requestOcr(window: NodeReader.TargetWindow, pkg: String) {
        if (ocrInFlight) return
        val wait = OCR_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastOcrAt)
        if (wait > 0) { scheduleRead(wait); return }
        ocrInFlight = true
        lastOcrAt = SystemClock.elapsedRealtime()
        lastOcrAtFor[window.id] = lastOcrAt
        if (lastOcrAtFor.size > 50) lastOcrAtFor.clear()
        val gen = frameGeneration
        val started = SystemClock.elapsedRealtime()
        val (bandTop, bandBottom) = ocrBand(settings)
        ocr.recognize(window.bounds, window.id, pkg, bandTop, bandBottom) { screen ->
            ocrInFlight = false
            val s = settings
            var found = false
            try {
                if (screen != null) {
                    found = adapter.extract(screen).let { it is AdapterResult.Offer || it is AdapterResult.OfferList }
                    if (found) opaqueMisses.remove(window.id) else opaqueMisses[window.id] = (opaqueMisses[window.id] ?: 0) + 1
                    if (opaqueMisses.size > 50) opaqueMisses.clear()
                }
                // The popup closed while OCR was running (a normal node frame arrived) → discard, so no stale verdict is shown
                if (screen != null && gen == frameGeneration && s.monitoringEnabled) {
                    lastOcrLines = screen.nodes.map { it.text }
                    lastOcrLinesAt = System.currentTimeMillis()
                    val adapted = adapter.extract(screen)
                    MonitorState.update { it.copy(lastAdapterNote = "OCR: " + (adapter.lastDebug?.note ?: "")) }
                    val now = System.currentTimeMillis()
                    // The list scrolled while this screenshot was being read: positions are stale, read again instead
                    if (adapted is AdapterResult.OfferList && lastScrollAt > started) scheduleRead(LIST_SETTLE_MS)
                    else dispatch(adapted, s, now)
                    Log.d(TAG, "ocr frame in ${SystemClock.elapsedRealtime() - started}ms, ${screen.nodes.size} lines")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ocr handling failed", t)
            }
            // Uber doesn't always emit events while the popup is up: look again so we notice it closing or changing.
            // After a few passes without an offer (e.g. a non-offer screen), stop polling and wait for the next window event.
            if (found || (opaqueMisses[window.id] ?: 0) < OPAQUE_MAX_MISSES) scheduleRead(OCR_MIN_INTERVAL_MS)
        }
    }

    /** Offer lists get per-card badges; everything else goes through the popup tracker. */
    private fun dispatch(adapted: AdapterResult, s: AppSettings, now: Long) {
        if (adapted is AdapterResult.OfferList) {
            handle(pipeline.processResult(AdapterResult.NoOffer, now), s, now) // no popup on screen
            showList(adapted, s)
        } else {
            overlay.hideCards()
            handle(pipeline.processResult(adapted, now), s, now)
        }
    }

    private val listHistoryKeys = LinkedHashSet<String>()

    /**
     * Trip Radar: the driver is browsing several offers, so there is no sound, speech or glow; each complete card gets
     * a small badge with its estimated net hourly next to its price.
     */
    private fun showList(list: AdapterResult.OfferList, s: AppSettings) {
        val badges = list.cards.filter { it.complete }.mapNotNull { c ->
            val r = evaluator.evaluate(c.offer, System.currentTimeMillis())
            if (!r.isConclusive) return@mapNotNull null
            val town = if (!s.evaluator.outOfTownAlert) null
            else com.offerpilot.core.parser.DropoffCity.outOfTown(c.offer.dropoffCity, s.homeCities.ifBlank { s.fuel.region })
            recordListHistory(c.offer, s)
            OverlayController.CardBadge(OfferMessages.overlay(c.offer, r, s.language, town), r.verdict, c.anchor.top, c.anchor.bottom)
        }
        if (s.overlayEnabled && badges.isNotEmpty()) overlay.showCards(badges) else overlay.hideCards()
        MonitorState.update { it.copy(lastAdapterNote = adapter.lastDebug?.note) }
        // Keep reading while the list is on screen so badges stay in place and new cards get one.
        scheduleRead(LIST_REFRESH_MS)
    }

    private fun recordListHistory(o: com.offerpilot.core.model.DeliveryOffer, s: AppSettings) {
        if (!s.historyEnabled || o.source == com.offerpilot.core.model.OfferSource.MOCK) return
        val price = o.estimatedPayout ?: return
        val km = o.distanceKm ?: return
        val min = o.durationMinutes ?: return
        val key = "$price/$km/$min"
        if (!listHistoryKeys.add(key)) return
        if (listHistoryKeys.size > 100) listHistoryKeys.remove(listHistoryKeys.first())
        val rec = com.offerpilot.core.stats.OfferRecord(System.currentTimeMillis(), price, km, min, o.kind, o.shopItems, o.shopUnits)
        workHandler.post { runCatching { com.offerpilot.app.history.OfferHistory.append(this, rec) } }
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

    private var lastHistoryKey: String? = null

    /** Stats tab history: one record per announced, judged offer (numbers only). */
    private fun recordHistory(out: PipelineOutput.Show, s: AppSettings) {
        val o = out.offer
        if (!s.historyEnabled || !out.announce || !out.result.isConclusive || o.source == com.offerpilot.core.model.OfferSource.MOCK) return
        val price = o.estimatedPayout ?: return
        val km = o.distanceKm ?: return
        val min = o.durationMinutes ?: return
        val key = "$price/$km/$min"
        if (key == lastHistoryKey) return
        lastHistoryKey = key
        val rec = com.offerpilot.core.stats.OfferRecord(System.currentTimeMillis(), price, km, min, o.kind, o.shopItems, o.shopUnits)
        workHandler.post { runCatching { com.offerpilot.app.history.OfferHistory.append(this, rec) } }
    }

    private var lastUnreadableLogAt = 0L

    /** Saves what was read and why it couldn't be judged, so "Unreadable" offers can be diagnosed later. Local only. */
    private fun logUnreadable(out: PipelineOutput.Show, s: AppSettings) {
        val now = System.currentTimeMillis()
        if (now - lastUnreadableLogAt < 3_000) return
        lastUnreadableLogAt = now
        val ocrLines = recentOcrLines()
        val adapterDebug = adapter.lastDebug
        workHandler.post {
            try {
                val o = out.offer
                val eval = org.json.JSONObject().apply {
                    put("source", o.source.name)
                    put("payout", o.estimatedPayout ?: org.json.JSONObject.NULL)
                    put("distanceKm", o.distanceKm ?: org.json.JSONObject.NULL)
                    put("durationMin", o.durationMinutes ?: org.json.JSONObject.NULL)
                    put("confidence", o.confidence.toDouble())
                    put("issues", org.json.JSONArray(o.issues.map { it.name }))
                    put("unknownReasons", org.json.JSONArray(out.result.unknownReasons.map { it.name }))
                    put("detail", OfferMessages.detail(o, out.result, com.offerpilot.core.i18n.Lang.EN))
                    put("appVersion", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "")
                }
                val file = snapshots.capture(s.targetPackages, "unreadable", adapterDebug, into = snapshots.unreadableDir, ocrLines = ocrLines, evaluation = eval)
                if (file != null && s.snapshotScreenshot) captureScreenshot(file)
                snapshots.trimUnreadable()
                MonitorState.update { it.copy(lastSnapshotPath = file?.absolutePath) }
            } catch (t: Throwable) {
                Log.w(TAG, "unreadable log failed", t)
            }
        }
    }

    private fun present(out: PipelineOutput.Show, s: AppSettings) {
        if (!out.result.isConclusive && s.logUnreadable && s.developerMode && out.offer.source != com.offerpilot.core.model.OfferSource.MOCK) logUnreadable(out, s)
        recordHistory(out, s)
        // City-only comparison in memory; the city is never logged or stored.
        val outOfTown = if (!s.evaluator.outOfTownAlert) null
        else com.offerpilot.core.parser.DropoffCity.outOfTown(out.offer.dropoffCity, s.homeCities.ifBlank { s.fuel.region })
        val overlayText = OfferMessages.overlay(out.offer, out.result, s.language, outOfTown)
        MonitorState.update {
            it.copy(
                lastRecognition = LastRecognition(
                    timeMs = out.offer.timestamp,
                    success = out.result.isConclusive,
                    summary = overlayText,
                    detail = OfferMessages.detail(out.offer, out.result, s.language),
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
            val soundMs = if (s.soundEnabled) sounds.play(out.result.verdict, s.tierSounds[out.result.verdict]) else 0L
            if (s.ttsEnabled) {
                val text = OfferMessages.speech(out.offer, out.result, s.language, outOfTown)
                pendingSpeech?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable { pendingSpeech = null; speaker.speak(text, s.ttsVolume) }
                pendingSpeech = r
                mainHandler.postDelayed(r, soundMs) // speak after the sound finishes
            }
            if (s.vibrationEnabled) mainHandler.post { runCatching { haptics.play(out.result.verdict) } }
        }
        actionHandler.onEvaluated(out.offer, out.result) // no-op: v1 never performs any action
    }

    private fun screenHeight(): Int = runCatching {
        getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.height()
    }.getOrDefault(resources.displayMetrics.heightPixels)

    /** The OCR reading area in screen pixels, from the user's percentages. */
    private fun ocrBand(s: AppSettings): Pair<Int, Int> {
        val h = screenHeight()
        val top = s.ocrTopPercent.coerceIn(0, 95)
        val bottom = s.ocrBottomPercent.coerceIn(top + 5, 100)
        return h * top / 100 to (if (bottom >= 100) Int.MAX_VALUE else h * bottom / 100)
    }

    /** Settings page: outline the reading area on the real screen for a few seconds. */
    fun showOcrBand(topPercent: Int, bottomPercent: Int) {
        if (!::overlay.isInitialized) return
        val h = screenHeight()
        overlay.showBand(h * topPercent / 100, h * bottomPercent / 100)
    }

    /** Called from the mock page: previews a mock offer with the real overlay / speech / vibration. */
    fun preview(offer: com.offerpilot.core.model.DeliveryOffer, result: com.offerpilot.core.model.EvaluationResult) {
        present(PipelineOutput.Show(offer, result, announce = true), settings)
    }

    private fun clearOutputs() {
        if (!::overlay.isInitialized) return
        overlay.hide()
        overlay.hideCards()
        pendingSpeech?.let { mainHandler.removeCallbacks(it) }
        pendingSpeech = null
        speaker.stop()
        sounds.stop()
    }

    // ---------------- Debug snapshots ----------------

    private fun maybeAutoSnapshot(s: AppSettings, out: PipelineOutput.Show) {
        if (!s.debugMode || !s.autoSnapshot) return
        val key = OfferTracker.dedupKey(out.offer)
        if (key == lastAutoSnapshotKey) return
        if (snapshots.list().size >= MAX_AUTO_SNAPSHOTS) return
        lastAutoSnapshotKey = key
        takeSnapshotNow("auto:${out.result.verdict}")
    }

    // ---------------- Continuous capture ----------------

    @Volatile private var recording = false

    /** Called from the UI: start continuous capture. Saves a snapshot each time the target app's text changes (at least 1 s apart). */
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

    /** Called from the UI: stop continuous capture and pack it into a zip. */
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

    /** Worker thread: save only when the screen text changed, to avoid duplicates from map animation. */
    private fun recordFrame(read: NodeReader.ScreenRead, now: Long) {
        val dir = recordDir ?: return
        val hash = read.signature
        if (hash == recordLastHash) return
        if (now - recordLastAt < RECORD_MIN_INTERVAL_MS) {
            // Read once more later so the final state after a change is also saved
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
        overlay.showBadge(settings.language.t("● REC $recordCount", "● 抓取中 $recordCount"))
    }

    /** Called from the UI: capture the current screen after [delayMs] (time to switch to Uber). */
    fun scheduleSnapshot(delayMs: Long) {
        MonitorState.update { it.copy(snapshotCountdownUntil = System.currentTimeMillis() + delayMs) }
        workHandler.postDelayed({
            MonitorState.update { it.copy(snapshotCountdownUntil = null) }
            takeSnapshotNow("delayed")
        }, delayMs)
    }

    /** Worker thread. */
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

    /** In debug mode, show the accessibility button in the nav bar: tap it while Uber shows an offer to capture a snapshot. */
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
        // Permission revoked or service turned off: remove all hints and exit safely
        clearOutputs()
        if (::overlay.isInitialized) overlay.hideBadge()
        MonitorState.update { it.copy(recording = null) }
        if (::speaker.isInitialized) speaker.shutdown()
        if (::sounds.isInitialized) runCatching { sounds.release() }
        if (::ocr.isInitialized) runCatching { ocr.close() }
        if (::worker.isInitialized) worker.quitSafely()
        scope.cancel()
        MonitorState.update { it.copy(serviceConnected = false, targetInForeground = false) }
        instanceRef = WeakReference(null)
        super.onDestroy()
    }
}
