package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.OfferParser
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
        assertEquals(31.0, ev.withShopTime(o).durationMinutes)
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
        assertEquals(11, r.netHourly!!.toInt()) // 55 min: 49 + 8 × 0.5 min shopping + 2 min waiting
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

/** Real (2026-11-22 16:34) cards. */
class RealCards1122Test {
    private fun screen(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    @Test fun shop_items_and_units() {
        val s = screen(
            "Shop & Deliver (2)" to Box(215, 1255, 595, 1315),
            "Exclusive" to Box(680, 1255, 875, 1315),
            "CA$22.03" to Box(110, 1365, 665, 1465),
            "including estimated tip" to Box(135, 1515, 630, 1575),
            "22 items (30 units)" to Box(215, 1680, 670, 1745),
            "1 hr 4 min (2.9 km) total" to Box(215, 1820, 785, 1885),
            "No Frills (261 Richmond Street West)" to Box(215, 1970, 1105, 2030),
            "Cecil St & Henry St, Toronto" to Box(215, 2075, 880, 2140),
            "Heavy" to Box(255, 2245, 390, 2305),
            "Accept" to Box(520, 2450, 690, 2515),
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(s)).offer
        assertEquals(22.03, o.estimatedPayout); assertEquals(2.9, o.distanceKm); assertEquals(64.0, o.durationMinutes)
        assertEquals(22, o.shopItems); assertEquals(30, o.shopUnits)
        assertEquals("Toronto", o.dropoffCity)
        assertEquals(com.offerpilot.core.model.OfferKind.ACCEPT, o.kind)
        assertEquals(64.0 + 22 * 0.5 + 2, OfferEvaluator().withExtraTime(o).durationMinutes)
        val units = OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(shopCountUnits = true))
        assertEquals(64.0 + 30 * 0.5 + 2, units.withExtraTime(o).durationMinutes)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }

    private fun scarborough(pickup: String) = screen(
        "North York" to Box(115, 500, 400, 550),
        "Scarborough" to Box(850, 400, 1190, 450),
        "Delivery" to Box(215, 1555, 390, 1615),
        "Exclusive" to Box(470, 1555, 670, 1615),
        "CA$27.52" to Box(110, 1665, 645, 1765),
        "including estimated tip" to Box(135, 1815, 630, 1875),
        "1 hr 10 min (29.0 km) total" to Box(215, 1985, 840, 2045),
        pickup to Box(215, 2140, 975, 2205),
        "Meadowglen Pl, Scarborough" to Box(215, 2245, 925, 2310),
        "Accept" to Box(520, 2450, 690, 2515),
    )

    @Test fun scarborough_cjk_pickup() {
        for (p in listOf("XiaoGuoHuBing 好兄弟小锅烀饼", "XiaoGuoHuBing 4FRHR", "XiaoGuoHuBing \$7 /1 5", "XiaoGuoHuBing 2 1 #")) {
            val ad = UberDriverAdapter(); val o = assertIs<AdapterResult.Offer>(ad.extract(scarborough(p)), p).offer
            assertEquals(27.52, o.estimatedPayout, p); assertEquals(29.0, o.distanceKm, p); assertEquals(70.0, o.durationMinutes, p)
            assertEquals("Scarborough", o.dropoffCity, p)
            assertTrue(OfferEvaluator().evaluate(o).isConclusive, p)
        }
    }
}

class AcceptHiddenByCountdownTest {
    @Test fun repaired_ocr_without_accept_is_still_readable() {
        val lines = listOf(
            "Delivery" to Box(215, 1555, 390, 1615),
            "CA$2752" to Box(110, 1665, 645, 1765), // decimal point lost
            "including estimated tip" to Box(135, 1815, 630, 1875),
            "1 hr 10 min (29.0 km) total" to Box(215, 1985, 840, 2045),
            "Meadowglen Pl, Scarborough" to Box(215, 2245, 925, 2310),
            // "Accept" not read: the countdown fill runs through it
        )
        val screen = ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer
        assertEquals(27.52, o.estimatedPayout)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive, "${o.confidence} ${o.issues}")
    }
}

class HourReadsTest {
    private fun card(vararg trip: Pair<String, Box>) = ScreenText(UBER, (listOf(
        "Delivery (2)" to Box(215, 1555, 460, 1615),
        "CA$29.09" to Box(110, 1665, 670, 1765),
        "including estimated tip" to Box(135, 1815, 630, 1875),
    ) + trip + listOf(
        "Swatow Restaurant" to Box(215, 2135, 685, 2200),
        "Sheppard Ave E, Scarborough" to Box(215, 2245, 930, 2310),
        "Accept" to Box(520, 2450, 690, 2515),
    )).map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    @Test fun hours_only() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(card("1 hr (14.1 km) total" to Box(215, 1985, 655, 2045)))).offer
        assertEquals(60.0, o.durationMinutes); assertEquals(14.1, o.distanceKm)
    }

    @Test fun ocr_split_hours_piece() {
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(card(
            "1 hr" to Box(215, 1985, 320, 2045),
            "10 min (25.8 km) total" to Box(345, 1985, 835, 2045),
        ))).offer
        assertEquals(70.0, o.durationMinutes); assertEquals(25.8, o.distanceKm)
    }

    @Test fun tracker_keeps_longer_time_and_city() {
        val t = com.offerpilot.core.tracking.OfferTracker()
        val full = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(card("1 hr 10 min (25.8 km) total" to Box(215, 1985, 835, 2045))))
        val short = full.copy(offer = full.offer.copy(durationMinutes = 10.0, dropoffCity = null))
        assertIs<com.offerpilot.core.tracking.TrackerEvent.NewOffer>(t.onFrame(short, 0))
        val e = assertIs<com.offerpilot.core.tracking.TrackerEvent.NewOffer>(t.onFrame(full, 100))
        assertTrue(e.suppressAnnouncement || e.offer.durationMinutes == 70.0)
        assertEquals(70.0, t.activeOffer!!.durationMinutes); assertEquals("Scarborough", t.activeOffer!!.dropoffCity)
        assertEquals(com.offerpilot.core.tracking.TrackerEvent.Unchanged, t.onFrame(short, 200))
        assertEquals(70.0, t.activeOffer!!.durationMinutes)
    }
}

/** Real (2026-11-24) cards the user reported as not recognised: the parser reads them; the cause was capture. */
class RealCards1124Test {
    private fun read(vararg lines: Pair<String, Box>) = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR))).offer

    @Test fun dairy_queen_wrapped_pickup() {
        val o = read(
            "CEDAR HOLLOW" to Box(740, 820, 1216, 870),
            "Delivery" to Box(215, 1460, 390, 1520), "Exclusive" to Box(470, 1460, 670, 1520),
            "CA$8.08" to Box(110, 1570, 610, 1670), "including estimated tip" to Box(135, 1720, 630, 1780),
            "23 min (11.3 km) total" to Box(215, 1890, 730, 1950),
            "Dairy Queen (1820 Adelaide St N," to Box(215, 2030, 1015, 2090), "Unit 3E)" to Box(215, 2105, 410, 2165),
            "Pebblecreek Walk & Sawgrass Link," to Box(215, 2180, 1065, 2240), "London" to Box(215, 2260, 400, 2320),
            "Accept" to Box(520, 2450, 690, 2515),
        )
        assertEquals(8.08, o.estimatedPayout); assertEquals(11.3, o.distanceKm); assertEquals(23.0, o.durationMinutes)
        assertEquals("London", o.dropoffCity); assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }

    @Test fun burger_king_match() {
        val o = read(
            "London" to Box(220, 725, 430, 775),
            "Delivery" to Box(215, 1525, 390, 1585),
            "CA$7.04" to Box(110, 1635, 590, 1735), "including estimated tip" to Box(135, 1790, 630, 1850),
            "20 min (11.7 km) total" to Box(215, 1955, 735, 2015),
            "Burger King (1295 Oxford Street" to Box(215, 2095, 985, 2155), "East)" to Box(215, 2175, 340, 2235),
            "Regent St & The Pky, London" to Box(215, 2265, 910, 2325),
            "Match" to Box(540, 2460, 675, 2525),
        )
        assertEquals(7.04, o.estimatedPayout); assertEquals(20.0, o.durationMinutes)
        assertEquals(com.offerpilot.core.model.OfferKind.MATCH, o.kind); assertEquals("London", o.dropoffCity)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }
}

class WholeDollarPriceTest {
    @Test fun ca7_no_cents() {
        for (p in listOf("CA$7", "CAS7", "CA$ 7")) {
            val lines = listOf(
                "Delivery" to Box(215, 1555, 390, 1615), "Exclusive" to Box(470, 1555, 670, 1615),
                p to Box(110, 1665, 430, 1765), "including estimated tip" to Box(135, 1815, 630, 1875),
                "23 min (8.4 km) total" to Box(215, 1985, 715, 2045),
                "Tahini's (London East)" to Box(215, 2135, 755, 2200),
                "Maxwell Cres & Maxwell Crt, London" to Box(215, 2245, 1085, 2310),
                "Accept" to Box(520, 2450, 690, 2515),
            )
            val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
                ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)), p).offer
            assertEquals(7.0, o.estimatedPayout, p)
            assertTrue(OfferEvaluator().evaluate(o).isConclusive, "$p ${o.confidence} ${o.issues}")
        }
    }
}

class MapLabelCityTest {
    @Test fun caps_label_not_part_of_city() {
        assertEquals("London", com.offerpilot.core.parser.DropoffCity.cityOf("Kipling Ave & Wellesley Cres, London RD"))
        val lines = listOf(
            "CA$9.08" to Box(110, 1630, 610, 1730), "including estimated tip" to Box(135, 1745, 630, 1805),
            "21 min (3.9 km) total" to Box(215, 1935, 705, 1995),
            "Rajdhani Sweets & Restaurant" to Box(215, 2070, 945, 2130), "(London)" to Box(215, 2150, 440, 2210),
            "Kipling Ave & Wellesley Cres, London" to Box(215, 2240, 1100, 2300),
            "ONERS RD E" to Box(800, 2600, 1090, 2660), // map label under the card; Accept not read (countdown)
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
            ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR))).offer
        assertEquals("London", o.dropoffCity)
    }
}

class LcboCardTest {
    @Test fun lcbo_one_item() {
        val lines = listOf(
            "Shop & Deliver" to Box(215, 1285, 525, 1345), "Exclusive" to Box(610, 1285, 805, 1345),
            "CA$12.01" to Box(110, 1400, 650, 1500), "including estimated tip" to Box(135, 1550, 630, 1610),
            "Must verify ID" to Box(215, 1675, 515, 1735),
            "1 item (1 unit)" to Box(215, 1845, 550, 1905),
            "34 min (14.5 km) total" to Box(215, 1985, 740, 2045),
            "LCBO (1073 Wellington Road South)" to Box(215, 2130, 1090, 2195),
            "Bexhill Dr & Hillhead Rd, London" to Box(215, 2240, 990, 2305),
            "Accept" to Box(520, 2450, 690, 2515),
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
            ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR))).offer
        assertEquals(12.01, o.estimatedPayout); assertEquals(14.5, o.distanceKm); assertEquals(34.0, o.durationMinutes)
        assertEquals(1, o.shopItems); assertEquals("London", o.dropoffCity)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }
}

/** Real OCR reads from the 2026-11-24 debug session (text only; positions not stored in snapshots). */
class Debug1124Test {
    private fun read(vararg lines: String) = UberDriverAdapter().extract(
        ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR))

    @Test fun km_read_as_m() {
        val o = assertIs<AdapterResult.Offer>(read("Exclusive", "CA$10.08", "including estimated tip",
            "() 24 min (15.5 m) total", "City South Pizza (London)", "Parkview Dr & The Pky, Dorchester", "X", "Accept")).offer
        assertEquals(10.08, o.estimatedPayout); assertEquals(15.5, o.distanceKm); assertEquals(24.0, o.durationMinutes)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }

    @Test fun clock_icon_glued_to_minutes() {
        val o = assertIs<AdapterResult.Offer>(read("YP Delivery (2)", "CA$9.6l", "including estimated tip",
            "937 min (14.5 km) total", "Church's Texas Chicken (1030", "Adelaide St N, London)", "Capulet Lane, Londd", "Match", "X")).offer
        assertEquals(9.61, o.estimatedPayout); assertEquals(14.5, o.distanceKm); assertEquals(37.0, o.durationMinutes)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }

    @Test fun real_minutes_untouched() {
        assertEquals("47 min (11.8 km) total", OfferParser.repairOcrTripLine("47 min (11.8 km) total").first)
        assertEquals("1 hr 37 min (31.0 km) total", OfferParser.repairOcrTripLine("1 hr 37 min (31.0 km) total").first)
        assertEquals("7 min (1.3 km) total", OfferParser.repairOcrTripLine("97 min (1.3 km) total").first)
    }

    @Test fun taken_card_is_no_offer() {
        assertIs<AdapterResult.NoOffer>(read("Delivery (2)", "CA$5.04", "including estimated tip", "26min (9.",
            "This reguest is no longer available", "Park Rd E)", "Thurman Cir London", "X", "Match"))
    }

    @Test fun nmin_for_min() {
        val o = assertIs<AdapterResult.Offer>(read("Y? Delivery (2) Exclusive", "CA$19.04", "including estimated tip", "2 Must verify ID",
            "() 49 nmin (29.8 km) total", "Jay Bhavani Vadapav (London)", "Sunset Rd, Elgin County", "Accept", "X")).offer
        assertEquals(19.04, o.estimatedPayout); assertEquals(29.8, o.distanceKm); assertEquals(49.0, o.durationMinutes)
    }

    @Test fun taken_card_split_over_two_lines() {
        assertIs<AdapterResult.NoOffer>(read("Delivery (3)", "CA$14.08", "New requests", "including estimated tip",
            "( 38 This request is no longer", "available", "Thurman Ci, London", "Match", "X"))
    }

    @Test fun one_item_read_as_litem() {
        for (line in listOf("litem (1 unit)", "t l item (1l unit)", "O litem (1 unit)")) {
            val o = assertIs<AdapterResult.Offer>(read("Shop & Deliver Exclusive", "CA$11.06", "including estimated tip", line,
                "() 30 min (11.1 km) total", "Shoppers Drug Mart (1118 Adelaide", "Beaufort St & Wharncliffe Rd N,", "London", "X", "Accept")).offer
            assertEquals(1, o.shopItems, line)
        }
    }
}

/** Add-on offers (an extra order while on a trip) show increments with a "+" (real device, 2026-11-26). */
class AddOnOfferTest {
    private fun read(vararg lines: String) = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
        ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR))).offer

    @Test fun plus_price_with_letter_and_lost_point() {
        val o = read("Shop & Deliver", "+CA\$l64", "including estimated tip", "Must verify ID", "Exclusive", "+2 items (3 units)",
            "Same Store", "O + 28 min (+ 7.4 km) total", "X", "Huron St & King's Highway 4, London", "Accept")
        assertEquals(1.64, o.estimatedPayout); assertEquals(7.4, o.distanceKm); assertEquals(28.0, o.durationMinutes)
        assertEquals(2, o.shopItems)
        assertTrue(OfferEvaluator().evaluate(o).isConclusive)
    }

    @Test fun plus_price_lost_point() {
        val o = read("Shop & Deliver Exclusive", "+ CA$703", "including estimated tip", "+2 items (2 units)",
            ") +17 min (+ 1.6 km) total", "Dollarama (Oxbury Mall)", "Accept", "St James St & William St, London", "X")
        assertEquals(7.03, o.estimatedPayout); assertEquals(1.6, o.distanceKm); assertEquals(17.0, o.durationMinutes)
    }

    @Test fun plus_price_read_correctly() {
        val o = read("Delivery", "+CA$3.25", "including estimated tip", "+ 9 min (+ 2.1 km) total", "Accept")
        assertEquals(3.25, o.estimatedPayout); assertEquals(2.1, o.distanceKm); assertEquals(9.0, o.durationMinutes)
    }
}

/** "1 hr 17 min": the hour must not get lost (user report 2026-11-27; four such reads in the offer log). */
class LostHourTest {
    private fun read(vararg lines: String) = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(
        ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR))).offer

    @Test fun hour_in_its_own_piece_without_positions() {
        for (h in listOf("1 hr", "l hr", "(O 1 hr", "() l hr", "9 1 hr", "1hr")) {
            val o = read("Delivery", "CA$29.09", "including estimated tip", h, "17 min (25.8 km) total", "Sunset Rd, St Thomas", "Accept")
            assertEquals(77.0, o.durationMinutes, h); assertEquals(25.8, o.distanceKm)
        }
    }

    @Test fun hour_misread_inside_the_line() {
        for (l in listOf("1 nr 17 min (25.8 km) total", "(O l hr 17 min (25.8 km) total", "1hr17 min (25.8 km) total", "1 hn 17 min (25.8 km) total"))
            assertEquals(77.0, read("CA$29.09", "including estimated tip", l, "Accept").durationMinutes, l)
    }

    @Test fun hour_missing_altogether_is_put_back_by_speed() {
        // from the offer log: 19.3 km in "10 min", 13.5 km in "6 min", 25.8 km in "10 min"
        assertEquals(70.0, read("CA$32.72", "including estimated tip", "10 min (19.3 km) total", "Accept").durationMinutes)
        assertEquals(66.0, read("CA$17.40", "including estimated tip", "6 min (13.5 km) total", "Accept").durationMinutes)
        assertEquals(70.0, read("CA$29.09", "including estimated tip", "10 min (25.8 km) total", "Accept").durationMinutes)
    }

    @Test fun ordinary_trips_keep_their_minutes() {
        assertEquals(30.0, read("CA$11.53", "including estimated tip", "30 min (21.1 km) total", "Match").durationMinutes)
        assertEquals(42.0, read("CA$13.01", "including estimated tip", "42 min (35.4 km) total", "Accept").durationMinutes)
        assertEquals(4.0, read("CA$19.04", "including estimated tip", "4 min (3.2 km) total", "Accept").durationMinutes)
        assertEquals(62.0, read("CA$22.07", "including estimated tip", "(9 lhr 2 min (32.6 km) total", "Match").durationMinutes)
    }
}
