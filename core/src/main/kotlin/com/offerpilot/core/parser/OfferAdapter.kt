package com.offerpilot.core.parser

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.ScreenText

/**
 * 屏幕文字 → 订单的适配器。Uber 改版时只替换/调整适配器，计算引擎不动。
 */
interface OfferAdapter {
    val id: String
    /** 该适配器的规则是否已经在真机快照上验证过。 */
    val verified: Boolean
    fun extract(screen: ScreenText): AdapterResult
}

sealed interface AdapterResult {
    /** 当前屏幕没有订单弹窗。 */
    data object NoOffer : AdapterResult

    /** 检测到订单弹窗（字段可能不完整）。 */
    data class Offer(val offer: DeliveryOffer, val complete: Boolean) : AdapterResult
}
