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
        Text(
            tr("Developer options. Snapshots and logs saved here can contain customer addresses shown on screen: keep them on your phone, share them only with the developer, never publicly, and delete them afterwards.",
                "开发者选项。这里保存的快照和记录可能包含屏幕上的顾客地址：只保存在手机上，只发给开发者，不要公开分享，用完请删除。"),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = {
            upd { it.copy(developerMode = false, debugMode = false, autoSnapshot = false, logUnreadable = false, snapshotScreenshot = false) }
        }) { Text(tr("Turn off developer options", "关闭开发者选项")) }
        Section(tr("Target app (advanced)", "目标应用（高级）")) {
            androidx.compose.material3.OutlinedTextField(
                targetsText, { targetsText = it },
                label = { Text(tr("Package names (comma-separated)", "包名（逗号分隔）")) }, singleLine = true,
            )
            Text(tr("Usually picked from the app list on the Status tab. Type package names here only for apps that list doesn't show.", "一般在「状态」页的应用列表里选。只有列表里找不到的应用才需要在这里手动填包名。"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val set = targetsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                upd { it.copy(targetPackages = set) }
            }) { Text(tr("Save", "保存")) }
            Text(tr("Last adapter status: ${status.lastAdapterNote ?: "—"}", "最近适配器状态：${status.lastAdapterNote ?: "—"}"), style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("Continuous capture (Phase 0 feasibility check)", "持续抓取（Phase 0 可行性验证）")) {
            val rec = status.recording
            Text(
                tr("Tap “Start capture”, then switch to Uber Driver and drive as usual. A snapshot is saved every time Uber's on-screen text changes (at most one per second; map movement doesn't create duplicates). ", "点「开始抓取」后切到 Uber Driver 正常跑单，Uber 画面上的文字每变化一次就自动存一份快照（最快每秒一份，地图移动不会重复保存）。") +
                    tr("Come back and tap “Stop capture” to pack everything into one zip, then tap “Share”. A small red badge shows in the top-left corner while capturing.", "回到这里点「结束抓取」，会自动打包成一个 zip，点「分享」发给我即可。抓取期间屏幕左上角有红色小标记。"),
                style = MaterialTheme.typography.bodySmall,
            )
            if (rec == null) {
                Button(
                    onClick = { OfferMonitorService.instance?.startRecording() },
                    enabled = status.serviceConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (status.serviceConnected) tr("Start capture", "开始抓取") else tr("Turn on the accessibility service first", "需先开启无障碍服务")) }
            } else {
                val mins = ((now - rec.startedAt) / 60_000).coerceAtLeast(0)
                Text(tr("Capturing: ${rec.count} saved (limit ${rec.maxCount}), $mins min so far", "抓取中：已保存 ${rec.count} 份（上限 ${rec.maxCount}），已进行 $mins 分钟"), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { OfferMonitorService.instance?.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Stop capture", "结束抓取")) }
            }
            SwitchRow(
                tr("Log unreadable offers", "无法识别时自动保存记录"), s.logUnreadable, { v -> upd { it.copy(logUnreadable = v) } },
                tr(
                    "Every time an offer shows \"Unreadable\", saves what was read and why into session-unreadable below (newest 100). Share that zip to get it fixed. Stays on the phone. The saved text can include the pickup and drop-off addresses shown on the card, so turn it off when you don't need it.",
                    "每次显示“无法识别”时，把读到的文字和原因存到下面的 session-unreadable（保留最近 100 份）。分享这个 zip 就能对症修。只存在手机上。保存的文字可能包含订单卡片上的取餐和送达地址，不需要时请关闭。",
                ),
            )
            SwitchRow(tr("Include screenshots in snapshots", "快照附带截图"), s.snapshotScreenshot, { v -> upd { it.copy(snapshotScreenshot = v) } }, tr("Off by default. Screenshots help with comparison but take space and may contain customer addresses", "默认关闭。截图能帮助对照，但会占空间，也可能包含顾客地址"))
        }

        Section(tr("Other ways to capture", "其他抓取方式")) {
            Text(
                tr("• Capture one in 10 seconds;\n• in debug mode, tap the system accessibility button to capture one;\n• debug mode + auto snapshot: saves automatically when a likely offer is detected (up to 30).\nOnly do this while parked.", "• 10 秒后抓取一份；\n• 开启调试模式后，点系统的无障碍按钮抓取一份；\n• 开启调试模式 + 自动快照：识别到疑似订单时自动保存（最多 30 份）。\n请在停车时操作。"),
                style = MaterialTheme.typography.bodySmall,
            )
            val until = status.snapshotCountdownUntil
            OutlinedButton(
                onClick = { OfferMonitorService.instance?.scheduleSnapshot(10_000) },
                enabled = status.serviceConnected && until == null,
            ) {
                Text(
                    when {
                        !status.serviceConnected -> tr("Turn on the accessibility service first", "需先开启无障碍服务")
                        until != null -> tr("Capturing in ${((until - now) / 1000 + 1).coerceAtLeast(0)} s…", "将在 ${((until - now) / 1000 + 1).coerceAtLeast(0)} 秒后抓取…")
                        else -> tr("Capture in 10 s", "10 秒后抓取")
                    }
                )
            }
            SwitchRow(tr("Auto snapshot", "自动快照"), s.autoSnapshot, { v -> upd { it.copy(autoSnapshot = v) } }, tr("Snapshots can include customer addresses shown on screen. They stay on the phone; delete them after sharing.", "快照可能包含屏幕上的顾客地址。只存在手机上，分享后请删除。"))
        }

        Section(tr("Captures", "抓取记录")) {
            Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall)
            sessions.forEach { d ->
                val n = d.listFiles()?.count { it.extension == "json" } ?: 0
                val recordingThis = status.recording?.sessionName == d.name
                Column {
                    Text(tr("${d.name} ($n${if (recordingThis) ", capturing" else ""})", "${d.name}（$n 份${if (recordingThis) "，抓取中" else ""}）"), style = MaterialTheme.typography.bodyMedium)
                    if (!recordingThis) {
                        Row {
                            TextButton(onClick = {
                                val newest = d.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
                                val zip = File(dir, d.name + ".zip").takeIf { it.exists() && it.lastModified() >= newest } ?: SnapshotStore.zipSession(d)
                                share(ctx, zip)
                            }) { Text(tr("Share zip", "分享 zip")) }
                            TextButton(onClick = { d.listFiles()?.filter { it.extension == "json" }?.maxByOrNull { it.name }?.let { preview = summarize(it) } }) { Text(tr("View latest", "看最后一份")) }
                            TextButton(onClick = { d.deleteRecursively(); File(dir, d.name + ".zip").delete(); refresh++ }) { Text(tr("Delete", "删除")) }
                        }
                    }
                }
            }
            files.take(30).forEach { f ->
                Column {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { preview = summarize(f) }) { Text(tr("View text", "查看文字")) }
                        TextButton(onClick = { share(ctx, f) }) { Text(tr("Share", "分享")) }
                        TextButton(onClick = {
                            f.delete(); File(f.parentFile, f.nameWithoutExtension + ".png").delete(); refresh++
                        }) { Text(tr("Delete", "删除")) }
                    }
                }
            }
            if (sessions.isEmpty() && files.isEmpty()) Text(tr("No captures yet", "暂无记录"))
            if ((sessions.isNotEmpty() || files.isNotEmpty()) && status.recording == null) {
                OutlinedButton(onClick = { dir.listFiles()?.forEach { it.deleteRecursively() }; refresh++; preview = null }) { Text(tr("Delete all", "全部删除")) }
            }
        }

        preview?.let {
            Section(tr("Text in snapshot", "快照中的文字")) {
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
}.getOrElse { "Can't read: ${it.message}" }

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
    ctx.startActivity(Intent.createChooser(intent, "Share snapshot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
