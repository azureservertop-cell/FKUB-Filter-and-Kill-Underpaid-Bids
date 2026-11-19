package com.offerpilot.app.settings

import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.rules.EvaluatorSettings

enum class OverlayPosition { TOP, CENTER }

/** 全部用户设置（本地 DataStore 持久化）。 */
data class AppSettings(
    val evaluator: EvaluatorSettings = EvaluatorSettings(),
    /** 主开关：暂停时服务保持连接但不识别、不提示。 */
    val monitoringEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val overlayEnabled: Boolean = true,
    /** 屏幕边缘荧光（蓝绿黄红）。 */
    val edgeGlowEnabled: Boolean = true,
    /** 节点读不到订单（Uber 隐藏了弹窗内容）时，用无障碍截图 + 本地 OCR 识别。 */
    val ocrEnabled: Boolean = true,
    /** 0.0–1.0 */
    val ttsVolume: Float = 1.0f,
    val overlayPosition: OverlayPosition = OverlayPosition.TOP,
    val overlaySeconds: Int = 8,
    val debugMode: Boolean = false,
    /** 调试模式下，检测到疑似订单时自动保存控件快照。 */
    val autoSnapshot: Boolean = false,
    /** 快照是否附带截图。默认关闭（PRD 10.4：默认不持久化截图）。 */
    val snapshotScreenshot: Boolean = false,
    val targetPackages: Set<String> = UberAdapterConfig().targetPackages,
)
