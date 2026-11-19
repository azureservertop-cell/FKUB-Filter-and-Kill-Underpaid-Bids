package com.offerpilot.core

import com.offerpilot.core.parser.OfferParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferParserTest {
    private fun approx(expected: Double, actual: Double?, eps: Double = 1e-6) {
        requireNotNull(actual) { "expected $expected but was null" }
        assertTrue(kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")
    }

    @Test fun money_formats() {
        approx(9.0, OfferParser.parseMoney("$9"))
        approx(9.0, OfferParser.parseMoney("$9.00"))
        approx(9.0, OfferParser.parseMoney("CA$9.00"))
        approx(33.01, OfferParser.parseMoney("CA $33.01"))
        approx(1234.5, OfferParser.parseMoney("$1,234.50"))
        approx(9.5, OfferParser.parseMoney("$9,50"))
        approx(12.0, OfferParser.parseMoney("Total:\n$ 12"))
    }

    @Test fun money_rejects_non_money() {
        assertNull(OfferParser.parseMoney("29.92 km"))
        assertNull(OfferParser.parseMoney("US$9.00"))
        assertNull(OfferParser.parseMoney("no price"))
        assertNull(OfferParser.parseMoney(""))
    }

    @Test fun money_negative_value_is_negative() {
        approx(-5.0, OfferParser.findMoney("Adjustment -$5.00").single().value)
    }

    @Test fun multiple_money_returns_all_and_parseMoney_refuses_to_guess() {
        val all = OfferParser.findMoney("$9.00 incl. $2.00 tip")
        assertEquals(listOf(9.0, 2.0), all.map { it.value })
        assertNull(OfferParser.parseMoney("$9.00 incl. $2.00 tip"))
    }

    @Test fun distance_formats() {
        approx(29.92, OfferParser.parseDistanceKm("29.92 km"))
        approx(3.5, OfferParser.parseDistanceKm("3.5km"))
        approx(29.92, OfferParser.parseDistanceKm("29,92 km"))
        approx(12.0, OfferParser.parseDistanceKm("12 kilometres"))
    }

    @Test fun miles_converted_to_km() {
        approx(1.2 * 1.609344, OfferParser.parseDistanceKm("1.2 mi"))
        approx(1.609344, OfferParser.parseDistanceKm("1 mile"))
    }

    @Test fun distance_does_not_match_inside_words() {
        assertNull(OfferParser.parseDistanceKm("5 minutes"))
        assertNull(OfferParser.parseDistanceKm("3 kmh"))
    }

    @Test fun duration_formats() {
        approx(42.0, OfferParser.parseDurationMinutes("42 min"))
        approx(42.0, OfferParser.parseDurationMinutes("42 mins"))
        approx(63.0, OfferParser.parseDurationMinutes("1 hr 3 min"))
        approx(65.0, OfferParser.parseDurationMinutes("1 hour 5 minutes"))
        approx(120.0, OfferParser.parseDurationMinutes("2 hrs"))
        approx(63.0, OfferParser.parseDurationMinutes("1 hr\n3 min"))
    }

    @Test fun separate_durations_are_not_merged() {
        val d = OfferParser.findDurationsMinutes("5 min away · 42 min trip")
        assertEquals(listOf(5.0, 42.0), d.map { it.value })
    }

    @Test fun combined_line() {
        val s = "42 min (29.92 km) total"
        approx(42.0, OfferParser.parseDurationMinutes(s))
        approx(29.92, OfferParser.parseDistanceKm(s))
        assertNull(OfferParser.parseMoney(s))
    }

    @Test fun malformed_input() {
        assertNull(OfferParser.parseNumber(""))
        assertNull(OfferParser.parseNumber("abc"))
        assertNull(OfferParser.parseMoney("$"))
        assertNull(OfferParser.parseDistanceKm("km"))
        assertNull(OfferParser.parseDurationMinutes("min"))
    }
}
