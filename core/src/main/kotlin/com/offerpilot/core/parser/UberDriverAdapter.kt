package com.offerpilot.core.parser

import com.offerpilot.core.model.AdapterDebug
import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Uber Driver offer popup adapter.
 *
 * Phase 0 (2026-11-19, Android 15): while an offer is up, Uber hides its UI from the accessibility tree,
 * so in practice this adapter runs on OCR text ("CA$5.50" / "including estimated tip" / "15 min (1.3 km) total" / "Accept").
 *  - It uses **no view IDs** (none are invented);
 *  - it relies only on money + distance + time appearing together, plus the configurable keywords below;
 *  - keywords not yet seen on a real device stay marked as pending; correct [UberAdapterConfig] from new snapshots;
 *  - when anything is ambiguous it returns "unknown" rather than guessing.
 */
class UberDriverAdapter(
    private val config: UberAdapterConfig = UberAdapterConfig(),
) : OfferAdapter {

    override val id: String = "uber-driver-heuristic-v1"
    override val verified: Boolean get() = config.verifiedOnRealDevice

    /** Debug info from the last extract call (shown on the debug page). */
    @Volatile
    var lastDebug: AdapterDebug? = null
        private set

    private data class Cand(val value: Double, val node: TextNode, val nodeIndex: Int)

    override fun extract(screen: ScreenText): AdapterResult {
        if (config.targetPackages.isNotEmpty() && screen.packageName !in config.targetPackages) {
            lastDebug = AdapterDebug(note = "not a target app: ${screen.packageName}")
            return AdapterResult.NoOffer
        }
        listCards(screen)?.let { return it }
        return extractSingle(screen)
    }

    /**
     * Trip Radar (real device, 2026-11-20): a scrolling list of cards, each "Delivery (2)" / "CA$11.56" /
     * "including estimated tip" / "29 min (8.4 km) total" / pickup / drop-off / "Match". Detected by the list title or by
     * two or more "estimated tip" labels. Each card spans from just above its price to the next card; cards cut off at
     * the screen edge come out incomplete and get no badge. Needs screen positions; without them, null.
     */
    private fun listCards(screen: ScreenText): AdapterResult? {
        if (!config.splitOfferLists) return null
        val nodes = screen.nodes.filter { it.text.isNotBlank() }
        if (nodes.isEmpty() || nodes.any { it.bounds == null }) return null
        val markers = nodes.filter { n -> containsAny(n.text.lowercase(), config.offerMarkerKeywords) }.sortedBy { it.bounds!!.top }
        // The list page has "Trip Radar" as its title, well above the cards. On the map, Uber shows a "Trip Radar" chip just
        // above a single card: that card is a normal offer and gets the full alert.
        val firstMarkerTop = markers.firstOrNull()?.bounds?.top ?: 0
        val titled = nodes.any { n ->
            config.offerListKeywords.any { n.text.trim().equals(it, ignoreCase = true) } && n.bounds!!.top < firstMarkerTop / 2
        }
        if (markers.size < 2 && !(titled && markers.isNotEmpty())) return null
        val starts = markers.map { m -> m.bounds!!.top - 3 * m.bounds.height }
        val cards = markers.mapIndexed { i, m ->
            val start = starts[i]
            val end = starts.getOrElse(i + 1) { Int.MAX_VALUE }
            val part = nodes.filter { n -> val cy = (n.bounds!!.top + n.bounds.bottom) / 2; cy in start until end }
            val r = extractSingle(screen.copy(nodes = part))
            val offer = (r as? AdapterResult.Offer) ?: return@mapIndexed null
            // Price line: the tallest text above the marker in this card
            val price = part.filter { it.bounds!!.bottom <= m.bounds!!.top + 4 }.maxByOrNull { it.bounds!!.height } ?: m
            AdapterResult.Card(offer.offer.copy(issues = offer.offer.issues - ParseIssue.MULTIPLE_OFFERS), offer.complete, price.bounds!!)
        }.filterNotNull()
        lastDebug = AdapterDebug(note = "offer list: ${cards.size} cards (${cards.count { it.complete }} complete)")
        return if (cards.isEmpty()) AdapterResult.NoOffer else AdapterResult.OfferList(cards)
    }

    private fun extractSingle(screen: ScreenText): AdapterResult {
        var repairedPrice = false
        val nodes = screen.nodes.filter { it.text.isNotBlank() }.map { n ->
            if (screen.source != com.offerpilot.core.model.OfferSource.OCR) return@map n
            val (t1, priceFixed) = OfferParser.repairOcrPriceLine(n.text)
            val (t, tripFixed) = OfferParser.repairOcrTripLine(t1)
            if (priceFixed || tripFixed) repairedPrice = true
            if (t != n.text) n.copy(text = t) else n
        }

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

        // Is there an offer popup? Real offer cards always carry an "Accept" button and an "including estimated tip"
        // label. Screens such as trip history also show money + km + min, so the numbers alone are not enough.
        val hasMarker = nodes.any { n -> containsAny(n.text.lowercase(), config.offerMarkerKeywords) }
        val anyValue = money.isNotEmpty() || dist.isNotEmpty() || dur.isNotEmpty()
        val offerPresent = if (config.requireOfferMarker) (hasAccept || hasMarker) && anyValue
        else allThree || (hasAccept && anyValue)
        if (!offerPresent) {
            lastDebug = AdapterDebug(
                note = "no offer popup detected",
                moneyCandidates = money.map { it.value },
                distanceCandidates = dist.map { it.value },
                durationCandidates = dur.map { it.value },
            )
            return AdapterResult.NoOffer
        }

        val issues = mutableListOf<ParseIssue>()
        var confidence = if (screen.source == com.offerpilot.core.model.OfferSource.OCR) 0.75f else 0.95f
        if (!hasAccept) confidence -= 0.10f
        if (repairedPrice) confidence -= 0.05f
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

        // Duration: prefer the one in the same node as the distance (e.g. "42 min (29.9 km)") so "2 min away" isn't taken as the total.
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

        val dropoffCity = if (!config.readDropoffCity) null else runCatching {
            val tripIdx = dist.firstOrNull { c -> containsAny(c.node.text.lowercase(), config.totalKeywords) }?.nodeIndex
                ?: dist.firstOrNull()?.nodeIndex ?: -1
            val acceptIdx = nodes.indexOfFirst { n -> config.acceptKeywords.any { n.text.trim().equals(it, ignoreCase = true) } }

            DropoffCity.extract(nodes, tripIdx, acceptIdx)
        }.getOrNull()

        val multiStop = if (nodes.any { containsAny(it.text.lowercase(), config.multiStopKeywords) }) true else null

        val offer = DeliveryOffer(
            offerId = localOfferId(payout, km, minutes, screen.timestamp),
            estimatedPayout = payout,
            distanceKm = km,
            durationMinutes = minutes,
            pickupText = null,       // not read in v1: not reliably extractable and privacy-sensitive
            destinationText = null,  // same as above
            multiStop = multiStop,
            dropoffCity = dropoffCity, // only the city, for the out-of-town alert

            timestamp = screen.timestamp,
            source = screen.source,
            confidence = confidence.coerceIn(0f, 1f),
            issues = issues,
        )
        lastDebug = AdapterDebug(
            note = "offer popup detected (adapter ${if (verified) "verified" else "pending verification"})",
            moneyCandidates = money.map { it.value },
            distanceCandidates = dist.map { it.value },
            durationCandidates = dur.map { it.value },
        )
        val complete = payout != null && km != null && minutes != null
        return AdapterResult.Offer(offer, complete)
    }

    /**
     * Picks a single value from the candidates. Returns (value, confidence penalty).
     * 1. only one distinct value → use it;
     * 2. only one candidate's text carries a label keyword → use it;
     * 3. with preferLargestText, if one candidate's font (node height) is clearly the largest → use it;
     * 4. otherwise null (ambiguous).
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
 * Uber adapter config. Keywords marked "pending" haven't been seen on a real device yet; correct them from debug snapshots.
 */
data class UberAdapterConfig(
    /**
     * Target package. "com.ubercab.driver" was confirmed on a real device (Phase 0);
     * it can be changed on the settings page.
     */
    val targetPackages: Set<String> = setOf("com.ubercab.driver"),
    /** Accept button text ("Accept" seen on a real device; others pending). Only raises confidence / detects multiple offers, never tapped. */
    val acceptKeywords: List<String> = listOf("Accept", "Match", "Tap to accept", "接受", "接单"),
    /** Text that only appears on an offer card (seen on a real device). Used with [acceptKeywords] to tell offers from history screens. */
    val offerMarkerKeywords: List<String> = listOf("including estimated tip", "estimated tip"),
    /** Title of a screen that lists several offers at once ("Trip Radar" seen on a real device). */
    val offerListKeywords: List<String> = listOf("Trip Radar"),
    /** Judge each card of an offer list separately (needs screen positions). */
    val splitOfferLists: Boolean = true,
    /** Require an Accept button or an offer marker before treating money + distance + time as an offer. */
    val requireOfferMarker: Boolean = true,
    /** Pending: labels that may appear next to the price. */
    val payoutLabelKeywords: List<String> = listOf("est.", "estimated", "total", "earn", "预估", "总计"),
    /** Amounts in text containing these are not the offer price (boosts, tip notes, earnings totals, ...). Partly pending. */
    val payoutExcludeKeywords: List<String> = listOf(
        "+$", "boost", "surge", "promo", "quest", "today", "this week", "balance",
        "/hr", "per hour", "/h", "incl.", "includes", "tip", "fee", "今日", "本周", "加价",
    ),
    /** Pending: durations in text containing these are not the trip total (e.g. ETA to pickup). */
    val durationExcludeKeywords: List<String> = listOf("away", "ago", "expires", "remaining", "left"),
    /** Labels for total distance / time ("total" seen on a real device). */
    val totalKeywords: List<String> = listOf("total", "trip", "总"),
    /** Read the drop-off city (city only, no street) for the out-of-town alert. */
    val readDropoffCity: Boolean = true,
    val multiStopKeywords: List<String> = listOf("stops", "multiple", "deliveries", "多点", "多单"),
    /** Whether to add up several leg distances when there's no "total". Off by default: not assumed until verified on a device. */
    val sumLegsWhenNoTotal: Boolean = false,
    /** Set to true once every keyword has been verified against real-device snapshots. */
    val verifiedOnRealDevice: Boolean = false,
)
