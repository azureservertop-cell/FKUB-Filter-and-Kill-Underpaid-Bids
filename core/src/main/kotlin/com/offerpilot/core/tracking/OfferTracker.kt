package com.offerpilot.core.tracking

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.parser.AdapterResult
import kotlin.math.roundToLong

/**
 * 订单生命周期跟踪：去重、不完整订单的稳定等待、消失即失效（PRD 4.3 / 4.4）。
 *
 * 纯逻辑，无线程、无计时器：调用方每次读到一帧屏幕就调用 [onFrame]，
 * 若返回 [TrackerEvent.PendingIncomplete]，调用方应在 [settleMs] 后再读一帧。
 */
class OfferTracker(
    /** 同一订单在此时间窗内不重复播报。 */
    private val dedupWindowMs: Long = 20_000,
    /** 字段不完整时等待其补全的时间，超过后才输出"信息不完整"。 */
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
                    // 已经在展示一个完整订单时，偶发的不完整帧不改变结论
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
    /** 订单字段不完整，等待补全；调用方应在 settleMs 后重新读取。 */
    data object PendingIncomplete : TrackerEvent
    /** 新订单（或订单内容变化）。suppressAnnouncement=true 时只刷新悬浮窗，不再语音播报。 */
    data class NewOffer(
        val offer: DeliveryOffer,
        val replaced: DeliveryOffer?,
        val suppressAnnouncement: Boolean,
    ) : TrackerEvent
    /** 订单弹窗消失：必须立即撤下结论。 */
    data class OfferGone(val offer: DeliveryOffer) : TrackerEvent
}
