package com.offerpilot.core.model

/**
 * Verdict: four tiers (edge glow colour) + unknown.
 *  (ratio = tip-free pay ÷ required pay, required pay = vehicle cost + target net hourly × hours)
 *  - LOSS red: ratio < 1, tip-free pay doesn't cover target net hourly + vehicle cost (spoken as "亏本" when even vehicle cost isn't covered)
 *  - MIN_WAGE yellow: 1 ≤ ratio < goodRatio, just meets the target
 *  - GOOD green: goodRatio ≤ ratio < greatRatio, pays well
 *  - GREAT blue: ratio ≥ greatRatio, must-take
 */
enum class Verdict(val label: String, val shortLabel: String, val speech: String) {
    LOSS("不达标", "不达标", "不达标"),
    MIN_WAGE("刚好达标", "达标", "刚好达标"),
    GOOD("比较赚", "比较赚", "比较赚"),
    GREAT("无脑接", "无脑接", "无脑接"),
    UNKNOWN("无法判断", "无法判断", "订单信息不完整"),
}

/** Why no verdict could be given. */
enum class UnknownReason(val description: String) {
    MISSING_PAYOUT("价格读取失败"),
    MISSING_DISTANCE("距离读取失败"),
    MISSING_DURATION("时间读取失败"),
    IMPLAUSIBLE_VALUES("数据明显不合理"),
    LOW_CONFIDENCE("识别置信度不足"),
    AMBIGUOUS("存在多个候选值或多个订单"),
    STALE("订单数据已过期"),
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
