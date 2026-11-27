package com.offerpilot.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import com.offerpilot.app.R
import com.offerpilot.app.settings.TierSound
import com.offerpilot.core.model.Verdict
import java.io.File

/**
 * Per-tier sound effects. Built-in clips come from free asset packs (see SOUND_CREDITS.md):
 *  - 🔵 must-take: meme air horn      - 🟢 good: coin
 *  - 🟡 meets target: silent by default - 🔴 below target: wet fart
 * Every tier can be replaced with the user's own audio file (copied into app-private storage).
 */
class SoundPlayer(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val loaded = HashSet<Int>()
    /** Built-in sounds: resource id → (SoundPool id, duration ms). */
    private val builtin = HashMap<Verdict, Pair<Int, Long>>()
    /** Custom file path → (SoundPool id, duration ms). */
    private val custom = HashMap<String, Pair<Int, Long>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        builtin[Verdict.GREAT] = pool.load(context, R.raw.sfx_horn, 1) to 1100L
        builtin[Verdict.GOOD] = pool.load(context, R.raw.sfx_coin, 1) to 550L
        builtin[Verdict.MIN_WAGE] = pool.load(context, R.raw.sfx_ready, 1) to 600L
        builtin[Verdict.LOSS] = pool.load(context, R.raw.sfx_poop, 1) to 1700L
    }

    /** Preloads custom files (call when settings change) so the first play isn't missed. */
    fun prepare(sounds: Map<Verdict, TierSound>) {
        sounds.values.mapNotNull { it.customPath }.forEach { path ->
            if (path !in custom && File(path).exists()) custom[path] = pool.load(path, 1) to durationMs(path)
        }
    }

    /** Plays the tier's sound and returns its duration in ms; 0 if the tier is off, has no sound or isn't loaded yet. */
    fun play(verdict: Verdict, cfg: TierSound?): Long {
        if (cfg == null || !cfg.enabled) return 0L
        val (id, ms) = cfg.customPath?.let { path ->
            custom[path] ?: if (File(path).exists()) (pool.load(path, 1) to durationMs(path)).also { custom[path] = it } else null
        } ?: builtin[verdict] ?: return 0L
        if (synchronized(loaded) { id !in loaded }) return 0L
        val v = cfg.volume.coerceIn(0f, 1f)
        pool.stop(lastStream)
        lastStream = pool.play(id, v, v, 1, 0, 1f)
        return if (lastStream != 0) ms.coerceAtMost(MAX_SPEECH_DELAY_MS) else 0L
    }

    private var lastStream = 0

    fun hasSound(verdict: Verdict, cfg: TierSound?): Boolean = cfg?.customPath != null || builtin.containsKey(verdict)

    fun stop() = pool.stop(lastStream)

    fun release() = pool.release()

    private fun durationMs(path: String): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
        }
    }.getOrDefault(1000L)

    companion object {
        /** However long a custom sound is, speech starts after at most this long. */
        const val MAX_SPEECH_DELAY_MS = 2500L

        /** Copies the audio file the user picked into app-private storage and returns the new path. */
        fun importFile(context: Context, uri: Uri, verdict: Verdict): String? = runCatching {
            val dir = File(context.filesDir, "sounds").apply { mkdirs() }
            dir.listFiles()?.filter { it.name.startsWith(verdict.name + "-") }?.forEach { it.delete() }
            val out = File(dir, "${verdict.name}-${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            out.absolutePath
        }.onFailure { Log.w("SoundPlayer", "import failed", it) }.getOrNull()

        fun deleteCustom(context: Context, verdict: Verdict) {
            File(context.filesDir, "sounds").listFiles()?.filter { it.name.startsWith(verdict.name + "-") }?.forEach { it.delete() }
        }
    }
}
