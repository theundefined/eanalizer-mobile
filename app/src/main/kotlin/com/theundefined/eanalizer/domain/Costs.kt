package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import java.time.YearMonth

/** One storage size: cost in the analysed period and savings scaled to a year. */
data class StorageScenario(
    val capacity: Double,
    val cost: Double,
    val annualSavings: Double,
    val investment: Double,
) {
    /** Simple payback in years, null when the storage doesn't save anything. */
    val paybackYears: Double?
        get() = if (annualSavings > 0 && investment > 0) investment / annualSavings else null
}

object StorageEconomics {
    val CAPACITIES = listOf(0.0, 2.5, 5.0, 7.5, 10.0, 15.0, 20.0)

    /**
     * Evaluates [capacities] (0 is always added as the baseline). [cost] returns the total cost of
     * the period for a capacity; savings are scaled from [days] to 365, the investment is
     * [pricePerKwh] × capacity.
     */
    fun scenarios(
        capacities: Collection<Double>,
        days: Int,
        pricePerKwh: Double,
        cost: (Double) -> Double,
    ): List<StorageScenario> {
        val caps = (capacities + 0.0).filter { it >= 0 }.distinct().sorted()
        val costs = caps.associateWith(cost)
        val base = costs.getValue(0.0)
        val scale = if (days > 0) 365.0 / days else 0.0
        return caps.map { c ->
            val k = costs.getValue(c)
            StorageScenario(c, k, (base - k) * scale, pricePerKwh * c)
        }
    }
}

/** One zone on a monthly bill. */
data class BillLine(
    val zone: String,
    val kwh: Double,
    val energyPrice: Double,
    val distPrice: Double,
) {
    val energyCost: Double
        get() = kwh * energyPrice

    val distCost: Double
        get() = kwh * distPrice
}

/** Monthly bill resembling an invoice (not a reproduction of it). */
data class MonthlyBill(
    val month: YearMonth,
    val lines: List<BillLine>,
    val fixedFee: Double,
    /** Net-billing: active energy covered by the deposit, null outside net-billing. */
    val coveredByDeposit: Double?,
    /** Net-billing: refund of an expiring deposit, null outside net-billing. */
    val refund: Double?,
) {
    val energyCost: Double
        get() = lines.sumOf { it.energyCost }

    val distCost: Double
        get() = lines.sumOf { it.distCost }

    val total: Double
        get() = energyCost + distCost + fixedFee - (coveredByDeposit ?: 0.0) - (refund ?: 0.0)
}

object Bills {
    /**
     * Monthly bills of [simulation] (grid import after storage) in [tariff]; with [netBilling] the
     * deposit coverage/refund of each month is included.
     */
    fun monthly(
        simulation: List<SimulationRow>,
        table: TariffTable,
        tariff: String,
        netBilling: NetBillingResult? = null,
    ): List<MonthlyBill> {
        val nb = netBilling?.months?.associateBy { it.month }
        val fee = table.fixedFee(tariff)
        return simulation
            .groupBy { YearMonth.from(it.timestamp) }
            .toSortedMap()
            .map { (month, rows) ->
                val lines = LinkedHashMap<String, BillLine>()
                for (r in rows) {
                    val zp = table.resolve(r.timestamp, tariff) ?: continue
                    val l = lines[zp.zone]
                    lines[zp.zone] =
                        if (l == null)
                            BillLine(zp.zone, r.poborZSieci, zp.energyPrice, zp.distPrice)
                        else l.copy(kwh = l.kwh + r.poborZSieci)
                }
                val m = nb?.get(month)
                MonthlyBill(
                    month,
                    lines.values.toList(),
                    fee,
                    m?.pokryteDepozytem ?: nb?.let { 0.0 },
                    m?.zwrotNadplaty ?: nb?.let { 0.0 },
                )
            }
    }
}

/** Cost of the period in a dynamic (hourly) tariff. */
data class DynamicTariffResult(
    val energyCost: Double,
    val distCost: Double,
    val fixedFees: Double,
    /** Net-billing settlement with hourly prices, null outside net-billing. */
    val netBilling: NetBillingResult?,
    /** Hours with import but no RCE price (priced at the regular tariff). */
    val missingHours: Int,
) {
    val totalCost: Double
        get() = netBilling?.calkowityKoszt ?: (energyCost + distCost + fixedFees)
}

/**
 * Estimate of a dynamic tariff: energy at hourly RCE + seller margin, plus VAT; distribution and
 * fixed fees from the distribution tariff. Real offers use the TGE day-ahead price and their own
 * margin/excise, so this is an approximation. Negative prices are floored at 0 (no payment for
 * consuming). Hours without RCE fall back to the tariff's energy price.
 */
object DynamicTariff {
    const val VAT = 1.23

    /** Gross zł/kWh for an hourly RCE price and a net margin (both zł/kWh). */
    fun price(rce: Double, marginNet: Double): Double = maxOf(0.0, (rce + marginNet) * VAT)

    fun cost(
        simulation: List<SimulationRow>,
        table: TariffTable,
        distTariff: String,
        rce: Map<LocalDateTime, Double>,
        marginNet: Double,
        fixedFees: Double,
        netBilling: Boolean,
        history: List<SimulationRow> = emptyList(),
        rcem: Map<YearMonth, Double> = emptyMap(),
    ): DynamicTariffResult {
        var missing = 0
        fun energy(ts: LocalDateTime): Double {
            val p = rce[ts]?.takeIf { !it.isNaN() }
            if (p != null) return price(p, marginNet)
            return table.resolve(ts, distTariff)?.energyPrice ?: 0.0
        }
        var energyCost = 0.0
        var distCost = 0.0
        for (r in simulation) {
            if (r.poborZSieci > 0 && rce[r.timestamp] == null) missing++
            energyCost += r.poborZSieci * energy(r.timestamp)
            distCost += r.poborZSieci * (table.resolve(r.timestamp, distTariff)?.distPrice ?: 0.0)
        }
        val nb =
            if (netBilling)
                NetBilling.settle(
                    simulation,
                    table,
                    distTariff,
                    rce,
                    rcem,
                    NetBillingValuation.RCE,
                    fixedFees,
                    history,
                    energyPrice = ::energy,
                )
            else null
        return DynamicTariffResult(energyCost, distCost, fixedFees, nb, missing)
    }
}
