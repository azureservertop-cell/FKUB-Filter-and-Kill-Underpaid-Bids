package com.offerpilot.core.format

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** 悬浮窗与语音的简短文案（PRD 5.2 / 5.3）。不朗读地址。 */
object OfferMessages {

    /** 例："不推荐 · $9 / 30km · 净$3/h" */
    fun overlay(offer: DeliveryOffer, result: EvaluationResult): String {
        val parts = buildList {
            offer.estimatedPayout?.let { add("$" + it.roundToInt()) }
            offer.distanceKm?.let { add("${it.roundToInt()}km") }
        }
        val label = result.verdict.shortLabel
        val base = if (parts.isEmpty()) label else "$label · ${parts.joinToString(" / ")}"
        val hourly = result.netHourly?.takeIf { result.isConclusive }?.let { " · 净$" + it.roundToInt() + "/h" } ?: ""
        return base + hourly
    }

    /** 例："9块，30公里，不推荐。" / "订单信息不完整。" */
    fun speech(offer: DeliveryOffer, result: EvaluationResult): String {
        if (result.verdict == Verdict.UNKNOWN) return "${Verdict.UNKNOWN.speech}。"
        val p = offer.estimatedPayout!!.roundToInt()
        val k = offer.distanceKm!!.roundToInt()
        return "${p}块，${k}公里，${result.verdict.speech}。"
    }

    /** 详细说明，用于主界面/模拟测试页。 */
    fun detail(offer: DeliveryOffer, result: EvaluationResult): String = buildString {
        appendLine("报价：${money(offer.estimatedPayout)}  距离：${num(offer.distanceKm)} km  时间：${num(offer.durationMinutes)} 分钟")
        if (result.isConclusive) {
            appendLine("车辆成本/距离部分：${money(result.distanceRequirement)}  时间部分：${money(result.timeRequirement)}")
            appendLine("最低报价：${money(result.requiredPrice)}  比值：${String.format(Locale.US, "%.2f", result.ratio)}")
        } else {
            appendLine("无法判断：" + result.unknownReasons.joinToString("、") { it.description })
        }
        if (result.belowMinimumOrder) appendLine("低于设置的最低订单金额")
        result.vehicleCost?.let { appendLine("估算车辆成本：${money(it)}" + (result.netAfterVehicleCost?.let { n -> "  扣除后：${money(n)}" } ?: "")) }
        result.netHourly?.let { appendLine("预计净时薪：${money(it)}/h") }
        if (offer.issues.isNotEmpty()) appendLine("解析提示：" + offer.issues.joinToString("、") { it.description })
        append("结论：${result.verdict.label}  置信度：${String.format(Locale.US, "%.2f", offer.confidence)}")
    }

    fun money(v: Double?): String = v?.let { "$" + String.format(Locale.US, "%.2f", it) } ?: "—"
    fun num(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: "—"
}
