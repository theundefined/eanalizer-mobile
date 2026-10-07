package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private val eps = 1e-9

    @Test
    fun workdaysExcludeWeekendsAndHolidays() {
        assertTrue(Insights.isWorkday(LocalDate.of(2024, 5, 2))) // Thursday
        assertFalse(Insights.isWorkday(LocalDate.of(2024, 5, 4))) // Saturday
        assertFalse(Insights.isWorkday(LocalDate.of(2024, 5, 3))) // Constitution Day
    }

    @Test
    fun dayProfileAveragesPerHour() {
        val p =
            Insights.dayProfile(
                listOf(
                    rec(at(2024, 5, 6, 8), 1.0, 0.0), // Monday
                    rec(at(2024, 5, 7, 8), 3.0, 1.0), // Tuesday
                    rec(at(2024, 5, 4, 8), 5.0, 0.0), // Saturday
                )
            )
        assertEquals(24, p.workdays.size)
        assertEquals(2.0, p.workdays[8].pobor, eps)
        assertEquals(0.5, p.workdays[8].oddanie, eps)
        assertEquals(5.0, p.weekends[8].pobor, eps)
        assertEquals(0.0, p.workdays[9].pobor, eps)
    }

    @Test
    fun heatmapNetPerMonthAndHour() {
        val h =
            Insights.heatmap(
                listOf(
                    rec(at(2024, 5, 6, 12), 1.0, 3.0),
                    rec(at(2024, 5, 7, 12), 1.0, 1.0),
                    rec(at(2024, 6, 1, 0), 2.0, 0.0),
                )
            )
        assertEquals(listOf(YearMonth.of(2024, 5), YearMonth.of(2024, 6)), h.months)
        assertEquals(-1.0, h.values[0][12], eps)
        assertTrue(h.values[0][0].isNaN())
        assertEquals(2.0, h.values[1][0], eps)
        assertEquals(2.0, h.maxAbs, eps)
    }

    @Test
    fun byYearNewestFirstWithGaps() {
        val y =
            Insights.byYear(
                listOf(rec(at(2023, 12, 1, 0), 1.0, 0.0), rec(at(2024, 1, 1, 0), 2.0, 0.5))
            )
        assertEquals(listOf(2024, 2023), y.map { it.year })
        assertEquals(2.0, y[0].months[0]!!.poborPrzed, eps)
        assertNull(y[0].months[1])
        assertEquals(1.0, y[1].poborPrzed, eps)
        assertEquals(0.5, y[0].oddaniePrzed, eps)
    }

    @Test
    fun byYearSplitByTariffZone() {
        // 2024-05-02 is a workday: 3:00 off-peak, 10:00 peak in G12w.
        val data =
            listOf(
                rec(at(2023, 5, 2, 3), 1.0, 0.0),
                rec(at(2024, 5, 2, 3), 2.0, 0.0),
                rec(at(2024, 5, 2, 10), 4.0, 1.0),
            )
        val y = Insights.byYear(data, TariffTable.default(), "G12w")
        val may = y[0].months[4]!!
        assertEquals(6.0, may.poborPrzed, eps)
        assertEquals(listOf("pozaszczytowa", "szczytowa"), may.zones.map { it.zone })
        assertEquals(listOf(2.0, 4.0), may.zones.map { it.poborPrzed })
        assertEquals(listOf(0.0, 1.0), may.zones.map { it.oddaniePrzed })
        assertEquals(listOf(1.0), y[1].months[4]!!.zones.map { it.poborPrzed })
        assertTrue(Insights.byYear(data).all { yr -> yr.months.all { it?.zones.isNullOrEmpty() } })
    }

    @Test
    fun pvSharesSumToOne() {
        assertEquals(1.0, Insights.PV_MONTHLY_SHARE.sum(), 1e-9)
        assertEquals(12, Insights.PV_MONTHLY_SHARE.size)
    }

    @Test
    fun selfUseEstimate() {
        // June: share 0.132, 1 of 30 days with data -> production 1000*0.132/30 = 4.4 kWh.
        val m =
            Insights.selfUse(
                    listOf(
                        TestData.rec(at(2024, 6, 1, 12), 0.5, 3.0, 0.2, 2.7),
                        TestData.rec(at(2024, 6, 1, 20), 1.0, 0.0, 1.0, 0.0),
                    ),
                    1000.0,
                )
                .single()
        assertEquals(4.4, m.production, 1e-9)
        assertEquals(1.4, m.selfUse, 1e-9)
        assertEquals(1.4 / 4.4, m.selfUseShare, 1e-9)
        assertEquals(1.4 / (1.4 + 1.5), m.coverage, 1e-9)
        assertEquals(0.3, m.balanced, 1e-9)
    }

    @Test
    fun selfUseNeverNegative() {
        val m = Insights.selfUse(listOf(rec(at(2024, 1, 1, 12), 0.0, 50.0)), 100.0).single()
        assertEquals(0.0, m.selfUse, eps)
        assertEquals(0.0, m.selfUseShare, eps)
    }
}

class CostsTest {
    private val eps = 1e-9
    private val table =
        TariffTable.parseCsv(
            "${TariffTable.HEADER}\n" +
                "G12,szczyt,all,6,22,0.8,0.4,20.0\n" +
                "G12,noc,all,22,6,0.4,0.1,20.0\n"
        )

    private fun row(ts: java.time.LocalDateTime, p: Double, o: Double = 0.0) =
        SimulationRow(ts, p, o, 0.0, 0.0, 0.0)

    @Test
    fun storageScenariosAnnualised() {
        val s =
            StorageEconomics.scenarios(listOf(5.0, 10.0), days = 73, StorageFinance(100.0)) {
                100.0 - it * 2
            }
        assertEquals(listOf(0.0, 5.0, 10.0), s.map { it.capacity })
        assertEquals(0.0, s[0].annualSavings, eps)
        assertNull(s[0].paybackYears)
        assertEquals(50.0, s[1].annualSavings, eps) // 10 zł in 73 days -> 50 zł/year
        assertEquals(500.0, s[1].investment, eps)
        assertEquals(10.0, s[1].paybackYears!!, eps)
        assertEquals(15 * 50.0 - 500.0, s[1].netGain, eps)
        assertNull(s[0].marginalPerKwh)
        assertEquals(10.0, s[1].marginalPerKwh!!, eps)
        assertEquals(10.0, s[2].marginalPerKwh!!, eps)
    }

    @Test
    fun storageFinanceSubsidyAndOutlook() {
        val f =
            StorageFinance(
                pricePerKwh = 1000.0,
                fixedCost = 4000.0,
                subsidyShare = 0.5,
                subsidyMax = 5000.0,
            )
        assertEquals(0.0, f.investment(0.0), eps)
        assertEquals(3000.0, f.investment(2.0), eps) // 6000 - 50 %
        assertEquals(9000.0, f.investment(10.0), eps) // 14000 - cap 5000
        val g = StorageFinance(1000.0, degradation = 0.1, priceGrowth = 0.0)
        assertEquals(100.0, g.yearSavings(100.0, 1), eps)
        assertEquals(90.0, g.yearSavings(100.0, 2), eps)
        assertEquals(1.5, StorageEconomics.payback(g, 100.0, 145.0)!!, eps)
        assertNull(StorageEconomics.payback(g, 100.0, 1e6))
        val d = StorageFinance(1000.0, discountRate = 0.1)
        assertEquals(100.0 / 1.1, d.yearSavings(100.0, 1), eps)
    }

    @Test
    fun monthlyBillPerZone() {
        val b =
            Bills.monthly(
                    listOf(
                        row(at(2024, 5, 1, 12), 10.0),
                        row(at(2024, 5, 1, 23), 5.0),
                        row(at(2024, 5, 2, 12), 2.0),
                    ),
                    table,
                    "G12",
                )
                .single()
        assertEquals(listOf("szczyt", "noc"), b.lines.map { it.zone })
        assertEquals(12.0, b.lines[0].kwh, eps)
        assertEquals(12.0 * 0.8 + 5.0 * 0.4, b.energyCost, eps)
        assertEquals(12.0 * 0.4 + 5.0 * 0.1, b.distCost, eps)
        assertNull(b.coveredByDeposit)
        assertEquals(b.energyCost + b.distCost + 20.0, b.total, eps)
    }

    @Test
    fun monthlyBillWithDeposit() {
        val sim = listOf(row(at(2024, 5, 1, 12), 0.0, 100.0), row(at(2024, 6, 1, 12), 10.0))
        val nb =
            NetBilling.settle(
                sim,
                table,
                "G12",
                emptyMap(),
                mapOf(YearMonth.of(2024, 5) to 0.3),
            )!!
        val bills = Bills.monthly(sim, table, "G12", nb)
        assertEquals(0.0, bills[0].coveredByDeposit!!, eps)
        assertEquals(8.0, bills[1].coveredByDeposit!!, eps)
        assertEquals(4.0 + 20.0, bills[1].total, eps)
    }

    @Test
    fun dynamicPrice() {
        assertEquals((0.5 + 0.1) * 1.23, DynamicTariff.price(0.5, 0.1), eps)
        assertEquals(0.0, DynamicTariff.price(-0.5, 0.1), eps)
    }

    @Test
    fun dynamicCostWithoutNetBilling() {
        val ts1 = at(2024, 7, 1, 12)
        val ts2 = at(2024, 7, 1, 23)
        val r =
            DynamicTariff.cost(
                listOf(row(ts1, 10.0), row(ts2, 5.0)),
                table,
                "G12",
                mapOf(ts1 to 0.2),
                marginNet = 0.1,
                fixedFees = 20.0,
                netBilling = false,
            )
        // ts2 has no RCE -> tariff energy price 0.4.
        assertEquals(10.0 * 0.3 * 1.23 + 5.0 * 0.4, r.energyCost, eps)
        assertEquals(10.0 * 0.4 + 5.0 * 0.1, r.distCost, eps)
        assertEquals(1, r.missingHours)
        assertEquals(r.energyCost + r.distCost + 20.0, r.totalCost, eps)
    }

    @Test
    fun dynamicCostWithNetBillingUsesHourlyPrices() {
        val ts1 = at(2024, 7, 1, 12)
        val ts2 = at(2024, 8, 1, 12)
        val r =
            DynamicTariff.cost(
                listOf(row(ts1, 0.0, 100.0), row(ts2, 10.0)),
                table,
                "G12",
                mapOf(ts1 to 0.3, ts2 to 0.2),
                marginNet = 0.1,
                fixedFees = 0.0,
                netBilling = true,
            )
        val nb = r.netBilling!!
        assertEquals(10.0 * 0.3 * 1.23, nb.kosztEnergii, eps)
        assertEquals(30.0, nb.wartoscDepozytu, eps) // before 02.2025 no coefficient
        assertEquals(nb.kosztEnergii, nb.pokryteDepozytem, eps)
        assertEquals(nb.calkowityKoszt, r.totalCost, eps)
    }
}
