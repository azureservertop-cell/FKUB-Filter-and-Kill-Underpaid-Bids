package com.offerpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offerpilot.core.model.Verdict

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 数字输入框：只在输入合法（且通过 [valid]）时提交。 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
    valid: (Double) -> Boolean = { it >= 0 },
) {
    var text by remember { mutableStateOf(fmt(value)) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = fmt(value)
    }
    val parsed = text.toDoubleOrNull()
    val ok = parsed != null && valid(parsed)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.toDoubleOrNull()?.takeIf(valid)?.let(onCommit)
        },
        label = { Text(label) },
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.GREAT -> Color(0xFF1565C0)
    Verdict.GOOD -> Color(0xFF2E7D32)
    Verdict.MIN_WAGE -> Color(0xFFF9A825)
    Verdict.LOSS -> Color(0xFFC62828)
    Verdict.UNKNOWN -> Color(0xFF616161)
}
