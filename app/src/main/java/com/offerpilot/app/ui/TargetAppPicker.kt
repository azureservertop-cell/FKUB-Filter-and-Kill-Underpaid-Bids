package com.offerpilot.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class InstalledApp(val label: String, val pkg: String)

private fun launchableApps(ctx: Context): List<InstalledApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = if (android.os.Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
    return infos
        .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun labelOf(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
}.getOrDefault(pkg)

/** The user picks which installed app(s) FKUB watches. Nothing is read until at least one is picked. */
@Composable
fun TargetAppSection(s: AppSettings, repo: SettingsRepository) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Section(tr("App to watch", "要识别的应用")) {
        if (s.targetPackages.isEmpty()) {
            Text(
                tr("No app picked yet, so FKUB reads nothing. Pick the delivery app you drive with.",
                    "还没有选择应用，FKUB 不会读取任何内容。请选择你跑单用的应用。"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            s.targetPackages.forEach { p ->
                Text("${labelOf(ctx, p)}  ($p)", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            tr("FKUB only reads windows of the apps you pick here. Other apps are only seen by name, to notice that you switched away.",
                "FKUB 只读取你在这里选的应用的窗口。其他应用只看包名，用来判断你是否已经切走。"),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { open = true }) { Text(tr("Choose app", "选择应用")) }
    }
    if (open) {
        AppPickerDialog(
            selected = s.targetPackages,
            onDismiss = { open = false },
            onConfirm = { set -> open = false; scope.launch { repo.update { it.copy(targetPackages = set) } } },
        )
    }
}

@Composable
private fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { launchableApps(ctx) }.getOrDefault(emptyList()) }
    }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Choose app", "选择应用")) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("Search", "搜索")) },
                )
                val list = apps
                if (list == null) {
                    Text(tr("Loading…", "加载中…"), Modifier.padding(top = 8.dp))
                } else {
                    val q = query.trim().lowercase()
                    val shown = list.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
                    // Keep picked apps that aren't in the launcher list (e.g. typed on the Debug tab) visible.
                    val extra = picked.filter { p -> list.none { it.pkg == p } }.map { InstalledApp(it, it) }
                    LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                        items(extra + shown, key = { it.pkg }) { a ->
                            val on = a.pkg in picked
                            Row(
                                Modifier.fillMaxWidth().clickable { picked = if (on) picked - a.pkg else picked + a.pkg }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, { picked = if (on) picked - a.pkg else picked + a.pkg })
                                Column {
                                    Text(a.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(a.pkg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }) { Text(tr("Save", "保存")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
    )
}
