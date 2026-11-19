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
import com.offerpilot.core.model.Verdict
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun tierTitle(v: Verdict) = when (v) {
    Verdict.GREAT -> "🔵 无脑接"
    Verdict.GOOD -> "🟢 比较赚"
    Verdict.MIN_WAGE -> "🟡 刚好达标"
    Verdict.LOSS -> "🔴 不达标"
    Verdict.UNKNOWN -> "无法识别"
}

private fun builtinName(v: Verdict) = when (v) {
    Verdict.GREAT -> "内置：气笛嘟嘟嘟"
    Verdict.GOOD -> "内置：金币"
    Verdict.LOSS -> "内置：拉稀"
    else -> "无内置音效，可选择文件"
}

/** 音效设置：总开关 + 每档开关、音量、试听、换文件、恢复默认。 */
@Composable
fun SoundSection(s: AppSettings, repo: SettingsRepository) {
    val ctx = LocalContext.current
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

    Section("音效") {
        SwitchRow("启用音效", s.soundEnabled, { v -> scope.launch { repo.update { it.copy(soundEnabled = v) } } }, "订单出现时按档位播放，之后才播语音")
        SOUND_TIERS.forEach { v ->
            val t = s.tierSounds[v] ?: DEFAULT_TIER_SOUNDS.getValue(v)
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tierTitle(v), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (t.customPath != null) "自定义文件" else builtinName(v),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = t.enabled, onCheckedChange = { on -> updTier(v) { it.copy(enabled = on) } }, enabled = s.soundEnabled)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音量 ${(t.volume * 100).roundToInt()}%", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = t.volume, onValueChange = { vol -> updTier(v) { it.copy(volume = vol) } },
                        modifier = Modifier.weight(1f), enabled = s.soundEnabled && t.enabled,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { player.play(v, t.copy(enabled = true)) },
                        enabled = player.hasSound(v, t),
                    ) { Text("试听") }
                    TextButton(onClick = { pickingFor = v; picker.launch(arrayOf("audio/*")) }) { Text("换文件") }
                    if (t.customPath != null) {
                        TextButton(onClick = {
                            SoundPlayer.deleteCustom(ctx, v)
                            updTier(v) { DEFAULT_TIER_SOUNDS.getValue(v).copy(volume = it.volume) }
                        }) { Text("恢复默认") }
                    }
                }
            }
        }
    }
}
