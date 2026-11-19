package com.offerpilot.core

import com.offerpilot.core.model.ReturnTrip
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.rules.RuleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 默认模式：最低报价 = 公里 × 每公里车辆成本 + 小时 × 目标净时薪。
 * 默认：油耗 10L/100km、油价 $1.36/L、维护 $10/100km → $0.236/km；目标净时薪 $18 → $0.30/min。
 */
class NetHourlyEvaluatorTest {
    private val ev = OfferEvaluator()
    private fun near(expected: Double, actual: Double?, eps: Double = 0.01) =
        assertTrue(actual != null && kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test fun defaults() {
        val s = EvaluatorSettings()
        assertEquals(RuleMode.NET_HOURLY, s.mode)
        assertEquals(18.0, s.targetNetHourly)
        near(0.236, s.vehicleCostPerKm, 1e-9)
        near(0.30, s.netPerMinute, 1e-9)
    }

    @Test fun caseA() {
        // 29.92 × 0.236 = 7.06；42 × 0.30 = 12.60；最低 19.66；ratio 0.46
        val r = ev.evaluate(offer(9.0, 29.92, 42.0))
        near(7.06, r.distanceRequirement)
        near(12.60, r.timeRequirement)
        near(19.66, r.requiredPrice)
        near(2.77, r.netHourly)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun caseB() {
        // 40.99 × 0.236 = 9.67；63 × 0.30 = 18.90；最低 28.57；ratio 1.155
        val r = ev.evaluate(offer(33.01, 40.99, 63.0))
        near(28.57, r.requiredPrice)
        near(22.22, r.netHourly)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun caseC() {
        // 3 × 0.236 = 0.71；10 × 0.30 = 3.00；最低 3.71；ratio 1.89
        val r = ev.evaluate(offer(7.0, 3.0, 10.0))
        near(3.71, r.requiredPrice)
        near(37.75, r.netHourly)
        assertEquals(Verdict.GREAT, r.verdict)
    }

    @Test fun ratio_one_means_exactly_target_net_hourly() {
        val s = EvaluatorSettings()
        val required = s.requiredFor(20.0, 30.0) // 4.72 + 9.00 = 13.72
        near(13.72, required)
        val r = OfferEvaluator(s).evaluate(offer(required, 20.0, 30.0))
        near(1.0, r.ratio, 1e-9)
        near(18.0, r.netHourly, 1e-9)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun vehicle_inputs_change_result() {
        // 油耗 15L、维护 $20/100km → (15×1.36+20)/100 = 0.404/km；案例 B 最低 = 16.56 + 18.90 = 35.46 → 不推荐
        val thirsty = OfferEvaluator(EvaluatorSettings(fuelLitersPer100Km = 15.0, maintenancePer100Km = 20.0))
        val r = thirsty.evaluate(offer(33.01, 40.99, 63.0))
        near(35.46, r.requiredPrice)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun higher_target_hourly() {
        val r = OfferEvaluator(EvaluatorSettings(targetNetHourly = 30.0)).evaluate(offer(33.01, 40.99, 63.0))
        near(41.17, r.requiredPrice) // 9.67 + 31.50
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }

    @Test fun loss_when_payout_below_vehicle_cost() {
        // 30km 成本 7.08；$6 不够回本 → 红
        val r = ev.evaluate(offer(6.0, 30.0, 40.0))
        near(7.08, r.vehicleCost)
        assertEquals(Verdict.LOSS, r.verdict)
        assertTrue(r.netHourly!! < 0)
    }

    @Test fun great_tier() {
        val r = OfferEvaluator(EvaluatorSettings(greatRatio = 1.5)).evaluate(offer(7.0, 3.0, 10.0)) // 1.89
        assertEquals(Verdict.GREAT, r.verdict)
        val r2 = OfferEvaluator(EvaluatorSettings(greatRatio = 2.0)).evaluate(offer(7.0, 3.0, 10.0))
        assertEquals(Verdict.GOOD, r2.verdict)
    }

    @Test fun manual_thresholds() {
        val r = OfferEvaluator(EvaluatorSettings(goodRatio = 1.1)).evaluate(offer(33.01, 40.99, 63.0))
        assertEquals(Verdict.GOOD, r.verdict)
    }

    @Test fun return_trip_adds_cost_and_time() {
        // 案例 B + 返程 20km / 20min：(60.99 × 0.236) + (83 × 0.30) = 14.39 + 24.90 = 39.29
        val r = ev.evaluate(offer(33.01, 40.99, 63.0), returnTrip = ReturnTrip(20.0, 20.0))
        near(39.29, r.requiredPrice)
        assertEquals(Verdict.MIN_WAGE, r.verdict)
    }
}
