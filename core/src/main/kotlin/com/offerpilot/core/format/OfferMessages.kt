package com.offerpilot.core.format

import com.offerpilot.core.i18n.Lang
import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** Short overlay and speech texts (PRD 5.2 / 5.3). Addresses are never read aloud. */
object OfferMessages {

    /**
     * Overlay pill: shows only the estimated net hourly (the tier is shown by the edge colour; distance is already in the net hourly).
     * EN: "Net $17/h", "Net -$4/h", "Unreadable"; ZH: "净 $17/h", "净 -$4/h", "无法识别".
     */
    fun overlay(offer: DeliveryOffer, result: EvaluationResult, lang: Lang = Lang.EN, outOfTown: String? = null): String {
        val town = outOfTown?.let { "⚠ $it · " } ?: ""
        val h = result.netHourly?.takeIf { result.isConclusive } ?: return town + lang.t("Unreadable", "无法识别")
        val v = h.roundToInt()
        val prefix = lang.t("Net", "净")
        return town + if (v < 0) "$prefix -$" + (-v) + "/h" else "$prefix $" + v + "/h"
    }

    /** EN: "9 dollars, 30 kilometres, below target." ZH: "9块，30公里，不达标。" Unknown: "Offer details incomplete." */
    fun speech(offer: DeliveryOffer, result: EvaluationResult, lang: Lang = Lang.EN, outOfTown: String? = null): String {
        val town = outOfTown?.let { lang.t(" Out of town to $it, watch the empty return.", "跨城单，去$it，注意空驶回程。") } ?: ""
        return speechCore(offer, result, lang) + town
    }

    private fun speechCore(offer: DeliveryOffer, result: EvaluationResult, lang: Lang): String {
        if (result.verdict == Verdict.UNKNOWN) return Verdict.UNKNOWN.speech(lang) + lang.t(".", "。")
        val p = offer.estimatedPayout!!.roundToInt()
        val k = offer.distanceKm!!.roundToInt()
        val word = result.verdict.speech(lang)
        return when (lang) {
            Lang.ZH -> "${p}块，${k}公里，$word。"
            Lang.EN -> "$p ${if (p == 1) "dollar" else "dollars"}, $k ${if (k == 1) "kilometre" else "kilometres"}, $word."
        }
    }

    /** Detailed explanation for the home and mock pages. */
    fun detail(offer: DeliveryOffer, result: EvaluationResult, lang: Lang = Lang.EN): String = buildString {
        val sep = lang.t(", ", "、")
        appendLine(
            lang.t("Price", "报价") + ": ${money(offer.estimatedPayout)}  " +
                lang.t("Distance", "距离") + ": ${num(offer.distanceKm)} km  " +
                lang.t("Time", "时间") + ": ${num(offer.durationMinutes)} min",
        )
        if (result.isConclusive) {
            appendLine(lang.t("Tip-free pay", "不含小费价") + ": ${money(result.basePay)}")
            appendLine(
                lang.t("Vehicle cost / distance part", "车辆成本/距离部分") + ": ${money(result.distanceRequirement)}  " +
                    lang.t("Time part", "时间部分") + ": ${money(result.timeRequirement)}",
            )
            appendLine(
                lang.t("Required pay", "达标价") + ": ${money(result.requiredPrice)}  " +
                    lang.t("Ratio", "比值") + ": ${String.format(Locale.US, "%.2f", result.ratio)}",
            )
        } else {
            appendLine(lang.t("Unknown", "无法判断") + ": " + result.unknownReasons.joinToString(sep) { it.description(lang) })
        }
        if (result.isConclusive) appendLine(lang.t("Time used includes your waiting / shopping time.", "计算用的时间已含等餐 / 购物时间。"))
        offer.shopItems?.let { n ->
            val u = offer.shopUnits?.let { lang.t(" ($it units)", "（共 $it 件）") } ?: ""
            appendLine(lang.t("Shop order: $n items$u (shopping time added if enabled)", "Shop 单：$n 种商品$u（开启时已加上购物时间）"))
        }
        if (result.belowMinimumOrder) appendLine(lang.t("Below your minimum order amount", "低于设置的最低订单金额"))
        result.vehicleCost?.let {
            appendLine(
                lang.t("Est. vehicle cost", "估算车辆成本") + ": ${money(it)}" +
                    (result.netAfterVehicleCost?.let { n -> "  " + lang.t("Tip-free pay after cost", "不含小费扣除后") + ": ${money(n)}" } ?: ""),
            )
        }
        result.netHourly?.let { appendLine(lang.t("Est. net hourly", "预计净时薪") + ": ${money(it)}/h") }
        if (offer.issues.isNotEmpty()) appendLine(lang.t("Parse notes", "解析提示") + ": " + offer.issues.joinToString(sep) { it.description(lang) })
        append(lang.t("Verdict", "结论") + ": ${result.verdict.label(lang)}  " + lang.t("Confidence", "置信度") + ": ${String.format(Locale.US, "%.2f", offer.confidence)}")
    }

    fun money(v: Double?): String = v?.let { "$" + String.format(Locale.US, "%.2f", it) } ?: "—"
    fun num(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: "—"
}
