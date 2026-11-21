package com.offerpilot.core.stats

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.rules.OfferEvaluator
import java.util.Locale

/** One offer seen on screen: only time, shown price, distance and duration are kept. */
data class OfferRecord(
    val timeMs: Long, val price: Double, val km: Double, val minutes: Double,
    /** "ACCEPT" / "MATCH", or null when unknown (and in records saved before this column existed). */
    val kind: com.offerpilot.core.model.OfferKind? = null,
) {
    fun toCsv(): String = String.format(Locale.US, "%d,%.2f,%.2f,%.1f,%s", timeMs, price, km, minutes, kind?.name ?: "")

    companion object {
        const val CSV_HEADER = "time_ms,price,km,minutes,kind"

        fun fromCsv(line: String): OfferRecord? {
            val p = line.split(',')
            if (p.size < 4) return null
            val t = p[0].trim().toLongOrNull() ?: return null
            val price = p[1].trim().toDoubleOrNull() ?: return null
            val km = p[2].trim().toDoubleOrNull() ?: return null
            val min = p[3].trim().toDoubleOrNull() ?: return null
            if (price <= 0 || km <= 0 || min <= 0) return null
            val kind = p.getOrNull(4)?.trim()?.let { k -> com.offerpilot.core.model.OfferKind.entries.firstOrNull { it.name == k } }
            return OfferRecord(t, price, km, min, kind)
        }
    }
}

/** One offer kind (Accept / Match) in the stats. */
data class KindSummary(
    val count: Int,
    val medianPrice: Double?,
    val medianPricePerKm: Double?,
    val medianNetHourly: Double?,
    /** Share of offers at or above the driver's own standard (not red). */
    val meetsStandardShare: Double,
)

/** A record judged with the user's current rules. */
data class JudgedRecord(val record: OfferRecord, val verdict: Verdict, val ratio: Double, val netHourly: Double)

data class OfferStatsSummary(
    val judged: List<JudgedRecord>,
    val countByVerdict: Map<Verdict, Int>,
    val medianPrice: Double?,
    val medianKm: Double?,
    val medianPricePerKm: Double?,
    val medianNetHourly: Double?,
    /** Net hourly histogram: lower bound of each $[NET_BIN]-wide bin → count. */
    val netHourlyBins: List<Pair<Int, Int>>,
    /** Hour of day (0–23) → counts per verdict. */
    val byHour: List<Map<Verdict, Int>>,
) {
    val total: Int get() = judged.size

    /** Accept vs Match comparison; kinds with no records are left out. */
    val byKind: Map<com.offerpilot.core.model.OfferKind, KindSummary>
        get() = com.offerpilot.core.model.OfferKind.entries.mapNotNull { k ->
            val js = judged.filter { it.record.kind == k }
            if (js.isEmpty()) null else k to KindSummary(
                count = js.size,
                medianPrice = OfferStats.median(js.map { it.record.price }),
                medianPricePerKm = OfferStats.median(js.map { it.record.price / it.record.km }),
                medianNetHourly = OfferStats.median(js.map { it.netHourly }),
                meetsStandardShare = js.count { it.verdict != Verdict.LOSS }.toDouble() / js.size,
            )
        }.toMap()

    companion object { const val NET_BIN = 5 }
}

/**
 * Offer history statistics. Records store only raw numbers; verdicts are recomputed with the current rules, so changing
 * thresholds or costs updates the whole history. Records are judged as fresh, complete, high-confidence offers.
 */
object OfferStats {
    val TIERS = listOf(Verdict.GREAT, Verdict.GOOD, Verdict.MIN_WAGE, Verdict.LOSS)

    fun compute(records: List<OfferRecord>, settings: EvaluatorSettings, hourOf: (Long) -> Int): OfferStatsSummary {
        val ev = OfferEvaluator(settings)
        val judged = records.mapNotNull { r ->
            val offer = DeliveryOffer(
                offerId = "h${r.timeMs}", estimatedPayout = r.price, distanceKm = r.km, durationMinutes = r.minutes,
                timestamp = 0L, source = OfferSource.MOCK, confidence = 1f,
            )
            val e = ev.evaluate(offer)
            if (!e.isConclusive) null else JudgedRecord(r, e.verdict, e.ratio ?: 0.0, e.netHourly ?: 0.0)
        }
        val counts = TIERS.associateWith { v -> judged.count { it.verdict == v } }
        val bins = judged.groupingBy { Math.floorDiv(Math.floor(it.netHourly).toInt(), OfferStatsSummary.NET_BIN) * OfferStatsSummary.NET_BIN }
            .eachCount().toList().sortedBy { it.first }
        val byHour = (0 until 24).map { h -> TIERS.associateWith { v -> judged.count { it.verdict == v && hourOf(it.record.timeMs) == h } } }
        return OfferStatsSummary(
            judged = judged,
            countByVerdict = counts,
            medianPrice = median(judged.map { it.record.price }),
            medianKm = median(judged.map { it.record.km }),
            medianPricePerKm = median(judged.map { it.record.price / it.record.km }),
            medianNetHourly = median(judged.map { it.netHourly }),
            netHourlyBins = bins,
            byHour = byHour,
        )
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
