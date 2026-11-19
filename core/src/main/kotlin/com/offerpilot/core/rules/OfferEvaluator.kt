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
 * 所有比较都用「不含小费价」= 显示价 × tipFreeShare（默认 0.85）。
 * 默认（NET_HOURLY）：达标价 = 公里 × 每公里车辆成本 + 小时 × 目标净时薪
 *   比值 = 不含小费价 / 达标价：< 1 红，1 ~ goodRatio 黄，goodRatio ~ greatRatio 绿，≥ greatRatio 蓝
 *   distanceRequirement = 车辆成本部分，timeRequirement = 净时薪部分，requiredPrice = 两者之和
 * 简单规则（PER_KM_PER_MINUTE）：最低报价 = max(公里 × minPerKm, 分钟 × minPerMinute)
 * 有返程时公里和分钟都加上返程部分。
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
        val distanceReq: Double
        val timeReq: Double
        val required: Double
        when (settings.mode) {
            RuleMode.NET_HOURLY -> {
                distanceReq = totalKm * settings.vehicleCostPerKm
                timeReq = totalMin * settings.netPerMinute
                required = distanceReq + timeReq
            }
            RuleMode.PER_KM_PER_MINUTE -> {
                distanceReq = totalKm * settings.minPerKm
                timeReq = totalMin * settings.minPerMinute
                required = max(distanceReq, timeReq)
            }
        }
        // Uber 显示价含预估小费；判断一律用不含小费的部分（默认显示价 × 85%）
        val basePay = payout * settings.tipFreeShare
        val ratio = basePay / required
        val belowMin = settings.minOrderAmount > 0 && payout < settings.minOrderAmount

        val verdict = when {
            belowMin -> Verdict.LOSS
            ratio + 1e-9 < 1.0 -> Verdict.LOSS          // 不含小费价不够「期望净时薪 + 车辆成本」：红
            ratio < settings.goodRatio -> Verdict.MIN_WAGE
            ratio < settings.greatRatio -> Verdict.GOOD
            else -> Verdict.GREAT
        }

        return EvaluationResult(
            verdict = verdict,
            ratio = ratio,
            requiredPrice = required,
            distanceRequirement = distanceReq,
            timeRequirement = timeReq,
            vehicleCost = vehicleCost,
            netAfterVehicleCost = vehicleCost?.let { basePay - it },
            netHourly = vehicleCost?.let { (basePay - it) / (totalMin / 60.0) },
            basePay = basePay,
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
