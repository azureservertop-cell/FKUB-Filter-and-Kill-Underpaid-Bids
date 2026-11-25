package com.offerpilot.app.ui

import androidx.compose.foundation.layout.Column
import java.util.Date
import java.text.DateFormat
import com.offerpilot.app.settings.FuelSettings
import com.offerpilot.app.settings.FuelPriceMode
import com.offerpilot.app.fuel.FuelPriceUpdater
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.SettingsRepository
import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.rules.RuleMode
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun RulesScreen(s: AppSettings, repo: SettingsRepository, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val e = s.evaluator
    fun upd(f: (EvaluatorSettings) -> EvaluatorSettings) = scope.launch { repo.update { it.copy(evaluator = f(it.evaluator)) } }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!s.rulesConfirmed) {
            Text(
                tr("The numbers below are only examples, not a recommendation. The target starts at the Ontario minimum wage. Enter your own car costs and the hourly pay you want: FKUB judges every offer by your numbers, and the decision is always yours.",
                    "下面的数字只是示例，不是推荐标准。目标时薪的初始值是安省最低工资。请填你自己的车辆成本和你想要的时薪：FKUB 按你的数字判断每一单，接不接永远由你决定。"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { scope.launch { repo.update { it.copy(rulesConfirmed = true) } } }) {
                Text(tr("These are my numbers", "这些就是我的数字"))
            }
        }
        Section(tr("My car", "我的车")) {
            Text(tr("Fill these in and FKUB works out which offers are worth it.", "填好这几项，就能自动算出什么样的单合适。"), style = MaterialTheme.typography.bodySmall)
            NumberField(tr("Fuel consumption (L/100 km)", "百公里油耗（L/100km）"), e.fuelLitersPer100Km, { v -> upd { it.copy(fuelLitersPer100Km = v) } })
            NumberField(tr("Maintenance ($/100 km: servicing, tires, depreciation…)", "百公里维护费用（$/100km，保养、轮胎、折旧等）"), e.maintenancePer100Km, { v -> upd { it.copy(maintenancePer100Km = v) } })
            FuelPriceBlock(s, repo)
            Text(tr("Vehicle cost per km ≈ ${OfferMessages.money(e.vehicleCostPerKm)}", "每公里车辆成本 ≈ ${OfferMessages.money(e.vehicleCostPerKm)}"), style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("Target earnings", "期望收入")) {
            NumberField(tr("Target net per hour ($/h, after fuel and maintenance)", "期望每小时净赚（$/h，已扣油费和维护）"), e.targetNetHourly, { v -> upd { it.copy(targetNetHourly = v) } }, valid = { it >= 0 })
            Text(
                tr("Starts at the Ontario minimum wage ($17.95/h, from Oct 1, 2026). Change it to your own target.",
                    "初始值是安省最低工资（$17.95/h，2026 年 10 月 1 日起）。请改成你自己的目标。"),
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField(
                tr("Share of shown price that isn't tip (%)", "显示价中不含小费的比例（%）"), e.tipFreeShare * 100, { v -> upd { it.copy(tipFreeShare = v / 100) } },
                valid = { it in 10.0..100.0 },
            )
            Text(tr("Uber's shown price includes the estimated tip. Only the tip-free part is judged: shown price × ${fmt(e.tipFreeShare * 100)}%. Enter 100 to not deduct anything.", "Uber 显示的是含预估小费的价格。判断时只算不含小费的部分：显示价 × ${fmt(e.tipFreeShare * 100)}%。填 100 表示不扣。"), style = MaterialTheme.typography.bodySmall)
            NumberField(tr("Minimum order amount ($, 0 = no limit)", "可接受的最低订单金额（$，0 = 不限）"), e.minOrderAmount, { v -> upd { it.copy(minOrderAmount = v) } })
        }

        Section(tr("Your thresholds", "自动算出的标准")) {
            if (e.mode == RuleMode.NET_HOURLY) {
                Text(tr("Required pay = km × ${OfferMessages.money(e.vehicleCostPerKm)} + min × ${OfferMessages.money(e.netPerMinute)}", "达标价 = 公里 × ${OfferMessages.money(e.vehicleCostPerKm)} + 分钟 × ${OfferMessages.money(e.netPerMinute)}"), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(tr("Required pay = max(km × ${OfferMessages.money(e.minPerKm)}, min × ${OfferMessages.money(e.minPerMinute)})", "达标价 = max(公里 × ${OfferMessages.money(e.minPerKm)}, 分钟 × ${OfferMessages.money(e.minPerMinute)})"), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                tr("Ratio = tip-free pay ÷ required pay\n🔴 < 1: below target   🟡 1 ~ ${fmt(e.goodRatio)}: meets target ($", "比值 = 不含小费价 ÷ 达标价\n🔴 < 1：不达标　🟡 1 ~ ${fmt(e.goodRatio)}：刚好达标（$") + fmt(e.targetNetHourly) + tr("/h + vehicle cost)\n🟢 ${fmt(e.goodRatio)} ~ ${fmt(e.greatRatio)}: good pay   🔵 ≥ ${fmt(e.greatRatio)}: must take", "/h + 车辆成本）\n🟢 ${fmt(e.goodRatio)} ~ ${fmt(e.greatRatio)}：比较赚　🔵 ≥ ${fmt(e.greatRatio)}：无脑接"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(tr("Minimum price Uber has to show (tip included) to reach each tier:", "下表是 Uber 至少要显示多少钱（含小费）才进入该档："), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                listOf(tr("Time / distance", "时间 / 距离"), tr("🟡 from", "🟡 起"), tr("🟢 from", "🟢 起"), tr("🔵 from", "🔵 起")).forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold) }
            }
            EXAMPLES.forEach { (min, km) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(tr("${min.toInt()} min / ${km.toInt()} km", "${min.toInt()}分 / ${km.toInt()}km"), Modifier.weight(1f))
                    Text(OfferMessages.money(e.displayedPriceFor(km, min, 1.0)), Modifier.weight(1f))
                    Text(OfferMessages.money(e.displayedPriceFor(km, min, e.goodRatio)), Modifier.weight(1f))
                    Text(OfferMessages.money(e.displayedPriceFor(km, min, e.greatRatio)), Modifier.weight(1f))
                }
            }
        }

        Section(tr("Tier thresholds (adjustable)", "档位阈值（可手动调整）")) {
            Text(tr("Ratio 1.0 means exactly $", "比值 1.0 正好是不含小费时净赚 $") + fmt(e.targetNetHourly) + tr("/h net without tips: the start of yellow.", "/h，这是黄色的起点。"), style = MaterialTheme.typography.bodySmall)
            NumberField(tr("🟢 Green starts at ratio", "🟢 绿色起点倍率"), e.goodRatio, { v -> upd { it.copy(goodRatio = v) } }, valid = { it >= 1.0 && it <= e.greatRatio })
            NumberField(tr("🔵 Blue (must take) starts at ratio", "🔵 蓝色起点倍率（无脑接）"), e.greatRatio, { v -> upd { it.copy(greatRatio = v) } }, valid = { it >= e.goodRatio })
        }

        Section(tr("Advanced: simple rule mode", "高级：简单规则模式")) {
            SwitchRow(
                tr("Use minimum per km / per minute instead", "改用每公里 / 每分钟最低价"), e.mode == RuleMode.PER_KM_PER_MINUTE,
                { v -> upd { it.copy(mode = if (v) RuleMode.PER_KM_PER_MINUTE else RuleMode.NET_HOURLY) } },
                tr("Ignores vehicle cost and uses max(km × A, min × B)", "不按车辆成本计算，直接用 max(公里 × A, 分钟 × B)"),
            )
            if (e.mode == RuleMode.PER_KM_PER_MINUTE) {
                NumberField(tr("Minimum per km ($/km)", "每公里最低报价（$/km）"), e.minPerKm, { v -> upd { it.copy(minPerKm = v) } })
                NumberField(tr("Minimum per minute ($/min)", "每分钟最低报价（$/min）"), e.minPerMinute, { v -> upd { it.copy(minPerMinute = v) } })
            }
        }

        Section(tr("Waiting for food", "等餐时间")) {
            NumberField(tr("Minutes added to every offer", "每单加上的分钟数"), e.pickupWaitMinutes,
                { v -> upd { it.copy(pickupWaitMinutes = v) } }, valid = { it in 0.0..60.0 })
            Text(tr("Uber's time doesn't include waiting at the restaurant. Set 0 to turn it off.", "Uber 给的时间不含在餐厅等餐的时间。设为 0 即关闭。"),
                style = MaterialTheme.typography.bodySmall)
        }

        Section(tr("Shop orders", "Shop 购物单")) {
            SwitchRow(
                tr("Add shopping time", "加上购物时间"), e.shopTimeEnabled, { v -> upd { it.copy(shopTimeEnabled = v) } },
                tr("Uber's time for a Shop order doesn't include picking the items. When the card shows “N items”, N × the minutes below are added before judging.",
                    "Uber 给 Shop 单的时间不含挑商品的时间。卡片上显示“N items”时，判断前会加上 N × 下面的分钟数。"),
            )
            if (e.shopTimeEnabled) {
                NumberField(tr("Minutes per item", "每件商品分钟数"), e.shopMinutesPerItem,
                    { v -> upd { it.copy(shopMinutesPerItem = v) } }, valid = { it in 0.0..10.0 })
                SwitchRow(
                    tr("Count units instead of items", "按总件数（units）算"), e.shopCountUnits, { v -> upd { it.copy(shopCountUnits = v) } },
                    tr("“22 items (30 units)”: 22 different products, 30 in total. Off = 22 × minutes (each product is a separate search in the store). On = 30 ×.",
                        "“22 items (30 units)”= 22 种商品，一共 30 件。关闭 = 按 22 算（每种商品要单独找一次）；开启 = 按 30 算。"),
                )
            }
        }

        Section(tr("Out-of-town offers", "跨城提醒")) {
            SwitchRow(
                tr("Out-of-town alert", "跨城提醒"), e.outOfTownAlert, { v -> upd { it.copy(outOfTownAlert = v) } },
                tr("When the city in the drop-off address isn't one of yours, the pill shows ⚠ and the city, a reminder to watch the empty return. Only the city name is compared, in memory, then discarded. It doesn't change the verdict.",
                    "目的地地址里的城市不是你设置的城市时，提示条显示 ⚠ 和城市名，提醒你注意空驶回程。只在内存中比对城市名，比完即丢弃，不影响档位判断。"),
            )
            if (e.outOfTownAlert) {
                val sAll = s
                var cities by remember(sAll.homeCities) { mutableStateOf(sAll.homeCities) }
                OutlinedTextField(
                    cities,
                    { v -> cities = v; scope.launch { repo.update { it.copy(homeCities = v) } } },
                    label = { Text(tr("Your cities (comma separated)", "你的城市（多个用逗号分隔）")) },
                    placeholder = { Text(sAll.fuel.region) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    tr("Count the drive back", "计入回城距离"), e.outOfTownReturnTrip, { v -> upd { it.copy(outOfTownReturnTrip = v) } },
                    tr("For out-of-town offers, distance and time are multiplied by the factor below before judging (1.5 = the trip plus half again for the empty drive back).",
                        "跨城单在计算前把距离和时间乘以下面的倍率（1.5 = 原路程再加一半，作为空驶回城）。"),
                )
                if (e.outOfTownReturnTrip) {
                    NumberField(tr("Return distance multiplier", "回城距离倍率"), e.returnDistanceMultiplier,
                        { v -> upd { it.copy(returnDistanceMultiplier = v) } }, valid = { it in 1.0..5.0 })
                }
                Text(
                    tr("Blank uses your fuel-price city (${sAll.fuel.region}). Add nearby towns you don't mind, e.g. “London, Thamesford”.",
                        "留空则使用油价城市（${sAll.fuel.region}）。不介意的周边城镇也可以加上，例如“London, Thamesford”。"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(onClick = { scope.launch { repo.update { it.copy(evaluator = EvaluatorSettings()) } } }) {
            Text(tr("Reset rules to defaults", "恢复默认规则"))
        }
    }
}

private val EXAMPLES = listOf(10.0 to 3.0, 20.0 to 8.0, 30.0 to 15.0, 45.0 to 25.0, 60.0 to 40.0)

private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** Fuel price: Auto (default, public regional average) or Manual. */
@Composable
private fun FuelPriceBlock(s: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val f = s.fuel
    var refreshing by remember { mutableStateOf(false) }
    fun updFuel(t: (FuelSettings) -> FuelSettings) = scope.launch { repo.update { it.copy(fuel = t(it.fuel)) } }
    fun refresh() = scope.launch {
        refreshing = true
        try { FuelPriceUpdater.refreshIfDue(repo, force = true) } finally { refreshing = false }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Fuel price", "油价"), Modifier.weight(1f))
        FuelPriceMode.entries.forEach { m ->
            val label = if (m == FuelPriceMode.AUTO) tr("Auto", "自动") else tr("Manual", "手动")
            if (f.mode == m) {
                Button(onClick = {}, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) }
            } else {
                TextButton(onClick = {
                    updFuel { it.copy(mode = m) }
                    if (m == FuelPriceMode.AUTO) refresh()
                }) { Text(label) }
            }
        }
    }
    if (f.mode == FuelPriceMode.MANUAL) {
        NumberField(tr("Fuel price ($/L)", "汽油单价（$/L）"), s.evaluator.fuelPricePerLiter, { v ->
            scope.launch { repo.update { it.copy(evaluator = it.evaluator.copy(fuelPricePerLiter = v)) } }
        })
        Text(tr("Manual mode makes no network requests.", "手动模式不会联网。"), style = MaterialTheme.typography.bodySmall)
    } else {
        var regionText by remember(f.region) { mutableStateOf(f.region) }
        OutlinedTextField(
            regionText, { regionText = it },
            label = { Text(tr("City / region", "城市 / 地区")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val r = regionText.trim().ifEmpty { "London" }
                    scope.launch {
                        repo.update { it.copy(fuel = it.fuel.copy(region = r)) }
                        refreshing = true
                        try { FuelPriceUpdater.refreshIfDue(repo, force = true) } finally { refreshing = false }
                    }
                },
                enabled = !refreshing,
            ) { Text(if (refreshing) tr("Updating…", "更新中…") else tr("Update now", "立即更新")) }
            Text(
                tr("Current: ", "当前：") + OfferMessages.money(s.evaluator.fuelPricePerLiter) + "/L",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val status = when {
            f.fetchedAt > 0 && f.error == null -> tr("Average for ", "来源地区：") + f.sourceRegion +
                (if (f.period.isNotBlank()) tr(", period ", "，统计期 ") + f.period else "") +
                tr(" · updated ", " · 更新于 ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.fetchedAt)) +
                "\n" + f.source
            f.error != null -> tr("Couldn't fetch the price, using the last value. ", "获取油价失败，暂用上次的值。") + f.error
            else -> tr("Not fetched yet; using the default until it is.", "尚未获取，暂用默认值。")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            tr(
                "Auto downloads public weekly regional averages (Ontario open data, falling back to Statistics Canada) about twice a day. Nothing about you or your offers is sent.",
                "自动模式每天约两次下载政府公开的地区平均油价（安大略省开放数据，取不到时用加拿大统计局），不会发送任何关于你或订单的信息。",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

