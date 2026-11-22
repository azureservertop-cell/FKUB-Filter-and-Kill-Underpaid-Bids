package com.offerpilot.core.model

import com.offerpilot.core.i18n.Lang

/**
 * How Uber offers the trip: ACCEPT = a popup sent to you ("Accept", often "Exclusive");
 * MATCH = a Trip Radar offer shown to several drivers ("Match").
 */
enum class OfferKind { ACCEPT, MATCH }

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
    /** City of the drop-off address only (no street), for the out-of-town alert. In memory only, never stored. */
    val dropoffCity: String? = null,
    /** Accept vs Match, from the button text; null when no button was read. */
    val kind: OfferKind? = null,
    /** Shop (grocery) orders: number of items to pick, when the card shows it. Null for normal deliveries. */
    val shopItems: Int? = null,
    /** Shop orders: total units ("22 items (30 units)": 30), when the card shows it. */
    val shopUnits: Int? = null,
    val timestamp: Long,
    val source: OfferSource,
    /** Recognition confidence, 0.0–1.0. */
    val confidence: Float,
    /** Problems found while parsing (ambiguity, missing fields, ...), used for degrading and debugging. */
    val issues: List<ParseIssue> = emptyList(),
)

/** Parse issues. Any of them prevents a confident recommendation. */
enum class ParseIssue(private val en: String, private val zh: String) {
    PAYOUT_MISSING("price not found", "未读到报价"),
    PAYOUT_AMBIGUOUS("several indistinguishable amounts", "出现多个无法区分的金额"),
    DISTANCE_MISSING("distance not found", "未读到距离"),
    DISTANCE_AMBIGUOUS("several indistinguishable distances", "出现多个无法区分的距离"),
    DURATION_MISSING("time not found", "未读到时间"),
    DURATION_AMBIGUOUS("several indistinguishable times", "出现多个无法区分的时间"),
    MULTIPLE_OFFERS("several candidate offers at once", "同时识别到多个候选订单"),
    ADAPTER_UNVERIFIED("adapter rules not yet verified on a real device", "适配规则尚未经真机验证");

    fun description(lang: Lang = Lang.EN): String = lang.t(en, zh)
}
