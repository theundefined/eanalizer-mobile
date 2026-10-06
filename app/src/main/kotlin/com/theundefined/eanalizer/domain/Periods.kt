package com.theundefined.eanalizer.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

enum class Period {
    LAST_30_DAYS,
    LAST_90_DAYS,
    LAST_365_DAYS,
    CURRENT_MONTH,
    PREVIOUS_MONTH,
    CURRENT_YEAR,
    PREVIOUS_YEAR,
    ALL,

    /** User-chosen date range. */
    CUSTOM,
}

/** Date-range helpers. Periods are relative to the last date in the data, not today. */
object Periods {
    /**
     * Inclusive `(from, to)` for [period]; start is clamped to [dataStart]. [Period.CUSTOM] uses
     * [customFrom]/[customTo] clamped to the data (all data when not set).
     */
    fun resolve(
        period: Period,
        dataStart: LocalDate,
        dataEnd: LocalDate,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
    ): Pair<LocalDate, LocalDate> {
        val end = dataEnd
        fun clamp(from: LocalDate, to: LocalDate = end) = maxOf(from, dataStart) to to
        return when (period) {
            Period.LAST_30_DAYS -> lastDays(30, dataStart, end)
            Period.LAST_90_DAYS -> lastDays(90, dataStart, end)
            Period.LAST_365_DAYS -> lastDays(365, dataStart, end)
            Period.CURRENT_MONTH -> clamp(end.withDayOfMonth(1))
            Period.PREVIOUS_MONTH -> {
                val last = end.withDayOfMonth(1).minusDays(1)
                clamp(last.withDayOfMonth(1), last)
            }
            Period.CURRENT_YEAR -> clamp(end.withDayOfYear(1))
            Period.PREVIOUS_YEAR ->
                clamp(LocalDate.of(end.year - 1, 1, 1), LocalDate.of(end.year - 1, 12, 31))
            Period.ALL -> dataStart to end
            Period.CUSTOM -> {
                if (customFrom == null || customTo == null) return dataStart to end
                val from = minOf(customFrom, customTo).coerceIn(dataStart, end)
                from to maxOf(customFrom, customTo).coerceIn(from, end)
            }
        }
    }

    /** Last [n] days ending at [dataEnd] (inclusive), clamped to [dataStart]. */
    fun lastDays(n: Int, dataStart: LocalDate, dataEnd: LocalDate): Pair<LocalDate, LocalDate> =
        maxOf(dataEnd.minusDays(n - 1L), dataStart) to dataEnd

    /** Records whose date is within `[from, to]`. */
    fun filter(records: List<HourlyRecord>, from: LocalDate, to: LocalDate): List<HourlyRecord> =
        records.filter { it.timestamp.toLocalDate().let { d -> d >= from && d <= to } }

    /**
     * Hours missing in [records] between [from] 00:00 and [to] 23:00 (defaults: first/last record).
     */
    fun findMissingHours(
        records: List<HourlyRecord>,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): List<LocalDateTime> {
        if (records.isEmpty()) return emptyList()
        val present = records.mapTo(HashSet()) { it.timestamp }
        var t = from?.atStartOfDay() ?: records.minOf { it.timestamp }
        val end = to?.atTime(23, 0) ?: records.maxOf { it.timestamp }
        val out = ArrayList<LocalDateTime>()
        while (t <= end) {
            if (t !in present) out += t
            t = t.plusHours(1)
        }
        return out
    }

    /** True on the last Sunday of March (spring DST switch — one hour legitimately missing). */
    fun isProbableDstSpringGap(ts: LocalDateTime): Boolean =
        ts.toLocalDate() ==
            LocalDate.of(ts.year, 3, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
}
