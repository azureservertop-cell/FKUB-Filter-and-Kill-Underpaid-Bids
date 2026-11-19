package com.offerpilot.core.parser

/**
 * 文字解析器（PRD 4.2）：金额、距离、时间。单位统一为加元 / 公里 / 分钟。
 *
 * 这里只做"从一段字符串中找出所有候选值"，不做"哪个才是订单报价"的判断——
 * 那是 [com.offerpilot.core.parser.OfferAdapter] 的职责，避免把第一个 "$" 当成报价。
 */
object OfferParser {

    const val KM_PER_MILE = 1.609344

    data class Match<T>(val value: T, val raw: String, val start: Int)

    // 金额：$9 / $9.00 / CA$9.00 / CA $1,234.50 / $ 9
    private val MONEY = Regex("""(?<![A-Za-z0-9])(?:CA|C|CAD)?\s?\$\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?![\d])""")

    // 距离：29.92 km / 3.5km / 1.2 mi / 1,2 km（欧式小数逗号）
    private val DISTANCE = Regex("""(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:[.,]\d+)?)\s?(km|kms|kilometers?|kilometres?|mi|miles?)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    private val HOURS = Regex("""(\d+(?:[.,]\d+)?)\s?(h|hr|hrs|hour|hours)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+(?:[.,]\d+)?)\s?(min|mins|minute|minutes)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** 把所有空白（含换行、不间断空格）压成单个空格。 */
    fun normalize(text: String): String =
        text.replace(' ', ' ').replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()

    /**
     * 解析数字字符串。
     * "1,234.5" → 1234.5；"1,234" → 1234；"29,92" → 29.92（单个逗号后非 3 位视为小数点）。
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
            // "$1,23" 这种不是合法金额格式；带两位小数的欧式逗号也接受
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
     * 解析时长。"1 hr 3 min" 这样的相邻组合会合并为一个值（63 分钟）。
     * 同一段文本中不相邻的多个时长分别返回。
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

    fun parseMoney(text: String): Double? = findMoney(text).singleOrNull()?.value
    fun parseDistanceKm(text: String): Double? = findDistancesKm(text).singleOrNull()?.value
    fun parseDurationMinutes(text: String): Double? = findDurationsMinutes(text).singleOrNull()?.value
}
