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
}
