package com.offerpilot.core.rules

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.ReturnTrip
import com.offerpilot.core.model.UnknownReason
import com.offerpilot.core.model.Verdict
import kotlin.math.max

/**
 * Earnings evaluator (PRD section 3).
 *
 * Takes only a [DeliveryOffer]; no dependency on AccessibilityService or any Android class.
 *
 * Every comparison uses tip-free pay = shown price × tipFreeShare (default 0.85).
 * Default (NET_HOURLY): required pay = km × vehicle cost per km + hours × target net hourly
 *   ratio = tip-free pay / required pay: < 1 red, 1 ~ goodRatio yellow, goodRatio ~ greatRatio green, ≥ greatRatio blue
 *   distanceRequirement = vehicle cost part, timeRequirement = net hourly part, requiredPrice = their sum
 * Simple rule (PER_KM_PER_MINUTE): required pay = max(km × minPerKm, minutes × minPerMinute)
 * With a return trip, its km and minutes are added to both.
 */
class OfferEvaluator(private val settings: EvaluatorSettings = EvaluatorSettings()) {

    fun evaluate(
        offer: DeliveryOffer,
        nowMs: Long = offer.timestamp,
        returnTrip: ReturnTrip? = null,
    ): EvaluationResult = withExtraTime(offer).let { o -> evaluateWith(o, nowMs, returnTrip ?: outOfTownReturnTrip(o)) }

    /** Time Uber's estimate leaves out: waiting for the food, and shopping for Shop orders. */
    fun withExtraTime(offer: DeliveryOffer): DeliveryOffer {
        val o = withShopTime(offer)
        val min = o.durationMinutes ?: return o
        if (settings.pickupWaitMinutes <= 0 || min <= 0) return o // a zero/negative time stays implausible
        return o.copy(durationMinutes = min + settings.pickupWaitMinutes)
    }

    /** Shop orders: Uber's time doesn't include picking the items, so add item count × minutes per item. */
    fun withShopTime(offer: DeliveryOffer): DeliveryOffer {
        val items = (if (settings.shopCountUnits) offer.shopUnits ?: offer.shopItems else offer.shopItems) ?: return offer
        val min = offer.durationMinutes?.takeIf { it > 0 } ?: return offer
        if (!settings.shopTimeEnabled || settings.shopMinutesPerItem <= 0) return offer
        return offer.copy(durationMinutes = min + items * settings.shopMinutesPerItem)
    }

    private fun evaluateWith(offer: DeliveryOffer, nowMs: Long, returnTrip: ReturnTrip?): EvaluationResult {
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
        // Uber's shown price includes the estimated tip; always judge on the tip-free part (default shown price × 85%)
        val basePay = payout * settings.tipFreeShare
        val ratio = basePay / required
        val belowMin = settings.minOrderAmount > 0 && payout < settings.minOrderAmount

        val verdict = when {
            belowMin -> Verdict.LOSS
            ratio + 1e-9 < 1.0 -> Verdict.LOSS          // tip-free pay doesn't cover target net hourly + vehicle cost: red
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

    /**
     * Out-of-town offers (drop-off city is not one of the driver's cities), when enabled: the empty drive back is
     * estimated as (multiplier − 1) × the trip's distance and time. City-level only.
     */
    fun outOfTownReturnTrip(offer: DeliveryOffer): ReturnTrip? {
        if (!settings.outOfTownAlert || !settings.outOfTownReturnTrip) return null
        val extra = settings.returnDistanceMultiplier - 1.0
        if (extra <= 0) return null
        com.offerpilot.core.parser.DropoffCity.outOfTown(offer.dropoffCity, settings.homeCities) ?: return null
        val km = offer.distanceKm ?: return null
        val min = offer.durationMinutes ?: return null
        return ReturnTrip(km * extra, min * extra)
    }
}
