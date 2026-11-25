package com.offerpilot.core.model

import com.offerpilot.core.i18n.Lang

/**
 * Verdict: four tiers (edge glow colour) + unknown.
 *  (ratio = tip-free pay ÷ required pay, required pay = vehicle cost + target net hourly × hours)
 *  - LOSS red: ratio < 1, tip-free pay doesn't cover target net hourly + vehicle cost
 *  - MIN_WAGE yellow: 1 ≤ ratio < goodRatio, just meets the target
 *  - GOOD green: goodRatio ≤ ratio < greatRatio, pays well
 *  - GREAT blue: ratio ≥ greatRatio, must-take
 */
enum class Verdict(
    private val labelEn: String,
    private val labelZh: String,
) {
    LOSS("Below target", "不达标"),
    MIN_WAGE("Meets target", "刚好达标"),
    GOOD("Good pay", "比较赚"),
    GREAT("Must take", "无脑接"),
    UNKNOWN("Unknown", "无法判断");

    fun label(lang: Lang = Lang.EN): String = lang.t(labelEn, labelZh)
}

/** Why no verdict could be given. */
enum class UnknownReason(private val en: String, private val zh: String) {
    MISSING_PAYOUT("price not read", "价格读取失败"),
    MISSING_DISTANCE("distance not read", "距离读取失败"),
    MISSING_DURATION("time not read", "时间读取失败"),
    IMPLAUSIBLE_VALUES("implausible values", "数据明显不合理"),
    LOW_CONFIDENCE("low recognition confidence", "识别置信度不足"),
    AMBIGUOUS("multiple candidate values or offers", "存在多个候选值或多个订单"),
    STALE("offer data is stale", "订单数据已过期");

    fun description(lang: Lang = Lang.EN): String = lang.t(en, zh)
}

data class EvaluationResult(
    val verdict: Verdict,
    /** basePay / requiredPrice; null when there is no verdict. */
    val ratio: Double?,
    val requiredPrice: Double?,
    val distanceRequirement: Double?,
    val timeRequirement: Double?,
    /** Estimated vehicle cost (fuel + wear), display only. */
    val vehicleCost: Double?,
    /** Payout minus vehicle cost, display only. */
    val netAfterVehicleCost: Double?,
    /** Tip-free pay = shown price × tipFreeShare. */
    val basePay: Double? = null,
    /** Estimated net hourly = (tip-free pay − vehicle cost) / hours (including return trip). */
    val netHourly: Double? = null,
    /** Payout is below the user's minimum order amount. */
    val belowMinimumOrder: Boolean = false,
    val unknownReasons: List<UnknownReason> = emptyList(),
) {
    val isConclusive: Boolean get() = verdict != Verdict.UNKNOWN
}

/** Optional return-trip estimate (PRD 6.2; v1 never infers it, only used when the user or a reliable source provides it). */
data class ReturnTrip(val km: Double, val minutes: Double)
