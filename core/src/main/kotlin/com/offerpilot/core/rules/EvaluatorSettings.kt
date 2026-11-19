package com.offerpilot.core.rules

/** Evaluation mode. */
enum class RuleMode {
    /**
     * Default: vehicle cost + target net hourly.
     * required pay = km × vehicle cost per km + hours × target net hourly
     * The user only enters fuel consumption, fuel price, maintenance per 100 km and target net hourly.
     */
    NET_HOURLY,

    /** PRD 3.1 simple rule: max(km × min per km, minutes × min per minute). */
    PER_KM_PER_MINUTE,
}

/**
 * User-configurable personal filter rules (PRD 3.1 / 3.4). Not an official Uber pay standard.
 * Units: CAD, km, minutes.
 */
data class EvaluatorSettings(
    val mode: RuleMode = RuleMode.NET_HOURLY,
    /** Target net earnings per hour (after fuel and maintenance). */
    val targetNetHourly: Double = 18.0,
    /** Used by the simple rule mode. */
    val minPerKm: Double = 0.80,
    val minPerMinute: Double = 0.50,
    /** Minimum acceptable order amount; 0 means no limit. */
    val minOrderAmount: Double = 0.0,
    /**
     * Share of the shown price that isn't tip. Uber shows a price "including estimated tip" and the tip isn't guaranteed,
     * so only shown price × this share is judged. Default 0.85 (about a 15–18% tip). 1 means no deduction.
     */
    val tipFreeShare: Double = 0.85,
    /** ratio ≥ 1 is yellow (meets target); ≥ this value is green (pays well). */
    val goodRatio: Double = 1.2,
    /** ratio ≥ this value is blue (must-take). */
    val greatRatio: Double = 1.5,
    val fuelLitersPer100Km: Double = 10.0,
    val fuelPricePerLiter: Double = 1.36,
    /** Maintenance / wear cost per 100 km (servicing, tires, depreciation, ...). */
    val maintenancePer100Km: Double = 10.0,
    /** No verdict below this confidence. */
    val minConfidence: Float = 0.6f,
    /** Offer data older than this many ms is stale. */
    val maxOfferAgeMs: Long = 30_000,
    val outOfTownAlert: Boolean = false,
    /** Plausibility limits: anything beyond is treated as bad data. */
    val maxPlausiblePayout: Double = 500.0,
    val maxPlausibleKm: Double = 300.0,
    val maxPlausibleMinutes: Double = 360.0,
    /** An average speed above this (km/h) is treated as bad data. */
    val maxPlausibleSpeedKmh: Double = 130.0,
) {
    val vehicleCostPerKm: Double
        get() = (fuelLitersPer100Km * fuelPricePerLiter + maintenancePer100Km) / 100.0

    /** Target net hourly converted to per minute. */
    val netPerMinute: Double get() = targetNetHourly / 60.0

    /** Minimum pay needed to hit the target for the given km and minutes (current mode's formula, no validation). */
    fun requiredFor(km: Double, minutes: Double): Double = when (mode) {
        RuleMode.NET_HOURLY -> km * vehicleCostPerKm + minutes * netPerMinute
        RuleMode.PER_KM_PER_MINUTE -> kotlin.math.max(km * minPerKm, minutes * minPerMinute)
    }

    /** What Uber must show (tip included) for the tip-free part to reach [ratio] × required pay. */
    fun displayedPriceFor(km: Double, minutes: Double, ratio: Double = 1.0): Double =
        requiredFor(km, minutes) * ratio / tipFreeShare.coerceAtLeast(0.01)
}
