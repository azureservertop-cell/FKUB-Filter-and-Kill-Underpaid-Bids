package com.offerpilot.core.model

/** Where the offer data came from. */
enum class OfferSource { ACCESSIBILITY, OCR, MOCK }

/**
 * Unified offer model (PRD 4.1).
 *
 * Every numeric field is nullable: unreadable means null, never invented.
 * Internal units: CAD, km, minutes.
 */
data class DeliveryOffer(
    val offerId: String,
    val estimatedPayout: Double?,
    val distanceKm: Double?,
    val durationMinutes: Double?,
    val pickupText: String? = null,
    val destinationText: String? = null,
    val multiStop: Boolean? = null,
    val timestamp: Long,
    val source: OfferSource,
    /** Recognition confidence, 0.0–1.0. */
    val confidence: Float,
    /** Problems found while parsing (ambiguity, missing fields, ...), used for degrading and debugging. */
    val issues: List<ParseIssue> = emptyList(),
)

/** Parse issues. Any of them prevents a confident recommendation. */
enum class ParseIssue(val description: String) {
    PAYOUT_MISSING("未读到报价"),
    PAYOUT_AMBIGUOUS("出现多个无法区分的金额"),
    DISTANCE_MISSING("未读到距离"),
    DISTANCE_AMBIGUOUS("出现多个无法区分的距离"),
    DURATION_MISSING("未读到时间"),
    DURATION_AMBIGUOUS("出现多个无法区分的时间"),
    MULTIPLE_OFFERS("同时识别到多个候选订单"),
    ADAPTER_UNVERIFIED("适配规则尚未经真机验证"),
}
