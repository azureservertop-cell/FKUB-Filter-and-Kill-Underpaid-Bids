package com.offerpilot.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offerpilot.core.i18n.Lang
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
        val greatRatio = doublePreferencesKey("great_ratio_v2") // v2: default changed to 1.5; ignore the 1.8 stored by older builds
        val tipFree = doublePreferencesKey("tip_free_share_v2") // v2: default changed to 85%; ignore the 80% stored by older builds
        val fuelL = doublePreferencesKey("fuel_l_per_100km")
        val fuelPrice = doublePreferencesKey("fuel_price")
        val maintenance = doublePreferencesKey("maintenance_per_100km")
        val mode = stringPreferencesKey("rule_mode")
        val targetHourly = doublePreferencesKey("target_net_hourly")
        val outOfTown = booleanPreferencesKey("out_of_town_alert")
        val returnTrip = booleanPreferencesKey("out_of_town_return_trip")
        val returnMultiplier = doublePreferencesKey("return_distance_multiplier")
        val shopTime = booleanPreferencesKey("shop_time_enabled")
        val pickupWait = doublePreferencesKey("pickup_wait_minutes")
        val shopPerItem = doublePreferencesKey("shop_minutes_per_item")
        val shopUnits = booleanPreferencesKey("shop_count_units")
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val sound = booleanPreferencesKey("sound_enabled")
        val vibration = booleanPreferencesKey("vibration_enabled")
        val overlay = booleanPreferencesKey("overlay_enabled")
        val ocr = booleanPreferencesKey("ocr_enabled")
        val glow = booleanPreferencesKey("edge_glow_enabled")
        val overlayPos = stringPreferencesKey("overlay_position")
        val overlaySec = intPreferencesKey("overlay_seconds")
        val overlayUntilGone = booleanPreferencesKey("overlay_until_gone")
        val debug = booleanPreferencesKey("debug_mode")
        val autoSnap = booleanPreferencesKey("auto_snapshot")
        val snapShot = booleanPreferencesKey("snapshot_screenshot")
        val logUnreadable = booleanPreferencesKey("log_unreadable")
        val history = booleanPreferencesKey("history_enabled")
        val homeCities = stringPreferencesKey("home_cities")
        val ocrTop = intPreferencesKey("ocr_top_percent")
        val ocrBottom = intPreferencesKey("ocr_bottom_percent")
        val developer = booleanPreferencesKey("developer_mode")
        val consent = booleanPreferencesKey("consent_v1")
        val rulesConfirmed = booleanPreferencesKey("rules_confirmed")
        val targets = stringPreferencesKey("target_packages")
        val language = stringPreferencesKey("language")
        val fuelMode = stringPreferencesKey("fuel_mode")
        val fuelRegion = stringPreferencesKey("fuel_region")
        val fuelFetchedAt = longPreferencesKey("fuel_fetched_at")
        val fuelAttemptedAt = longPreferencesKey("fuel_attempted_at")
        val fuelFetchedRegion = stringPreferencesKey("fuel_fetched_region")
        val fuelPeriod = stringPreferencesKey("fuel_period")
        val fuelSourceRegion = stringPreferencesKey("fuel_source_region")
        val fuelSource = stringPreferencesKey("fuel_source")
        val fuelError = stringPreferencesKey("fuel_error")
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
                outOfTownReturnTrip = this[K.returnTrip] ?: e.outOfTownReturnTrip,
                returnDistanceMultiplier = this[K.returnMultiplier] ?: e.returnDistanceMultiplier,
                shopTimeEnabled = this[K.shopTime] ?: e.shopTimeEnabled,
                pickupWaitMinutes = this[K.pickupWait] ?: e.pickupWaitMinutes,
                shopMinutesPerItem = this[K.shopPerItem] ?: e.shopMinutesPerItem,
                shopCountUnits = this[K.shopUnits] ?: e.shopCountUnits,
            ),
            language = this[K.language]?.let { runCatching { Lang.valueOf(it) }.getOrNull() } ?: d.language,
            fuel = FuelSettings(
                mode = this[K.fuelMode]?.let { runCatching { FuelPriceMode.valueOf(it) }.getOrNull() } ?: d.fuel.mode,
                region = this[K.fuelRegion] ?: d.fuel.region,
                fetchedAt = this[K.fuelFetchedAt] ?: 0L,
                attemptedAt = this[K.fuelAttemptedAt] ?: 0L,
                fetchedRegion = this[K.fuelFetchedRegion] ?: "",
                period = this[K.fuelPeriod] ?: "",
                sourceRegion = this[K.fuelSourceRegion] ?: "",
                source = this[K.fuelSource] ?: "",
                error = this[K.fuelError]?.ifEmpty { null },
            ),
            monitoringEnabled = this[K.monitoring] ?: d.monitoringEnabled,
            soundEnabled = this[K.sound] ?: d.soundEnabled,
            tierSounds = SOUND_TIERS.associateWith { v ->
                val def = DEFAULT_TIER_SOUNDS.getValue(v)
                TierSound(
                    enabled = this[booleanPreferencesKey("sound_${v.name}_on")] ?: def.enabled,
                    volume = this[floatPreferencesKey("sound_${v.name}_vol")] ?: def.volume,
                    customPath = this[stringPreferencesKey("sound_${v.name}_path")]?.takeIf { it.isNotEmpty() },
                )
            },
            vibrationEnabled = this[K.vibration] ?: d.vibrationEnabled,
            overlayEnabled = this[K.overlay] ?: d.overlayEnabled,
            ocrEnabled = this[K.ocr] ?: d.ocrEnabled,
            edgeGlowEnabled = this[K.glow] ?: d.edgeGlowEnabled,
            overlayPosition = this[K.overlayPos]?.let { runCatching { OverlayPosition.valueOf(it) }.getOrNull() } ?: d.overlayPosition,
            overlaySeconds = this[K.overlaySec] ?: d.overlaySeconds,
            overlayUntilGone = this[K.overlayUntilGone] ?: d.overlayUntilGone,
            debugMode = this[K.debug] ?: d.debugMode,
            autoSnapshot = this[K.autoSnap] ?: d.autoSnapshot,
            snapshotScreenshot = this[K.snapShot] ?: d.snapshotScreenshot,
            logUnreadable = this[K.logUnreadable] ?: d.logUnreadable,
            historyEnabled = this[K.history] ?: d.historyEnabled,
            homeCities = this[K.homeCities] ?: d.homeCities,
            ocrTopPercent = this[K.ocrTop] ?: d.ocrTopPercent,
            ocrBottomPercent = this[K.ocrBottom] ?: d.ocrBottomPercent,
            developerMode = this[K.developer] ?: d.developerMode,
            consentAccepted = this[K.consent] ?: d.consentAccepted,
            rulesConfirmed = this[K.rulesConfirmed] ?: d.rulesConfirmed,
            targetPackages = this[K.targets]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: d.targetPackages,
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
            p[K.returnTrip] = e.outOfTownReturnTrip
            p[K.returnMultiplier] = e.returnDistanceMultiplier
            p[K.shopTime] = e.shopTimeEnabled
            p[K.pickupWait] = e.pickupWaitMinutes
            p[K.shopPerItem] = e.shopMinutesPerItem
            p[K.shopUnits] = e.shopCountUnits
            p[K.monitoring] = n.monitoringEnabled
            p[K.sound] = n.soundEnabled
            for ((v, t) in n.tierSounds) {
                p[booleanPreferencesKey("sound_${v.name}_on")] = t.enabled
                p[floatPreferencesKey("sound_${v.name}_vol")] = t.volume
                p[stringPreferencesKey("sound_${v.name}_path")] = t.customPath ?: ""
            }
            p[K.vibration] = n.vibrationEnabled
            p[K.overlay] = n.overlayEnabled
            p[K.ocr] = n.ocrEnabled
            p[K.glow] = n.edgeGlowEnabled
            p[K.overlayPos] = n.overlayPosition.name
            p[K.overlaySec] = n.overlaySeconds
            p[K.overlayUntilGone] = n.overlayUntilGone
            p[K.debug] = n.debugMode
            p[K.autoSnap] = n.autoSnapshot
            p[K.snapShot] = n.snapshotScreenshot
            p[K.logUnreadable] = n.logUnreadable
            p[K.history] = n.historyEnabled
            p[K.homeCities] = n.homeCities
            p[K.ocrTop] = n.ocrTopPercent
            p[K.ocrBottom] = n.ocrBottomPercent
            p[K.developer] = n.developerMode
            p[K.consent] = n.consentAccepted
            p[K.rulesConfirmed] = n.rulesConfirmed
            p[K.targets] = n.targetPackages.joinToString(",")
            p[K.language] = n.language.name
            p[K.fuelMode] = n.fuel.mode.name
            p[K.fuelRegion] = n.fuel.region
            p[K.fuelFetchedAt] = n.fuel.fetchedAt
            p[K.fuelAttemptedAt] = n.fuel.attemptedAt
            p[K.fuelFetchedRegion] = n.fuel.fetchedRegion
            p[K.fuelPeriod] = n.fuel.period
            p[K.fuelSourceRegion] = n.fuel.sourceRegion
            p[K.fuelSource] = n.fuel.source
            p[K.fuelError] = n.fuel.error ?: ""
        }
    }
}
