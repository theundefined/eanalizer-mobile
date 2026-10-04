package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Cost of consumption / revenue from export at RCE market prices (zł). */
data class RceResult(
    val cost: Double,
    val revenue: Double,
    val balance: Double,
    val missingHours: Int
)

object RceAnalysis {
    private val DTIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

    /**
     * Prices post-balancing volumes with hourly [prices] (zł/kWh); hours without price are counted.
     */
    fun run(records: List<HourlyRecord>, prices: Map<LocalDateTime, Double>): RceResult {
        var cost = 0.0
        var revenue = 0.0
        var missing = 0
        for (r in records) {
            val p = prices[r.timestamp]
            if (p == null || p.isNaN()) {
                missing++
                continue
            }
            cost += r.pobor * p
            revenue += r.oddanie * p
        }
        return RceResult(cost, revenue, revenue - cost, missing)
    }

    /**
     * Converts PSE `rce-pln` entries (`dtime`, price in zł/MWh, typically 15-min) to hourly mean
     * prices in zł/kWh. Like pandas `resample("h")`, an entry belongs to the hour it starts in
     * (e.g. `01:00` goes to 01:00). `a`/`b` DST suffixes in `dtime` are ignored.
     */
    fun hourlyPrices(entries: List<Pair<String, Double>>): Map<LocalDateTime, Double> =
        entries
            .mapNotNull { (dtime, price) ->
                val s = dtime.replace("a", "").replace("b", "").trim().replace('T', ' ')
                runCatching { LocalDateTime.parse(s, DTIME) }
                    .getOrNull()
                    ?.let { it.truncatedTo(ChronoUnit.HOURS) to price }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.average() / 1000.0 }
            .toSortedMap()
}
