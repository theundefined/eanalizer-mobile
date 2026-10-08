package com.theundefined.eanalizer.domain

import java.time.YearMonth

/** One future month of the net-billing deposit forecast (zł). */
data class DepositForecastMonth(
    val month: YearMonth,
    /** Estimated active-energy cost of the month. */
    val expectedCost: Double,
    /** Part of [expectedCost] paid from the deposits. */
    val covered: Double,
    /** Deposit left in deposits whose validity ends this month. */
    val expiring: Double,
    val refund: Double,
    val lost: Double,
    /** Deposits left after the month. */
    val balance: Double,
)

/**
 * What happens to the deposits left at the end of the data: future months use them oldest first
 * (like [NetBilling.settle]) for the expected energy cost, expiring ones are refunded up to their
 * limit and the rest is lost. Deposits created by future export are not modelled: they are used
 * after the existing ones and expire later, so they don't change the result.
 */
object DepositForecast {
    /** Months from [start] until the last of [deposits] expires. */
    fun forecast(
        deposits: List<DepositLeft>,
        start: YearMonth,
        expectedCost: (YearMonth) -> Double,
    ): List<DepositForecastMonth> {
        val last = deposits.maxOfOrNull { it.to } ?: return emptyList()
        val left = deposits.sortedBy { it.from }.map { it to it.left }.toMutableList()
        val out = ArrayList<DepositForecastMonth>()
        var month = start
        while (month <= last) {
            val cost = maxOf(0.0, expectedCost(month))
            var toCover = cost
            for (i in left.indices) {
                val (dep, l) = left[i]
                if (toCover <= 0) break
                if (month >= dep.from && month <= dep.to && l > 0) {
                    val used = minOf(l, toCover)
                    left[i] = dep to l - used
                    toCover -= used
                }
            }
            var expiring = 0.0
            var refund = 0.0
            for (i in left.indices) {
                val (dep, l) = left[i]
                if (dep.to == month && l > 0) {
                    expiring += l
                    refund += minOf(l, dep.value * dep.refundLimit)
                    left[i] = dep to 0.0
                }
            }
            out +=
                DepositForecastMonth(
                    month = month,
                    expectedCost = cost,
                    covered = cost - toCover,
                    expiring = expiring,
                    refund = refund,
                    lost = expiring - refund,
                    balance = left.sumOf { it.second },
                )
            month = month.plusMonths(1)
        }
        return out
    }

    /**
     * Expected active-energy cost of a month: the same month a year earlier in [months], else the
     * average of [months]; the last month (usually incomplete) is used only when it is the only
     * one. 0 without data.
     */
    fun expectedCost(months: List<NetBillingMonth>): (YearMonth) -> Double {
        val full = months.dropLast(1).ifEmpty { months }
        val byMonth = full.associate { it.month to it.kosztEnergii }
        val average = if (full.isEmpty()) 0.0 else full.sumOf { it.kosztEnergii } / full.size
        return { m -> byMonth[m.minusYears(1)] ?: average }
    }
}
