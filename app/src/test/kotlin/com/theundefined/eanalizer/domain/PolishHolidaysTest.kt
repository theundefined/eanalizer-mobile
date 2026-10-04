package com.theundefined.eanalizer.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishHolidaysTest {
    @Test
    fun easterDates() {
        assertEquals(LocalDate.of(2024, 3, 31), PolishHolidays.easter(2024))
        assertEquals(LocalDate.of(2025, 4, 20), PolishHolidays.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), PolishHolidays.easter(2026))
        assertEquals(LocalDate.of(2019, 4, 21), PolishHolidays.easter(2019))
        assertEquals(LocalDate.of(2038, 4, 25), PolishHolidays.easter(2038))
    }

    @Test
    fun holidays2024() {
        val h = PolishHolidays.forYear(2024)
        assertEquals(13, h.size)
        listOf(
                "2024-01-01",
                "2024-01-06",
                "2024-03-31",
                "2024-04-01",
                "2024-05-01",
                "2024-05-03",
                "2024-05-19",
                "2024-05-30",
                "2024-08-15",
                "2024-11-01",
                "2024-11-11",
                "2024-12-25",
                "2024-12-26",
            )
            .forEach { assertTrue(it, LocalDate.parse(it) in h) }
        assertFalse(LocalDate.of(2024, 12, 24) in h)
    }

    @Test
    fun christmasEveFrom2025() {
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2025, 12, 24)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2026, 12, 24)))
        assertEquals(14, PolishHolidays.forYear(2026).size)
    }

    @Test
    fun movableFeasts2025and2026() {
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2025, 4, 21)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2025, 6, 8)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2025, 6, 19)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2026, 4, 6)))
        assertTrue(PolishHolidays.isHoliday(LocalDate.of(2026, 6, 4)))
        assertFalse(PolishHolidays.isHoliday(LocalDate.of(2026, 4, 7)))
    }
}
