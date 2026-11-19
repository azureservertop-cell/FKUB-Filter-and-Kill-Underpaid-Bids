package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real offer that showed "Unreadable" (2026-11-19, London ON): a 3-order batch with an hour-long trip,
 * "Delivery (3)" / "CA$25.07" / "1 hr 1 min (31.0 km) total", plus a two-line pickup and drop-off.
 * Coordinates approximate the 1216×2688 screenshot.
 */
class MultiStopOfferTest {
    private fun ocr(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    private val card = arrayOf(
        "Delivery (3)" to Box(215, 1440, 460, 1525),
        "Exclusive" to Box(540, 1440, 740, 1525),
        "CA$25.07" to Box(110, 1560, 665, 1680),
        "including estimated tip" to Box(135, 1710, 630, 1790),
        "1 hr 1 min (31.0 km) total" to Box(215, 1885, 805, 1965),
        "LR Sushi (580 Fanshawe Park Road" to Box(215, 2020, 1060, 2100),
        "East)" to Box(215, 2100, 345, 2175),
        "Beaty Ln & Thames Springs Cres," to Box(215, 2180, 1020, 2255),
        "Thamesford" to Box(215, 2260, 515, 2335),
        "Accept" to Box(520, 2445, 700, 2525),
    )

    private fun check(screen: ScreenText) {
        val r = UberDriverAdapter().extract(screen)
        val o = assertIs<AdapterResult.Offer>(r, "adapter: ${UberDriverAdapter().also { it.extract(screen) }.lastDebug}")
        assertEquals(25.07, o.offer.estimatedPayout, "issues ${o.offer.issues}")
        assertEquals(31.0, o.offer.distanceKm, "issues ${o.offer.issues}")
        assertEquals(61.0, o.offer.durationMinutes, "issues ${o.offer.issues}")
        val e = OfferEvaluator().evaluate(o.offer)
        assertTrue(e.isConclusive, "unknown: ${e.unknownReasons} issues ${o.offer.issues} conf ${o.offer.confidence}")
    }

    @Test fun card_only() = check(ocr(*card))

    @Test fun full_screen_with_map_labels_and_our_pill() = check(
        ocr(
            "17:57" to Box(45, 30, 140, 75),
            "Unreadable" to Box(450, 205, 765, 270),
            "London" to Box(105, 980, 300, 1030),
            "Dorchester" to Box(770, 960, 1065, 1010),
            *card,
        ),
    )

    /** OCR misreads seen or expected on this font; each must still give a verdict with the right numbers. */
    @Test fun ocr_misread_variants() {
        val trips = listOf(
            "1 hr 1 min (31.0 km) total", "1hr 1min (31.0km) total", "l hr l min (31.0 km) total", "I hr I min (31.0 km) total",
            "1 hr 1 min (31.O km) total", "1 hr 1 min (31.0 krn) total", "1 hr 1 min (310 km) total", "1 hr 1 min (31,0 km) total",
        )
        val prices = listOf("CA$25.07", "CA$25.O7", "CA$2507", "CAS25.07", "CA$25 07")
        for (p in prices) for (t in trips) {
            val nodes = card.map { (text, box) ->
                when (text) {
                    "CA$25.07" -> p to box
                    "1 hr 1 min (31.0 km) total" -> t to box
                    else -> text to box
                }
            }
            val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(*nodes.toTypedArray())), "[$p] [$t]").offer
            assertEquals(25.07, o.estimatedPayout, "[$p] [$t]")
            assertEquals(31.0, o.distanceKm, "[$p] [$t]")
            assertEquals(61.0, o.durationMinutes, "[$p] [$t]")
            assertTrue(OfferEvaluator().evaluate(o).isConclusive, "[$p] [$t] ${o.issues} ${o.confidence}")
        }
    }

    @Test fun trip_line_repair_leaves_good_text_alone() {
        val fix = { s: String -> com.offerpilot.core.parser.OfferParser.repairOcrTripLine(s) }
        assertEquals("15 min (1.3 km) total" to false, fix("15 min (1.3 km) total"))
        assertEquals("22 min (8.0 km) total" to false, fix("22 min (8.0 km) total"))
        assertEquals("1 hr 1 min (31.0 km) total" to true, fix("1 hr 1 min (310 km) total"))
        assertEquals("1 hr 1 min (31.0 km) total" to true, fix("l hr l min (31.O krn) total"))
        assertEquals("LR Sushi (580 Fanshawe Park Road" to false, fix("LR Sushi (580 Fanshawe Park Road"))
    }

    @Test fun price_lookalike_repair_only_on_price_lines() {
        val fix = { s: String -> com.offerpilot.core.parser.OfferParser.repairOcrPriceLine(s) }
        assertEquals("CA$25.07" to true, fix("CA$25.O7"))
        assertEquals("$5 off" to false, fix("$5 off"))
    }
}
