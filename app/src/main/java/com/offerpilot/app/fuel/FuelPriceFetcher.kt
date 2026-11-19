package com.offerpilot.app.fuel

import com.offerpilot.core.fuel.FuelPriceCsv
import com.offerpilot.core.fuel.FuelQuote
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches a regional average gasoline price from public government open data.
 *
 * Only plain GET/POST requests for public datasets are made; nothing about the user or their offers is sent.
 * Dataset layouts are discovered at runtime instead of hard-coding file URLs or IDs:
 *  1. Ontario open data (CKAN): weekly regional averages for regular unleaded, by city.
 *  2. Fallback: Statistics Canada table 18-10-0001-01 via the Web Data Service (monthly, larger cities).
 * Blocking; call off the main thread.
 */
object FuelPriceFetcher {
    private const val ONTARIO_SEARCH = "https://data.ontario.ca/api/3/action/package_search"
    private const val STATCAN_WDS = "https://www150.statcan.gc.ca/t1/wds/rest"
    private const val STATCAN_TABLE = 18100001
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun fetch(region: String): Result<FuelQuote> {
        val errors = mutableListOf<String>()
        for ((name, attempt) in listOf<Pair<String, (String) -> FuelQuote?>>("Ontario" to ::ontario, "StatCan" to ::statcan)) {
            try {
                attempt(region)?.let { return Result.success(it) }
                errors += "$name: no price for \"$region\""
            } catch (e: Exception) {
                errors += "$name: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
            }
        }
        return Result.failure(IOException(errors.joinToString("; ")))
    }

    // ---------------- Ontario open data ----------------

    private fun ontario(region: String): FuelQuote? {
        val q = URLEncoder.encode("fuel prices", "UTF-8")
        val results = JSONObject(get("$ONTARIO_SEARCH?q=$q&rows=10")).getJSONObject("result").getJSONArray("results")
        data class Res(val url: String, val label: String, val score: Int)
        val candidates = mutableListOf<Res>()
        for (i in 0 until results.length()) {
            val pkg = results.getJSONObject(i)
            val pkgTitle = pkg.optString("title")
            val resources = pkg.optJSONArray("resources") ?: continue
            for (j in 0 until resources.length()) {
                val r = resources.getJSONObject(j)
                val url = r.optString("url")
                val format = r.optString("format").lowercase()
                if (format != "csv" && !url.lowercase().endsWith(".csv")) continue
                val text = (r.optString("name") + " " + url + " " + pkgTitle).lowercase()
                var score = 0
                if ("regular" in text) score += 3
                if ("gas" in text) score += 1
                if ("week" in text) score += 1
                if (listOf("diesel", "premium", "propane", "furnace", "heating", "mid-grade", "midgrade").any { it in text }) score -= 5
                candidates += Res(url, r.optString("name").ifBlank { pkgTitle }, score)
            }
        }
        for (c in candidates.filter { it.score > 0 }.sortedByDescending { it.score }.take(3)) {
            val quote = FuelPriceCsv.parse(get(c.url), region, "Ontario open data · ${c.label}".trim())
            if (quote != null) return quote
        }
        return null
    }

    // ---------------- Statistics Canada ----------------

    private fun statcan(region: String): FuelQuote? {
        val meta = JSONArray(post("$STATCAN_WDS/getCubeMetadata", """[{"productId":$STATCAN_TABLE}]"""))
            .getJSONObject(0).getJSONObject("object")
        val dims = meta.getJSONArray("dimension")
        val coord = IntArray(10)
        var place = ""
        for (d in 0 until dims.length()) {
            val dim = dims.getJSONObject(d)
            val pos = dim.getInt("dimensionPositionId")
            val name = dim.optString("dimensionNameEn").lowercase()
            val members = dim.getJSONArray("member")
            val list = (0 until members.length()).map { members.getJSONObject(it) }
            val pick = when {
                "geograph" in name -> list.firstOrNull { it.optString("memberNameEn").contains(region, ignoreCase = true) }
                    ?: list.firstOrNull { it.optString("memberNameEn").startsWith("Toronto") }
                "fuel" in name || "type" in name -> list.firstOrNull {
                    val n = it.optString("memberNameEn").lowercase()
                    "regular unleaded" in n && "self" in n
                } ?: list.firstOrNull { "regular unleaded" in it.optString("memberNameEn").lowercase() }
                else -> list.firstOrNull()
            } ?: return null
            if ("geograph" in name) place = pick.optString("memberNameEn")
            if (pos in 1..10) coord[pos - 1] = pick.getInt("memberId")
        }
        val body = """[{"productId":$STATCAN_TABLE,"coordinate":"${coord.joinToString(".")}","latestN":1}]"""
        val obj = JSONArray(post("$STATCAN_WDS/getDataFromCubePidCoordAndLatestNPeriods", body))
            .getJSONObject(0).getJSONObject("object")
        val point = obj.getJSONArray("vectorDataPoint").getJSONObject(0)
        val price = FuelPriceCsv.price(point.optString("value")) ?: return null
        return FuelQuote(price, point.optString("refPer"), place, "Statistics Canada 18-10-0001-01 (monthly)")
    }

    // ---------------- HTTP ----------------

    private fun get(url: String): String = request(url, null)
    private fun post(url: String, json: String): String = request(url, json)

    private fun request(url: String, json: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "FKUB-Android")
            c.setRequestProperty("Accept", "application/json, text/csv, */*")
            if (json != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            val bytes = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) throw IOException("response too large: $url")
                }
                buf.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }
}
