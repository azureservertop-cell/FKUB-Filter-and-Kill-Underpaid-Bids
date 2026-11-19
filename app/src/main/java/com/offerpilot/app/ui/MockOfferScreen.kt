package com.offerpilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.accessibility.MonitorState
import com.offerpilot.app.accessibility.OfferMonitorService
import com.offerpilot.app.settings.AppSettings
import com.offerpilot.core.format.OfferMessages
import com.offerpilot.core.model.Box
import com.offerpilot.core.model.DeliveryOffer
import com.offerpilot.core.model.EvaluationResult
import com.offerpilot.core.model.OfferSource
import com.offerpilot.core.model.ScreenText
import com.offerpilot.core.model.TextNode
import com.offerpilot.core.model.Verdict
import com.offerpilot.core.parser.AdapterResult
import com.offerpilot.core.parser.OfferParser
import com.offerpilot.core.parser.UberAdapterConfig
import com.offerpilot.core.parser.UberDriverAdapter
import com.offerpilot.core.rules.OfferEvaluator

/** 无需启动 Uber 的模拟订单测试页（PRD 十一.7）。 */
@Composable
fun MockOfferScreen(s: AppSettings, modifier: Modifier) {
    var payout by rememberSaveable { mutableStateOf("$9.00") }
    var distance by rememberSaveable { mutableStateOf("29.92 km") }
    var duration by rememberSaveable { mutableStateOf("42 min") }
    var rawText by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<DeliveryOffer, EvaluationResult>?>(null) }
    var adapterNote by remember { mutableStateOf<String?>(null) }
    val status by MonitorState.status.collectAsStateWithLifecycle()

    fun evalFields() {
        val offer = DeliveryOffer(
            offerId = "mock",
            estimatedPayout = OfferParser.parseMoney(payout) ?: payout.trim().toDoubleOrNull(),
            distanceKm = OfferParser.parseDistanceKm(distance) ?: distance.trim().toDoubleOrNull(),
            durationMinutes = OfferParser.parseDurationMinutes(duration) ?: duration.trim().toDoubleOrNull(),
            timestamp = System.currentTimeMillis(),
            source = OfferSource.MOCK,
            confidence = 1f,
        )
        result = offer to OfferEvaluator(s.evaluator).evaluate(offer)
        adapterNote = null
    }

    fun evalRaw() {
        val adapter = UberDriverAdapter(UberAdapterConfig(targetPackages = emptySet()))
        val lines = rawText.lines().filter { it.isNotBlank() }
        val screen = ScreenText(
            packageName = "mock",
            nodes = lines.mapIndexed { i, l -> TextNode(l, bounds = Box(0, i * 60, 1000, i * 60 + 40)) },
            timestamp = System.currentTimeMillis(),
            source = OfferSource.MOCK,
        )
        when (val r = adapter.extract(screen)) {
            AdapterResult.NoOffer -> { result = null; adapterNote = "适配器：未检测到订单（${adapter.lastDebug?.note}）" }
            is AdapterResult.Offer -> {
                result = r.offer to OfferEvaluator(s.evaluator).evaluate(r.offer)
                adapterNote = "适配器：${adapter.lastDebug?.note}"
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("模拟订单") {
            Text("PRD 测试案例：", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { payout = "$9.00"; distance = "29.92 km"; duration = "42 min"; evalFields() }) { Text("A") }
                OutlinedButton(onClick = { payout = "$33.01"; distance = "40.99 km"; duration = "1 hr 3 min"; evalFields() }) { Text("B") }
                OutlinedButton(onClick = { payout = "$7.00"; distance = "3 km"; duration = "10 min"; evalFields() }) { Text("C") }
            }
            OutlinedTextField(payout, { payout = it }, label = { Text("报价（如 \$9.00 / CA\$9）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(distance, { distance = it }, label = { Text("距离（如 29.92 km / 1.2 mi）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(duration, { duration = it }, label = { Text("时间（如 42 min / 1 hr 3 min）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { evalFields() }) { Text("计算") }
        }

        Section("模拟屏幕文字（测试适配器）") {
            Text("每行一个控件文字，例如从快照里复制。适配器会像真实识别一样处理歧义。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                rawText, { rawText = it }, label = { Text("屏幕文字") },
                minLines = 4, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("\$9.00\n42 min (29.92 km) total\nAccept") },
            )
            Button(onClick = { evalRaw() }) { Text("用适配器解析并计算") }
        }

        adapterNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        result?.let { (offer, r) ->
            Section("结果") {
                Text(
                    OfferMessages.overlay(offer, r),
                    color = if (r.verdict == Verdict.MIN_WAGE) Color.Black else Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.background(verdictColor(r.verdict), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text("语音：" + OfferMessages.speech(offer, r))
                Text(OfferMessages.detail(offer, r), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { OfferMonitorService.instance?.preview(offer, r) },
                    enabled = status.serviceConnected,
                ) { Text(if (status.serviceConnected) "用悬浮层 + 语音预览" else "开启无障碍服务后可预览悬浮层") }
            }
        }
    }
}
