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

    /** Real (2026-11-21): a single "Match" card over the map, with the "Trip Radar 2" chip above it → full offer. */
    @Test fun match_card_over_map_with_radar_chip() {
        val screen = ocr(
            "21:58" to Box(50, 35, 145, 80),
            "LONDON" to Box(160, 520, 430, 565),
            "ARGYLE PARK" to Box(345, 935, 770, 985),
            "Trip Radar" to Box(450, 1330, 670, 1390),
            "2" to Box(715, 1330, 750, 1385),
            "Delivery (2)" to Box(215, 1530, 460, 1590),
            "CA$13.66" to Box(110, 1640, 665, 1750),
            "including estimated tip" to Box(135, 1800, 630, 1860),
            "24 min (6.0 km) total" to Box(215, 1960, 715, 2025),
            "McDonald's (1950 Dundas)" to Box(215, 2110, 870, 2175),
            "Bonaventure Dr & Trafalgar St," to Box(215, 2210, 950, 2270),
            "London" to Box(215, 2290, 400, 2350),
            "Match" to Box(540, 2460, 675, 2525),
        )
        val r = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen))
        assertEquals(13.66, r.offer.estimatedPayout); assertEquals(6.0, r.offer.distanceKm); assertEquals(24.0, r.offer.durationMinutes)
        assertTrue(r.complete)
        assertEquals("London", r.offer.dropoffCity)
        assertTrue(OfferEvaluator().evaluate(r.offer).isConclusive)
    }

    /** Real (2026-11-21): "Match" card over the map without the chip. */
    @Test fun match_card_over_map() {
        val screen = ocr(
            "London" to Box(425, 415, 635, 470),
            "Highland Country" to Box(405, 1255, 805, 1310),
            "Club" to Box(550, 1315, 655, 1370),
            "Delivery (2)" to Box(215, 1575, 460, 1635),
            "CA$12.07" to Box(110, 1690, 660, 1795),
            "including estimated tip" to Box(135, 1845, 630, 1905),
            "28 min (8.7 km) total" to Box(215, 2005, 715, 2070),
            "Wendy's (338 Wellington Rd)" to Box(215, 2155, 920, 2220),
            "Forbes St & Winston Ave, London" to Box(215, 2265, 1010, 2330),
            "Match" to Box(540, 2460, 675, 2525),
        )
        val r = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen))
        assertEquals(12.07, r.offer.estimatedPayout); assertEquals(8.7, r.offer.distanceKm); assertEquals(28.0, r.offer.durationMinutes)
        assertTrue(r.complete)
        assertEquals("London", r.offer.dropoffCity)
    }
}
