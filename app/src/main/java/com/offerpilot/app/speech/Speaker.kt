package com.offerpilot.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 简短语音播报。QUEUE_FLUSH：新结论覆盖旧结论，不排队打断导航。 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setSpeechRate(1.15f)
        ready = true
    }

    fun speak(text: String, volume: Float) {
        if (!ready) return
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "offer")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        ready = false
        tts.shutdown()
    }
}
