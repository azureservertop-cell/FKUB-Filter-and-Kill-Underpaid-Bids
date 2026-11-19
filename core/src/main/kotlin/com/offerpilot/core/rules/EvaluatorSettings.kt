package com.offerpilot.core.rules

/** 计算模式。 */
enum class RuleMode {
    /**
     * 默认：车辆成本 + 目标净时薪。
     * 最低报价 = 公里 × 每公里车辆成本 + 小时 × 目标净时薪
     * 用户只需填油耗、油价、每百公里维护费和期望净时薪。
     */
    NET_HOURLY,

    /** PRD 3.1 的简单规则：max(公里 × 每公里最低, 分钟 × 每分钟最低)。 */
    PER_KM_PER_MINUTE,
}

/**
 * 用户可配置的个人筛选规则（PRD 3.1 / 3.4）。不是 Uber 官方报酬标准。
 * 单位：加元、公里、分钟。
 */
data class EvaluatorSettings(
    val mode: RuleMode = RuleMode.NET_HOURLY,
    /** 期望每小时净赚（扣除油费和维护费后）。 */
    val targetNetHourly: Double = 18.0,
    /** 简单规则模式用。 */
    val minPerKm: Double = 0.80,
    val minPerMinute: Double = 0.50,
    /** 可接受的最低订单金额，0 表示不限制。 */
    val minOrderAmount: Double = 0.0,
    /** ratio ≥ 此值：值得考虑。 */
    val goodRatio: Double = 1.2,
    /** ratio ≥ 此值：勉强合格；否则不推荐。 */
    val passRatio: Double = 1.0,
    val fuelLitersPer100Km: Double = 10.0,
    val fuelPricePerLiter: Double = 1.36,
    /** 每百公里维护 / 车损费用（保养、轮胎、折旧等）。 */
    val maintenancePer100Km: Double = 10.0,
    /** 低于此置信度不给出结论。 */
    val minConfidence: Float = 0.6f,
    /** 订单数据超过此毫秒数视为过期。 */
    val maxOfferAgeMs: Long = 30_000,
    val outOfTownAlert: Boolean = false,
    /** 合理性上限：超出视为异常数据。 */
    val maxPlausiblePayout: Double = 500.0,
    val maxPlausibleKm: Double = 300.0,
    val maxPlausibleMinutes: Double = 360.0,
    /** 平均速度超过此值（km/h）视为异常。 */
    val maxPlausibleSpeedKmh: Double = 130.0,
) {
    val vehicleCostPerKm: Double
        get() = (fuelLitersPer100Km * fuelPricePerLiter + maintenancePer100Km) / 100.0

    /** 目标净时薪折算成每分钟。 */
    val netPerMinute: Double get() = targetNetHourly / 60.0

    /** 给定公里和分钟，达到目标所需的最低报价（仅用当前模式公式，不做校验）。 */
    fun requiredFor(km: Double, minutes: Double): Double = when (mode) {
        RuleMode.NET_HOURLY -> km * vehicleCostPerKm + minutes * netPerMinute
        RuleMode.PER_KM_PER_MINUTE -> kotlin.math.max(km * minPerKm, minutes * minPerMinute)
    }
}
