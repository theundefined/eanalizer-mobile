package com.theundefined.eanalizer.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Average volumes in one hour of the day (kWh, before balancing). */
data class HourProfile(val hour: Int, val pobor: Double, val oddanie: Double)

/** Average day: workdays vs Saturdays, Sundays and Polish holidays (as in tariff zones). */
data class DayProfile(val workdays: List<HourProfile>, val weekends: List<HourProfile>)

/**
 * Average net consumption (import - export, before balancing) per month and hour; NaN = no data.
 */
data class Heatmap(val months: List<YearMonth>, val values: List<DoubleArray>) {
    val maxAbs: Double
        get() =
            values.maxOfOrNull { row ->
                row.filterNot { it.isNaN() }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            } ?: 0.0
}

/**
 * Forecast of the incomplete last month of the data: its volumes (zones too) scaled by 1 / [share],
 * where [share] is the part of the month's hours that has data.
 */
data class MonthForecast(val month: Int, val share: Double, val row: AggregateRow)

/**
 * Twelve months of one year; null where there is no data. [forecast] is set for the year holding
 * the incomplete last month of the data.
 */
data class YearMonths(
    val year: Int,
    val months: List<AggregateRow?>,
    val forecast: MonthForecast? = null,
) {
    /** Forecast for month [index] (0-based) if there is one, else its row. */
    fun forecastOrActual(index: Int): AggregateRow? =
        forecast?.takeIf { it.month == index + 1 }?.row ?: months[index]

    val poborPrzed: Double
        get() = months.sumOf { it?.poborPrzed ?: 0.0 }

    val oddaniePrzed: Double
        get() = months.sumOf { it?.oddaniePrzed ?: 0.0 }
}

/** Self-consumption of one month, with PV production estimated (the meter can't see it). */
data class SelfUseMonth(
    val month: YearMonth,
    val production: Double,
    val pobor: Double,
    val oddanie: Double,
    /** Interphase (vector) balancing: import offset by export within the hour. */
    val balanced: Double,
) {
    /** Production used on site (production - export), never negative. */
    val selfUse: Double
        get() = maxOf(0.0, production - oddanie)

    /** Share of production used on site. */
    val selfUseShare: Double
        get() = if (production > 0) selfUse / production else 0.0

    /** Share of consumption covered directly by own production. */
    val coverage: Double
        get() = if (selfUse + pobor > 0) selfUse / (selfUse + pobor) else 0.0
}

object Insights {
    fun isWorkday(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY &&
            d.dayOfWeek != DayOfWeek.SUNDAY &&
            !PolishHolidays.isHoliday(d)

    /** Average day profile; the hour is the start of the metering hour (like tariff zones). */
    fun dayProfile(records: List<HourlyRecord>): DayProfile {
        fun profile(rs: List<HourlyRecord>): List<HourProfile> {
            val byHour = rs.groupBy { it.timestamp.hour }
            return (0..23).map { h ->
                val list = byHour[h].orEmpty()
                if (list.isEmpty()) HourProfile(h, 0.0, 0.0)
                else
                    HourProfile(
                        h,
                        list.sumOf { it.poborPrzed } / list.size,
                        list.sumOf { it.oddaniePrzed } / list.size,
                    )
            }
        }
        val (work, free) = records.partition { isWorkday(it.timestamp.toLocalDate()) }
        return DayProfile(profile(work), profile(free))
    }

    /**
     * Only records accepted by [keep] are averaged (e.g. one tariff zone); months stay listed even
     * when nothing in them is kept.
     */
    fun heatmap(
        records: List<HourlyRecord>,
        keep: (LocalDateTime) -> Boolean = { true },
    ): Heatmap {
        val byMonth = records.groupBy { YearMonth.from(it.timestamp) }.toSortedMap()
        val values =
            byMonth.values.map { rs ->
                val byHour = rs.filter { keep(it.timestamp) }.groupBy { it.timestamp.hour }
                DoubleArray(24) { h ->
                    val list = byHour[h].orEmpty()
                    if (list.isEmpty()) Double.NaN
                    else list.sumOf { it.poborPrzed - it.oddaniePrzed } / list.size
                }
            }
        return Heatmap(byMonth.keys.toList(), values)
    }

    /**
     * Monthly sums grouped by year, newest year first. With [table] and [tariff] every month is
     * also split by the tariff's zones (see [Aggregation.monthly]).
     */
    fun byYear(
        records: List<HourlyRecord>,
        table: TariffTable? = null,
        tariff: String? = null,
    ): List<YearMonths> {
        val monthly =
            Aggregation.monthly(records, table, tariff).associateBy { YearMonth.parse(it.key) }
        val forecast = forecast(records, monthly)
        return monthly.keys
            .map { it.year }
            .distinct()
            .sortedDescending()
            .map { y ->
                YearMonths(
                    y,
                    (1..12).map { m -> monthly[YearMonth.of(y, m)] },
                    forecast?.takeIf { it.first.year == y }?.second,
                )
            }
    }

    /**
     * Forecast of the last month of the data when it has fewer hours than the month (e.g. 1/3 of
     * the month with data = volumes x 3).
     */
    private fun forecast(
        records: List<HourlyRecord>,
        monthly: Map<YearMonth, AggregateRow>,
    ): Pair<YearMonth, MonthForecast>? {
        val last = monthly.keys.maxOrNull() ?: return null
        val row = monthly.getValue(last)
        val hours = records.count { YearMonth.from(it.timestamp) == last }
        val share = hours.toDouble() / (last.lengthOfMonth() * 24)
        if (hours == 0 || share >= 1.0) return null
        return last to MonthForecast(last.monthValue, share, scale(row, 1 / share))
    }

    private fun scale(r: AggregateRow, f: Double) =
        r.copy(
            poborPrzed = r.poborPrzed * f,
            oddaniePrzed = r.oddaniePrzed * f,
            pobor = r.pobor * f,
            oddanie = r.oddanie * f,
            zones =
                r.zones.map {
                    it.copy(poborPrzed = it.poborPrzed * f, oddaniePrzed = it.oddaniePrzed * f)
                },
        )

    /**
     * Share of the annual PV production per month for a typical south-facing installation in Poland
     * (PVGIS-like profile). Sums to 1.
     */
    val PV_MONTHLY_SHARE =
        doubleArrayOf(
            0.026,
            0.045,
            0.082,
            0.115,
            0.132,
            0.132,
            0.135,
            0.119,
            0.090,
            0.067,
            0.035,
            0.022,
        )

    /** Typical annual yield in Poland, kWh per kWp. */
    const val PV_YIELD_PER_KWP = 1000.0

    /**
     * Self-consumption per month. Production is estimated from [annualProduction] (kWh/year) spread
     * over months with [PV_MONTHLY_SHARE] and over the days that have data.
     */
    fun selfUse(records: List<HourlyRecord>, annualProduction: Double): List<SelfUseMonth> =
        records
            .groupBy { YearMonth.from(it.timestamp) }
            .toSortedMap()
            .map { (month, rs) ->
                val days = rs.map { it.timestamp.toLocalDate() }.distinct().size
                val production =
                    annualProduction * PV_MONTHLY_SHARE[month.monthValue - 1] * days /
                        month.lengthOfMonth()
                SelfUseMonth(
                    month = month,
                    production = production,
                    pobor = rs.sumOf { it.poborPrzed },
                    oddanie = rs.sumOf { it.oddaniePrzed },
                    balanced = rs.sumOf { it.poborPrzed - it.pobor },
                )
            }
}
