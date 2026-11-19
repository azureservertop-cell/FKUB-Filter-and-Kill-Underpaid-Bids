package com.offerpilot.core.parser

import com.offerpilot.core.model.AdapterDebug
import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Uber Driver 订单弹窗适配器。
 *
 * ⚠️ 待验证（PENDING VERIFICATION）
 * 截至本版本，尚未取得 Uber Driver 真实订单弹窗的无障碍快照。因此：
 *  - 本适配器 **不使用任何控件 ID**（不虚构 view id）；
 *  - 只依赖"屏幕上同时出现金额 + 距离 + 时间"这一通用特征和下面可配置的关键字；
 *  - 所有关键字都标记为待验证，拿到 GKD 快照后应按实际文字修正 [UberAdapterConfig]；
 *  - 出现歧义时宁可输出"无法判断"，也不猜。
 */
class UberDriverAdapter(
    private val config: UberAdapterConfig = UberAdapterConfig(),
) : OfferAdapter {

    override val id: String = "uber-driver-heuristic-v1"
    override val verified: Boolean get() = config.verifiedOnRealDevice

    /** 最近一次 extract 的调试信息（供调试页展示）。 */
    @Volatile
    var lastDebug: AdapterDebug? = null
        private set

    private data class Cand(val value: Double, val node: TextNode, val nodeIndex: Int)

    override fun extract(screen: ScreenText): AdapterResult {
        if (config.targetPackages.isNotEmpty() && screen.packageName !in config.targetPackages) {
            lastDebug = AdapterDebug(note = "非目标应用 ${screen.packageName}")
            return AdapterResult.NoOffer
        }
        val nodes = screen.nodes.filter { it.text.isNotBlank() }

        val money = mutableListOf<Cand>()
        val dist = mutableListOf<Cand>()
        val dur = mutableListOf<Cand>()
        nodes.forEachIndexed { i, n ->
            val lower = n.text.lowercase()
            if (!containsAny(lower, config.payoutExcludeKeywords)) {
                OfferParser.findMoney(n.text).filter { it.value > 0 }.forEach { money += Cand(it.value, n, i) }
            }
            OfferParser.findDistancesKm(n.text).forEach { dist += Cand(it.value, n, i) }
            if (!containsAny(lower, config.durationExcludeKeywords)) {
                OfferParser.findDurationsMinutes(n.text).forEach { dur += Cand(it.value, n, i) }
            }
        }

        val acceptNodes = nodes.filter { n -> config.acceptKeywords.any { n.text.trim().equals(it, ignoreCase = true) } }
        val hasAccept = acceptNodes.isNotEmpty()
        val allThree = money.isNotEmpty() && dist.isNotEmpty() && dur.isNotEmpty()

        // 是否存在订单弹窗：三类数值齐全，或有"接受"按钮且至少出现一类数值。
        val offerPresent = allThree || (hasAccept && (money.isNotEmpty() || dist.isNotEmpty() || dur.isNotEmpty()))
        if (!offerPresent) {
            lastDebug = AdapterDebug(
                note = "未检测到订单弹窗",
                moneyCandidates = money.map { it.value },
                distanceCandidates = dist.map { it.value },
                durationCandidates = dur.map { it.value },
            )
            return AdapterResult.NoOffer
        }

        val issues = mutableListOf<ParseIssue>()
        var confidence = if (screen.source == com.offerpilot.core.model.OfferSource.OCR) 0.75f else 0.95f
        if (!hasAccept) confidence -= 0.10f
        if (acceptNodes.size > 1) issues += ParseIssue.MULTIPLE_OFFERS

        val (payout, payoutPenalty) = pick(money, config.payoutLabelKeywords, preferLargestText = true, centsTolerance = 0.005)
        confidence -= payoutPenalty
        when {
            money.isEmpty() -> issues += ParseIssue.PAYOUT_MISSING
            payout == null -> issues += ParseIssue.PAYOUT_AMBIGUOUS
        }

        var (km, kmPenalty) = pick(dist, config.totalKeywords, preferLargestText = false, centsTolerance = 0.05)
        confidence -= kmPenalty
        if (km == null && dist.isNotEmpty() && config.sumLegsWhenNoTotal) {
            km = dist.distinctBy { it.nodeIndex }.sumOf { it.value }
            confidence -= 0.25f
        }
        when {
            dist.isEmpty() -> issues += ParseIssue.DISTANCE_MISSING
            km == null -> issues += ParseIssue.DISTANCE_AMBIGUOUS
        }

        // 时长：优先与距离同一节点的（例如 "42 min (29.9 km)"），避免把"2 min away"之类当作总时长。
        val durPreferred = dur.filter { d -> dist.any { it.nodeIndex == d.nodeIndex } }.ifEmpty { dur }
        var (minutes, minPenalty) = pick(durPreferred, config.totalKeywords, preferLargestText = false, centsTolerance = 0.5)
        confidence -= minPenalty
        if (minutes == null && durPreferred.isNotEmpty() && config.sumLegsWhenNoTotal) {
            minutes = durPreferred.distinctBy { it.nodeIndex }.sumOf { it.value }
            confidence -= 0.25f
        }
        when {
            dur.isEmpty() -> issues += ParseIssue.DURATION_MISSING
            minutes == null -> issues += ParseIssue.DURATION_AMBIGUOUS
        }

        if (!verified) issues += ParseIssue.ADAPTER_UNVERIFIED

        val multiStop = if (nodes.any { containsAny(it.text.lowercase(), config.multiStopKeywords) }) true else null

        val offer = DeliveryOffer(
            offerId = localOfferId(payout, km, minutes, screen.timestamp),
            estimatedPayout = payout,
            distanceKm = km,
            durationMinutes = minutes,
            pickupText = null,       // 待验证：未确认能可靠提取，且涉及隐私，第一版不读取
            destinationText = null,  // 同上
            multiStop = multiStop,
            timestamp = screen.timestamp,
            source = screen.source,
            confidence = confidence.coerceIn(0f, 1f),
            issues = issues,
        )
        lastDebug = AdapterDebug(
            note = "检测到订单弹窗（适配器${if (verified) "已验证" else "待验证"}）",
            moneyCandidates = money.map { it.value },
            distanceCandidates = dist.map { it.value },
            durationCandidates = dur.map { it.value },
        )
        val complete = payout != null && km != null && minutes != null
        return AdapterResult.Offer(offer, complete)
    }

    /**
     * 从候选值中选出唯一值。返回 (值, 置信度惩罚)。
     * 1. 去重后只有一个值 → 直接用；
     * 2. 只有一个候选所在文字带"标签关键字" → 用它；
     * 3. preferLargestText 时，若某个候选的字号（节点高度）明显最大 → 用它；
     * 4. 否则返回 null（歧义）。
     */
    private fun pick(
        cands: List<Cand>,
        labelKeywords: List<String>,
        preferLargestText: Boolean,
        centsTolerance: Double,
    ): Pair<Double?, Float> {
        if (cands.isEmpty()) return null to 0f
        val distinct = cands.distinctValues(centsTolerance)
        if (distinct.size == 1) return distinct.first() to 0f

        val labelled = cands.filter { containsAny(it.node.text.lowercase(), labelKeywords) }
            .distinctValues(centsTolerance)
        if (labelled.size == 1) return labelled.first() to 0.10f

        if (preferLargestText) {
            val withH = cands.filter { it.node.bounds != null }
            if (withH.size == cands.size) {
                val sorted = withH.sortedByDescending { it.node.bounds!!.height }
                val top = sorted[0].node.bounds!!.height
                val second = sorted.firstOrNull { abs(it.value - sorted[0].value) > centsTolerance }?.node?.bounds?.height
                if (second != null && top >= second * 1.3) return sorted[0].value to 0.20f
            }
        }
        return null to 0f
    }

    private fun List<Cand>.distinctValues(tol: Double): List<Double> {
        val out = mutableListOf<Double>()
        for (c in this) if (out.none { abs(it - c.value) <= tol }) out += c.value
        return out
    }

    private fun containsAny(lower: String, keywords: List<String>) = keywords.any { lower.contains(it.lowercase()) }

    companion object {
        fun localOfferId(payout: Double?, km: Double?, min: Double?, ts: Long): String {
            val p = payout?.let { (it * 100).roundToLong() } ?: -1
            val k = km?.let { (it * 10).roundToLong() } ?: -1
            val m = min?.roundToLong() ?: -1
            return "o-$p-$k-$m-${ts % 100_000}"
        }
    }
}

/**
 * Uber 适配器配置。**所有关键字均为待验证**，请用 GKD / 本应用调试快照的真实文字修正。
 */
data class UberAdapterConfig(
    /**
     * 目标应用包名。"com.ubercab.driver" 是 Uber Driver 在 Google Play 上公开的包名，
     * 但仍需在真机快照中确认；可在设置页修改。
     */
    val targetPackages: Set<String> = setOf("com.ubercab.driver"),
    /** 待验证：接单按钮的文字。只用于提高置信度/检测多订单，不会被点击。 */
    val acceptKeywords: List<String> = listOf("Accept", "Match", "Tap to accept", "接受", "接单"),
    /** 待验证：报价旁边可能出现的标签。 */
    val payoutLabelKeywords: List<String> = listOf("est.", "estimated", "total", "earn", "预估", "总计"),
    /** 待验证：这些文字所在的金额不是订单报价（加价、小费说明、累计收入等）。 */
    val payoutExcludeKeywords: List<String> = listOf(
        "+$", "boost", "surge", "promo", "quest", "today", "this week", "balance",
        "/hr", "per hour", "/h", "incl.", "includes", "tip", "fee", "今日", "本周", "加价",
    ),
    /** 待验证：这些文字中的时间不是行程总时长（如到取货点的 ETA）。 */
    val durationExcludeKeywords: List<String> = listOf("away", "ago", "expires", "remaining", "left"),
    /** 待验证：总距离/总时间的标签。 */
    val totalKeywords: List<String> = listOf("total", "trip", "总"),
    val multiStopKeywords: List<String> = listOf("stops", "multiple", "deliveries", "多点", "多单"),
    /** 多段距离且没有"total"时是否相加。默认关闭：在真机验证前不做此假设。 */
    val sumLegsWhenNoTotal: Boolean = false,
    /** 用真机快照验证适配器后设为 true。 */
    val verifiedOnRealDevice: Boolean = false,
)
