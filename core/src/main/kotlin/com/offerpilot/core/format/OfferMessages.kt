package com.offerpilot.core.format

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** Short overlay and speech texts (PRD 5.2 / 5.3). Addresses are never read aloud. */
object OfferMessages {

    /**
     * Overlay pill: shows only the estimated net hourly (the tier is shown by the edge colour; distance is already in the net hourly).
     * e.g. "净 $17/h", "净 -$4/h"; "无法识别" when no verdict.
     */
    fun overlay(offer: DeliveryOffer, result: EvaluationResult): String {
        val h = result.netHourly?.takeIf { result.isConclusive } ?: return "无法识别"
        val v = h.roundToInt()
        return if (v < 0) "净 -$" + (-v) + "/h" else "净 $" + v + "/h"
    }

    /** e.g. "9块，30公里，不达标。" / "订单信息不完整。" */
    fun speech(offer: DeliveryOffer, result: EvaluationResult): String {
        if (result.verdict == Verdict.UNKNOWN) return "${Verdict.UNKNOWN.speech}。"
        val p = offer.estimatedPayout!!.roundToInt()
        val k = offer.distanceKm!!.roundToInt()
        val word = if (result.verdict == Verdict.LOSS && (result.netAfterVehicleCost ?: 0.0) < 0) "亏本" else result.verdict.speech
        return "${p}块，${k}公里，$word。"
    }

    /** Detailed explanation for the home and mock pages. */
    fun detail(offer: DeliveryOffer, result: EvaluationResult): String = buildString {
        appendLine("报价：${money(offer.estimatedPayout)}  距离：${num(offer.distanceKm)} km  时间：${num(offer.durationMinutes)} 分钟")
        if (result.isConclusive) {
            appendLine("不含小费价：${money(result.basePay)}")
            appendLine("车辆成本/距离部分：${money(result.distanceRequirement)}  时间部分：${money(result.timeRequirement)}")
            appendLine("达标价：${money(result.requiredPrice)}  比值：${String.format(Locale.US, "%.2f", result.ratio)}")
        } else {
            appendLine("无法判断：" + result.unknownReasons.joinToString("、") { it.description })
        }
        if (result.belowMinimumOrder) appendLine("低于设置的最低订单金额")
        result.vehicleCost?.let { appendLine("估算车辆成本：${money(it)}" + (result.netAfterVehicleCost?.let { n -> "  不含小费扣除后：${money(n)}" } ?: "")) }
        result.netHourly?.let { appendLine("预计净时薪：${money(it)}/h") }
        if (offer.issues.isNotEmpty()) appendLine("解析提示：" + offer.issues.joinToString("、") { it.description })
        append("结论：${result.verdict.label}  置信度：${String.format(Locale.US, "%.2f", offer.confidence)}")
    }

    fun money(v: Double?): String = v?.let { "$" + String.format(Locale.US, "%.2f", it) } ?: "—"
    fun num(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: "—"
}
