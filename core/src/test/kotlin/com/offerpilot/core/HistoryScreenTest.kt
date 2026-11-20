package com.offerpilot.core

import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.UberDriverAdapter
import kotlin.test.Test
import kotlin.test.assertIs

/** Screens that show money + distance + time but are not offers (synthetic, modelled on trip history). */
class HistoryScreenTest {
    @Test fun trip_history_is_not_an_offer() {
        val s = screen("Trip details", "CA$16.06", "Fare", "50 min", "30.5 km", "Culture Crust (London)", "Sunset Rd, St Thomas")
        assertIs<AdapterResult.NoOffer>(UberDriverAdapter().extract(s))
    }

    @Test fun earnings_list_is_not_an_offer() {
        val s = screen("Today", "CA$25.07 · 1 hr 1 min · 31.0 km", "CA$5.50 · 15 min · 1.3 km", "Weekly summary")
        assertIs<AdapterResult.NoOffer>(UberDriverAdapter().extract(s))
    }

    @Test fun offer_without_accept_but_with_tip_label_still_counts() {
        val s = screen("Delivery", "CA$5.50", "including estimated tip", "15 min (1.3 km) total")
        assertIs<AdapterResult.Offer>(UberDriverAdapter().extract(s))
    }
}
