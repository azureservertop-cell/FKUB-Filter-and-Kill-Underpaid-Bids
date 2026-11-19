package com.offerpilot.core.tracking

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.parser.AdapterResult
import kotlin.math.roundToLong

/**
 * Offer lifecycle tracking: dedupe, settle time for incomplete offers, invalidate as soon as it disappears (PRD 4.3 / 4.4).
 *
 * Pure logic, no threads or timers: the caller calls [onFrame] for every screen frame it reads;
 * on [TrackerEvent.PendingIncomplete] it should read another frame after [settleMs].
 */
class OfferTracker(
    /** The same offer isn't announced twice within this window. */
    private val dedupWindowMs: Long = 20_000,
    /** How long to wait for missing fields before reporting "incomplete". */
    val settleMs: Long = 700,
) {
    private var current: DeliveryOffer? = null
    private var currentKey: String? = null
    private var incompleteSince: Long? = null
    private val recentlyAnnounced = LinkedHashMap<String, Long>()

    val activeOffer: DeliveryOffer? get() = current

    fun onFrame(result: AdapterResult, now: Long): TrackerEvent {
        prune(now)
        when (result) {
            is AdapterResult.NoOffer -> {
                incompleteSince = null
                val prev = current ?: return TrackerEvent.Idle
                current = null
                currentKey = null
                return TrackerEvent.OfferGone(prev)
            }
            is AdapterResult.Offer -> {
                val offer = result.offer
                val key = dedupKey(offer)
                if (!result.complete) {
                    // While a complete offer is shown, an occasional incomplete frame doesn't change the verdict
                    if (current != null && currentKey != null && !currentKey!!.contains("?")) return TrackerEvent.Unchanged
                    val since = incompleteSince ?: now.also { incompleteSince = it }
                    if (now - since < settleMs) return TrackerEvent.PendingIncomplete
                }
                incompleteSince = if (result.complete) null else incompleteSince
                if (key == currentKey) return TrackerEvent.Unchanged
                val replaced = current
                current = offer
                currentKey = key
                val announcedAt = recentlyAnnounced[key]
                val duplicate = announcedAt != null && now - announcedAt < dedupWindowMs
                recentlyAnnounced[key] = now
                return TrackerEvent.NewOffer(offer, replaced = replaced, suppressAnnouncement = duplicate)
            }
        }
    }

    fun reset() {
        current = null; currentKey = null; incompleteSince = null; recentlyAnnounced.clear()
    }

    private fun prune(now: Long) {
        recentlyAnnounced.entries.removeAll { now - it.value > dedupWindowMs }
    }

    companion object {
        fun dedupKey(o: DeliveryOffer): String {
            val p = o.estimatedPayout?.let { (it * 100).roundToLong().toString() } ?: "?"
            val k = o.distanceKm?.let { (it * 10).roundToLong().toString() } ?: "?"
            val m = o.durationMinutes?.roundToLong()?.toString() ?: "?"
            return "$p|$k|$m"
        }
    }
}

sealed interface TrackerEvent {
    data object Idle : TrackerEvent
    data object Unchanged : TrackerEvent
    /** Offer fields incomplete, waiting for them; the caller should read again after settleMs. */
    data object PendingIncomplete : TrackerEvent
    /** New offer (or its content changed). With suppressAnnouncement=true only the overlay is refreshed, no speech. */
    data class NewOffer(
        val offer: DeliveryOffer,
        val replaced: DeliveryOffer?,
        val suppressAnnouncement: Boolean,
    ) : TrackerEvent
    /** Offer popup gone: the verdict must be withdrawn immediately. */
    data class OfferGone(val offer: DeliveryOffer) : TrackerEvent
}
