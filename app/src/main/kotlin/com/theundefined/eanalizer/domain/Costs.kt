package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import java.time.YearMonth

/** Investment and long-term assumptions for storage profitability. */
data class StorageFinance(
    /** zł per kWh of capacity. */
    val pricePerKwh: Double,
    /** zł per installation regardless of size (hybrid inverter, mounting). */
    val fixedCost: Double = 0.0,
    /** Share of the gross investment covered by a subsidy, 0..1. */
    val subsidyShare: Double = 0.0,
    /** Subsidy cap, zł (0 = no cap). */
    val subsidyMax: Double = 0.0,
    /** Yearly capacity loss, 0..1, scales the savings of later years. */
    val degradation: Double = 0.0,
    /** Yearly energy price growth, scales the savings of later years. */
    val priceGrowth: Double = 0.0,
    /** Yearly discount rate (0 = plain sums). */
    val discountRate: Double = 0.0,
    val horizonYears: Int = 15,
) {
    /** Investment for [capacity] after the subsidy, 0 for no storage. */
    fun investment(capacity: Double): Double {
        if (capacity <= 0) return 0.0
        val gross = fixedCost + pricePerKwh * capacity
        val subsidy =
            (gross * subsidyShare).let { if (subsidyMax > 0) minOf(it, subsidyMax) else it }
        return (gross - subsidy).coerceAtLeast(0.0)
    }

    /** Savings of year [n] (1-based) for first-year savings [first], discounted to today. */
    fun yearSavings(first: Double, n: Int): Double =
        first * Math.pow((1 - degradation) * (1 + priceGrowth), n - 1.0) /
            Math.pow(1 + discountRate, n.toDouble())
}

/** One storage size: cost in the analysed period, savings scaled to a year and their outlook. */
data class StorageScenario(
    val capacity: Double,
    val cost: Double,
    val annualSavings: Double,
    val investment: Double,
    /** Years until the (degraded, price-adjusted, discounted) savings cover the investment. */
    val paybackYears: Double?,
    /** Sum of savings over the horizon minus the investment. */
    val netGain: Double,
    /** Extra annual savings per extra kWh compared with the next smaller size. */
    val marginalPerKwh: Double?,
)

object StorageEconomics {
    val CAPACITIES = listOf(0.0, 2.5, 5.0, 7.5, 10.0, 15.0, 20.0)

    /** Payback is searched up to this many years. */
    const val MAX_PAYBACK_YEARS = 40

    /**
     * Evaluates [capacities] (0 is always added as the baseline). [cost] returns the total cost of
     * the period for a capacity; savings are scaled from [days] to 365.
     */
    fun scenarios(
        capacities: Collection<Double>,
        days: Int,
        finance: StorageFinance,
        cost: (Double) -> Double,
    ): List<StorageScenario> {
        val caps = (capacities + 0.0).filter { it >= 0 }.distinct().sorted()
        val costs = caps.associateWith(cost)
        val base = costs.getValue(0.0)
        val scale = if (days > 0) 365.0 / days else 0.0
        var prev: Pair<Double, Double>? = null
        return caps.map { c ->
            val k = costs.getValue(c)
            val savings = (base - k) * scale
            val investment = finance.investment(c)
            val marginal = prev?.let { (pc, ps) -> (savings - ps) / (c - pc) }
            prev = c to savings
            StorageScenario(
                capacity = c,
                cost = k,
                annualSavings = savings,
                investment = investment,
                paybackYears = payback(finance, savings, investment),
                netGain =
                    (1..finance.horizonYears).sumOf { finance.yearSavings(savings, it) } -
                        investment,
                marginalPerKwh = marginal,
            )
        }
    }

    /** First (fractional) year in which cumulative savings reach [investment]. */
    fun payback(finance: StorageFinance, first: Double, investment: Double): Double? {
        if (first <= 0 || investment <= 0) return null
        var cumulative = 0.0
        for (n in 1..MAX_PAYBACK_YEARS) {
            val y = finance.yearSavings(first, n)
            if (cumulative + y >= investment) return n - 1 + (investment - cumulative) / y
            cumulative += y
        }
        return null
    }
}

/** How a storage of one size works over the analysed period. */
data class StorageUsage(
    val capacity: Double,
    val usable: Double,
    /** Energy delivered from the storage, kWh. */
    val discharged: Double,
    /** Grid energy put into the storage, kWh. */
    val fromGrid: Double,
    /** Energy still exported, kWh. */
    val exported: Double,
    /** Full equivalent cycles (delivered / usable capacity). */
    val cycles: Double,
    /** Share of days on which the storage got full. */
    val fullDays: Double,
    /** Energy delivered from the storage per month, kWh. */
    val monthly: List<Pair<YearMonth, Double>>,
) {
    companion object {
        fun of(capacity: Double, usable: Double, sim: List<SimulationRow>): StorageUsage {
            val discharged = sim.sumOf { it.poborZMagazynu }
            val days = sim.groupBy { it.timestamp.toLocalDate() }
            return StorageUsage(
                capacity = capacity,
                usable = usable,
                discharged = discharged,
                fromGrid = sim.sumOf { it.zSieciDoMagazynu },
                exported = sim.sumOf { it.oddanieDoSieci },
                cycles = if (usable > 0) discharged / usable else 0.0,
                fullDays =
                    if (days.isEmpty() || usable <= 0) 0.0
                    else
                        days.values.count { d -> d.any { it.stanMagazynu >= usable - 1e-6 } } /
                            days.size.toDouble(),
                monthly =
                    sim.groupBy { YearMonth.from(it.timestamp) }
                        .map { (m, rows) -> m to rows.sumOf { it.poborZMagazynu } }
                        .sortedBy { it.first },
            )
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
