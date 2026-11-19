package com.offerpilot.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.accessibility.MonitorState
import com.offerpilot.app.accessibility.OfferMonitorService
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DebugScreen(s: AppSettings, repo: SettingsRepository, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MonitorState.status.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<String?>(null) }
    var targetsText by remember(s.targetPackages) { mutableStateOf(s.targetPackages.joinToString(",")) }
    val dir = File(ctx.getExternalFilesDir(null), "snapshots")
    val files = remember(refresh, status.lastSnapshotPath) {
        dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }.orEmpty()
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.snapshotCountdownUntil) {
        while (status.snapshotCountdownUntil != null) { now = System.currentTimeMillis(); delay(250) }
    }
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { repo.update(f) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!s.debugMode) {
            Section("调试模式未开启") {
                Text("快照只在你主动操作时保存，只包含目标应用窗口的控件文字和位置，保存在本机，不会上传。")
                Button(onClick = { upd { it.copy(debugMode = true) } }) { Text("开启调试模式") }
            }
            return@Column
        }

        Section("目标应用") {
            androidx.compose.material3.OutlinedTextField(
                targetsText, { targetsText = it },
                label = { Text("包名（逗号分隔）") }, singleLine = true,
            )
            Text("默认 com.ubercab.driver（Uber Driver 公开包名，待真机确认）。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val set = targetsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                if (set.isNotEmpty()) upd { it.copy(targetPackages = set) }
            }) { Text("保存") }
            Text("最近适配器状态：${status.lastAdapterNote ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }

        Section("抓取控件快照（Phase 0 可行性验证）") {
            Text(
                "三种方式任选：\n" +
                    "1. 点下面的「10 秒后抓取」，然后切到 Uber 订单弹窗；\n" +
                    "2. 调试模式下导航栏/无障碍快捷菜单会出现无障碍按钮，订单弹出时点一下；\n" +
                    "3. 打开「自动快照」，识别到疑似订单时自动保存（最多 30 份）。\n" +
                    "请在停车时操作。",
                style = MaterialTheme.typography.bodySmall,
            )
            val until = status.snapshotCountdownUntil
            Button(
                onClick = { OfferMonitorService.instance?.scheduleSnapshot(10_000) },
                enabled = status.serviceConnected && until == null,
            ) {
                Text(
                    when {
                        !status.serviceConnected -> "需先开启无障碍服务"
                        until != null -> "将在 ${((until - now) / 1000 + 1).coerceAtLeast(0)} 秒后抓取…"
                        else -> "10 秒后抓取"
                    }
                )
            }
            SwitchRow("自动快照", s.autoSnapshot, { v -> upd { it.copy(autoSnapshot = v) } })
            SwitchRow("快照附带截图", s.snapshotScreenshot, { v -> upd { it.copy(snapshotScreenshot = v) } }, "默认关闭。截图可能包含顾客地址，分享前请检查")
        }

        Section("已保存的快照（${files.size}）") {
            Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall)
            files.take(30).forEach { f ->
                Column {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { preview = summarize(f) }) { Text("查看文字") }
                        TextButton(onClick = { share(ctx, f) }) { Text("分享") }
                        TextButton(onClick = {
                            f.delete(); File(f.parentFile, f.nameWithoutExtension + ".png").delete(); refresh++
                        }) { Text("删除") }
                    }
                }
            }
            if (files.isNotEmpty()) {
                OutlinedButton(onClick = { dir.listFiles()?.forEach { it.delete() }; refresh++; preview = null }) { Text("全部删除") }
            }
        }

        preview?.let {
            Section("快照中的文字") {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 列出快照中目标窗口的所有文字及 id/bounds，方便直接看出报价、距离、时间是否可读。 */
private fun summarize(f: File): String = runCatching {
    val root = org.json.JSONObject(f.readText())
    val sb = StringBuilder()
    sb.appendLine("reason=${root.optString("reason")} hasTarget=${root.optBoolean("hasTargetWindow")}")
    root.optJSONObject("adapter")?.let { sb.appendLine("adapter: ${it.optString("note")}") }
    val windows = root.getJSONArray("windows")
    for (i in 0 until windows.length()) {
        val w = windows.getJSONObject(i)
        sb.appendLine("— window ${w.optString("windowType")} pkg=${w.optString("package")} ${w.optString("bounds")}")
        w.optJSONObject("tree")?.let { walk(it, sb) }
    }
    sb.toString()
}.getOrElse { "无法读取：${it.message}" }

private fun walk(n: org.json.JSONObject, sb: StringBuilder) {
    val t = n.optString("text").takeIf { it.isNotEmpty() }
    val d = n.optString("desc").takeIf { it.isNotEmpty() }
    if (t != null || d != null) {
        sb.append("  ".repeat(n.optInt("depth").coerceAtMost(12)))
        t?.let { sb.append("\"$it\" ") }
        d?.let { sb.append("[desc:$it] ") }
        n.optString("id").takeIf { it.isNotEmpty() }?.let { sb.append("#$it ") }
        sb.appendLine(n.optString("bounds"))
    }
    val c = n.optJSONArray("children") ?: return
    for (i in 0 until c.length()) walk(c.getJSONObject(i), sb)
}

private fun share(ctx: android.content.Context, f: File) {
    val uris = arrayListOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
    val png = File(f.parentFile, f.nameWithoutExtension + ".png")
    if (png.exists()) uris += FileProvider.getUriForFile(ctx, ctx.packageName + ".files", png)
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "分享快照").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
