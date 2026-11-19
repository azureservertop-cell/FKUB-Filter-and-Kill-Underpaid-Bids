package com.offerpilot.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.offerpilot.core.i18n.Lang
import java.util.Locale

/** Short spoken verdicts. QUEUE_FLUSH: a new verdict replaces the old one instead of queueing over navigation. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile private var lang: Lang = Lang.EN
    private var applied: Lang? = null

    /** Speech language follows the app language (English by default, Chinese optional). */
    fun setLanguage(l: Lang) {
        lang = l
        if (ready && applied != l) applyLanguage(l)
    }

    private fun applyLanguage(l: Lang) {
        val locale = if (l == Lang.ZH) Locale.SIMPLIFIED_CHINESE else Locale.CANADA
        val r = tts.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(if (l == Lang.ZH) Locale.getDefault() else Locale.US)
        }
        applied = l
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        applyLanguage(lang)
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
