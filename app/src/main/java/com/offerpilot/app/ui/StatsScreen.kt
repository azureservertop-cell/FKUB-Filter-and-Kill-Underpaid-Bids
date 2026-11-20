package com.offerpilot.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.history.OfferHistory
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.SettingsRepository
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.stats.OfferRecord
import com.offerpilot.core.stats.OfferStats
import com.offerpilot.core.stats.OfferStatsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class Range(val en: String, val zh: String, val days: Int?) {
    TODAY("Today", "今天", 1), WEEK("7 days", "7 天", 7), MONTH("30 days", "30 天", 30), ALL("All", "全部", null)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(settings: AppSettings, repo: SettingsRepository, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { repo.update(f) }
    val version by OfferHistory.version.collectAsStateWithLifecycle()
    val all by produceState(initialValue = emptyList<OfferRecord>(), version) {
        value = withContext(Dispatchers.IO) { runCatching { OfferHistory.load(ctx) }.getOrDefault(emptyList()) }
    }
    var range by rememberSaveable { mutableIntStateOf(Range.WEEK.ordinal) }
    var confirmClear by remember { mutableStateOf(false) }
    val lang = LocalLang.current

    val records = remember(all, range) {
        val days = Range.entries[range].days
        if (days == null) all else {
            val from = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -(days - 1))
            }.timeInMillis
            all.filter { it.timeMs >= from }
        }
    }
    val stats = remember(records, settings.evaluator) {
        val cal = Calendar.getInstance()
        OfferStats.compute(records, settings.evaluator) { t -> cal.timeInMillis = t; cal.get(Calendar.HOUR_OF_DAY) }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Offer stats", "订单统计"), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(
                "Every offer FKUB reads is logged with only its time, shown price, km and minutes, and judged again with your current rules. Nothing leaves the phone.",
                "FKUB 读到的每一单只记录时间、显示价、公里和分钟，并按你当前的规则重新判断。数据只保存在手机上。",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow(tr("Log offers", "记录订单"), settings.historyEnabled, { v -> upd { it.copy(historyEnabled = v) } })

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Range.entries.forEachIndexed { i, r ->
                FilterChip(selected = range == i, onClick = { range = i }, label = { Text(tr(r.en, r.zh)) })
            }
        }

        if (stats.total == 0) {
            Text(
                tr("No offers logged in this range yet. Go online in Uber Driver and they will show up here.",
                    "这个时间段还没有记录。打开 Uber Driver 上线后，收到的订单会出现在这里。"),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Summary(stats)
            Section(tr("Verdicts", "档位分布")) { TierBars(stats, lang) }
            Section(tr("Price vs distance", "价格 vs 距离")) {
                ScatterChart(stats)
                Text(tr("Each dot is one offer. Dashed line: price needed to meet your target at the median speed.",
                    "每个点是一单。虚线：按中位车速，达到你目标时薪所需的显示价。"), style = MaterialTheme.typography.bodySmall)
            }
            Section(tr("Estimated net hourly", "预估净时薪分布")) { HourlyHistogram(stats) }
            Section(tr("Offers by hour of day", "各时段订单")) { ByHourChart(stats) }
            Section(tr("Recent offers", "最近订单")) { RecentList(stats) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportCsv(ctx) }, enabled = all.isNotEmpty()) { Text(tr("Export CSV", "导出 CSV")) }
            if (!confirmClear) {
                TextButton(onClick = { confirmClear = true }, enabled = all.isNotEmpty()) { Text(tr("Clear history", "清空记录")) }
            } else {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch(Dispatchers.IO) { OfferHistory.clear(ctx) }
                }) { Text(tr("Tap again to delete ${all.size} offers", "再点一次删除 ${all.size} 条"), color = verdictColor(Verdict.LOSS)) }
            }
        }
        Text(tr("${all.size} offers stored in total.", "共保存 ${all.size} 条。"), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Summary(s: OfferStatsSummary) {
    val bad = s.countByVerdict[Verdict.LOSS] ?: 0
    val pct = if (s.total > 0) bad * 100 / s.total else 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tr("${s.total} offers · $pct% below target", "${s.total} 单 · $pct% 不达标"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            tr(
                "Median: ${money(s.medianPrice)} · ${num(s.medianKm)} km · ${money(s.medianPricePerKm)}/km · net ${money(s.medianNetHourly)}/h",
                "中位数：${money(s.medianPrice)} · ${num(s.medianKm)} km · ${money(s.medianPricePerKm)}/km · 净 ${money(s.medianNetHourly)}/h",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TierBars(s: OfferStatsSummary, lang: com.offerpilot.core.i18n.Lang) {
    val maxCount = max(1, OfferStats.TIERS.maxOf { s.countByVerdict[it] ?: 0 })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OfferStats.TIERS.forEach { v ->
            val c = s.countByVerdict[v] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.label(lang), Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(18.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(verdictColor(v), size = Size(size.width * c / maxCount, size.height))
                    }
                }
                Text(" $c", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScatterChart(s: OfferStatsSummary) {
    val pts = s.judged
    val maxKm = niceCeil(pts.maxOf { it.record.km })
    val maxPrice = niceCeil(pts.maxOf { it.record.price })
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    // Reference line: required shown price per km at median speed. Approximated from the median offer.
    val medKm = OfferStats.median(pts.map { it.record.km }) ?: 1.0
    val medMin = OfferStats.median(pts.map { it.record.minutes }) ?: 1.0
    val medNeededPerKm = OfferStats.median(pts.map { it.record.price / it.ratio.coerceAtLeast(1e-6) / it.record.km })
    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            drawAxes(axis)
            if (medNeededPerKm != null && medKm > 0 && medMin > 0) {
                val y = (medNeededPerKm * maxKm).toFloat()
                drawLine(
                    axis, Offset(0f, size.height), Offset(size.width, size.height - (y / maxPrice.toFloat()) * size.height),
                    strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }
            pts.forEach { p ->
                val x = (p.record.km / maxKm).toFloat() * size.width
                val y = size.height - (p.record.price / maxPrice).toFloat() * size.height
                drawCircle(verdictColor(p.verdict).copy(alpha = 0.75f), radius = 5.dp.toPx(), center = Offset(x, y))
            }
        }
        AxisLabels(tr("0 km", "0 km"), tr("${num(maxKm)} km", "${num(maxKm)} km"), tr("price up to ${money(maxPrice)}", "最高 ${money(maxPrice)}"))
    }
}

@Composable
private fun HourlyHistogram(s: OfferStatsSummary) {
    val bins = s.netHourlyBins
    if (bins.isEmpty()) return
    val lo = bins.first().first
    val hi = bins.last().first
    val step = OfferStatsSummary.NET_BIN
    val counts = (lo..hi step step).map { b -> b to (bins.firstOrNull { it.first == b }?.second ?: 0) }
    val maxC = max(1, counts.maxOf { it.second })
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            drawAxes(axis)
            val w = size.width / counts.size
            counts.forEachIndexed { i, (b, c) ->
                val h = size.height * c / maxC
                drawRect(binColor(b + step / 2.0, s), Offset(i * w + 1f, size.height - h), Size(w - 2f, h))
            }
        }
        AxisLabels("$$lo/h", "$${hi + step}/h", tr("count up to $maxC", "最多 $maxC 单"))
    }
}

/** Colour a net-hourly bin by the verdict most offers in it got. */
private fun binColor(mid: Double, s: OfferStatsSummary): Color {
    val inBin = s.judged.filter { kotlin.math.abs(it.netHourly - mid) <= OfferStatsSummary.NET_BIN / 2.0 }
    val v = inBin.groupingBy { it.verdict }.eachCount().maxByOrNull { it.value }?.key ?: Verdict.UNKNOWN
    return verdictColor(v)
}

@Composable
private fun ByHourChart(s: OfferStatsSummary) {
    val totals = s.byHour.map { it.values.sum() }
    val maxC = max(1, totals.max())
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            drawAxes(axis)
            val w = size.width / 24f
            s.byHour.forEachIndexed { h, m ->
                var top = size.height
                // Stack worst at the bottom so the red share is easy to compare across hours.
                OfferStats.TIERS.reversed().forEach { v ->
                    val c = m[v] ?: 0
                    if (c > 0) {
                        val bh = size.height * c / maxC
                        top -= bh
                        drawRect(verdictColor(v), Offset(h * w + 1f, top), Size(w - 2f, bh))
                    }
                }
            }
        }
        AxisLabels("0:00", "24:00", tr("max $maxC per hour", "每小时最多 $maxC 单"))
    }
}

@Composable
private fun RecentList(s: OfferStatsSummary) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        s.judged.sortedByDescending { it.record.timeMs }.take(30).forEach { j ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(verdictColor(j.verdict), CircleShape))
                Text(
                    "  ${fmt.format(Date(j.record.timeMs))}   ${money(j.record.price)} · ${num(j.record.km)} km · ${j.record.minutes.toInt()} min  →  ${money(j.netHourly)}/h",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AxisLabels(left: String, right: String, note: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(left, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  $note", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(right, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun DrawScope.drawAxes(c: Color) {
    drawLine(c, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
    drawLine(c, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
}

private fun niceCeil(v: Double): Double {
    if (v <= 0) return 1.0
    val steps = doubleArrayOf(1.0, 2.0, 5.0, 10.0)
    var mag = 1.0
    while (mag * 10 <= v) mag *= 10
    return steps.map { it * mag }.first { it >= v }
}

private fun money(v: Double?): String = if (v == null) "–" else String.format(Locale.US, "$%.2f", v)
private fun num(v: Double?): String = if (v == null) "–" else String.format(Locale.US, "%.1f", v)

private fun exportCsv(ctx: Context) {
    val src = OfferHistory.exportFile(ctx) ?: return
    runCatching {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val out = File(dir, "fkub_offer_history.csv")
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "FKUB offer history").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
