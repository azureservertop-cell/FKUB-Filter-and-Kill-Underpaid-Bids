package com.offerpilot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offerpilot.app.feedback.SoundPlayer
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.DEFAULT_TIER_SOUNDS
import com.offerpilot.app.settings.SOUND_TIERS
import com.offerpilot.app.settings.SettingsRepository
import com.offerpilot.app.settings.TierSound
import com.offerpilot.core.i18n.Lang
import com.offerpilot.core.model.Verdict
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun tierTitle(v: Verdict, lang: Lang) = when (v) {
    Verdict.GREAT -> "🔵 "
    Verdict.GOOD -> "🟢 "
    Verdict.MIN_WAGE -> "🟡 "
    Verdict.LOSS -> "🔴 "
    Verdict.UNKNOWN -> ""
} + v.label(lang)

private fun builtinName(v: Verdict, lang: Lang) = when (v) {
    Verdict.GREAT -> lang.t("Built-in: air horn", "内置：气笛嘟嘟嘟")
    Verdict.GOOD -> lang.t("Built-in: coin", "内置：金币")
    Verdict.MIN_WAGE -> lang.t("Built-in: “Ready”", "内置：“Ready”")
    Verdict.LOSS -> lang.t("Built-in: wet fart", "内置：拉稀")
    else -> lang.t("No built-in sound; pick a file", "无内置音效，可选择文件")
}

/** Sound settings: master switch + per-tier toggle, volume, preview, change file, reset. */
@Composable
fun SoundSection(s: AppSettings, repo: SettingsRepository) {
    val ctx = LocalContext.current
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    val player = remember { SoundPlayer(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(s.tierSounds) { player.prepare(s.tierSounds) }

    var pickingFor by remember { mutableStateOf<Verdict?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val v = pickingFor
        pickingFor = null
        if (uri != null && v != null) {
            val path = SoundPlayer.importFile(ctx, uri, v)
            if (path != null) scope.launch {
                repo.update { it.copy(tierSounds = it.tierSounds + (v to it.tierSounds.getValue(v).copy(customPath = path, enabled = true))) }
            }
        }
    }

    fun updTier(v: Verdict, f: (TierSound) -> TierSound) = scope.launch {
        repo.update { it.copy(tierSounds = it.tierSounds + (v to f(it.tierSounds[v] ?: DEFAULT_TIER_SOUNDS.getValue(v)))) }
    }

    Section(tr("Sounds", "音效")) {
        SwitchRow(tr("Enable sounds", "启用音效"), s.soundEnabled, { v -> scope.launch { repo.update { it.copy(soundEnabled = v) } } }, tr("Plays per tier when an offer appears", "订单出现时按档位播放"))
        SOUND_TIERS.forEach { v ->
            val t = s.tierSounds[v] ?: DEFAULT_TIER_SOUNDS.getValue(v)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tierTitle(v, lang), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (t.customPath != null) tr("Custom file", "自定义文件") else builtinName(v, lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = t.enabled, onCheckedChange = { on -> updTier(v) { it.copy(enabled = on) } }, enabled = s.soundEnabled)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Volume ${(t.volume * 100).roundToInt()}%", "音量 ${(t.volume * 100).roundToInt()}%"), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = t.volume, onValueChange = { vol -> updTier(v) { it.copy(volume = vol) } },
                        modifier = Modifier.weight(1f), enabled = s.soundEnabled && t.enabled,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { player.play(v, t.copy(enabled = true)) },
                        enabled = player.hasSound(v, t),
                    ) { Text(tr("Preview", "试听")) }
                    TextButton(onClick = { pickingFor = v; picker.launch(arrayOf("audio/*")) }) { Text(tr("Change file", "换文件")) }
                    if (t.customPath != null) {
                        TextButton(onClick = {
                            SoundPlayer.deleteCustom(ctx, v)
                            updTier(v) { DEFAULT_TIER_SOUNDS.getValue(v).copy(volume = it.volume) }
                        }) { Text(tr("Reset", "恢复默认")) }
                    }
                }
            }
        }
    }
}
