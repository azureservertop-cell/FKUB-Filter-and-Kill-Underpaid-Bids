package com.offerpilot.core.parser

import com.offerpilot.core.model.TextNode

/**
 * Out-of-town check. Reads only the **city** of the drop-off address on Uber's offer card and compares it with the
 * driver's own city. The street part is discarded right here and never leaves this object; the city is kept only on
 * the in-memory offer (never logged to the offer history).
 *
 * Real cards (London ON, 2026-11-19) list the stops between the "... min (... km) total" line and "Accept":
 *   "LR Sushi (580 Fanshawe Park Road" / "East)" / "Beaty Ln & Thames Springs Cres," / "Thamesford"
 *   "Culture Crust (London)" / "Sunset Rd, St Thomas"
 * The last stop is the drop-off, and its city is the part after the last comma. No comma → city unknown → no alert.
 * Only whole cities are compared, never streets, neighbourhoods or postal codes: the alert is about the empty drive
 * back, not about the destination itself.
 */
object DropoffCity {

    private val PROVINCES = setOf(
        "on", "ontario", "qc", "quebec", "bc", "ab", "alberta", "mb", "manitoba", "sk", "saskatchewan",
        "ns", "nb", "nl", "pe", "pei", "yt", "nt", "nu", "canada",
    )
    private val POSTAL = Regex("""\b[A-Za-z]\d[A-Za-z]\s?\d[A-Za-z]\d\b""")

    /**
     * @param nodes card text (already OCR-repaired)
     * @param tripIndex index of the "... min (... km) total" line
     * @param acceptIndex index of the "Accept" button, or -1 if not found
     * With screen positions, takes the lines below the trip line and above Accept in top-to-bottom order
     * (OCR blocks are not guaranteed to be in reading order); without them, the lines between the two indices.
     */
    fun extract(nodes: List<TextNode>, tripIndex: Int, acceptIndex: Int): String? {
        if (tripIndex !in nodes.indices) return null
        val trip = nodes[tripIndex]
        val accept = nodes.getOrNull(acceptIndex)
        val tb = trip.bounds
        val selected = if (tb != null && nodes.all { it.bounds != null }) {
            val bottomLimit = accept?.bounds?.top ?: Int.MAX_VALUE
            nodes.filter { n -> n.bounds!!.top >= tb.bottom - 5 && n.bounds!!.bottom <= bottomLimit + 5 && n !== trip && n !== accept }
                .sortedWith(compareBy({ it.bounds!!.top }, { it.bounds!!.left }))
        } else {
            val end = if (acceptIndex > tripIndex) acceptIndex else nodes.size
            nodes.subList(tripIndex + 1, end)
        }
        val lines = selected.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        // Join lines that wrap: a line ending with a comma, or with an unclosed "(", continues on the next line.
        val blocks = mutableListOf<String>()
        var cur = ""
        for (l in lines) {
            cur = if (cur.isEmpty()) l else "$cur $l"
            val open = cur.count { it == '(' } > cur.count { it == ')' }
            if (!cur.endsWith(",") && !open) { blocks += cur; cur = "" }
        }
        if (cur.isNotEmpty()) blocks += cur
        return cityOf(blocks.last())
    }

    /** "Sunset Rd, St Thomas" → "St Thomas"; "12 King St, London, ON N6A 1A1" → "London"; "Whitney St" → null. */
    fun cityOf(address: String): String? {
        val parts = address.replace(POSTAL, "").split(',').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val tail = parts.drop(1).lastOrNull { normalize(it) !in PROVINCES } ?: return null
        // A city name has letters and no house number
        if (tail.none { it.isLetter() } || tail.any { it.isDigit() }) return null
        return tail
    }

    /** Lowercase, "Saint"/"St." → "st", punctuation and extra spaces removed. */
    fun normalize(city: String): String = city.lowercase()
        .replace(Regex("""[.'’\-]"""), " ")
        .replace(Regex("""\bsaint\b"""), "st")
        .replace(Regex("""\bste\b"""), "st")
        .replace(Regex("""\s+"""), " ").trim()

    /**
     * Your cities, separated by commas or semicolons (e.g. "London; Thamesford").
     * Returns the drop-off city when it is none of them, null when it matches or is unknown.
     */
    fun outOfTown(dropoffCity: String?, homeCities: String): String? {
        val city = dropoffCity ?: return null
        val homes = homeCities.split(',', ';').map { normalize(it) }.filter { it.isNotEmpty() }
        if (homes.isEmpty()) return null
        return if (normalize(city) in homes) null else city
    }
}
