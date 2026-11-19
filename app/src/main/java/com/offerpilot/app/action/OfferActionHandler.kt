package com.offerpilot.app.action

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult

/**
 * Reserved action interface (PRD section 7, stage C).
 *
 * v1 does **not** implement any auto-accept / auto-reject / tapping. The only implementation is [NoOpOfferActionHandler].
 * The service's accessibility config doesn't declare gesture capability either. Whether to build any automation must be decided
 * separately, after reviewing platform rules, driving safety and the risk of mis-taps.
 */
interface OfferActionHandler {
    /** Returns whether an action was performed. */
    fun onEvaluated(offer: DeliveryOffer, result: EvaluationResult): Boolean
}

object NoOpOfferActionHandler : OfferActionHandler {
    override fun onEvaluated(offer: DeliveryOffer, result: EvaluationResult): Boolean = false
}
