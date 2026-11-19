package com.offerpilot.core

import com.offerpilot.core.fuel.FuelPriceCsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Synthetic CSV layouts; the real files are only seen on the device. */
class FuelPriceCsvTest {
    private fun near(e: Double, a: Double?) = kotlin.test.assertTrue(a != null && kotlin.math.abs(e - a) < 1e-6, "expected $e, got $a")

    @Test fun wide_layout_in_cents_takes_latest_row() {
        val csv = """
            Regular Unleaded Gasoline (cents per litre)
            Date,Ottawa,Toronto West,London,Ontario Average
            2026-11-03,139.1,141.0,138.4,140.2
            2026-11-10,140.0,142.3,139.9,141.1
            2026-11-17,,143.0,,141.5
        """.trimIndent()
        val q = FuelPriceCsv.parse(csv, "London", "test")!!
        near(1.399, q.pricePerLiter)
        assertEquals("2026-11-10", q.period)
        assertEquals("London", q.region)
        near(1.43, FuelPriceCsv.parse(csv, "toronto", "test")!!.pricePerLiter)
    }

    @Test fun long_layout_in_dollars_with_quotes() {
        val csv = "﻿Week,Location,Price\r\n\"2026-11-10\",\"London, ON\",1.399\r\n\"2026-11-17\",\"London, ON\",1.415\r\n2026-11-17,Ottawa,1.43\r\n"
        val q = FuelPriceCsv.parse(csv, "London", "test")!!
        near(1.415, q.pricePerLiter)
        assertEquals("2026-11-17", q.period)
        assertEquals("London, ON", q.region)
    }

    @Test fun unknown_region_or_garbage_gives_null() {
        assertNull(FuelPriceCsv.parse("Date,Ottawa\n2026-11-10,140.0", "London", "test"))
        assertNull(FuelPriceCsv.parse("<html>not a csv</html>", "London", "test"))
        assertNull(FuelPriceCsv.parse("Date,London\n2026-11-10,9999", "London", "test"))
    }
}
