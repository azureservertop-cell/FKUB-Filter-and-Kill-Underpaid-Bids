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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offerpilot.app.OfferPilotApp
import com.offerpilot.app.fuel.FuelPriceUpdater
import com.offerpilot.app.settings.AppSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as OfferPilotApp).settings
        setContent {
            MaterialTheme {
                val loaded by repo.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
                val settings = loaded ?: return@MaterialTheme // wait for saved settings so the consent page doesn't flash
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalLang provides settings.language) {
                    if (!settings.consentAccepted) {
                        ConsentScreen(
                            lang = settings.language,
                            onLanguage = { l -> scope.launch { repo.update { it.copy(language = l) } } },
                            onAgree = { scope.launch { repo.update { it.copy(consentAccepted = true) } } },
                            onDecline = { finish() },
                        )
                    } else {
                        AppRoot(settings, repo)
                    }
                }
            }
        }
    }
}

private enum class Tab(val en: String, val zh: String, val glyph: String) {
    HOME("Status", "状态", "●"), RULES("Rules", "规则", "$"), STATS("Stats", "统计", "▤"), MOCK("Mock", "模拟", "▶"), DEBUG("Debug", "调试", "⚙")
}

@Composable
private fun AppRoot(settings: AppSettings, repo: com.offerpilot.app.settings.SettingsRepository) {
    // The Debug tab only exists once developer options are on (tap the version number 7 times).
    val tabs = Tab.entries.filter { it != Tab.DEBUG || settings.developerMode }
    var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    val current = tabs.firstOrNull { it.name == tabName } ?: Tab.HOME
    LaunchedEffect(Unit) { runCatching { FuelPriceUpdater.refreshIfDue(repo) } }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t,
                        onClick = { tabName = t.name },
                        icon = { Text(t.glyph) },
                        label = { Text(tr(t.en, t.zh)) },
                    )
                }
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (current) {
            Tab.HOME -> HomeScreen(settings, repo, m)
            Tab.RULES -> RulesScreen(settings, repo, m)
            Tab.STATS -> StatsScreen(settings, repo, m)
            Tab.MOCK -> MockOfferScreen(settings, m)
            Tab.DEBUG -> DebugScreen(settings, repo, m)
        }
    }
}
