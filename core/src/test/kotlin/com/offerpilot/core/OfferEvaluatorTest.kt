package com.offerpilot.core

import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.ReturnTrip
import com.offerpilot.core.model.UnknownReason
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.rules.RuleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the PRD 3.1 simple rule mode (max(km×0.8, min×0.5)). The default net-hourly mode is covered by NetHourlyEvaluatorTest. */
class OfferEvaluatorTest {
    private val simple = EvaluatorSettings(mode = RuleMode.PER_KM_PER_MINUTE, tipFreeShare = 1.0)
    private val ev = OfferEvaluator(simple)
    private fun near(expected: Double, actual: Double?, eps: Double = 0.005) =
        assertTrue(actual != null && kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")

    // ---- PRD 3.3 test cases ----
    @Test fun caseA_longCheap_notRecommended() {
        val r = ev.evaluate(offer(9.0, 29.92, 42.0))
        near(23.94, r.distanceRequirement)
        near(21.0, r.timeRequirement)
        near(23.94, r.requiredPrice)
        assertEquals(Verdict.LOSS, r.verdict)
    }

    @Test fun caseB_walmartMultiStop_marginal() {
        val r = ev.evaluate(offer(33.01, 40.99, 63.0))
        near(32.79, r.distanceRequirement)
        near(31.50, r.timeRequirement)
        near(32.79, r.requiredPrice)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun caseC_short_recommended() {
        val r = ev.evaluate(offer(7.0, 3.0, 10.0))
        near(2.40, r.distanceRequirement)
        near(5.0, r.timeRequirement)
        near(5.0, r.requiredPrice)
        assertEquals(Verdict.GOOD, r.verdict)
    }

    // ---- Boundaries ----
    @Test fun tier_boundaries() {
        assertEquals(Verdict.GREAT, ev.evaluate(offer(14.4, 10.0, 10.0)).verdict)   // ratio 1.8
        assertEquals(Verdict.GOOD, ev.evaluate(offer(14.39, 10.0, 10.0)).verdict)
        assertEquals(Verdict.LOSS, ev.evaluate(offer(2.35, 10.0, 10.0)).verdict)     // below cost
    }

    @Test fun ratio_exactly_1_is_marginal_and_1_2_is_recommended() {
        assertEquals(Verdict.MIN_WAGE, ev.evaluate(offer(8.0, 10.0, 10.0)).verdict)   // req 8, ratio 1.0
        assertEquals(Verdict.GOOD, ev.evaluate(offer(9.6, 10.0, 10.0)).verdict) // ratio 1.2
        assertEquals(Verdict.LOSS, ev.evaluate(offer(7.99, 10.0, 10.0)).verdict)
        assertEquals(Verdict.MIN_WAGE, ev.evaluate(offer(9.59, 10.0, 10.0)).verdict)
    }

    @Test fun custom_thresholds() {
        val strict = OfferEvaluator(simple.copy(minPerKm = 1.0, minPerMinute = 0.6, goodRatio = 1.5))
        val r = strict.evaluate(offer(7.0, 3.0, 10.0)) // req max(3, 6) = 6, ratio 1.17
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun min_order_amount() {
        val s = OfferEvaluator(simple.copy(minOrderAmount = 8.0))
        val r = s.evaluate(offer(7.0, 3.0, 10.0))
        assertEquals(Verdict.LOSS, r.verdict)
        assertTrue(r.belowMinimumOrder)
    }

    // ---- Missing / invalid ----
    @Test fun missing_values_unknown() {
        assertEquals(listOf(UnknownReason.MISSING_PAYOUT), ev.evaluate(offer(null, 3.0, 10.0)).unknownReasons)
        assertEquals(listOf(UnknownReason.MISSING_DISTANCE), ev.evaluate(offer(7.0, null, 10.0)).unknownReasons)
        assertEquals(listOf(UnknownReason.MISSING_DURATION), ev.evaluate(offer(7.0, 3.0, null)).unknownReasons)
        val r = ev.evaluate(offer(null, null, null))
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertNull(r.ratio)
    }

    @Test fun zero_negative_huge_are_implausible() {
        listOf(
            offer(0.0, 3.0, 10.0), offer(-7.0, 3.0, 10.0), offer(7.0, 0.0, 10.0), offer(7.0, 3.0, 0.0),
            offer(7.0, -3.0, 10.0), offer(99999.0, 3.0, 10.0), offer(7.0, 5000.0, 10.0), offer(7.0, 3.0, 10000.0),
            offer(50.0, 100.0, 10.0), // 600 km/h
            offer(Double.NaN, 3.0, 10.0),
        ).forEach {
            val r = ev.evaluate(it)
            assertEquals(Verdict.UNKNOWN, r.verdict, "should be unknown: $it")
            assertTrue(UnknownReason.IMPLAUSIBLE_VALUES in r.unknownReasons)
        }
    }

    @Test fun low_confidence_unknown() {
        val r = ev.evaluate(offer(7.0, 3.0, 10.0, confidence = 0.3f))
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertTrue(UnknownReason.LOW_CONFIDENCE in r.unknownReasons)
    }

    @Test fun ambiguity_issue_unknown() {
        val o = offer(7.0, 3.0, 10.0).copy(issues = listOf(ParseIssue.MULTIPLE_OFFERS))
        assertEquals(Verdict.UNKNOWN, ev.evaluate(o).verdict)
    }

    @Test fun stale_offer_unknown() {
        val r = ev.evaluate(offer(7.0, 3.0, 10.0, ts = 0), nowMs = 60_000)
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertTrue(UnknownReason.STALE in r.unknownReasons)
    }

    // ---- Return trip cost (PRD 6.2) ----
    @Test fun return_trip_cost() {
        // case B + 20 km / 20 min return: max(60.99*0.8, 83*0.5) = 48.79 → ratio 0.68
        val r = ev.evaluate(offer(33.01, 40.99, 63.0), returnTrip = ReturnTrip(20.0, 20.0))
        near(48.79, r.requiredPrice)
        assertEquals(Verdict.LOSS, r.verdict)
        // a zero return trip equals no return trip
        assertEquals(Verdict.MIN_WAGE, ev.evaluate(offer(33.01, 40.99, 63.0), returnTrip = ReturnTrip(0.0, 0.0)).verdict)
    }

    @Test fun vehicle_cost_display_only() {
        // (10L × 1.36 + $10) / 100 = 0.236 $/km → 29.92km = 7.06
        val r = ev.evaluate(offer(9.0, 29.92, 42.0))
        near(7.06, r.vehicleCost, 0.01)
        near(1.94, r.netAfterVehicleCost, 0.01)
        assertEquals(Verdict.LOSS, r.verdict) // below target → red
    }
}
