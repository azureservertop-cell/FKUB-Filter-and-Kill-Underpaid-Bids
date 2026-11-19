package com.offerpilot.core

import com.offerpilot.core.model.Box
import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode

const val UBER = "com.ubercab.driver"

fun offer(payout: Double?, km: Double?, min: Double?, ts: Long = 1_000L, confidence: Float = 0.95f) =
    DeliveryOffer("t", payout, km, min, timestamp = ts, source = OfferSource.MOCK, confidence = confidence)

/**
 * Synthetic screen text (NOT real Uber snapshots!). Only used to check the adapter's conservative logic.
 */
fun screen(vararg lines: String, pkg: String = UBER, ts: Long = 1_000L) =
    ScreenText(pkg, lines.mapIndexed { i, s -> TextNode(s, bounds = Box(0, i * 100, 500, i * 100 + 40)) }, ts)

fun screenNodes(vararg nodes: TextNode, pkg: String = UBER, ts: Long = 1_000L) = ScreenText(pkg, nodes.toList(), ts)
