package com.theundefined.eanalizer.domain

import java.time.DayOfWeek
import java.time.LocalDate
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

/** Twelve months of one year; null where there is no data. */
data class YearMonths(val year: Int, val months: List<AggregateRow?>) {
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

    fun heatmap(records: List<HourlyRecord>): Heatmap {
        val byMonth = records.groupBy { YearMonth.from(it.timestamp) }.toSortedMap()
        val values =
            byMonth.values.map { rs ->
                val byHour = rs.groupBy { it.timestamp.hour }
                DoubleArray(24) { h ->
                    val list = byHour[h].orEmpty()
                    if (list.isEmpty()) Double.NaN
                    else list.sumOf { it.poborPrzed - it.oddaniePrzed } / list.size
                }
            }
        return Heatmap(byMonth.keys.toList(), values)
    }

    /** Monthly sums grouped by year, newest year first. */
    fun byYear(records: List<HourlyRecord>): List<YearMonths> {
        val monthly = Aggregation.monthly(records).associateBy { YearMonth.parse(it.key) }
        return monthly.keys
            .map { it.year }
            .distinct()
            .sortedDescending()
            .map { y -> YearMonths(y, (1..12).map { m -> monthly[YearMonth.of(y, m)] }) }
    }

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
