package com.offerpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offerpilot.core.i18n.Lang

/**
 * First-run prominent disclosure: what FKUB reads from the screen and what it never does.
 * Shown before anything else; the accessibility service reads nothing until the user agrees.
 */
@Composable
fun ConsentScreen(lang: Lang, onLanguage: (Lang) -> Unit, onAgree: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Text("FKUB", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Lang.entries.forEach { l ->
                    TextButton(onClick = { onLanguage(l) }, enabled = l != lang) { Text(l.nativeName) }
                }
            }
            Text(tr("Before you start", "开始之前"), style = MaterialTheme.typography.titleLarge)
            Text(
                tr("FKUB uses Android's Accessibility Service to read delivery offers from the app you choose, so it can tell you whether an offer meets your own numbers.",
                    "FKUB 使用 Android 无障碍服务读取你所选应用里的配送订单，按你自己设定的标准告诉你这一单值不值得接。"),
            )
            Point(
                tr("What it reads", "读取什么"),
                tr("Only windows of the app you pick. From each offer card: the price, distance and time, and (if the out-of-town alert is on) the city name of the drop-off. Because the delivery app hides this text from accessibility, FKUB takes a screenshot of that app's window and recognises the text on your phone; the screenshot is discarded right away.",
                    "只读取你选的应用的窗口。每张订单卡片上只取：价格、距离、时间，以及（开启跨城提醒时）目的地的城市名。因为配送应用会对无障碍隐藏这些文字，FKUB 会截取该应用的窗口，在手机本地识别文字，截图用完立即丢弃。"),
            )
            Point(
                tr("What it keeps", "保存什么"),
                tr("Time, price, km and minutes of each offer, for the Stats tab (you can turn it off or clear it). No addresses, names, order numbers or screenshots.",
                    "每一单的时间、价格、公里和分钟，用于「统计」页（可以关闭或清空）。不保存地址、姓名、订单号或截图。"),
            )
            Point(
                tr("What it never does", "绝不会做什么"),
                tr("It never taps, accepts or rejects anything for you. It never uploads your offers or screenshots. Its only network use is downloading public fuel prices in Auto mode.",
                    "不会替你点击、接单或拒单。不会上传你的订单或截图。唯一的联网是“自动油价”模式下载公开的油价数据。"),
            )
            Text(
                tr("You can turn the service off at any time in Android's accessibility settings.", "你可以随时在系统的无障碍设置里关闭这个服务。"),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) { Text(tr("I agree, continue", "我同意，继续")) }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text(tr("Not now", "暂不使用")) }
        }
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
