package com.offerpilot.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.rules.RuleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "offerpilot_settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val perKm = doublePreferencesKey("min_per_km")
        val perMin = doublePreferencesKey("min_per_minute")
        val minOrder = doublePreferencesKey("min_order_amount")
        val goodRatio = doublePreferencesKey("good_ratio")
        val greatRatio = doublePreferencesKey("great_ratio")
        val tipFree = doublePreferencesKey("tip_free_share")
        val fuelL = doublePreferencesKey("fuel_l_per_100km")
        val fuelPrice = doublePreferencesKey("fuel_price")
        val maintenance = doublePreferencesKey("maintenance_per_100km")
        val mode = stringPreferencesKey("rule_mode")
        val targetHourly = doublePreferencesKey("target_net_hourly")
        val outOfTown = booleanPreferencesKey("out_of_town_alert")
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val tts = booleanPreferencesKey("tts_enabled")
        val vibration = booleanPreferencesKey("vibration_enabled")
        val overlay = booleanPreferencesKey("overlay_enabled")
        val ocr = booleanPreferencesKey("ocr_enabled")
        val glow = booleanPreferencesKey("edge_glow_enabled")
        val volume = floatPreferencesKey("tts_volume")
        val overlayPos = stringPreferencesKey("overlay_position")
        val overlaySec = intPreferencesKey("overlay_seconds")
        val debug = booleanPreferencesKey("debug_mode")
        val autoSnap = booleanPreferencesKey("auto_snapshot")
        val snapShot = booleanPreferencesKey("snapshot_screenshot")
        val targets = stringPreferencesKey("target_packages")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val e = d.evaluator
        return AppSettings(
            evaluator = EvaluatorSettings(
                minPerKm = this[K.perKm] ?: e.minPerKm,
                minPerMinute = this[K.perMin] ?: e.minPerMinute,
                minOrderAmount = this[K.minOrder] ?: e.minOrderAmount,
                goodRatio = this[K.goodRatio] ?: e.goodRatio,
                greatRatio = this[K.greatRatio] ?: e.greatRatio,
                tipFreeShare = this[K.tipFree] ?: e.tipFreeShare,
                fuelLitersPer100Km = this[K.fuelL] ?: e.fuelLitersPer100Km,
                fuelPricePerLiter = this[K.fuelPrice] ?: e.fuelPricePerLiter,
                maintenancePer100Km = this[K.maintenance] ?: e.maintenancePer100Km,
                mode = this[K.mode]?.let { runCatching { RuleMode.valueOf(it) }.getOrNull() } ?: e.mode,
                targetNetHourly = this[K.targetHourly] ?: e.targetNetHourly,
                outOfTownAlert = this[K.outOfTown] ?: e.outOfTownAlert,
            ),
            monitoringEnabled = this[K.monitoring] ?: d.monitoringEnabled,
            ttsEnabled = this[K.tts] ?: d.ttsEnabled,
            vibrationEnabled = this[K.vibration] ?: d.vibrationEnabled,
            overlayEnabled = this[K.overlay] ?: d.overlayEnabled,
            ocrEnabled = this[K.ocr] ?: d.ocrEnabled,
            edgeGlowEnabled = this[K.glow] ?: d.edgeGlowEnabled,
            ttsVolume = this[K.volume] ?: d.ttsVolume,
            overlayPosition = this[K.overlayPos]?.let { runCatching { OverlayPosition.valueOf(it) }.getOrNull() } ?: d.overlayPosition,
            overlaySeconds = this[K.overlaySec] ?: d.overlaySeconds,
            debugMode = this[K.debug] ?: d.debugMode,
            autoSnapshot = this[K.autoSnap] ?: d.autoSnapshot,
            snapshotScreenshot = this[K.snapShot] ?: d.snapshotScreenshot,
            targetPackages = this[K.targets]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?.ifEmpty { null } ?: d.targetPackages,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = transform(p.toSettings())
            val e = n.evaluator
            p[K.perKm] = e.minPerKm
            p[K.perMin] = e.minPerMinute
            p[K.minOrder] = e.minOrderAmount
            p[K.goodRatio] = e.goodRatio
            p[K.greatRatio] = e.greatRatio
            p[K.tipFree] = e.tipFreeShare
            p[K.fuelL] = e.fuelLitersPer100Km
            p[K.fuelPrice] = e.fuelPricePerLiter
            p[K.maintenance] = e.maintenancePer100Km
            p[K.mode] = e.mode.name
            p[K.targetHourly] = e.targetNetHourly
            p[K.outOfTown] = e.outOfTownAlert
            p[K.monitoring] = n.monitoringEnabled
            p[K.tts] = n.ttsEnabled
            p[K.vibration] = n.vibrationEnabled
            p[K.overlay] = n.overlayEnabled
            p[K.ocr] = n.ocrEnabled
            p[K.glow] = n.edgeGlowEnabled
            p[K.volume] = n.ttsVolume
            p[K.overlayPos] = n.overlayPosition.name
            p[K.overlaySec] = n.overlaySeconds
            p[K.debug] = n.debugMode
            p[K.autoSnap] = n.autoSnapshot
            p[K.snapShot] = n.snapshotScreenshot
            p[K.targets] = n.targetPackages.joinToString(",")
        }
    }
}
