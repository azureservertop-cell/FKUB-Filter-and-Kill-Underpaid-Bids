package com.offerpilot.core.rules

/**
 * 用户可配置的个人筛选规则（PRD 3.1 / 3.4）。不是 Uber 官方报酬标准。
 * 单位：加元、公里、分钟。
 */
data class EvaluatorSettings(
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
    val wearPerKm: Double = 0.10,
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
        get() = fuelLitersPer100Km / 100.0 * fuelPricePerLiter + wearPerKm
}
