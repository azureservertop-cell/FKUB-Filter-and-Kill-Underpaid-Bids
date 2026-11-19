package com.offerpilot.core.rules

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.ReturnTrip
import com.offerpilot.core.model.UnknownReason
import com.offerpilot.core.model.Verdict
import kotlin.math.max

/**
 * 收益计算引擎（PRD 三）。
 *
 * 只接收 [DeliveryOffer]，不依赖 AccessibilityService 或任何 Android 类。
 *
 * 最低接受报价 = max(公里 × minPerKm, 分钟 × minPerMinute)
 * 有返程时   = max((配送公里 + 返程公里) × minPerKm, (配送分钟 + 返程分钟) × minPerMinute)
 */
class OfferEvaluator(private val settings: EvaluatorSettings = EvaluatorSettings()) {

    fun evaluate(
        offer: DeliveryOffer,
        nowMs: Long = offer.timestamp,
        returnTrip: ReturnTrip? = null,
    ): EvaluationResult {
        val reasons = mutableListOf<UnknownReason>()
        val payout = offer.estimatedPayout
        val km = offer.distanceKm
        val min = offer.durationMinutes

        if (payout == null) reasons += UnknownReason.MISSING_PAYOUT
        if (km == null) reasons += UnknownReason.MISSING_DISTANCE
        if (min == null) reasons += UnknownReason.MISSING_DURATION
        if (offer.issues.any { it in AMBIGUITY_ISSUES }) reasons += UnknownReason.AMBIGUOUS
        if (offer.confidence < settings.minConfidence) reasons += UnknownReason.LOW_CONFIDENCE
        if (nowMs - offer.timestamp > settings.maxOfferAgeMs) reasons += UnknownReason.STALE
        if (payout != null && km != null && min != null && !isPlausible(payout, km, min, returnTrip)) {
            reasons += UnknownReason.IMPLAUSIBLE_VALUES
        }

        val vehicleCost = km?.let { (it + (returnTrip?.km ?: 0.0)) * settings.vehicleCostPerKm }

        if (reasons.isNotEmpty() || payout == null || km == null || min == null) {
            return EvaluationResult(
                verdict = Verdict.UNKNOWN,
                ratio = null,
                requiredPrice = null,
                distanceRequirement = null,
                timeRequirement = null,
                vehicleCost = vehicleCost,
                netAfterVehicleCost = null,
                unknownReasons = reasons.distinct(),
            )
        }

        val totalKm = km + (returnTrip?.km ?: 0.0)
        val totalMin = min + (returnTrip?.minutes ?: 0.0)
        val distanceReq = totalKm * settings.minPerKm
        val timeReq = totalMin * settings.minPerMinute
        val required = max(distanceReq, timeReq)
        val ratio = payout / required
        val belowMin = settings.minOrderAmount > 0 && payout < settings.minOrderAmount

        val verdict = when {
            belowMin -> Verdict.NOT_RECOMMENDED
            ratio < settings.passRatio -> Verdict.NOT_RECOMMENDED
            ratio < settings.goodRatio -> Verdict.MARGINAL
            else -> Verdict.RECOMMENDED
        }

        return EvaluationResult(
            verdict = verdict,
            ratio = ratio,
            requiredPrice = required,
            distanceRequirement = distanceReq,
            timeRequirement = timeReq,
            vehicleCost = vehicleCost,
            netAfterVehicleCost = vehicleCost?.let { payout - it },
            belowMinimumOrder = belowMin,
        )
    }

    private fun isPlausible(payout: Double, km: Double, min: Double, rt: ReturnTrip?): Boolean {
        if (payout <= 0 || km <= 0 || min <= 0) return false
        if (payout.isNaN() || km.isNaN() || min.isNaN()) return false
        if (payout > settings.maxPlausiblePayout) return false
        if (km > settings.maxPlausibleKm || min > settings.maxPlausibleMinutes) return false
        if (km / (min / 60.0) > settings.maxPlausibleSpeedKmh) return false
        if (rt != null && (rt.km < 0 || rt.minutes < 0)) return false
        return true
    }

    private companion object {
        val AMBIGUITY_ISSUES = setOf(
            ParseIssue.PAYOUT_AMBIGUOUS,
            ParseIssue.DISTANCE_AMBIGUOUS,
            ParseIssue.DURATION_AMBIGUOUS,
            ParseIssue.MULTIPLE_OFFERS,
        )
    }
}
