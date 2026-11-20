package com.offerpilot.core

import com.offerpilot.core.model.Verdict
import com.offerpilot.core.rules.EvaluatorSettings
import com.offerpilot.core.stats.OfferRecord
import com.offerpilot.core.stats.OfferStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfferStatsTest {
    private val records = listOf(
        OfferRecord(3_600_000L * 1, 5.50, 1.3, 15.0),   // red (ratio 0.97)
        OfferRecord(3_600_000L * 1, 9.00, 29.92, 42.0), // red
        OfferRecord(3_600_000L * 2, 7.00, 3.0, 10.0),   // blue (1.60)
        OfferRecord(3_600_000L * 2, 33.01, 40.99, 63.0), // red (0.98)
    )

    @Test fun counts_medians_and_hours() {
        val s = OfferStats.compute(records, EvaluatorSettings()) { t -> ((t / 3_600_000L) % 24).toInt() }
        assertEquals(4, s.total)
        assertEquals(3, s.countByVerdict[Verdict.LOSS])
        assertEquals(1, s.countByVerdict[Verdict.GREAT])
        assertEquals(0, s.countByVerdict[Verdict.GOOD])
        assertEquals((7.00 + 9.00) / 2, s.medianPrice)
        assertEquals(2, s.byHour[1][Verdict.LOSS])
        assertEquals(1, s.byHour[2][Verdict.GREAT])
        assertEquals(4, s.netHourlyBins.sumOf { it.second })
    }

    @Test fun thresholds_are_applied_retroactively() {
        val strict = OfferStats.compute(records, EvaluatorSettings(targetNetHourly = 40.0)) { 0 }
        assertEquals(4, strict.countByVerdict[Verdict.LOSS])
    }

    @Test fun csv_round_trip_and_bad_lines() {
        val r = OfferRecord(1_763_600_000_000L, 25.07, 31.0, 61.0)
        assertEquals(r, OfferRecord.fromCsv(r.toCsv()))
        assertNull(OfferRecord.fromCsv(OfferRecord.CSV_HEADER))
        assertNull(OfferRecord.fromCsv("1,0,3,10"))
        assertNull(OfferStats.median(emptyList()))
    }
}
