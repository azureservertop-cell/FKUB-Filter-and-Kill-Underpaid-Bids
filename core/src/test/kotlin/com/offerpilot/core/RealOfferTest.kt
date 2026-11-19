package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real Uber Driver offer popup (2026-11-19, Android 15, London ON).
 * Nodes are hidden during the popup; the text below comes from a screenshot at the same moment (what the OCR path sees),
 * with pickup / drop-off addresses removed. Line coordinates approximate the 1216×2688 screenshot.
 */
class RealOfferTest {
    private fun ocrScreen(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    private val popup = ocrScreen(
        "Delivery" to Box(150, 1495, 380, 1565),
        "Exclusive" to Box(470, 1495, 690, 1565),
        "CA$5.50" to Box(110, 1600, 610, 1730),
        "including estimated tip" to Box(130, 1755, 640, 1835),
        "15 min (1.3 km) total" to Box(210, 1925, 710, 2005),
        "Accept" to Box(520, 2445, 700, 2525),
    )

    @Test fun real_popup_parsed() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(popup))
        assertTrue(o.complete)
        assertEquals(5.50, o.offer.estimatedPayout)
        assertEquals(1.3, o.offer.distanceKm)
        assertEquals(15.0, o.offer.durationMinutes)
        assertEquals(OfferSource.OCR, o.offer.source)
        assertTrue(o.offer.confidence >= 0.6f)
        // required 1.3 × 0.236 + 15 × 0.30 = 4.81; tip-free 5.50 × 0.85 = 4.68; ratio 0.97 → red (below target)
        assertEquals(Verdict.LOSS, OfferEvaluator().evaluate(o.offer).verdict)
    }

    @Test fun earnings_pill_does_not_confuse_payout() {
        // if today's earnings "CA$0.00" above the popup is OCR'd too, it must not affect the price (0 is filtered out)
        val withPill = ocrScreen("CA$0.00" to Box(407, 137, 729, 239), *popup.nodes.map { it.text to it.bounds!! }.toTypedArray())
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(withPill))
        assertEquals(5.50, o.offer.estimatedPayout)
    }
}

/** Real-device OCR misread repairs. */
class OcrRepairTest {
    private fun fix(s: String) = com.offerpilot.core.parser.OfferParser.repairOcrPriceLine(s).first

    @Test fun missing_decimal_point() {
        assertEquals("CA$6.78", fix("CA$678"))
        assertEquals("CA$12.50", fix("CA$1250"))
    }

    @Test fun decimal_read_as_other_char() {
        assertEquals("CA$6.78", fix("CA$6 78"))
        assertEquals("CA$6.78", fix("CA$6,78"))
        assertEquals("CA$6.78", fix("CAS6.78"))
    }

    @Test fun correct_lines_untouched_or_equivalent() {
        assertEquals("CA$5.05", fix("CA$5.05"))
        assertEquals(false, com.offerpilot.core.parser.OfferParser.repairOcrPriceLine("CA$5.05").second)
        assertEquals("15 min (3.4 km) total", fix("15 min (3.4 km) total"))
        assertEquals("including estimated tip", fix("including estimated tip"))
        assertEquals("S12", fix("S12"))
    }

    @Test fun real_second_popup_with_own_overlay_text() {
        // raw real-device OCR (Uber popping over the launcher, full screenshot), including our own pill read as "F$5/ 3km A$17/h"
        val lines = listOf(
            "07:39 e", "N", "N", "A", "F$5/ 3km A$17/h", "CLARKE RD", "ARGYLE", "ARGYLE PARK", "Y Delivery Exclusive",
            "CA$5.05", "including estimated tip", "() 15 min (3.4 km) total", "HeBlSu 100", "TRAFALGAR ST", "Accept",
        )
        val screen = ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(5.05, o.estimatedPayout)
        assertEquals(3.4, o.distanceKm)
        assertEquals(15.0, o.durationMinutes)
    }

    @Test fun ocr_678_parsed_as_6_78() {
        val screen = ScreenText(
            UBER, listOf("Delivery", "CA$678", "including estimated tip", "12 min (2.1 km) total", "Accept").map { TextNode(it) },
            1_000L, OfferSource.OCR,
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(6.78, o.estimatedPayout)
    }
}

/** Real device: Uber in the background, offer card over Google Maps; integer price "CA$6". */
class IntegerPriceTest {
    @Test fun integer_price_not_mangled() {
        val p = com.offerpilot.core.parser.OfferParser
        assertEquals("CA$6", p.repairOcrPriceLine("CA$6").first)
        assertEquals(false, p.repairOcrPriceLine("CA$6").second)
        assertEquals("CA$12", p.repairOcrPriceLine("CA$12").first)
        assertEquals(6.0, p.parseMoney("CA$6"))
    }

    @Test fun real_offer_over_maps() {
        val lines = listOf(
            "Ask Maps", "Restaurants", "Shopping", "Mr. Burrito", "Home", "Whitney St", "Giant Creative Inc",
            "Delivery", "Exclusive", "CA$6", "including estimated tip", "22 min (8.0 km) total", "Accept",
        )
        val screen = ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(6.0, o.estimatedPayout)
        assertEquals(8.0, o.distanceKm)
        assertEquals(22.0, o.durationMinutes)
        // required 8 × 0.236 + 22 × 0.30 = 8.49; tip-free 6 × 0.85 = 5.10 → red
        assertEquals(Verdict.LOSS, OfferEvaluator().evaluate(o).verdict)
    }
}
