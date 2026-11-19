package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.ParseIssue
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 以下屏幕文字均为合成数据，用于验证保守策略，并非真实 Uber 界面。 */
class UberDriverAdapterTest {
    private val adapter = UberDriverAdapter()
    private val ev = OfferEvaluator()

    @Test fun simple_offer_parsed() {
        val r = adapter.extract(screen("Delivery", "$9.00", "42 min (29.92 km) total", "Accept"))
        val o = assertIs<AdapterResult.Offer>(r)
        assertTrue(o.complete)
        assertEquals(9.0, o.offer.estimatedPayout)
        assertEquals(29.92, o.offer.distanceKm)
        assertEquals(42.0, o.offer.durationMinutes)
        assertTrue(ParseIssue.ADAPTER_UNVERIFIED in o.offer.issues)
        assertEquals(Verdict.NOT_RECOMMENDED, ev.evaluate(o.offer).verdict)
    }

    @Test fun other_app_ignored() {
        assertEquals(AdapterResult.NoOffer, adapter.extract(screen("$9.00", "42 min", "29.92 km", pkg = "com.android.chrome")))
    }

    @Test fun no_offer_on_earnings_screen() {
        assertEquals(AdapterResult.NoOffer, adapter.extract(screen("Today", "$123.45", "Online")))
    }

    @Test fun no_offer_on_navigation_screen() {
        assertEquals(AdapterResult.NoOffer, adapter.extract(screen("12 min", "5.3 km", "Turn left")))
    }

    @Test fun excluded_amounts_are_not_payout() {
        val r = adapter.extract(screen("+$2.00 Boost", "$9.00", "Includes expected tip $3.00", "42 min (29.92 km)", "Accept"))
        assertEquals(9.0, assertIs<AdapterResult.Offer>(r).offer.estimatedPayout)
    }

    @Test fun two_unlabelled_amounts_same_size_is_ambiguous() {
        val r = assertIs<AdapterResult.Offer>(adapter.extract(screen("$9.00", "$14.00", "42 min (29.92 km)", "Accept")))
        assertNull(r.offer.estimatedPayout)
        assertTrue(ParseIssue.PAYOUT_AMBIGUOUS in r.offer.issues)
        assertEquals(Verdict.UNKNOWN, ev.evaluate(r.offer).verdict)
    }

    @Test fun largest_text_wins_when_clearly_larger() {
        val s = screenNodes(
            TextNode("$9.00", bounds = Box(0, 0, 300, 120)),
            TextNode("$1.50", bounds = Box(0, 200, 100, 240)),
            TextNode("42 min (29.92 km)", bounds = Box(0, 300, 400, 340)),
            TextNode("Accept", bounds = Box(0, 900, 500, 1000)),
        )
        val o = assertIs<AdapterResult.Offer>(adapter.extract(s)).offer
        assertEquals(9.0, o.estimatedPayout)
        assertTrue(o.confidence < 0.95f)
    }

    @Test fun two_accept_buttons_means_multiple_offers() {
        val o = assertIs<AdapterResult.Offer>(adapter.extract(screen("$9.00", "42 min (29.92 km)", "Accept", "$7.00", "10 min (3 km)", "Accept"))).offer
        assertTrue(ParseIssue.MULTIPLE_OFFERS in o.issues)
        assertEquals(Verdict.UNKNOWN, ev.evaluate(o).verdict)
    }

    @Test fun legs_without_total_are_ambiguous_by_default() {
        val o = assertIs<AdapterResult.Offer>(adapter.extract(screen("$33.01", "Pickup 5 km", "Dropoff 36 km", "63 min", "Accept"))).offer
        assertNull(o.distanceKm)
        assertTrue(ParseIssue.DISTANCE_AMBIGUOUS in o.issues)
    }

    @Test fun eta_away_is_not_trip_duration() {
        val o = assertIs<AdapterResult.Offer>(adapter.extract(screen("$7.00", "3 min away", "10 min (3 km) total", "Accept"))).offer
        assertEquals(10.0, o.durationMinutes)
        assertEquals(Verdict.RECOMMENDED, ev.evaluate(o).verdict)
    }

    @Test fun incomplete_offer_with_accept_button() {
        val r = assertIs<AdapterResult.Offer>(adapter.extract(screen("$9.00", "Accept")))
        assertTrue(!r.complete)
        assertTrue(ParseIssue.DISTANCE_MISSING in r.offer.issues)
        assertEquals(Verdict.UNKNOWN, ev.evaluate(r.offer).verdict)
    }

    @Test fun miles_screen() {
        val o = assertIs<AdapterResult.Offer>(adapter.extract(screen("CA$7.00", "10 mins", "1.86 mi", "Accept"))).offer
        assertEquals(3.0, o.distanceKm!!, 0.01)
    }

    @Test fun multi_stop_flag() {
        val o = assertIs<AdapterResult.Offer>(adapter.extract(screen("$33.01", "2 stops", "1 hr 3 min (40.99 km) total", "Accept"))).offer
        assertEquals(true, o.multiStop)
        assertEquals(63.0, o.durationMinutes)
        assertEquals(Verdict.MARGINAL, ev.evaluate(o).verdict)
    }
}
