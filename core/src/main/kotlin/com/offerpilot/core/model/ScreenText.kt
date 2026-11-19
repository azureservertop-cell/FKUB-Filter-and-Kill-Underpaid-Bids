package com.offerpilot.core.model

/** 与 Android 无关的矩形，便于在 JVM 上测试。 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 从无障碍节点（或将来 OCR）读到的一段文字。
 * viewId / className 仅作参考，适配器不得依赖未经验证的控件 ID。
 */
data class TextNode(
    val text: String,
    val viewId: String? = null,
    val className: String? = null,
    val bounds: Box? = null,
    val depth: Int = 0,
    val fromContentDescription: Boolean = false,
)

/** 某一时刻目标应用屏幕上的全部可见文字。 */
data class ScreenText(
    val packageName: String,
    val nodes: List<TextNode>,
    val timestamp: Long,
    val source: OfferSource = OfferSource.ACCESSIBILITY,
)
