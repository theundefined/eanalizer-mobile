package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingTest {
    private val eps = 1e-9

    @Test
    fun fixedFeesProratedByDays() {
        // 16 of 31 days of May + all of June + 10 of 31 days of July.
        val fees = Billing.fixedFees(31.0, LocalDate.of(2024, 5, 16), LocalDate.of(2024, 7, 10))
        assertEquals(16.0 + 31.0 + 10.0, fees, eps)
        assertEquals(
            0.0,
            Billing.fixedFees(31.0, LocalDate.of(2024, 5, 2), LocalDate.of(2024, 5, 1)),
            eps
        )
    }

    @Test
    fun toDateCountsOnlyTheRangeWithProratedFees() {
        val g11 =
            TariffTable.parseCsv(
                "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\n" +
                    "G11,stala,all,0,24,0.5,0.5,30.0"
            )
        val records =
            listOf(
                rec(at(2024, 6, 14, 10), 5.0),
                rec(at(2024, 6, 15, 10), 2.0),
                rec(at(2024, 6, 16, 10), 1.0, 4.0),
            )
        val b =
            Billing.toDate(
                records,
                LocalDate.of(2024, 6, 15),
                LocalDate.of(2024, 6, 16),
                g11,
                "G11"
            )
        assertNotNull(b)
        b!!
        assertEquals(2, b.days)
        assertEquals(2.0, b.result.energyCost, eps)
        assertEquals(2.0, b.fixedFees, eps) // 2 of 30 days
        assertEquals(4.0, b.total, eps)
        assertNull(b.netBilling)
        assertNull(
            Billing.toDate(records, LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 2), g11, "G11")
        )
    }

    @Test
    fun toDatePassesProratedFeesToSettlement() {
        val g11 =
            TariffTable.parseCsv(
                "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\n" +
                    "G11,stala,all,0,24,0.5,0.5,30.0"
            )
        val records = listOf(rec(at(2024, 6, 15, 10), 2.0), rec(at(2024, 6, 16, 10), 0.0, 3.0))
        val from = LocalDate.of(2024, 6, 15)
        val b =
            Billing.toDate(records, from, LocalDate.of(2024, 6, 16), g11, "G11") { sim, fee ->
                NetBilling.settle(
                    sim,
                    g11,
                    "G11",
                    emptyMap(),
                    mapOf(YearMonth.of(2024, 6) to 0.3),
                    fixedFee = fee
                )
            }!!
        val nb = b.netBilling!!
        assertEquals(2.0, nb.oplatyStale, eps)
        // 2 kWh x (0.5 + 0.5) + fees; the June deposit is usable only from July.
        assertEquals(4.0, b.total, eps)
        assertEquals(1.0 + 1.0, b.usageCost, eps)
    }
}

class DepositForecastTest {
    private val eps = 1e-9

    private fun month(m: Int, cost: Double) =
        NetBillingMonth(YearMonth.of(2024, m), 0.0, 0.0, cost, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun settlementExposesRemainingDeposits() {
        val g11 =
            TariffTable.parseCsv(
                "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\n" +
                    "G11,stala,all,0,24,0.5,0.5,0.0"
            )
        val sim =
            listOf(
                SimulationRow(at(2024, 5, 10, 12), 0.0, 10.0, 0.0, 0.0, 0.0),
                SimulationRow(at(2024, 6, 10, 20), 4.0, 0.0, 0.0, 0.0, 0.0),
            )
        val nb =
            NetBilling.settle(
                sim,
                g11,
                "G11",
                emptyMap(),
                mapOf(YearMonth.of(2024, 5) to 0.5, YearMonth.of(2024, 6) to 0.5),
            )!!
        // May deposit 5 zł, June uses 2 zł of energy.
        assertEquals(1, nb.deposits.size)
        val d = nb.deposits.single()
        assertEquals(YearMonth.of(2024, 6), d.from)
        assertEquals(YearMonth.of(2025, 5), d.to)
        assertEquals(5.0, d.value, eps)
        assertEquals(3.0, d.left, eps)
        assertEquals(nb.depozytPozostaly, d.left, eps)
        assertEquals(1.0, d.maxRefund, eps) // 20% of 5 zł
    }

    @Test
    fun forecastUsesOldestFirstAndRefundsOnExpiry() {
        val deposits =
            listOf(
                DepositLeft(YearMonth.of(2024, 2), YearMonth.of(2025, 1), 10.0, 6.0, 0.2),
                DepositLeft(YearMonth.of(2024, 3), YearMonth.of(2025, 2), 10.0, 10.0, 0.3),
            )
        val f = DepositForecast.forecast(deposits, YearMonth.of(2024, 12)) { 3.0 }
        assertEquals(
            listOf(YearMonth.of(2024, 12), YearMonth.of(2025, 1), YearMonth.of(2025, 2)),
            f.map { it.month }
        )
        // Dec: 3 from the first (3 left). Jan: 3 from the first (0 left) -> nothing expires.
        assertEquals(3.0, f[0].covered, eps)
        assertEquals(0.0, f[1].expiring, eps)
        assertEquals(10.0, f[1].balance, eps)
        // Feb: 3 from the second, 7 expire, refund 30% of 10 = 3, 4 lost.
        assertEquals(7.0, f[2].expiring, eps)
        assertEquals(3.0, f[2].refund, eps)
        assertEquals(4.0, f[2].lost, eps)
        assertEquals(0.0, f[2].balance, eps)
    }

    @Test
    fun forecastWithoutUsageIsTheUpperBound() {
        val d = DepositLeft(YearMonth.of(2024, 2), YearMonth.of(2025, 1), 10.0, 6.0, 0.2)
        val f = DepositForecast.forecast(listOf(d), YearMonth.of(2025, 1)) { 0.0 }
        assertEquals(d.maxRefund, f.single().refund, eps)
        assertEquals(4.0, f.single().lost, eps)
        assertTrue(DepositForecast.forecast(emptyList(), YearMonth.of(2025, 1)) { 1.0 }.isEmpty())
    }

    @Test
    fun expectedCostSameMonthLastYearElseAverage() {
        val cost =
            DepositForecast.expectedCost(listOf(month(5, 10.0), month(6, 20.0), month(7, 99.0)))
        assertEquals(10.0, cost(YearMonth.of(2025, 5)), eps)
        // Average without the last (incomplete) month.
        assertEquals(15.0, cost(YearMonth.of(2025, 1)), eps)
        // The incomplete last month is not used as an estimate.
        assertEquals(15.0, cost(YearMonth.of(2025, 7)), eps)
        assertEquals(0.0, DepositForecast.expectedCost(emptyList())(YearMonth.of(2025, 1)), eps)
    }
}

class LoadAnalysisTest {
    private val eps = 1e-9
    private val g11 =
        TariffTable.parseCsv(
            "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\n" +
                "G11,stala,all,0,24,0.6,0.4,0.0"
        )

    @Test
    fun baseLoadIsMedianOfDailyNightMinimum() {
        val records =
            listOf(
                rec(at(2024, 5, 1, 1), 0.3),
                rec(at(2024, 5, 1, 2), 0.2),
                rec(at(2024, 5, 1, 12), 0.0), // PV hour, ignored
                rec(at(2024, 5, 2, 1), 0.1),
                rec(at(2024, 5, 3, 3), 0.4),
                rec(at(2024, 6, 1, 0), 0.5),
            )
        val b = LoadAnalysis.baseLoad(records, g11, "G11")!!
        // Daily minima 0.2, 0.1, 0.4, 0.5 -> median 0.3.
        assertEquals(0.3, b.kw, eps)
        assertEquals(
            listOf(YearMonth.of(2024, 5), YearMonth.of(2024, 6)),
            b.months.map { it.month }
        )
        assertEquals(0.2, b.months[0].kw, eps)
        assertEquals(3, b.months[0].days)
        assertEquals(1.0, b.avgPrice, eps)
        assertEquals(0.3 * 8760, b.annualKwh, eps)
        assertEquals(0.3 * 8760, b.annualCost, eps)
        // 0.3 kW x 6 h / 1.5 kWh.
        assertEquals(1.0, b.share, eps)
        assertNull(LoadAnalysis.baseLoad(listOf(rec(at(2024, 5, 1, 12), 1.0)), g11, "G11"))
    }

    @Test
    fun peaksTopMonthlyAndHistogram() {
        val records =
            listOf(
                rec(at(2024, 5, 1, 18), 4.0),
                rec(at(2024, 5, 2, 18), 6.0),
                rec(at(2024, 5, 3, 3), 0.3),
                rec(at(2024, 6, 1, 18), 2.0),
                rec(at(2024, 6, 2, 18), 9.0),
            )
        val p = LoadAnalysis.peaks(records, n = 2)
        assertEquals(listOf(9.0, 6.0), p.top.map { it.poborPrzed })
        assertEquals(listOf(6.0, 9.0), p.monthly.map { it.record.poborPrzed })
        // <=0.5, <=1, <=2, <=3, <=5, <=8, more.
        assertEquals(listOf(1, 0, 1, 0, 1, 1, 1), p.histogram)
        assertEquals(2, LoadAnalysis.hoursAbove(records, 10.0, 0.5))
        assertEquals(0, LoadAnalysis.hoursAbove(records, 0.0, 0.5))
    }

    @Test
    fun medianEvenAndOdd() {
        assertEquals(2.0, LoadAnalysis.median(listOf(3.0, 1.0, 2.0)), eps)
        assertEquals(2.5, LoadAnalysis.median(listOf(4.0, 1.0, 2.0, 3.0)), eps)
        assertEquals(0.0, LoadAnalysis.median(emptyList()), eps)
    }
}

class ExtraLoadTest {
    private val eps = 1e-9

    @Test
    fun heatShareSumsToOne() {
        assertEquals(1.0, ExtraLoads.HEAT_MONTHLY_SHARE.sum(), 1e-9)
    }

    @Test
    fun heatPumpSpreadEvenlyOverTheMonthsHours() {
        val records = listOf(rec(at(2024, 1, 10, 5)), rec(at(2024, 1, 10, 6)))
        val extra = ExtraLoads.hourly(records, ExtraLoad(heatPumpAnnualKwh = 1000.0))
        val perHour = 1000.0 * 0.18 / (31 * 24)
        assertEquals(perHour, extra.getValue(at(2024, 1, 10, 5)), eps)
        assertEquals(2, extra.size)
    }

    @Test
    fun evChargesFromStartHourLimitedByPower() {
        val records =
            (0..23).map { rec(at(2024, 5, 1, it)) } + (0..23).map { rec(at(2024, 5, 2, it)) }
        // 50 km x 18 kWh/100 km = 9 kWh at 3.7 kW from 22:00: 3.7 + 3.7 + 1.6.
        val extra =
            ExtraLoads.hourly(
                records,
                ExtraLoad(evKmPerDay = 50.0, evChargeKw = 3.7, evStartHour = 22)
            )
        assertEquals(3.7, extra.getValue(at(2024, 5, 1, 22)), eps)
        assertEquals(3.7, extra.getValue(at(2024, 5, 1, 23)), eps)
        assertEquals(1.6, extra.getValue(at(2024, 5, 2, 0)), eps)
        // The second day's charging spills into hours without data and is dropped.
        assertEquals(9.0 + 3.7 + 3.7, extra.values.sum(), eps)
        assertTrue(ExtraLoad().isEmpty)
    }

    @Test
    fun applyAddsImportAndAbsorbsExport() {
        val ts = at(2024, 5, 1, 12)
        val r = rec(ts, pp = 1.0, op = 3.0, p = 0.0, o = 2.0)
        val out =
            ExtraLoads.apply(listOf(r, rec(at(2024, 5, 1, 13), 1.0)), mapOf(ts to 2.5)).first()
        assertEquals(3.5, out.poborPrzed, eps)
        assertEquals(3.0, out.oddaniePrzed, eps)
        assertEquals(0.5, out.pobor, eps)
        assertEquals(0.0, out.oddanie, eps)
    }
}
