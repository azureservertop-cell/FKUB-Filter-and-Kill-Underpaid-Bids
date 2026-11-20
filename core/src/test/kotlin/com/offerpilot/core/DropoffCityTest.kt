package com.offerpilot.core

import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.i18n.Lang
import com.offerpilot.core.model.Box
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.DropoffCity
import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropoffCityTest {
    private fun ocr(vararg lines: Pair<String, Box>) =
        ScreenText(UBER, lines.map { (t, b) -> TextNode(t, bounds = b) }, 1_000L, OfferSource.OCR)

    private fun city(screen: ScreenText) = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(screen)).offer.dropoffCity

    /** Real card: drop-off wraps onto a second line after a comma. */
    @Test fun thamesford_wrapped_line() {
        val s = ocr(
            "Delivery (3)" to Box(215, 1440, 460, 1525),
            "CA$25.07" to Box(110, 1560, 665, 1680),
            "including estimated tip" to Box(135, 1710, 630, 1790),
            "1 hr 1 min (31.0 km) total" to Box(215, 1885, 805, 1965),
            "LR Sushi (580 Fanshawe Park Road" to Box(215, 2020, 1060, 2100),
            "East)" to Box(215, 2100, 345, 2175),
            "Beaty Ln & Thames Springs Cres," to Box(215, 2180, 1020, 2255),
            "Thamesford" to Box(215, 2260, 515, 2335),
            "Accept" to Box(520, 2445, 700, 2525),
        )
        assertEquals("Thamesford", city(s))
    }

    /** Real card; also with OCR blocks out of reading order. */
    @Test fun st_thomas_any_order() {
        val lines = listOf(
            "Delivery (2)" to Box(215, 1535, 460, 1620),
            "CA$16.06" to Box(110, 1655, 670, 1775),
            "including estimated tip" to Box(135, 1805, 630, 1885),
            "50 min (30.5 km) total" to Box(215, 1975, 750, 2055),
            "Culture Crust (London)" to Box(215, 2140, 780, 2215),
            "Sunset Rd, St Thomas" to Box(215, 2245, 750, 2320),
            "Accept" to Box(520, 2445, 700, 2525),
        )
        assertEquals("St Thomas", city(ocr(*lines.toTypedArray())))
        assertEquals("St Thomas", city(ocr(*lines.reversed().toTypedArray())))
    }

    /** Street only (no comma) → unknown, no alert. Map labels above the card are ignored. */
    @Test fun no_city_shown() {
        val lines = listOf(
            "CLARKE RD", "ARGYLE", "Y Delivery Exclusive", "CA$5.05", "including estimated tip",
            "() 15 min (3.4 km) total", "HeBlSu 100", "TRAFALGAR ST", "Accept",
        )
        assertNull(city(ScreenText(UBER, lines.map { TextNode(it) }, 1_000L, OfferSource.OCR)))
    }

    @Test fun can_be_turned_off() {
        val s = ocr(
            "CA$16.06" to Box(110, 1655, 670, 1775),
            "including estimated tip" to Box(135, 1805, 630, 1885),
            "50 min (30.5 km) total" to Box(215, 1975, 750, 2055),
            "Sunset Rd, St Thomas" to Box(215, 2245, 750, 2320),
            "Accept" to Box(520, 2445, 700, 2525),
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter(UberAdapterConfig(readDropoffCity = false)).extract(s)).offer
        assertNull(o.dropoffCity)
    }

    @Test fun city_of_address() {
        assertEquals("London", DropoffCity.cityOf("12 King St, London, ON N6A 1A1"))
        assertEquals("St Thomas", DropoffCity.cityOf("Sunset Rd, St Thomas"))
        assertNull(DropoffCity.cityOf("Whitney St"))
        assertNull(DropoffCity.cityOf("Main St, 1200"))
    }

    @Test fun compare_with_home() {
        assertEquals("Thamesford", DropoffCity.outOfTown("Thamesford", "London"))
        assertNull(DropoffCity.outOfTown("london", "London"))
        assertNull(DropoffCity.outOfTown("St. Thomas", "London; Saint Thomas"))
        assertNull(DropoffCity.outOfTown(null, "London"))
        assertNull(DropoffCity.outOfTown("Thamesford", ""))
    }

    @Test fun messages() {
        val s = ocr(
            "CA$16.06" to Box(110, 1655, 670, 1775),
            "including estimated tip" to Box(135, 1805, 630, 1885),
            "50 min (30.5 km) total" to Box(215, 1975, 750, 2055),
            "Sunset Rd, St Thomas" to Box(215, 2245, 750, 2320),
            "Accept" to Box(520, 2445, 700, 2525),
        )
        val o = assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(s)).offer
        val r = OfferEvaluator().evaluate(o)
        assertTrue(OfferMessages.overlay(o, r, Lang.EN, "St Thomas").startsWith("⚠ St Thomas · Net "))
        assertTrue(OfferMessages.speech(o, r, Lang.EN, "St Thomas").endsWith(" Out of town to St Thomas, watch the empty return."))
        assertTrue(OfferMessages.speech(o, r, Lang.ZH, "St Thomas").endsWith("跨城单，去St Thomas，注意空驶回程。"))
        assertEquals(OfferMessages.overlay(o, r, Lang.EN), OfferMessages.overlay(o, r, Lang.EN, null))
    }
}
