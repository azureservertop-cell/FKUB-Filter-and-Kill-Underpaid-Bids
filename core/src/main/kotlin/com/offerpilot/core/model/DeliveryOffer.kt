package com.offerpilot.core.model

/** 订单数据来源。 */
enum class OfferSource { ACCESSIBILITY, OCR, MOCK }

/**
 * 统一的订单数据模型（PRD 4.1）。
 *
 * 所有数值字段都允许为 null：读不到就是 null，绝不编造。
 * 内部单位：加元、公里、分钟。
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
    /** 0.0–1.0 识别置信度。 */
    val confidence: Float,
    /** 解析过程中发现的问题（歧义、缺字段等），用于降级与调试。 */
    val issues: List<ParseIssue> = emptyList(),
)

/** 解析问题。任何一项出现都会阻止"肯定推荐"。 */
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
