package com.offerpilot.core.parser

/**
 * Text parser (PRD 4.2): money, distance, duration. Units are CAD / km / minutes.
 *
 * It only finds every candidate value in a string; deciding which one is the offer price is the job of
 * [com.offerpilot.core.parser.OfferAdapter], so the first "$" is never blindly taken as the price.
 */
object OfferParser {

    const val KM_PER_MILE = 1.609344

    data class Match<T>(val value: T, val raw: String, val start: Int)

    // Money: $9 / $9.00 / CA$9.00 / CA $1,234.50 / $ 9
    private val MONEY = Regex("""(?<![A-Za-z0-9])(?:CA|C|CAD)?\s?\$\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?![\d])""")

    // Distance: 29.92 km / 3.5km / 1.2 mi / 1,2 km (European decimal comma)
    private val DISTANCE = Regex("""(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:[.,]\d+)?)\s?(km|kms|kilometers?|kilometres?|mi|miles?)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    private val HOURS = Regex("""(\d+(?:[.,]\d+)?)\s?(h|hr|hrs|hour|hours)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+(?:[.,]\d+)?)\s?(min|mins|minute|minutes)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** Collapses all whitespace (incl. newlines and non-breaking spaces) into single spaces. */
    fun normalize(text: String): String =
        text.replace(' ', ' ').replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()

    /**
     * Parses a number string.
     * "1,234.5" → 1234.5; "1,234" → 1234; "29,92" → 29.92 (a single comma not followed by 3 digits is a decimal point).
     */
    fun parseNumber(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val cleaned = when {
            s.contains(',') && s.contains('.') -> s.replace(",", "")
            s.count { it == ',' } == 1 -> {
                val after = s.substringAfter(',')
                if (after.length == 3) s.replace(",", "") else s.replace(',', '.')
            }
            s.count { it == ',' } > 1 -> s.replace(",", "")
            else -> s
        }
        return cleaned.toDoubleOrNull()
    }

    fun findMoney(text: String): List<Match<Double>> {
        val t = normalize(text)
        return MONEY.findAll(t).mapNotNull { m ->
            val raw = m.groupValues[1]
            // "$1,23" is not a valid money format; a European comma with two decimals is accepted
            val v = parseNumber(raw) ?: return@mapNotNull null
            val before = t.getOrNull(m.range.first - 1)
            val signed = if (before == '-' || before == '\u2212') -v else v
            Match(signed, m.value, m.range.first)
        }.toList()
    }

    fun findDistancesKm(text: String): List<Match<Double>> {
        val t = normalize(text)
        return DISTANCE.findAll(t).mapNotNull { m ->
            val v = parseNumber(m.groupValues[1]) ?: return@mapNotNull null
            val unit = m.groupValues[2].lowercase()
            val km = if (unit.startsWith("mi")) v * KM_PER_MILE else v
            Match(km, m.value, m.range.first)
        }.toList()
    }

    /**
     * Parses durations. Adjacent parts like "1 hr 3 min" are merged into one value (63 minutes).
     * Non-adjacent durations in the same text are returned separately.
     */
    fun findDurationsMinutes(text: String): List<Match<Double>> {
        val t = normalize(text)
        data class Part(val minutes: Double, val start: Int, val end: Int, val isHour: Boolean)

        val parts = (HOURS.findAll(t).mapNotNull { m ->
            parseNumber(m.groupValues[1])?.let { Part(it * 60, m.range.first, m.range.last + 1, true) }
        } + MINUTES.findAll(t).mapNotNull { m ->
            parseNumber(m.groupValues[1])?.let { Part(it, m.range.first, m.range.last + 1, false) }
        }).sortedBy { it.start }.toList()

        val out = mutableListOf<Match<Double>>()
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            val next = parts.getOrNull(i + 1)
            if (p.isHour && next != null && !next.isHour && isJoiner(t.substring(p.end, next.start))) {
                out += Match(p.minutes + next.minutes, t.substring(p.start, next.end), p.start)
                i += 2
            } else {
                out += Match(p.minutes, t.substring(p.start, p.end), p.start)
                i += 1
            }
        }
        return out
    }

    private fun isJoiner(between: String): Boolean =
        between.isBlank() || between.trim().lowercase() in setOf("and", "&", ",")

    // ---------------- OCR repair ----------------

    /** A price on its own line, e.g. "CA$6.78", OCR-misread "CA$678", "CAS6 78". */
    private val OCR_PRICE_LINE = Regex("""^\s*(CA|C|CAD)?\s?[${'$'}S]\s?(\d{1,4})(?:[\s.,·•:'](\d{2}))?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Repairs common OCR misreads of the price line (on real devices Uber shows prices like "CA$5.05" or "CA$6").
     *  - lost decimal point: "CA$678" → "CA$6.78" (3–4 digit integer and the line holds only this amount)
     *  - decimal point read as space / comma / middle dot: "CA$6 78" → "CA$6.78"
     *  - "$" read as "S": "CAS6.78" → "CA$6.78" (only for standalone amount lines with a CA prefix)
     * Returns (repaired text, whether anything was repaired). Lines that aren't a standalone amount are returned unchanged.
     */
    fun repairOcrPriceLine(line: String): Pair<String, Boolean> {
        val m = OCR_PRICE_LINE.matchEntire(line.trim()) ?: return line to false
        val prefix = m.groupValues[1]
        val symbol = line.trim().substring(m.groups[2]!!.range.first - 1).first { it == '$' || it == 'S' || it == 's' }
        if (symbol != '$' && prefix.isEmpty()) return line to false // a lone "S12" is not money
        val whole = m.groupValues[2]
        val cents = m.groupValues[3]
        val fixed = when {
            cents.isNotEmpty() -> "$whole.$cents"
            whole.length >= 3 -> whole.dropLast(2) + "." + whole.takeLast(2)
            else -> whole
        }
        val out = "CA$" + fixed
        val original = line.trim()
        return out to (out != original && !(original.endsWith(fixed) && symbol == '$' && cents.isEmpty() && whole.length < 3))
    }

    fun parseMoney(text: String): Double? = findMoney(text).singleOrNull()?.value
    fun parseDistanceKm(text: String): Double? = findDistancesKm(text).singleOrNull()?.value
    fun parseDurationMinutes(text: String): Double? = findDurationsMinutes(text).singleOrNull()?.value
}
