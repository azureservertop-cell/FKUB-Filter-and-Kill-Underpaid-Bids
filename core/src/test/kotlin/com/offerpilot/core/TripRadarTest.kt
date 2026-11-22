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

class OfferKindTest {
    private fun ocr(vararg lines: String) = ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)
    private val card = arrayOf("CA$12.07", "including estimated tip", "28 min (8.7 km) total")

    @Test fun kind_from_button() {
        assertEquals(com.offerpilot.core.model.OfferKind.MATCH, assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(*card, "Match"))).offer.kind)
        assertEquals(com.offerpilot.core.model.OfferKind.ACCEPT, assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(*card, "Accept"))).offer.kind)
        assertEquals(null, assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(*card))).offer.kind)
    }

    @Test fun csv_keeps_kind_and_reads_old_rows() {
        val r = com.offerpilot.core.stats.OfferRecord(1L, 12.07, 8.7, 28.0, com.offerpilot.core.model.OfferKind.MATCH)
        assertEquals(r, com.offerpilot.core.stats.OfferRecord.fromCsv(r.toCsv()))
        assertEquals(null, com.offerpilot.core.stats.OfferRecord.fromCsv("1,5.50,1.30,15.0")!!.kind)
    }

    @Test fun stats_split_by_kind() {
        val recs = listOf(
            com.offerpilot.core.stats.OfferRecord(1L, 7.0, 3.0, 10.0, com.offerpilot.core.model.OfferKind.MATCH),
            com.offerpilot.core.stats.OfferRecord(2L, 5.5, 1.3, 15.0, com.offerpilot.core.model.OfferKind.ACCEPT),
            com.offerpilot.core.stats.OfferRecord(3L, 9.0, 29.92, 42.0, null),
        )
        val k = com.offerpilot.core.stats.OfferStats.compute(recs, com.offerpilot.core.rules.EvaluatorSettings()) { 0 }.byKind
        assertEquals(1, k[com.offerpilot.core.model.OfferKind.MATCH]!!.count)
        assertEquals(1.0, k[com.offerpilot.core.model.OfferKind.MATCH]!!.meetsStandardShare)
        assertEquals(0.0, k[com.offerpilot.core.model.OfferKind.ACCEPT]!!.meetsStandardShare)
    }
}

class ShopOrderTest {
    private fun ocr(vararg lines: String) = ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)

    @Test fun items_add_shopping_time() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(
            "Shop & Pay", "CA$18.40", "including estimated tip", "12 items", "25 min (6.0 km) total", "Accept",
        ))).offer
        assertEquals(12, o.shopItems)
        val ev = OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings())
        assertEquals(43.0, ev.withShopTime(o).durationMinutes)
        val off = OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(shopTimeEnabled = false))
        assertTrue(ev.evaluate(o).netHourly!! < off.evaluate(o).netHourly!!)
    }

    @Test fun normal_delivery_has_no_items() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(ocr(
            "Delivery (2)", "CA$12.07", "including estimated tip", "28 min (8.7 km) total", "Match",
        ))).offer
        assertEquals(null, o.shopItems)
    }

    @Test fun csv_keeps_items() {
        val r = com.offerpilot.core.stats.OfferRecord(1L, 18.4, 6.0, 25.0, null, 12)
        assertEquals(r, com.offerpilot.core.stats.OfferRecord.fromCsv(r.toCsv()))
    }
}

/** Real Shop card (2026-11-21, London ON): "Shop & Deliver" / "8 items (8 units)" / "49 min (10.3 km) total" / "Match". */
class RealShopCardTest {
    @Test fun shop_and_deliver() {
        val lines = listOf(
            "London" to Box(80, 860, 290, 915),
            "Shop & Deliver" to Box(215, 1440, 525, 1500),
            "CA$15.08" to Box(110, 1550, 665, 1660),
            "including estimated tip" to Box(135, 1705, 630, 1765),
            "8 items (8 units)" to Box(215, 1875, 615, 1935),
            "49 min (10.3 km) total" to Box(215, 2010, 740, 2075),
            "Walmart (330 Clarke Rd)" to Box(215, 2160, 810, 2225),
            "Central Ave & St George St, London" to Box(215, 2270, 1060, 2335),
            "Match" to Box(540, 2460, 675, 2525),
        )
        val screen = ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(15.08, o.estimatedPayout); assertEquals(10.3, o.distanceKm); assertEquals(49.0, o.durationMinutes)
        assertEquals(8, o.shopItems)
        assertEquals("London", o.dropoffCity)
        assertEquals(com.offerpilot.core.model.OfferKind.MATCH, o.kind)
        val r = OfferEvaluator().evaluate(o)
        assertEquals(9, r.netHourly!!.toInt()) // 63 min: 49 + 8 × 1.5 min shopping + 2 min waiting
    }
}

class PickupWaitTest {
    private fun offer(min: Double) = com.offerpilot.core.model.DeliveryOffer(
        offerId = "w", estimatedPayout = 10.0, distanceKm = 5.0, durationMinutes = min, timestamp = 0L, source = OfferSource.OCR, confidence = 0.9f,
    )

    @Test fun default_two_minutes_added() {
        assertEquals(22.0, OfferEvaluator().withExtraTime(offer(20.0)).durationMinutes)
        assertEquals(20.0, OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(pickupWaitMinutes = 0.0)).withExtraTime(offer(20.0)).durationMinutes)
        assertEquals(25.0, OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(pickupWaitMinutes = 5.0)).withExtraTime(offer(20.0)).durationMinutes)
    }

    @Test fun zero_time_stays_implausible() {
        assertTrue(!OfferEvaluator().evaluate(offer(0.0)).isConclusive)
    }
}

/** Real (2026-11-21 21:57): exclusive Accept popup over the map that showed nothing. */
class ExclusiveAcceptPopupTest {
    @Test fun pizza_pizza_popup() {
        val lines = listOf(
            "21:57" to Box(45, 30, 140, 75),
            "London" to Box(985, 115, 1160, 165),
            "International" to Box(925, 175, 1216, 225),
            "Airport (YXU)" to Box(920, 235, 1216, 285),
            "RONLEIGH" to Box(680, 640, 990, 690),
            "ARGYLE PARK" to Box(250, 1000, 675, 1050),
            "FAIRMONT" to Box(200, 1370, 520, 1420),
            "Delivery (2)" to Box(215, 1555, 460, 1615),
            "Exclusive" to Box(540, 1555, 740, 1615),
            "CA$7.05" to Box(110, 1665, 585, 1765),
            "including estimated tip" to Box(135, 1815, 630, 1875),
            "27 min (4.0 km) total" to Box(215, 1985, 710, 2045),
            "Pizza Pizza (1836 Dundas St.)" to Box(215, 2135, 925, 2200),
            "First St & Oxford St E, London" to Box(215, 2245, 925, 2310),
            "Accept" to Box(520, 2450, 690, 2515),
        )
        val screen = ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)
        val r = UberDriverAdapter().extract(screen)
        val o = assertIs<AdapterResult.Offer>(r, "${UberDriverAdapter().also { it.extract(screen) }.lastDebug}").offer
        assertEquals(7.05, o.estimatedPayout); assertEquals(4.0, o.distanceKm); assertEquals(27.0, o.durationMinutes)
        assertEquals("London", o.dropoffCity)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive, "${o.issues} ${o.confidence}")
    }
}
