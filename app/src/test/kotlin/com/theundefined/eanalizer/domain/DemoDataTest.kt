package com.theundefined.eanalizer.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun coversWholeDaysEndingYesterday() {
        val recs = DemoData.records(today)
        assertEquals(DemoData.DAYS * 24, recs.size)
        assertEquals(today.minusDays(1), recs.last().timestamp.toLocalDate())
        assertEquals(23, recs.last().timestamp.hour)
        assertEquals(0, recs.first().timestamp.hour)
        assertEquals(recs.size, recs.map { it.timestamp }.toSet().size)
    }

    @Test
    fun deterministic() {
        assertEquals(DemoData.records(today), DemoData.records(today))
    }

    @Test
    fun balancedValuesAreConsistent() {
        val recs = DemoData.records(today)
        assertTrue(recs.all { it.poborPrzed >= 0 && it.oddaniePrzed >= 0 })
        assertTrue(recs.all { it.pobor == 0.0 || it.oddanie == 0.0 })
        assertTrue(
            recs.all {
                Math.abs((it.poborPrzed - it.oddaniePrzed) - (it.pobor - it.oddanie)) < 0.002
            }
        )
    }

    @Test
    fun hasProductionInSummerAndNoneAtNight() {
        val recs = DemoData.records(today)
        assertTrue(recs.filter { it.timestamp.hour in 0..3 }.all { it.oddaniePrzed == 0.0 })
        val july = recs.filter { it.timestamp.monthValue == 7 }.sumOf { it.oddaniePrzed }
        val january = recs.filter { it.timestamp.monthValue == 1 }.sumOf { it.oddaniePrzed }
        assertTrue(july > 4 * january)
    }
}
