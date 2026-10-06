package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import java.time.YearMonth

/** How exported energy is valued in net-billing. */
enum class NetBillingValuation {
    /** Monthly market price RCEm. */
    RCEM,

    /** Hourly RCE (from 07.2024; earlier months fall back to RCEm). Negative prices count as 0. */
    RCE,
}

data class NetBillingMonth(
    val month: YearMonth,
    val pobor: Double,
    val oddanie: Double,
    val kosztEnergii: Double,
    val pokryteDepozytem: Double,
    val kosztDystrybucji: Double,
    val nowyDepozyt: Double,
    val zwrotNadplaty: Double,
    val saldoDepozytu: Double,
) {
    val energiaDoZaplaty: Double
        get() = kosztEnergii - pokryteDepozytem
}

data class NetBillingResult(
    val valuation: NetBillingValuation,
    val kosztEnergii: Double,
    val energiaDoZaplaty: Double,
    val kosztDystrybucji: Double,
    val oplatyStale: Double,
    /** Deposit carried over from [NetBilling.settle]'s `history` into the first month. */
    val depozytPoczatkowy: Double,
    val wartoscDepozytu: Double,
    val pokryteDepozytem: Double,
    val zwrotNadplaty: Double,
    val przepadlyDepozyt: Double,
    val depozytPozostaly: Double,
    val calkowityKoszt: Double,
    val missingRceHours: Int,
    val missingRcemMonths: List<YearMonth>,
    val months: List<NetBillingMonth>,
)

/**
 * Prosumer settlement in the net-billing system (port of eanalizer `netbilling.py`):
 * - consumption is bought at tariff prices (energy + distribution),
 * - exported energy creates a deposit valued at RCEm (monthly) or hourly RCE, negative prices as 0,
 * - from 02.2025 the deposit is multiplied by 1.23,
 * - a deposit from month M is usable from M+1 for 12 months, covers active energy only (not
 *   distribution), oldest funds first,
 * - unused deposit is refunded up to 20% of its value (30% for hourly RCE from 02.2025); refunds
 *   apply to deposits expiring from 07.2024.
 *
 * Unlike eanalizer, settlement may start with deposits created before the analysed period (see
 * `history` in [NetBilling.settle]).
 */
object NetBilling {
    val HOURLY_RCE_START: YearMonth = YearMonth.of(2024, 7)
    val COEFFICIENT_START: YearMonth = YearMonth.of(2025, 2)
    const val COEFFICIENT = 1.23
    val REFUND_START: YearMonth = YearMonth.of(2024, 7)
    const val REFUND_LIMIT_RCEM = 0.20
    const val REFUND_LIMIT_RCE = 0.30
    const val DEPOSIT_VALIDITY_MONTHS = 12L

    private class MonthAcc {
        var pobor = 0.0
        var oddanie = 0.0
        var kosztEnergii = 0.0
        var kosztDystrybucji = 0.0
    }

    private class Deposit(
        val from: YearMonth,
        val to: YearMonth,
        val value: Double,
        var left: Double,
        val refundLimit: Double,
    )

    /**
     * Settles [simulation] rows (grid import/export after any physical storage). [rcePrices] are
     * hourly zł/kWh, [rcemPrices] monthly zł/kWh. Returns null when there is nothing to settle.
     *
     * [history] are simulation rows before the analysed period (whole months before the month of
     * the first [simulation] row, typically 12): they are settled the same way so deposits created
     * then (and not used/expired yet) are available in the period, but they are not part of the
     * returned totals or months.
     *
     * [energyPrice] overrides the tariff's energy price per hour (gross zł/kWh, e.g. a dynamic
     * tariff); distribution always comes from [tariff].
     */
    fun settle(
        simulation: List<SimulationRow>,
        table: TariffTable,
        tariff: String,
        rcePrices: Map<LocalDateTime, Double>,
        rcemPrices: Map<YearMonth, Double>,
        valuation: NetBillingValuation = NetBillingValuation.RCEM,
        fixedFee: Double = 0.0,
        history: List<SimulationRow> = emptyList(),
        energyPrice: ((LocalDateTime) -> Double)? = null,
    ): NetBillingResult? {
        if (simulation.isEmpty()) return null
        val reportStart = YearMonth.from(simulation.first().timestamp)
        val past = history.filter { YearMonth.from(it.timestamp) < reportStart }
        val monthly = HashMap<YearMonth, MonthAcc>()
        val hourlyValue = HashMap<YearMonth, Double>()
        var missingRce = 0
        val hourlyStart = HOURLY_RCE_START.atDay(1).atStartOfDay()

        for (row in past + simulation) {
            val month = YearMonth.from(row.timestamp)
            val m = monthly.getOrPut(month) { MonthAcc() }
            val zp = table.resolve(row.timestamp, tariff)
            m.pobor += row.poborZSieci
            m.oddanie += row.oddanieDoSieci
            val energy = energyPrice?.invoke(row.timestamp) ?: zp?.energyPrice ?: 0.0
            m.kosztEnergii += row.poborZSieci * energy
            m.kosztDystrybucji += row.poborZSieci * (zp?.distPrice ?: 0.0)
            if (
                valuation == NetBillingValuation.RCE &&
                    row.timestamp >= hourlyStart &&
                    row.oddanieDoSieci > 0
            ) {
                val price =
                    rcePrices[row.timestamp]?.takeIf { !it.isNaN() }
                        ?: run {
                            missingRce++
                            0.0
                        }
                hourlyValue[month] =
                    (hourlyValue[month] ?: 0.0) + row.oddanieDoSieci * maxOf(price, 0.0)
            }
        }

        val first = monthly.keys.min()
        val last = monthly.keys.max()
        val missingRcem = ArrayList<YearMonth>()
        val deposits = ArrayList<Deposit>()
        val rows = ArrayList<NetBillingMonth>()
        var wartosc = 0.0
        var pokryte = 0.0
        var zwrot = 0.0
        var przepadly = 0.0
        var opening = 0.0

        var month = first
        while (month <= last) {
            if (month == reportStart) opening = deposits.sumOf { it.left }
            val m = monthly[month] ?: MonthAcc()

            // 1. Active-energy cost covered from deposits, oldest first.
            var toCover = m.kosztEnergii
            var covered = 0.0
            for (dep in deposits) {
                if (toCover <= 0) break
                if (month >= dep.from && month <= dep.to && dep.left > 0) {
                    val used = minOf(dep.left, toCover)
                    dep.left -= used
                    toCover -= used
                    covered += used
                }
            }

            // 2. Expiring deposits: refund, the rest is lost.
            var refund = 0.0
            for (dep in deposits) {
                if (dep.to == month && dep.left > 0) {
                    val limit = if (month >= REFUND_START) dep.refundLimit else 0.0
                    val depRefund = minOf(dep.left, dep.value * limit)
                    refund += depRefund
                    if (month >= reportStart) przepadly += dep.left - depRefund
                    dep.left = 0.0
                }
            }

            // 3. New deposit from this month's export.
            val hourly = valuation == NetBillingValuation.RCE && month >= HOURLY_RCE_START
            val baseValue =
                when {
                    hourly -> hourlyValue[month] ?: 0.0
                    m.oddanie > 0 -> {
                        val rcem =
                            rcemPrices[month]
                                ?: run {
                                    missingRcem += month
                                    0.0
                                }
                        m.oddanie * maxOf(rcem, 0.0)
                    }
                    else -> 0.0
                }
            val depositValue = baseValue * (if (month >= COEFFICIENT_START) COEFFICIENT else 1.0)
            val refundLimit =
                if (hourly && month >= COEFFICIENT_START) REFUND_LIMIT_RCE else REFUND_LIMIT_RCEM
            if (depositValue > 0) {
                deposits +=
                    Deposit(
                        from = month.plusMonths(1),
                        to = month.plusMonths(DEPOSIT_VALIDITY_MONTHS),
                        value = depositValue,
                        left = depositValue,
                        refundLimit = refundLimit,
                    )
            }

            if (month < reportStart) {
                month = month.plusMonths(1)
                continue
            }
            wartosc += depositValue
            pokryte += covered
            zwrot += refund
            rows +=
                NetBillingMonth(
                    month = month,
                    pobor = m.pobor,
                    oddanie = m.oddanie,
                    kosztEnergii = m.kosztEnergii,
                    pokryteDepozytem = covered,
                    kosztDystrybucji = m.kosztDystrybucji,
                    nowyDepozyt = depositValue,
                    zwrotNadplaty = refund,
                    saldoDepozytu = deposits.sumOf { it.left },
                )
            month = month.plusMonths(1)
        }

        val kosztEnergii = rows.sumOf { it.kosztEnergii }
        val kosztDystrybucji = rows.sumOf { it.kosztDystrybucji }
        val doZaplaty = kosztEnergii - pokryte
        return NetBillingResult(
            valuation = valuation,
            kosztEnergii = kosztEnergii,
            energiaDoZaplaty = doZaplaty,
            kosztDystrybucji = kosztDystrybucji,
            oplatyStale = fixedFee,
            depozytPoczatkowy = opening,
            wartoscDepozytu = wartosc,
            pokryteDepozytem = pokryte,
            zwrotNadplaty = zwrot,
            przepadlyDepozyt = przepadly,
            depozytPozostaly = deposits.sumOf { it.left },
            calkowityKoszt = doZaplaty + kosztDystrybucji + fixedFee - zwrot,
            missingRceHours = missingRce,
            missingRcemMonths = missingRcem,
            months = rows,
        )
    }

    /** Months spanned by [records] (inclusive), e.g. to know which RCEm prices are needed. */
    fun monthsOf(records: List<HourlyRecord>): List<YearMonth> {
        if (records.isEmpty()) return emptyList()
        val out = ArrayList<YearMonth>()
        var m = YearMonth.from(records.first().timestamp)
        val last = YearMonth.from(records.last().timestamp)
        while (m <= last) {
            out += m
            m = m.plusMonths(1)
        }
        return out
    }
}

/** Parser of the PSE RCEm page (one table per year, prices in zł/MWh). */
object RcemParser {
    private val MONTHS =
        listOf(
            "styczeń",
            "luty",
            "marzec",
            "kwiecień",
            "maj",
            "czerwiec",
            "lipiec",
            "sierpień",
            "wrzesień",
            "październik",
            "listopad",
            "grudzień",
        )
    private val TABLE =
        Regex("<table.*?</table>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<[^>]+>")
    private val WS = Regex("\\s+")
    private val YEAR = Regex("\\b(20\\d{2})\\b")
    // Android's ICU regex rejects the UNICODE_CHARACTER_CLASS flag and without it the JVM's `\b`
    // does not treat "ń" as a word character, so use explicit letter lookarounds instead.
    private val MONTH =
        Regex(
            "(?<![\\p{L}\\d])(" + MONTHS.joinToString("|") + ")(?![\\p{L}\\d])\\**",
            RegexOption.IGNORE_CASE,
        )
    private val ENTRY = Regex("(\\d+(?:,\\d+)?)\\s+(\\d{2})\\.(\\d{2})\\.(\\d{4})")

    /**
     * Returns month -> price in zł/kWh. When a corrected RCEm was published, the value with the
     * latest publication date wins.
     */
    fun parse(page: String): Map<YearMonth, Double> {
        val out = HashMap<YearMonth, Double>()
        for (table in TABLE.findAll(page)) {
            val text = unescape(table.value.replace(TAG, " ")).replace(WS, " ")
            val year = YEAR.find(text)?.groupValues?.get(1)?.toInt() ?: continue
            val months = MONTH.findAll(text).toList()
            months.forEachIndexed { i, m ->
                val end = if (i + 1 < months.size) months[i + 1].range.first else text.length
                val entries = ENTRY.findAll(text.substring(m.range.last + 1, end)).toList()
                val latest =
                    entries.maxByOrNull { e ->
                        val (_, d, mo, y) = e.destructured
                        "$y$mo$d"
                    } ?: return@forEachIndexed
                val monthNo = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
                val price = latest.groupValues[1].replace(',', '.').toDouble() / 1000.0
                out[YearMonth.of(year, monthNo)] = Math.round(price * 1e6) / 1e6
            }
        }
        return out
    }

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|nbsp|amp|lt|gt|quot|apos);")

    internal fun unescape(s: String): String =
        s.replace(ENTITY) {
            val e = it.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toInt(16).toChar().toString()
                e.startsWith("#") -> e.drop(1).toInt().toChar().toString()
                e == "nbsp" -> " "
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                else -> "'"
            }
        }
}
