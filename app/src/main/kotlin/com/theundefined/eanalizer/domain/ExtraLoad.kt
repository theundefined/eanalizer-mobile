package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Additional consumption for a what-if: a heat pump with [heatPumpAnnualKwh] spread over the year
 * by [ExtraLoads.HEAT_MONTHLY_SHARE] (evenly within a month), and an electric car driving
 * [evKmPerDay] every day, charged from [evStartHour] at most [evChargeKw] per hour until the day's
 * energy is in.
 */
data class ExtraLoad(
    val heatPumpAnnualKwh: Double = 0.0,
    val evKmPerDay: Double = 0.0,
    val evKwhPer100Km: Double = 18.0,
    val evChargeKw: Double = 3.7,
    val evStartHour: Int = 22,
) {
    val evDailyKwh: Double
        get() = evKmPerDay * evKwhPer100Km / 100

    val isEmpty: Boolean
        get() = heatPumpAnnualKwh <= 0 && evDailyKwh <= 0
}

object ExtraLoads {
    /**
     * Share of the annual heat pump consumption per month for Poland (heating degree days, plus hot
     * water in summer). Sums to 1.
     */
    val HEAT_MONTHLY_SHARE =
        doubleArrayOf(
            0.180,
            0.155,
            0.130,
            0.080,
            0.030,
            0.010,
            0.010,
            0.010,
            0.030,
            0.080,
            0.120,
            0.165,
        )

    /** Extra kWh per hour of [records] (hours without data get nothing). */
    fun hourly(records: List<HourlyRecord>, load: ExtraLoad): Map<LocalDateTime, Double> {
        val out = HashMap<LocalDateTime, Double>()
        val present = records.mapTo(HashSet()) { it.timestamp }
        if (load.heatPumpAnnualKwh > 0) {
            for (ts in present) {
                val m = YearMonth.from(ts)
                val perHour =
                    load.heatPumpAnnualKwh * HEAT_MONTHLY_SHARE[m.monthValue - 1] /
                        (m.lengthOfMonth() * 24)
                out.merge(ts, perHour, Double::plus)
            }
        }
        val daily = load.evDailyKwh
        val power = load.evChargeKw
        if (daily > 0 && power > 0) {
            for (date in present.map { it.toLocalDate() }.distinct()) {
                var left = daily
                var ts = date.atTime(load.evStartHour.coerceIn(0, 23), 0)
                while (left > 1e-9) {
                    val e = minOf(left, power)
                    if (ts in present) out.merge(ts, e, Double::plus)
                    left -= e
                    ts = ts.plusHours(1)
                }
            }
        }
        return out
    }

    /**
     * [records] with the [extra] consumption added: import before balancing grows by it; after
     * balancing the hour's export absorbs it first.
     */
    fun apply(records: List<HourlyRecord>, extra: Map<LocalDateTime, Double>): List<HourlyRecord> =
        records.map { r ->
            val e = extra[r.timestamp] ?: return@map r
            r.copy(
                poborPrzed = r.poborPrzed + e,
                pobor = r.pobor + maxOf(0.0, e - r.oddanie),
                oddanie = maxOf(0.0, r.oddanie - e),
            )
        }
}
