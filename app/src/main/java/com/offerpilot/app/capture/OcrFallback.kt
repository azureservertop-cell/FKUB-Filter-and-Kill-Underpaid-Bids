package com.offerpilot.app.capture

import com.offerpilot.core.model.ScreenText

/**
 * OCR 兜底接口（PRD 2.2 第二优先级，Phase 2）。
 *
 * 只有在 Phase 0 真机快照确认报价/距离/时间无法从无障碍节点读取时才实现：
 * AccessibilityService.takeScreenshot()（Android 14+ 可用 takeScreenshotOfWindow）
 * → 裁剪报价区域 → ML Kit 本地文字识别 → ScreenText(source = OCR) → 同一个适配器。
 * 第一版不包含 OCR 依赖，也不做循环截图。
 */
interface OcrFallback {
    suspend fun recognize(packageName: String): ScreenText?
}

object DisabledOcr : OcrFallback {
    override suspend fun recognize(packageName: String): ScreenText? = null
}
