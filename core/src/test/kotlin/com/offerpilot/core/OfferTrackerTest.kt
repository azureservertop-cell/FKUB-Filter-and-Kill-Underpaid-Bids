package com.offerpilot.core

import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.i18n.Lang
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator
import com.offerpilot.core.tracking.OfferTracker
import com.offerpilot.core.tracking.TrackerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfferTrackerTest {
    private val a = AdapterResult.Offer(offer(9.0, 29.92, 42.0), complete = true)
    private val c = AdapterResult.Offer(offer(7.0, 3.0, 10.0), complete = true)

    @Test fun new_then_unchanged_then_gone() {
        val t = OfferTracker()
        assertIs<TrackerEvent.NewOffer>(t.onFrame(a, 0))
        assertEquals(TrackerEvent.Unchanged, t.onFrame(a, 100))
        assertIs<TrackerEvent.OfferGone>(t.onFrame(AdapterResult.NoOffer, 200))
        assertEquals(TrackerEvent.Idle, t.onFrame(AdapterResult.NoOffer, 300))
    }

    @Test fun same_offer_reappearing_quickly_not_reannounced() {
        val t = OfferTracker(dedupWindowMs = 20_000)
        assertTrue(!(t.onFrame(a, 0) as TrackerEvent.NewOffer).suppressAnnouncement)
        t.onFrame(AdapterResult.NoOffer, 500)
        assertTrue((t.onFrame(a, 1_000) as TrackerEvent.NewOffer).suppressAnnouncement)
        t.onFrame(AdapterResult.NoOffer, 1_500)
        assertTrue(!(t.onFrame(a, 30_000) as TrackerEvent.NewOffer).suppressAnnouncement)
    }

    @Test fun different_offer_replaces() {
        val t = OfferTracker()
        t.onFrame(a, 0)
        val e = assertIs<TrackerEvent.NewOffer>(t.onFrame(c, 100))
        assertEquals(9.0, e.replaced?.estimatedPayout)
        assertTrue(!e.suppressAnnouncement)
    }

    @Test fun incomplete_waits_for_settle() {
        val t = OfferTracker(settleMs = 700)
        val inc = AdapterResult.Offer(offer(9.0, null, null), complete = false)
        assertEquals(TrackerEvent.PendingIncomplete, t.onFrame(inc, 0))
        assertEquals(TrackerEvent.PendingIncomplete, t.onFrame(inc, 300))
        assertIs<TrackerEvent.NewOffer>(t.onFrame(inc, 800))
        // completed later → new verdict
        assertIs<TrackerEvent.NewOffer>(t.onFrame(a, 900))
    }

    @Test fun incomplete_that_completes_quickly_is_silent() {
        val t = OfferTracker(settleMs = 700)
        assertEquals(TrackerEvent.PendingIncomplete, t.onFrame(AdapterResult.Offer(offer(9.0, null, null), false), 0))
        assertIs<TrackerEvent.NewOffer>(t.onFrame(a, 200))
        // an occasional incomplete frame while a complete offer is shown doesn't change the verdict
        assertEquals(TrackerEvent.Unchanged, t.onFrame(AdapterResult.Offer(offer(9.0, null, null), false), 300))
    }

    @Test fun pipeline_messages_match_prd_examples() {
        val ev = OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(pickupWaitMinutes = 0.0))
        val o1 = offer(9.0, 29.92, 42.0); val r1 = ev.evaluate(o1)
        assertEquals("净 $1/h", OfferMessages.overlay(o1, r1, Lang.ZH))
        val o2 = offer(33.01, 40.99, 63.0)
        assertEquals("净 $18/h", OfferMessages.overlay(o2, ev.evaluate(o2), Lang.ZH))
        val o3 = offer(7.0, 3.0, 10.0); val r3 = ev.evaluate(o3)
        assertEquals("净 $31/h", OfferMessages.overlay(o3, r3, Lang.ZH))
        assertEquals("无法识别", OfferMessages.overlay(offer(7.0, null, 10.0), ev.evaluate(offer(7.0, null, 10.0)), Lang.ZH))
        assertEquals("净 -$4/h", OfferMessages.overlay(offer(3.0, 20.0, 30.0), ev.evaluate(offer(3.0, 20.0, 30.0)), Lang.ZH)) // (2.55-4.72)/0.5
        // English (default)
        assertEquals("Net $1/h", OfferMessages.overlay(o1, r1))
        assertEquals("Net -$4/h", OfferMessages.overlay(offer(3.0, 20.0, 30.0), ev.evaluate(offer(3.0, 20.0, 30.0))))
        assertEquals("Unreadable", OfferMessages.overlay(offer(7.0, null, 10.0), ev.evaluate(offer(7.0, null, 10.0))))
    }

    @Test fun end_to_end_pipeline_other_app_clears() {
        val p = com.offerpilot.core.OfferPipeline(UberDriverAdapter(), OfferEvaluator(com.offerpilot.core.rules.EvaluatorSettings(pickupWaitMinutes = 0.0)))
        val show = p.process(screen("$9.00", "42 min (29.92 km)", "Accept", ts = 0), 0)
        assertIs<com.offerpilot.core.PipelineOutput.Show>(show)
        // switching to another app → withdraw the verdict immediately
        assertEquals(com.offerpilot.core.PipelineOutput.Clear, p.process(screen("$9.00", "42 min (29.92 km)", pkg = "com.google.android.apps.maps", ts = 100), 100))
    }
}
