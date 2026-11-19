package com.offerpilot.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.OfferPilotApp
import com.offerpilot.app.settings.AppSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as OfferPilotApp).settings
        setContent {
            MaterialTheme {
                val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                CompositionLocalProvider(LocalLang provides settings.language) {
                    AppRoot(settings, repo)
                }
            }
        }
    }
}

private enum class Tab(val en: String, val zh: String, val glyph: String) {
    HOME("Status", "状态", "●"), RULES("Rules", "规则", "$"), MOCK("Mock", "模拟", "▶"), DEBUG("Debug", "调试", "⚙")
}

@Composable
private fun AppRoot(settings: AppSettings, repo: com.offerpilot.app.settings.SettingsRepository) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.glyph) },
                        label = { Text(tr(t.en, t.zh)) },
                    )
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.HOME -> HomeScreen(settings, repo, m)
            Tab.RULES -> RulesScreen(settings, repo, m)
            Tab.MOCK -> MockOfferScreen(settings, m)
            Tab.DEBUG -> DebugScreen(settings, repo, m)
        }
    }
}
