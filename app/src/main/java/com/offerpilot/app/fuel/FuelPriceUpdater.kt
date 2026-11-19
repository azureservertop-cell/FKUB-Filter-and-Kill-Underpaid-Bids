package com.offerpilot.app.fuel

import com.offerpilot.app.settings.FuelPriceMode
import com.offerpilot.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps the fuel price up to date in Auto mode. Refreshes at most every [MAX_AGE_MS] unless forced. */
object FuelPriceUpdater {
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    private const val RETRY_AFTER_FAILURE_MS = 60 * 60 * 1000L
    private val lock = Mutex()

    suspend fun refreshIfDue(repo: SettingsRepository, force: Boolean = false) {
        if (!lock.tryLock()) return
        try {
            val s = repo.settings.first()
            if (s.fuel.mode != FuelPriceMode.AUTO) return
            val now = System.currentTimeMillis()
            val f = s.fuel
            val due = force ||
                f.region != f.fetchedRegion ||
                (f.error == null && now - f.fetchedAt > MAX_AGE_MS) ||
                (f.error != null && now - f.attemptedAt > RETRY_AFTER_FAILURE_MS)
            if (!due) return
            val result = withContext(Dispatchers.IO) { FuelPriceFetcher.fetch(f.region) }
            repo.update { cur ->
                result.fold(
                    onSuccess = { q ->
                        cur.copy(
                            evaluator = cur.evaluator.copy(fuelPricePerLiter = q.pricePerLiter),
                            fuel = cur.fuel.copy(
                                fetchedAt = now, attemptedAt = now, fetchedRegion = f.region,
                                period = q.period, sourceRegion = q.region, source = q.source, error = null,
                            ),
                        )
                    },
                    onFailure = { e -> cur.copy(fuel = cur.fuel.copy(attemptedAt = now, fetchedRegion = f.region, error = e.message ?: e.toString())) },
                )
            }
        } finally {
            lock.unlock()
        }
    }
}
