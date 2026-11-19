package com.offerpilot.app.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.offerpilot.core.model.Verdict

/** 不同结论用不同震动节奏：推荐一下、勉强两下、不推荐长震、无法判断不震。 */
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
            Verdict.RECOMMENDED -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            Verdict.MARGINAL -> VibrationEffect.createWaveform(longArrayOf(0, 100, 120, 100), -1)
            Verdict.NOT_RECOMMENDED -> VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)
            Verdict.UNKNOWN -> return
        }
        vibrator.vibrate(effect)
    }
}
