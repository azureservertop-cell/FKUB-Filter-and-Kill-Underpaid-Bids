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
 * 档位音效。内置音效全部为程序合成（见 tools/make_sounds.py）：
 *  - 🔵 无脑接：气笛"嘟嘟嘟"   - 🟢 比较赚：金币
 *  - 🟡 刚好达标：默认无音效    - 🔴 不达标：拉稀
 * 每一档都可以换成用户自己的音频文件（复制到应用私有目录）。
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
    /** 内置音效：资源 id → (SoundPool id, 时长 ms)。 */
    private val builtin = HashMap<Verdict, Pair<Int, Long>>()
    /** 自定义文件路径 → (SoundPool id, 时长 ms)。 */
    private val custom = HashMap<String, Pair<Int, Long>>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        builtin[Verdict.GREAT] = pool.load(context, R.raw.sfx_horn, 1) to 1100L
        builtin[Verdict.GOOD] = pool.load(context, R.raw.sfx_coin, 1) to 550L
        builtin[Verdict.LOSS] = pool.load(context, R.raw.sfx_poop, 1) to 1700L
    }

    /** 预加载自定义文件（设置变化时调用），避免第一次播放时还没加载好。 */
    fun prepare(sounds: Map<Verdict, TierSound>) {
        sounds.values.mapNotNull { it.customPath }.forEach { path ->
            if (path !in custom && File(path).exists()) custom[path] = pool.load(path, 1) to durationMs(path)
        }
    }

    /** 播放该档位音效，返回时长（毫秒）；该档关闭、没有音效或尚未加载时返回 0。 */
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
        /** 自定义音效再长，语音最多等这么久就开始说。 */
        const val MAX_SPEECH_DELAY_MS = 2500L

        /** 把用户选的音频复制到应用私有目录，返回新路径。 */
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
