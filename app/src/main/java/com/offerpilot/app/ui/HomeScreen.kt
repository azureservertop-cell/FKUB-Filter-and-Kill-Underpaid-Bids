package com.offerpilot.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.accessibility.MonitorState
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.OverlayPosition
import com.offerpilot.app.settings.SettingsRepository
import com.offerpilot.core.i18n.Lang
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun HomeScreen(s: AppSettings, repo: SettingsRepository, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MonitorState.status.collectAsStateWithLifecycle()
    val enabledInSystem = isServiceEnabled(ctx)
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { repo.update(f) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FKUB", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Lang.entries.forEach { l ->
                val selected = s.language == l
                if (selected) {
                    Button(onClick = {}, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(l.nativeName) }
                } else {
                    TextButton(onClick = { upd { it.copy(language = l) } }) { Text(l.nativeName) }
                }
            }
        }
        Text(tr("Filter & Kill Underpaid Bids · you decide what an offer is worth", "Filter & Kill Underpaid Bids · 筛掉低价单，报价好坏司机说了算"), style = MaterialTheme.typography.bodySmall)
        Text(tr("Recognition and advice only. It never accepts or rejects offers for you.", "只做识别与建议，不会替你接单或拒单。"), style = MaterialTheme.typography.bodySmall)

        TargetAppSection(s, repo)

        Section(tr("Status", "运行状态")) {
            Text(tr("Accessibility service: ", "无障碍服务：") + when {
                status.serviceConnected -> tr("on", "已开启")
                enabledInSystem -> tr("enabled in system, waiting to connect", "已在系统中开启，等待连接")
                else -> tr("off", "未开启")
            })
            Text(tr("Watching Uber Driver: ", "监测 Uber Driver：") + when {
                !status.serviceConnected -> "—"
                !s.monitoringEnabled -> tr("paused", "已暂停")
                status.targetInForeground -> tr("active (target app in foreground)", "正在监测（目标应用在前台）")
                else -> tr("standby (target app not in foreground)", "待命（目标应用不在前台）")
            })
            val last = status.lastRecognition
            Text(
                tr("Last recognition: ", "最近一次识别：") + (last?.let {
                    "${DateFormat.getTimeInstance().format(Date(it.timeMs))} · ${if (it.success) tr("OK", "成功") else tr("unknown", "无法判断")} · ${it.summary}"
                } ?: tr("none yet", "暂无"))
            )
            // When the last offer couldn't be judged, show what was read and why, to make misreads easy to report.
            if (last != null && !last.success) {
                Text(
                    last.detail + (status.lastAdapterNote?.let { "\n" + it } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status.serviceConnected) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(tr("Open accessibility settings", "打开系统无障碍设置"))
                }
                Text(
                    tr("Find FKUB in the list and turn it on. If the switch is greyed out on Android 13+ for a sideloaded app: ", "在列表中找到「FKUB · 订单判断」并开启。Android 13+ 侧载安装的应用如果开关是灰色的：") +
                        tr("Settings → Apps → FKUB → ⋮ (top right) → Allow restricted settings, then try again.", "系统设置 → 应用 → FKUB → 右上角 ⋮ → 允许受限设置，然后再开启。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SwitchRow(tr("Enable offer checks", "启用辅助判断"), s.monitoringEnabled, { v -> upd { it.copy(monitoringEnabled = v) } }, tr("When off, the service stays connected but doesn't recognize or alert", "关闭后服务保持连接但不识别、不提示"))
        }

        Section(tr("Alerts", "辅助设置")) {
            SwitchRow(tr("Spoken verdict", "语音播报"), s.ttsEnabled, { v -> upd { it.copy(ttsEnabled = v) } }, tr("After the sound, reads out price, distance and verdict", "音效之后读出金额、公里和结论"))
            Text(tr("Speech volume ${(s.ttsVolume * 100).roundToInt()}%", "语音音量 ${(s.ttsVolume * 100).roundToInt()}%"))
            Slider(value = s.ttsVolume, onValueChange = { v -> upd { it.copy(ttsVolume = v) } })
            SwitchRow(tr("Vibration", "震动"), s.vibrationEnabled, { v -> upd { it.copy(vibrationEnabled = v) } })
            SwitchRow(
                tr("Screen edge glow", "屏幕边缘发光"), s.edgeGlowEnabled, { v -> upd { it.copy(edgeGlowEnabled = v) } },
                tr("Blue = must take, green = good pay, yellow = meets target, red = below target", "蓝 = 无脑接，绿 = 比较赚，黄 = 刚好达标，红 = 不达标"),
            )
            SwitchRow(tr("Top pill (estimated net hourly)", "顶部提示条（预估净时薪）"), s.overlayEnabled, { v -> upd { it.copy(overlayEnabled = v) } })
            SwitchRow(
                tr("Screenshot recognition (OCR)", "截图识别（OCR）"), s.ocrEnabled, { v -> upd { it.copy(ocrEnabled = v) } },
                tr("Uber hides the offer text from accessibility, so it's read from a screenshot on the device. Screenshots are never saved or uploaded", "Uber 弹出订单时会隐藏文字，需要截图后在本机识别。截图不保存、不上传"),
            )
            SwitchRow(
                tr("Show the pill in the middle of the screen", "悬浮提示放在屏幕中间"), s.overlayPosition == OverlayPosition.CENTER,
                { v -> upd { it.copy(overlayPosition = if (v) OverlayPosition.CENTER else OverlayPosition.TOP) } },
                tr("Top by default so it doesn't cover the accept area at the bottom (the overlay never intercepts touches)", "默认在顶部，避免遮挡底部接单区域（悬浮层不可点击，不会拦截触摸）"),
            )
            Text(tr("Show the pill for ${s.overlaySeconds} s (hidden as soon as the offer disappears)", "悬浮提示显示 ${s.overlaySeconds} 秒（订单消失时立即隐藏）"))
            Slider(
                value = s.overlaySeconds.toFloat(), valueRange = 3f..20f, steps = 16,
                onValueChange = { v -> upd { it.copy(overlaySeconds = v.roundToInt()) } },
            )
            if (s.developerMode) {
                SwitchRow(tr("Debug mode", "调试模式"), s.debugMode, { v -> upd { it.copy(debugMode = v) } }, tr("Enables snapshot capture and other tools (see the Debug tab)", "启用快照抓取等调试工具（见「调试」页）"))
            }
        }

        SoundSection(s, repo)

        VersionFooter(s, repo)

    }
}

fun isServiceEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
}

/** Version line. Tapping it 7 times turns on developer options (the Debug tab), like Android's build number. */
@androidx.compose.runtime.Composable
private fun VersionFooter(s: AppSettings, repo: SettingsRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val version = androidx.compose.runtime.remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Text(
        tr("Version $version", "版本 $version"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp).clickable {
            if (s.developerMode) return@clickable
            taps++
            if (taps >= 7) {
                taps = 0
                scope.launch { repo.update { it.copy(developerMode = true) } }
                android.widget.Toast.makeText(ctx, if (s.language == Lang.ZH) "已开启开发者选项（调试页）" else "Developer options on (Debug tab)", android.widget.Toast.LENGTH_SHORT).show()
            } else if (taps >= 4) {
                android.widget.Toast.makeText(ctx, if (s.language == Lang.ZH) "再点 ${7 - taps} 次" else "${7 - taps} more taps", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )
}
