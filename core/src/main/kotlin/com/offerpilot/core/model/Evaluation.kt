package com.offerpilot.core.model

/**
 * 判断结果：四档（屏幕边缘发光颜色）+ 无法判断。
 *  （比值 = 不含小费价 ÷ 达标价，达标价 = 车辆成本 + 期望净时薪 × 小时）
 *  - LOSS 红：比值 < 1，不含小费价不够期望净时薪 + 车辆成本（连车辆成本都不够时语音说"亏本"）
 *  - MIN_WAGE 黄：1 ≤ 比值 < goodRatio，刚好达标
 *  - GOOD 绿：goodRatio ≤ 比值 < greatRatio，比较赚
 *  - GREAT 蓝：比值 ≥ greatRatio，无脑接
 */
enum class Verdict(val label: String, val shortLabel: String, val speech: String) {
    LOSS("不达标", "不达标", "不达标"),
    MIN_WAGE("刚好达标", "达标", "刚好达标"),
    GOOD("比较赚", "比较赚", "比较赚"),
    GREAT("无脑接", "无脑接", "无脑接"),
    UNKNOWN("无法判断", "无法判断", "订单信息不完整"),
}

/** 无法判断的原因。 */
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
    /** offerPrice / requiredPrice；无法判断时为 null。 */
    val ratio: Double?,
    val requiredPrice: Double?,
    val distanceRequirement: Double?,
    val timeRequirement: Double?,
    /** 估算车辆成本（油费 + 车损），仅展示用。 */
    val vehicleCost: Double?,
    /** 报价 - 车辆成本，仅展示用。 */
    val netAfterVehicleCost: Double?,
    /** 不含小费价 = 显示价 × tipFreeShare。 */
    val basePay: Double? = null,
    /** 预计净时薪 =（不含小费价 - 车辆成本）/ 小时（含返程时间）。 */
    val netHourly: Double? = null,
    /** 报价低于用户设置的最低订单金额。 */
    val belowMinimumOrder: Boolean = false,
    val unknownReasons: List<UnknownReason> = emptyList(),
) {
    val isConclusive: Boolean get() = verdict != Verdict.UNKNOWN
}

/** 可选的返程估算（PRD 6.2，第一版不会自动推断，只在用户/可靠来源提供时使用）。 */
data class ReturnTrip(val km: Double, val minutes: Double)
