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
}
