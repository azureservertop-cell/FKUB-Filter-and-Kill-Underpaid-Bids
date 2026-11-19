package com.offerpilot.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.accessibility.MonitorState
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.OverlayPosition
import com.offerpilot.app.settings.SettingsRepository
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
        Text("FKUB", style = MaterialTheme.typography.headlineMedium)
        Text("Filter & Kill Underpaid Bids · 筛掉低价单，报价好坏司机说了算", style = MaterialTheme.typography.bodySmall)
        Text("只做识别与建议，不会替你接单或拒单。", style = MaterialTheme.typography.bodySmall)

        Section("运行状态") {
            Text("无障碍服务：" + when {
                status.serviceConnected -> "已开启"
                enabledInSystem -> "已在系统中开启，等待连接"
                else -> "未开启"
            })
            Text("监测 Uber Driver：" + when {
                !status.serviceConnected -> "—"
                !s.monitoringEnabled -> "已暂停"
                status.targetInForeground -> "正在监测（目标应用在前台）"
                else -> "待命（目标应用不在前台）"
            })
            val last = status.lastRecognition
            Text(
                "最近一次识别：" + (last?.let {
                    "${DateFormat.getTimeInstance().format(Date(it.timeMs))} · ${if (it.success) "成功" else "无法判断"} · ${it.summary}"
                } ?: "暂无")
            )
            if (!status.serviceConnected) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("打开系统无障碍设置")
                }
                Text(
                    "在列表中找到「FKUB · 订单判断」并开启。Android 13+ 侧载安装的应用如果开关是灰色的：" +
                        "系统设置 → 应用 → FKUB → 右上角 ⋮ → 允许受限设置，然后再开启。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SwitchRow("启用辅助判断", s.monitoringEnabled, { v -> upd { it.copy(monitoringEnabled = v) } }, "关闭后服务保持连接但不识别、不提示")
        }

        Section("辅助设置") {
            SwitchRow("语音播报", s.ttsEnabled, { v -> upd { it.copy(ttsEnabled = v) } }, "音效之后读出金额、公里和结论")
            Text("语音音量 ${(s.ttsVolume * 100).roundToInt()}%")
            Slider(value = s.ttsVolume, onValueChange = { v -> upd { it.copy(ttsVolume = v) } })
            SwitchRow("震动", s.vibrationEnabled, { v -> upd { it.copy(vibrationEnabled = v) } })
            SwitchRow(
                "屏幕边缘发光", s.edgeGlowEnabled, { v -> upd { it.copy(edgeGlowEnabled = v) } },
                "蓝 = 无脑接，绿 = 比较赚，黄 = 刚好达标，红 = 不达标",
            )
            SwitchRow("顶部提示条（预估净时薪）", s.overlayEnabled, { v -> upd { it.copy(overlayEnabled = v) } })
            SwitchRow(
                "截图识别（OCR）", s.ocrEnabled, { v -> upd { it.copy(ocrEnabled = v) } },
                "Uber 弹出订单时会隐藏文字，需要截图后在本机识别。截图不保存、不上传",
            )
            SwitchRow(
                "悬浮提示放在屏幕中间", s.overlayPosition == OverlayPosition.CENTER,
                { v -> upd { it.copy(overlayPosition = if (v) OverlayPosition.CENTER else OverlayPosition.TOP) } },
                "默认在顶部，避免遮挡底部接单区域（悬浮层不可点击，不会拦截触摸）",
            )
            Text("悬浮提示显示 ${s.overlaySeconds} 秒（订单消失时立即隐藏）")
            Slider(
                value = s.overlaySeconds.toFloat(), valueRange = 3f..20f, steps = 16,
                onValueChange = { v -> upd { it.copy(overlaySeconds = v.roundToInt()) } },
            )
            SwitchRow("调试模式", s.debugMode, { v -> upd { it.copy(debugMode = v) } }, "启用快照抓取等调试工具（见「调试」页）")
        }

        SoundSection(s, repo)

    }
}

fun isServiceEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
}
