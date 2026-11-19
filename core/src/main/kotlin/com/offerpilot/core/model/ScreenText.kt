package com.offerpilot.core.model

/** Android-independent rectangle, so it can be tested on the JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A piece of text read from an accessibility node or from OCR.
 * viewId / className are informational only; adapters must not rely on unverified view IDs.
 */
data class TextNode(
    val text: String,
    val viewId: String? = null,
    val className: String? = null,
    val bounds: Box? = null,
    val depth: Int = 0,
    val fromContentDescription: Boolean = false,
)

/** All visible text of the target app's screen at one moment. */
data class ScreenText(
    val packageName: String,
    val nodes: List<TextNode>,
    val timestamp: Long,
    val source: OfferSource = OfferSource.ACCESSIBILITY,
)
