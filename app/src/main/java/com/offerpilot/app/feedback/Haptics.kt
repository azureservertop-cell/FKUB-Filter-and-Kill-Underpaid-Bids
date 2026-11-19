package com.offerpilot.app.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.offerpilot.core.model.Verdict

/** Vibration per tier: blue three pulses, green one, yellow two, red one long; none when unknown. */
class Haptics(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun play(verdict: Verdict) {
        if (!vibrator.hasVibrator()) return
        val effect = when (verdict) {
            Verdict.GREAT -> VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80, 80, 80), -1)
            Verdict.GOOD -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            Verdict.MIN_WAGE -> VibrationEffect.createWaveform(longArrayOf(0, 100, 120, 100), -1)
            Verdict.LOSS -> VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)
            Verdict.UNKNOWN -> return
        }
        vibrator.vibrate(effect)
    }
}
