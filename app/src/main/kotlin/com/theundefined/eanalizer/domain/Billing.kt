package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.time.YearMonth

/** Cost from the last settlement (invoice) date to the end of the data. */
data class BillingToDate(
    val from: LocalDate,
    val to: LocalDate,
    val result: AnalysisResult,
    /** Fixed fees prorated by days (see [Billing.fixedFees]). */
    val fixedFees: Double,
    /** Net-billing settlement of the range, null outside net-billing. */
    val netBilling: NetBillingResult?,
) {
    val days: Int
        get() = (to.toEpochDay() - from.toEpochDay() + 1).toInt()

    /** Energy and distribution (after net-metering credit / deposit), without fixed fees. */
    val usageCost: Double
        get() = netBilling?.let { it.energiaDoZaplaty + it.kosztDystrybucji } ?: result.energyCost

    val total: Double
        get() = netBilling?.calkowityKoszt ?: (result.energyCost + fixedFees)
}

/**
 * State of a settlement period as if it were settled at the end of [date]: total cost (with fixed
 * fees prorated to that day) and the credit left - unused net-metering credit in kWh, or the
 * net-billing deposit left in zł; null without a settlement of exported energy.
 */
data class BillingPoint(
    val date: LocalDate,
    val cost: Double,
    val credit: Double?,
    /**
     * Net-metering only: kWh balance, unused credit minus energy still to pay for (negative when
     * the exported energy did not cover the consumption); null elsewhere.
     */
    val balance: Double? = null,
    /** Per-zone (tariff) split of the day's state; empty with a net-billing settlement. */
    val zones: List<ZoneStats> = emptyList(),
)

/** Cost since the settlement date day by day, and the same days of the year before. */
data class BillingTimeline(
    val current: List<BillingPoint>,
    /** The whole previous cycle (a year before the settlement date up to the day before it). */
    val previous: List<BillingPoint>,
    /** Cycles 2, 3, ... years before the current one (nearest first), as full as [previous]. */
    val older: List<List<BillingPoint>> = emptyList(),
)

object Billing {
    /**
     * Monthly [fee] for the days `[from, to]`: each month counts with the share of its days in the
     * range (unlike [Analyzer.runFullAnalysis], which charges every touched month in full).
     */
    fun fixedFees(fee: Double, from: LocalDate, to: LocalDate): Double {
        if (to < from) return 0.0
        var sum = 0.0
        var m = YearMonth.from(from)
        while (m <= YearMonth.from(to)) {
            val start = maxOf(from, m.atDay(1))
            val end = minOf(to, m.atEndOfMonth())
            sum += fee * (end.toEpochDay() - start.toEpochDay() + 1) / m.lengthOfMonth()
            m = m.plusMonths(1)
        }
        return sum
    }

    /**
     * Cost of [records] in `[from, to]` in [tariff] without storage. [settle] turns the simulation
     * and the prorated fixed fees into a net-billing settlement (null = no net-billing).
     *
     * Net-billing limitation: deposits are monthly, so days of the first month before [from] are
     * neither in the period nor in the history - their export creates no deposit.
     */
    fun toDate(
        records: List<HourlyRecord>,
        from: LocalDate,
        to: LocalDate,
        table: TariffTable,
        tariff: String,
        netMeteringRatio: Double? = null,
        settle: (List<SimulationRow>, Double) -> NetBillingResult? = { _, _ -> null },
    ): BillingToDate? {
        val recs = Periods.filter(records, from, to)
        if (recs.isEmpty()) return null
        val result = Analyzer.runFullAnalysis(recs, 0.0, table, tariff, netMeteringRatio)
        val fees = fixedFees(table.fixedFee(tariff), from, to)
        return BillingToDate(from, to, result, fees, settle(result.simulation, fees))
    }

    /**
     * [BillingPoint]s of [simulation] (rows from [from] on, no storage) for every day in `[from,
     * to]` - the last one equals [toDate]'s total and unused credit. Days without data carry the
     * previous values. With [settle] (net-billing) the cost and deposit come from settling the rows
     * up to the day, only every [settleStep] days and on the last day (it is much slower).
     */
    fun timeline(
        simulation: List<SimulationRow>,
        from: LocalDate,
        to: LocalDate,
        table: TariffTable,
        tariff: String,
        netMeteringRatio: Double? = null,
        settle: ((List<SimulationRow>, Double) -> NetBillingResult?)? = null,
        settleStep: Int = 7,
    ): List<BillingPoint> {
        if (to < from) return emptyList()
        val fee = table.fixedFee(tariff)
        val acc = LinkedHashMap<String, Analyzer.ZoneAcc>()
        val points = ArrayList<BillingPoint>()
        var i = 0
        var day = from
        while (day <= to) {
            val end = day.plusDays(1).atStartOfDay()
            while (i < simulation.size && simulation[i].timestamp < end) {
                val row = simulation[i++]
                table.resolve(row.timestamp, tariff)?.let {
                    val z = acc.getOrPut(it.zone) { Analyzer.ZoneAcc(it.price) }
                    z.pobor += row.poborZSieci
                    z.oddanie += row.oddanieDoSieci
                    z.koszt += row.poborZSieci * z.price
                }
            }
            val fees = fixedFees(fee, from, day)
            if (settle != null) {
                val offset = day.toEpochDay() - from.toEpochDay()
                if (offset % settleStep == 0L || day == to)
                    settle(simulation.subList(0, i), fees)?.let {
                        points += BillingPoint(day, it.calkowityKoszt, it.depozytPozostaly)
                    }
            } else if (netMeteringRatio != null) {
                val (zones, cost, unused) = Analyzer.netMetering(acc, netMeteringRatio)
                val toPay = zones.sumOf { it.energiaDoOplacenia ?: 0.0 }
                points += BillingPoint(day, cost + fees, unused, unused - toPay, zones)
            } else {
                val zones =
                    acc.map { (n, z) -> ZoneStats(n, z.price, z.pobor, z.oddanie, z.koszt) }
                points += BillingPoint(day, zones.sumOf { it.kosztPoboru } + fees, null, null, zones)
            }
            day = day.plusDays(1)
        }
        return points
    }

    /**
     * The cycle a year before a period starting at [from]: from the same date a year earlier to the
     * day before [from]; null when the data ([dataStart]) starts later (a partial cycle would not
     * be comparable).
     */
    fun previousCycle(from: LocalDate, dataStart: LocalDate): Pair<LocalDate, LocalDate>? {
        val start = from.minusYears(1)
        return if (start < dataStart) null else start to from.minusDays(1)
    }

    /** [timeline] of [records] in `[from, to]` ([toDate] without storage); empty without data. */
    fun timelineOf(
        records: List<HourlyRecord>,
        from: LocalDate,
        to: LocalDate,
        table: TariffTable,
        tariff: String,
        netMeteringRatio: Double? = null,
        settle: ((List<SimulationRow>, Double) -> NetBillingResult?)? = null,
    ): List<BillingPoint> =
        toDate(records, from, to, table, tariff, netMeteringRatio)?.let {
            timeline(it.result.simulation, from, to, table, tariff, netMeteringRatio, settle)
        } ?: emptyList()
}
