package com.offerpilot.core.fuel

/** A regional average pump price. [pricePerLiter] is in dollars. */
data class FuelQuote(
    val pricePerLiter: Double,
    /** Reporting period as given by the source, e.g. "2026-11-17". */
    val period: String,
    /** Region the price is for, as named by the source. */
    val region: String,
    val source: String,
)

/**
 * Parses public fuel-price CSV files (e.g. Ontario's weekly regional averages).
 *
 * The exact layout isn't hard-coded. Two shapes are accepted:
 *  - wide: a date column followed by one column per region ("Date, Ottawa, Toronto West, London, ...");
 *  - long: one row per region ("Date, Location, Price").
 * Prices may be in cents per litre (e.g. 142.3) or dollars (1.423); values above 20 are treated as cents.
 */
object FuelPriceCsv {

    fun parse(csv: String, region: String, source: String): FuelQuote? {
        val rows = csv.lineSequence().map { splitCsvLine(it) }.filter { r -> r.any { it.isNotBlank() } }.toList()
        if (rows.size < 2) return null
        val want = region.trim().lowercase()
        if (want.isEmpty()) return null

        // Header: the first row that has a column naming the region, or a location/price pair.
        for ((hi, header) in rows.withIndex().take(20)) {
            val h = header.map { it.trim().lowercase() }
            val data = rows.drop(hi + 1)

            val locCol = h.indexOfFirst { it in LOCATION_HEADERS }
            val priceCol = h.indexOfFirst { c -> PRICE_HEADERS.any { c.contains(it) } }
            if (locCol >= 0 && priceCol >= 0) {
                val dateCol = h.indexOfFirst { c -> DATE_HEADERS.any { c.contains(it) } }
                val hit = data.lastOrNull { r -> r.getOrNull(locCol)?.trim()?.lowercase()?.contains(want) == true && price(r.getOrNull(priceCol)) != null }
                    ?: continue
                return FuelQuote(price(hit[priceCol])!!, hit.getOrNull(dateCol)?.trim().orEmpty(), hit[locCol].trim(), source)
            }

            val col = h.indexOfFirst { it == want }.takeIf { it >= 0 } ?: h.indexOfFirst { it.contains(want) }
            if (col > 0) {
                val hit = data.lastOrNull { r -> price(r.getOrNull(col)) != null } ?: continue
                return FuelQuote(price(hit[col])!!, hit[0].trim(), header[col].trim(), source)
            }
        }
        return null
    }

    /** Price cell → dollars per litre, or null if it isn't a plausible price. */
    fun price(cell: String?): Double? {
        val v = cell?.trim()?.removePrefix("$")?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val dollars = if (v > 20) v / 100 else v
        return dollars.takeIf { it in 0.3..5.0 }
    }

    /** Minimal CSV splitting with double-quote support. */
    fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trimEnd('\r')
        return out.map { it.removePrefix("﻿") }
    }

    private val LOCATION_HEADERS = setOf("location", "city", "market", "region", "geography", "centre", "center")
    private val PRICE_HEADERS = listOf("price", "value", "cents")
    private val DATE_HEADERS = listOf("date", "week", "period", "ref")
}
