package com.offerpilot.app.action

import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult

/**
 * 预留的动作接口（PRD 七，阶段 C）。
 *
 * 第一版 **不实现** 任何自动接单 / 拒单 / 点击。唯一的实现是 [NoOpOfferActionHandler]。
 * 本服务的无障碍配置也未声明手势能力。是否开发自动操作须在确认平台规则、
 * 驾驶安全与误操作风险之后单独决定。
 */
interface OfferActionHandler {
    /** 返回是否执行了操作。 */
    fun onEvaluated(offer: DeliveryOffer, result: EvaluationResult): Boolean
}

object NoOpOfferActionHandler : OfferActionHandler {
    override fun onEvaluated(offer: DeliveryOffer, result: EvaluationResult): Boolean = false
}
