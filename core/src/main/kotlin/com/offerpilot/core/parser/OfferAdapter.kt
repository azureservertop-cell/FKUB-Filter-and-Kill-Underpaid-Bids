package com.offerpilot.core.parser

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.ScreenText

/**
 * Screen text → offer adapter. When Uber changes its UI only the adapter is replaced/tuned; the evaluator stays the same.
 */
interface OfferAdapter {
    val id: String
    /** Whether this adapter's rules have been verified against real-device snapshots. */
    val verified: Boolean
    fun extract(screen: ScreenText): AdapterResult
}

sealed interface AdapterResult {
    /** No offer popup on screen. */
    data object NoOffer : AdapterResult

    /** Offer popup detected (fields may be incomplete). */
    data class Offer(val offer: DeliveryOffer, val complete: Boolean) : AdapterResult

    /**
     * A list of offer cards on screen at once (Uber's "Trip Radar"). Each card is judged on its own; the driver is browsing,
     * so the app shows a small badge next to each card instead of the full alert.
     */
    data class OfferList(val cards: List<Card>) : AdapterResult

    /** One card of an [OfferList]; [anchor] is the price line's screen bounds, used to place the badge. */
    data class Card(val offer: DeliveryOffer, val complete: Boolean, val anchor: com.offerpilot.core.model.Box)
}
