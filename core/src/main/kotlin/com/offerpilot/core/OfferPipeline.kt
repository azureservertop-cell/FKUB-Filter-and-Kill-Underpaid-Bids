package com.offerpilot.core

import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.OfferAdapter
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.tracking.OfferTracker
import com.offerpilot.core.tracking.TrackerEvent

/**
 * Recognize → track → evaluate. Display and actions are handled by the Android layer from [PipelineOutput].
 */
class OfferPipeline(
    private val adapter: OfferAdapter,
    private val evaluator: OfferEvaluator,
    val tracker: OfferTracker = OfferTracker(),
) {
    fun process(screen: ScreenText, now: Long): PipelineOutput = processResult(adapter.extract(screen), now)

    /** For a result the adapter already produced (e.g. the caller checks the node result before deciding on OCR). */
    fun processResult(adapted: AdapterResult, now: Long): PipelineOutput {
        return when (val ev = tracker.onFrame(adapted, now)) {
            is TrackerEvent.NewOffer -> PipelineOutput.Show(ev.offer, evaluator.evaluate(ev.offer, now), announce = !ev.suppressAnnouncement)
            is TrackerEvent.OfferGone -> PipelineOutput.Clear
            TrackerEvent.PendingIncomplete -> PipelineOutput.RecheckLater(tracker.settleMs)
            TrackerEvent.Unchanged, TrackerEvent.Idle -> PipelineOutput.None
        }
    }
}

sealed interface PipelineOutput {
    data object None : PipelineOutput
    data object Clear : PipelineOutput
    data class RecheckLater(val delayMs: Long) : PipelineOutput
    data class Show(
        val offer: com.offerpilot.core.model.DeliveryOffer,
        val result: EvaluationResult,
        val announce: Boolean,
    ) : PipelineOutput
}
