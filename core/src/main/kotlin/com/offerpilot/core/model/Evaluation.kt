package com.offerpilot.core.model

/** 判断结果（PRD 3.2）。 */
enum class Verdict(val label: String, val shortLabel: String, val speech: String) {
    NOT_RECOMMENDED("不推荐", "不推荐", "不推荐"),
    MARGINAL("勉强合格", "勉强合格", "勉强合格"),
    RECOMMENDED("值得考虑", "推荐", "可以考虑"),
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
    /** 预计净时薪 =（报价 - 车辆成本）/ 小时（含返程时间）。 */
    val netHourly: Double? = null,
    /** 报价低于用户设置的最低订单金额。 */
    val belowMinimumOrder: Boolean = false,
    val unknownReasons: List<UnknownReason> = emptyList(),
) {
    val isConclusive: Boolean get() = verdict != Verdict.UNKNOWN
}

/** 可选的返程估算（PRD 6.2，第一版不会自动推断，只在用户/可靠来源提供时使用）。 */
data class ReturnTrip(val km: Double, val minutes: Double)
