package com.offerpilot.app.history

import android.content.Context
import com.offerpilot.core.stats.OfferRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Offer history for the Stats tab: one CSV line per recognised offer with only time, shown price, km and minutes.
 * No addresses, names or screenshots. Stored in app-private storage; never uploaded.
 */
object OfferHistory {
    private const val FILE = "offer_history.csv"
    private const val MAX_RECORDS = 20_000

    private val _version = MutableStateFlow(0)
    /** Bumped on every change so the UI can reload. */
    val version: StateFlow<Int> = _version

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    @Synchronized
    fun append(ctx: Context, r: OfferRecord) {
        val f = file(ctx)
        if (!f.exists()) f.writeText(OfferRecord.CSV_HEADER + "\n")
        f.appendText(r.toCsv() + "\n")
        if (f.length() > MAX_RECORDS * 40L) {
            val keep = load(ctx).takeLast(MAX_RECORDS)
            f.writeText(OfferRecord.CSV_HEADER + "\n" + keep.joinToString("") { it.toCsv() + "\n" })
        }
        _version.value++
    }

    @Synchronized
    fun load(ctx: Context): List<OfferRecord> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { OfferRecord.fromCsv(it) }
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
        _version.value++
    }

    /** The CSV file, for sharing / export. */
    fun exportFile(ctx: Context): File? = file(ctx).takeIf { it.exists() }
}
