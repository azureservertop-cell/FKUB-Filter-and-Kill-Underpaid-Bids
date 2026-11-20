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

/** Real Trip Radar screenshot (2026-11-20, London ON, 1216×2688): two stacked cards with "Match" buttons. */
class TripRadarTest {
    private fun ocr(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    private val radar = arrayOf(
        "07:41" to Box(45, 30, 140, 75),
        "Trip Radar" to Box(200, 185, 450, 245),
        "Delivery (2)" to Box(190, 450, 430, 510),
        "CA$11.56" to Box(85, 560, 630, 665),
        "including estimated tip" to Box(110, 720, 605, 775),
        "29 min (8.4 km) total" to Box(190, 885, 690, 950),
        "McDonald's Canada - 1950 Dundas" to Box(190, 1025, 1045, 1085),
        "Street" to Box(190, 1105, 340, 1160),
        "Trafalgar St, London" to Box(190, 1195, 680, 1255),
        "Customer verification" to Box(230, 1365, 700, 1420),
        "Match" to Box(540, 1550, 675, 1610),
        "Delivery" to Box(190, 1815, 365, 1875),
        "CA$6.55" to Box(85, 1925, 575, 2030),
        "including estimated tip" to Box(110, 2085, 605, 2145),
        "18 min (5.3 km) total" to Box(190, 2255, 680, 2320),
        "McDonald's Canada - 1950 Dundas" to Box(190, 2395, 1045, 2455),
        "Street" to Box(190, 2475, 340, 2530),
        "Sovereign Rd & Tartan Dr, London" to Box(190, 2565, 995, 2625),
    )

    @Test fun two_cards_judged_separately() {
        val r = assertIs<AdapterResult.OfferList>(UberDriverAdapter().extract(ocr(*radar)))
        assertEquals(2, r.cards.size)
        val (a, b) = r.cards
        assertEquals(11.56, a.offer.estimatedPayout); assertEquals(8.4, a.offer.distanceKm); assertEquals(29.0, a.offer.durationMinutes)
        assertEquals(6.55, b.offer.estimatedPayout); assertEquals(5.3, b.offer.distanceKm); assertEquals(18.0, b.offer.durationMinutes)
        assertTrue(a.complete && b.complete)
        assertEquals(560, a.anchor.top); assertEquals(1925, b.anchor.top)
        assertEquals("London", a.offer.dropoffCity)
        assertEquals("London", b.offer.dropoffCity)
        assertTrue(OfferEvaluator().evaluate(a.offer).isConclusive)
        assertTrue(OfferEvaluator().evaluate(b.offer).isConclusive)
    }

    /** Scrolled so the second card's trip line is cut off: only the first card is complete. */
    @Test fun card_cut_off_is_incomplete() {
        val cut = radar.filter { it.second.bottom <= 2160 }.toTypedArray()
        val r = assertIs<AdapterResult.OfferList>(UberDriverAdapter().extract(ocr(*cut)))
        assertTrue(r.cards[0].complete)
        assertTrue(r.cards.drop(1).none { it.complete })
    }

    /** One card left on Trip Radar is still a list (no full popup alert). */
    @Test fun titled_list_with_one_card() {
        val one = radar.filter { it.second.top < 1700 }.toTypedArray()
        val r = assertIs<AdapterResult.OfferList>(UberDriverAdapter().extract(ocr(*one)))
        assertEquals(1, r.cards.size)
    }

    /** A single offer popup with "Match" instead of "Accept" is a normal offer. */
    @Test fun match_button_popup() {
        val popup = radar.filter { it.second.top in 400..1700 }.toTypedArray()
        val r = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(*popup)))
        assertEquals(11.56, r.offer.estimatedPayout)
        assertTrue(r.complete)
    }
}
