package com.offerpilot.app.settings

import com.offerpilot.core.i18n.Lang
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.rules.EvaluatorSettings

enum class OverlayPosition { TOP, CENTER }

/**
 * Sound settings for one tier. A null [customPath] uses the built-in sound (yellow has none).
 * Custom files are copied into app-private storage and their path is stored here.
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

/** All user settings (persisted locally in DataStore). */
data class AppSettings(
    val evaluator: EvaluatorSettings = EvaluatorSettings(),
    /** App, overlay and speech language. English by default. */
    val language: Lang = Lang.EN,
    /** Master switch: when paused the service stays connected but doesn't recognize or alert. */
    val monitoringEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    /** Master switch for tier sounds: blue air horn, green coin, yellow none, red fart. */
    val soundEnabled: Boolean = true,
    /** Per-tier sound settings (custom file, volume, on/off). */
    val tierSounds: Map<Verdict, TierSound> = DEFAULT_TIER_SOUNDS,
    val vibrationEnabled: Boolean = true,
    val overlayEnabled: Boolean = true,
    /** Screen edge glow (blue / green / yellow / red). */
    val edgeGlowEnabled: Boolean = true,
    /** When nodes don't contain the offer (Uber hides the popup), recognize it via accessibility screenshot + on-device OCR. */
    val ocrEnabled: Boolean = true,
    /** 0.0–1.0 */
    val ttsVolume: Float = 1.0f,
    val overlayPosition: OverlayPosition = OverlayPosition.TOP,
    val overlaySeconds: Int = 8,
    val debugMode: Boolean = false,
    /** In debug mode, automatically save a node snapshot when a likely offer is detected. */
    val autoSnapshot: Boolean = false,
    /** Whether snapshots include a screenshot. Off by default (PRD 10.4: screenshots are not persisted by default). */
    val snapshotScreenshot: Boolean = false,
    val targetPackages: Set<String> = UberAdapterConfig().targetPackages,
)
