package com.offerpilot.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.app.settings.SettingsRepository
import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.rules.EvaluatorSettings
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(s: AppSettings, repo: SettingsRepository, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val e = s.evaluator
    fun upd(f: (EvaluatorSettings) -> EvaluatorSettings) = scope.launch { repo.update { it.copy(evaluator = f(it.evaluator)) } }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("收益规则") {
            Text(
                "最低接受报价 = max(公里 × 每公里最低, 分钟 × 每分钟最低)\n这是你的个人筛选规则，不是 Uber 官方报酬标准。",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField("每公里最低报价（$/km）", e.minPerKm, { v -> upd { it.copy(minPerKm = v) } })
            NumberField("每分钟最低报价（$/min）", e.minPerMinute, { v -> upd { it.copy(minPerMinute = v) } })
            Text("相当于毛时薪 ${OfferMessages.money(e.minPerMinute * 60)}", style = MaterialTheme.typography.bodySmall)
            NumberField("可接受的最低订单金额（$，0 = 不限）", e.minOrderAmount, { v -> upd { it.copy(minOrderAmount = v) } })
        }
        Section("判定阈值") {
            NumberField("合格倍率（低于此为不推荐）", e.passRatio, { v -> upd { it.copy(passRatio = v) } }, valid = { it > 0 && it <= e.goodRatio })
            NumberField("优质倍率（达到此为值得考虑）", e.goodRatio, { v -> upd { it.copy(goodRatio = v) } }, valid = { it >= e.passRatio })
        }
        Section("车辆成本（仅显示，不改变判断）") {
            NumberField("油耗（L/100km）", e.fuelLitersPer100Km, { v -> upd { it.copy(fuelLitersPer100Km = v) } })
            NumberField("汽油单价（$/L）", e.fuelPricePerLiter, { v -> upd { it.copy(fuelPricePerLiter = v) } })
            NumberField("车辆磨损（$/km）", e.wearPerKm, { v -> upd { it.copy(wearPerKm = v) } })
            Text("合计约 ${OfferMessages.money(e.vehicleCostPerKm)}/km", style = MaterialTheme.typography.bodySmall)
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
