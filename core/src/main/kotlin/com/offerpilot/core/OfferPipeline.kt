package com.offerpilot.core

import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.OfferAdapter
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.tracking.OfferTracker
import com.offerpilot.core.tracking.TrackerEvent

/**
 * 识别 → 跟踪 → 计算 的组合。显示与动作由 Android 层订阅 [PipelineOutput] 自行处理。
 */
class OfferPipeline(
    private val adapter: OfferAdapter,
    private val evaluator: OfferEvaluator,
    val tracker: OfferTracker = OfferTracker(),
) {
    fun process(screen: ScreenText, now: Long): PipelineOutput = processResult(adapter.extract(screen), now)

    /** 已经由适配器处理过的结果（例如调用方需要先看节点结果再决定是否走 OCR）。 */
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
