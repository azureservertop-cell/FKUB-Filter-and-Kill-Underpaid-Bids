package com.offerpilot.app.ui

import androidx.compose.foundation.layout.Column
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
        Section("我的车") {
            Text("填好这几项，就能自动算出什么样的单合适。", style = MaterialTheme.typography.bodySmall)
            NumberField("百公里油耗（L/100km）", e.fuelLitersPer100Km, { v -> upd { it.copy(fuelLitersPer100Km = v) } })
            NumberField("百公里维护费用（$/100km，保养、轮胎、折旧等）", e.maintenancePer100Km, { v -> upd { it.copy(maintenancePer100Km = v) } })
            NumberField("汽油单价（$/L）", e.fuelPricePerLiter, { v -> upd { it.copy(fuelPricePerLiter = v) } })
            Text("每公里车辆成本 ≈ ${OfferMessages.money(e.vehicleCostPerKm)}", style = MaterialTheme.typography.bodySmall)
        }

        Section("期望收入") {
            NumberField("期望每小时净赚（$/h，已扣油费和维护）", e.targetNetHourly, { v -> upd { it.copy(targetNetHourly = v) } }, valid = { it >= 0 })
            NumberField("可接受的最低订单金额（$，0 = 不限）", e.minOrderAmount, { v -> upd { it.copy(minOrderAmount = v) } })
        }

        Section("自动算出的标准") {
            if (e.mode == RuleMode.NET_HOURLY) {
                Text("达标报价 = 公里 × ${OfferMessages.money(e.vehicleCostPerKm)} + 分钟 × ${OfferMessages.money(e.netPerMinute)}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("达标报价 = max(公里 × ${OfferMessages.money(e.minPerKm)}, 分钟 × ${OfferMessages.money(e.minPerMinute)})", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "🔴 低于回本价：亏本　🟡 回本 ~ ${fmt(e.goodRatio)} 倍：只够最低工资\n🟢 ${fmt(e.goodRatio)} ~ ${fmt(e.greatRatio)} 倍：比较赚　🔵 ${fmt(e.greatRatio)} 倍以上：无脑接",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth()) {
                listOf("时间 / 距离", "回本", "🟢 起", "🔵 起").forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.Bold) }
            }
            EXAMPLES.forEach { (min, km) ->
                val req = e.requiredFor(km, min)
                Row(Modifier.fillMaxWidth()) {
                    Text("${min.toInt()}分 / ${km.toInt()}km", Modifier.weight(1f))
                    Text(OfferMessages.money(km * e.vehicleCostPerKm), Modifier.weight(1f))
                    Text(OfferMessages.money(req * e.goodRatio), Modifier.weight(1f))
                    Text(OfferMessages.money(req * e.greatRatio), Modifier.weight(1f))
                }
            }
        }

        Section("档位阈值（可手动调整）") {
            Text("比值 = 报价 ÷ 达标报价。比值 1.0 正好是期望净时薪。", style = MaterialTheme.typography.bodySmall)
            NumberField("🟢 绿色起点倍率（低于此为黄色）", e.goodRatio, { v -> upd { it.copy(goodRatio = v) } }, valid = { it > 0 && it <= e.greatRatio })
            NumberField("🔵 蓝色起点倍率（无脑接）", e.greatRatio, { v -> upd { it.copy(greatRatio = v) } }, valid = { it >= e.goodRatio })
        }

        Section("高级：简单规则模式") {
            SwitchRow(
                "改用每公里 / 每分钟最低价", e.mode == RuleMode.PER_KM_PER_MINUTE,
                { v -> upd { it.copy(mode = if (v) RuleMode.PER_KM_PER_MINUTE else RuleMode.NET_HOURLY) } },
                "不按车辆成本计算，直接用 max(公里 × A, 分钟 × B)",
            )
            if (e.mode == RuleMode.PER_KM_PER_MINUTE) {
                NumberField("每公里最低报价（$/km）", e.minPerKm, { v -> upd { it.copy(minPerKm = v) } })
                NumberField("每分钟最低报价（$/min）", e.minPerMinute, { v -> upd { it.copy(minPerMinute = v) } })
            }
        }

        Section("城外订单") {
            SwitchRow(
                "启用城外订单提醒", e.outOfTownAlert, { v -> upd { it.copy(outOfTownAlert = v) } },
                "第二阶段功能：需要先在真机上确认能可靠读取终点文字，当前版本不会据此改变判断",
            )
        }
        OutlinedButton(onClick = { scope.launch { repo.update { it.copy(evaluator = EvaluatorSettings()) } } }) {
            Text("恢复默认规则")
        }
    }
}

private val EXAMPLES = listOf(10.0 to 3.0, 20.0 to 8.0, 30.0 to 15.0, 45.0 to 25.0, 60.0 to 40.0)

private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
