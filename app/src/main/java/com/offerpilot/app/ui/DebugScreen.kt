package com.offerpilot.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.offerpilot.app.debug.SnapshotStore
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
    val sessions = remember(refresh, status.lastSnapshotPath, status.recording?.sessionName) {
        dir.listFiles()?.filter { it.isDirectory && it.name.startsWith("session-") }?.sortedByDescending { it.name }.orEmpty()
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.snapshotCountdownUntil, status.recording != null) {
        while (status.snapshotCountdownUntil != null || status.recording != null) { now = System.currentTimeMillis(); delay(500) }
    }
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { repo.update(f) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
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

        Section("持续抓取（Phase 0 可行性验证）") {
            val rec = status.recording
            Text(
                "点「开始抓取」后切到 Uber Driver 正常跑单，Uber 画面上的文字每变化一次就自动存一份快照（最快每秒一份，地图移动不会重复保存）。" +
                    "回到这里点「结束抓取」，会自动打包成一个 zip，点「分享」发给我即可。抓取期间屏幕左上角有红色小标记。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rec == null) {
                Button(
                    onClick = { OfferMonitorService.instance?.startRecording() },
                    enabled = status.serviceConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (status.serviceConnected) "开始抓取" else "需先开启无障碍服务") }
            } else {
                val mins = ((now - rec.startedAt) / 60_000).coerceAtLeast(0)
                Text("抓取中：已保存 ${rec.count} 份（上限 ${rec.maxCount}），已进行 $mins 分钟", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { OfferMonitorService.instance?.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("结束抓取") }
            }
            SwitchRow("快照附带截图", s.snapshotScreenshot, { v -> upd { it.copy(snapshotScreenshot = v) } }, "默认关闭。截图能帮助对照，但会占空间，也可能包含顾客地址")
        }

        Section("其他抓取方式") {
            Text(
                "• 10 秒后抓取一份；\n• 开启调试模式后，点系统的无障碍按钮抓取一份；\n• 开启调试模式 + 自动快照：识别到疑似订单时自动保存（最多 30 份）。\n请在停车时操作。",
                style = MaterialTheme.typography.bodySmall,
            )
            val until = status.snapshotCountdownUntil
            OutlinedButton(
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
        }

        Section("抓取记录") {
            Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall)
            sessions.forEach { d ->
                val n = d.listFiles()?.count { it.extension == "json" } ?: 0
                val recordingThis = status.recording?.sessionName == d.name
                Column {
                    Text("${d.name}（$n 份${if (recordingThis) "，抓取中" else ""}）", style = MaterialTheme.typography.bodyMedium)
                    if (!recordingThis) {
                        Row {
                            TextButton(onClick = {
                                val zip = File(dir, d.name + ".zip").takeIf { it.exists() } ?: SnapshotStore.zipSession(d)
                                share(ctx, zip)
                            }) { Text("分享 zip") }
                            TextButton(onClick = { d.listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.name }?.let { preview = summarize(it) } }) { Text("看最后一份") }
                            TextButton(onClick = { d.deleteRecursively(); File(dir, d.name + ".zip").delete(); refresh++ }) { Text("删除") }
                        }
                    }
                }
            }
            files.take(30).forEach { f ->
                Column {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { preview = summarize(f) }) { Text("查看文字") }
                        TextButton(onClick = { share(ctx, f) }) { Text("分享") }
                        TextButton(onClick = {
                            f.delete(); File(f.parentFile, f.nameWithoutExtension + ".png").delete(); refresh++
                        }) { Text("删除") }
                    }
                }
            }
            if (sessions.isEmpty() && files.isEmpty()) Text("暂无记录")
            if ((sessions.isNotEmpty() || files.isNotEmpty()) && status.recording == null) {
                OutlinedButton(onClick = { dir.listFiles()?.forEach { it.deleteRecursively() }; refresh++; preview = null }) { Text("全部删除") }
            }
        }

        preview?.let {
            Section("快照中的文字") {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Lists every text in the snapshot's target windows with id/bounds, to see at a glance whether price, distance and time are readable. */
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
    if (f.extension == "json" && png.exists()) uris += FileProvider.getUriForFile(ctx, ctx.packageName + ".files", png)
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "分享快照").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
