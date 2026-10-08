package com.theundefined.eanalizer.domain

import java.time.YearMonth

/** Base (standby) load of one month, kW. */
data class BaseLoadMonth(val month: YearMonth, val kw: Double, val days: Int)

/**
 * Constant consumption in the background (fridge, router, standby), estimated from night import.
 * [kw] is the median over all days, [avgPrice] the average tariff price (energy + distribution) of
 * the analysed hours, [hours] their number and [consumption] the import before balancing.
 */
data class BaseLoad(
    val kw: Double,
    val months: List<BaseLoadMonth>,
    val avgPrice: Double,
    val hours: Int,
    val consumption: Double,
) {
    val annualKwh: Double
        get() = kw * HOURS_PER_YEAR

    val annualCost: Double
        get() = annualKwh * avgPrice

    /** Share of the import (before balancing) of the analysed hours. */
    val share: Double
        get() = if (consumption > 0) minOf(1.0, kw * hours / consumption) else 0.0

    companion object {
        const val HOURS_PER_YEAR = 8760.0
    }
}

/** Hour with the highest import of a month. */
data class MonthPeak(val month: YearMonth, val record: HourlyRecord)

/**
 * Peak load from hourly data. An hourly volume in kWh is the average power of that hour in kW;
 * short peaks within the hour (which trip fuses or exceed the contracted power) are higher.
 */
data class Peaks(
    val top: List<HourlyRecord>,
    val monthly: List<MonthPeak>,
    /** Hours by average power, upper bounds of [PEAK_BUCKETS_KW] (last = above). */
    val histogram: List<Int>,
) {
    companion object {
        val PEAK_BUCKETS_KW = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 8.0)
    }
}

object LoadAnalysis {
    /**
     * Night hours (start of the metering hour) used for the base load: no PV production, little
     * activity.
     */
    val NIGHT_HOURS = 0..3

    /**
     * Base load: per day the lowest night import (before balancing), median per month and over all
     * days. With a home battery covering the night the meter sees ~0, so the result is too low.
     * Null without night data.
     */
    fun baseLoad(records: List<HourlyRecord>, table: TariffTable, tariff: String): BaseLoad? {
        val daily =
            records
                .filter { it.timestamp.hour in NIGHT_HOURS }
                .groupBy { it.timestamp.toLocalDate() }
                .mapValues { (_, rs) -> rs.minOf { it.poborPrzed } }
        if (daily.isEmpty()) return null
        val months =
            daily.entries
                .groupBy { YearMonth.from(it.key) }
                .toSortedMap()
                .map { (m, days) -> BaseLoadMonth(m, median(days.map { it.value }), days.size) }
        val prices = records.mapNotNull { table.resolve(it.timestamp, tariff)?.price }
        return BaseLoad(
            kw = median(daily.values.toList()),
            months = months,
            avgPrice = if (prices.isEmpty()) 0.0 else prices.average(),
            hours = records.size,
            consumption = records.sumOf { it.poborPrzed },
        )
    }

    /** [n] hours with the highest import, the peak of every month and the power histogram. */
    fun peaks(records: List<HourlyRecord>, n: Int = 10): Peaks {
        val buckets = IntArray(Peaks.PEAK_BUCKETS_KW.size + 1)
        for (r in records) {
            val i = Peaks.PEAK_BUCKETS_KW.indexOfFirst { r.poborPrzed <= it }
            buckets[if (i < 0) buckets.size - 1 else i]++
        }
        return Peaks(
            top = records.sortedByDescending { it.poborPrzed }.take(n),
            monthly =
                records
                    .groupBy { YearMonth.from(it.timestamp) }
                    .toSortedMap()
                    .map { (m, rs) -> MonthPeak(m, rs.maxBy { it.poborPrzed }) },
            histogram = buckets.toList(),
        )
    }

    /** Hours whose average power exceeds [share] of [contractedKw]. */
    fun hoursAbove(records: List<HourlyRecord>, contractedKw: Double, share: Double): Int =
        if (contractedKw <= 0) 0 else records.count { it.poborPrzed > contractedKw * share }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
