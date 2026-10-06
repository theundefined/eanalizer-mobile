package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodsTest {
    private val early = LocalDate.of(2000, 1, 1)

    private fun d(s: String) = LocalDate.parse(s)

    private fun check(p: Period, end: String, from: String, to: String, start: LocalDate = early) =
        assertEquals(d(from) to d(to), Periods.resolve(p, start, d(end)))

    @Test fun last365() = check(Period.LAST_365_DAYS, "2026-07-31", "2025-08-01", "2026-07-31")

    @Test fun last30() = check(Period.LAST_30_DAYS, "2026-07-31", "2026-07-02", "2026-07-31")

    @Test fun last90() = check(Period.LAST_90_DAYS, "2026-07-31", "2026-05-03", "2026-07-31")

    @Test
    fun lastDaysCustom() =
        assertEquals(
            d("2026-07-22") to d("2026-07-31"),
            Periods.lastDays(10, early, d("2026-07-31"))
        )

    @Test fun currentMonth() = check(Period.CURRENT_MONTH, "2026-07-15", "2026-07-01", "2026-07-15")

    @Test
    fun previousMonth() = check(Period.PREVIOUS_MONTH, "2026-07-15", "2026-06-01", "2026-06-30")

    @Test
    fun previousMonthAcrossYear() =
        check(Period.PREVIOUS_MONTH, "2026-01-10", "2025-12-01", "2025-12-31")

    @Test fun currentYear() = check(Period.CURRENT_YEAR, "2026-07-15", "2026-01-01", "2026-07-15")

    @Test fun previousYear() = check(Period.PREVIOUS_YEAR, "2026-07-15", "2025-01-01", "2025-12-31")

    @Test
    fun clampsToEarliestData() =
        check(Period.LAST_365_DAYS, "2026-07-31", "2026-06-01", "2026-07-31", d("2026-06-01"))

    @Test
    fun custom() {
        val start = d("2023-01-01")
        val end = d("2026-09-30")
        fun r(from: String?, to: String?) =
            Periods.resolve(Period.CUSTOM, start, end, from?.let(::d), to?.let(::d))
        assertEquals(d("2025-03-01") to d("2025-08-31"), r("2025-03-01", "2025-08-31"))
        // Clamped to the data, swapped when reversed, all data when not set.
        assertEquals(start to end, r("2020-01-01", "2030-01-01"))
        assertEquals(d("2025-03-01") to d("2025-08-31"), r("2025-08-31", "2025-03-01"))
        assertEquals(start to end, r(null, null))
        assertEquals(end to end, r("2027-01-01", "2027-02-01"))
    }

    @Test fun all() = check(Period.ALL, "2026-07-31", "2026-06-01", "2026-07-31", d("2026-06-01"))

    @Test
    fun filterIsInclusive() {
        val recs = (0..3).map { rec(at(2024, 5, 1 + it, 23)) }
        val f = Periods.filter(recs, d("2024-05-02"), d("2024-05-03"))
        assertEquals(listOf(at(2024, 5, 2, 23), at(2024, 5, 3, 23)), f.map { it.timestamp })
    }

    @Test
    fun missingHoursDstGap() {
        val recs = (0..23).filter { it != 1 }.map { rec(at(2023, 3, 26, it)) }
        val missing = Periods.findMissingHours(recs, d("2023-03-26"), d("2023-03-26"))
        assertEquals(listOf(at(2023, 3, 26, 1)), missing)
        assertTrue(Periods.isProbableDstSpringGap(missing[0]))
    }

    @Test
    fun missingHoursRegularGap() {
        val recs = (0..23).filter { it != 10 }.map { rec(at(2023, 6, 15, it)) }
        val missing = Periods.findMissingHours(recs)
        assertEquals(listOf(at(2023, 6, 15, 10)), missing)
        assertFalse(Periods.isProbableDstSpringGap(missing[0]))
        assertTrue(Periods.findMissingHours(emptyList()).isEmpty())
    }

    @Test
    fun dstGapDates() {
        assertTrue(Periods.isProbableDstSpringGap(at(2024, 3, 31, 2)))
        assertTrue(Periods.isProbableDstSpringGap(at(2025, 3, 30, 2)))
        assertFalse(Periods.isProbableDstSpringGap(at(2025, 3, 23, 2)))
    }
}
