package com.offerpilot.app.settings

import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.rules.EvaluatorSettings

enum class OverlayPosition { TOP, CENTER }

/**
 * 某一档的音效。[customPath] 为 null 时用内置音效（黄色没有内置音效）。
 * 自定义文件会复制到应用私有目录，路径存在这里。
 */
data class TierSound(
    val enabled: Boolean = true,
    val volume: Float = 1.0f,
    val customPath: String? = null,
)

val SOUND_TIERS = listOf(Verdict.GREAT, Verdict.GOOD, Verdict.MIN_WAGE, Verdict.LOSS)

val DEFAULT_TIER_SOUNDS: Map<Verdict, TierSound> = mapOf(
    Verdict.GREAT to TierSound(),
    Verdict.GOOD to TierSound(),
    Verdict.MIN_WAGE to TierSound(enabled = false),
    Verdict.LOSS to TierSound(),
)

/** 全部用户设置（本地 DataStore 持久化）。 */
data class AppSettings(
    val evaluator: EvaluatorSettings = EvaluatorSettings(),
    /** 主开关：暂停时服务保持连接但不识别、不提示。 */
    val monitoringEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    /** 档位音效总开关：蓝 气笛、绿 金币、黄 无、红 拉稀。 */
    val soundEnabled: Boolean = true,
    /** 各档音效设置（自定义文件、音量、开关）。 */
    val tierSounds: Map<Verdict, TierSound> = DEFAULT_TIER_SOUNDS,
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
