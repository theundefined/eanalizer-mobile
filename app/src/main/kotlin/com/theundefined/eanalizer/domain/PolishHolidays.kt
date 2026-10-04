package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Public (non-working) holidays in Poland. */
object PolishHolidays {
    private val cache = ConcurrentHashMap<Int, Set<LocalDate>>()

    fun forYear(year: Int): Set<LocalDate> = cache.getOrPut(year) { compute(year) }

    fun isHoliday(date: LocalDate): Boolean = date in forYear(date.year)

    /** Gregorian Easter Sunday (Meeus/Jones/Butcher algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private fun compute(year: Int): Set<LocalDate> {
        val easter = easter(year)
        fun d(m: Int, day: Int) = LocalDate.of(year, m, day)
        return buildSet {
            add(d(1, 1))
            if (year >= 2011) add(d(1, 6))
            add(easter)
            add(easter.plusDays(1))
            add(d(5, 1))
            add(d(5, 3))
            add(easter.plusDays(49)) // Zielone Świątki
            add(easter.plusDays(60)) // Boże Ciało
            add(d(8, 15))
            add(d(11, 1))
            add(d(11, 11))
            if (year >= 2025) add(d(12, 24))
            add(d(12, 25))
            add(d(12, 26))
        }
    }
}
