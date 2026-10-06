package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private val table = TestData.coreTariffs
    private val data = TestData.records
    private val eps = 1e-9

    @Test
    fun netMeteringCascade() {
        // Port of test_net_metering_cascade_logic.
        val input = listOf(data[3], data[2], rec(at(2024, 5, 2, 12), op = 5.0, o = 5.0))
        val r = Analyzer.runFullAnalysis(input, 0.0, table, "G12w", netMeteringRatio = 0.8)
        val high = r.zones.first { it.zone == "szczytowa" }
        val low = r.zones.first { it.zone == "pozaszczytowa" }
        // high: credit 5 * 0.8 = 4, consumption 2.5, rollover 1.5
        assertEquals(4.0, high.magazynWStrefie!!, eps)
        assertEquals(0.0, high.kredytZPoprzedniej!!, eps)
        assertEquals(0.0, high.energiaDoOplacenia!!, eps)
        // low: consumption 2.0, credit 1.5, pay 0.5 * 0.76 = 0.38
        assertEquals(1.5, low.kredytZPoprzedniej!!, eps)
        assertEquals(0.5, low.energiaDoOplacenia!!, eps)
        assertEquals(0.38, low.kosztPoboru, eps)
        assertEquals(1, r.months)
        assertEquals(10.0, r.fixedFees, eps)
        assertEquals(10.38, r.totalCost, eps)
        assertEquals(0.38, r.energyCost, eps)
        assertEquals(0.0, r.unusedCredit!!, eps)
    }

    @Test
    fun netMeteringLeavesUnusedCredit() {
        val input = listOf(rec(at(2024, 5, 2, 12), op = 10.0), rec(at(2024, 5, 2, 23), pp = 1.0))
        val r = Analyzer.runFullAnalysis(input, 0.0, table, "G12w", netMeteringRatio = 0.7)
        assertEquals(6.0, r.unusedCredit!!, eps)
        assertEquals(10.0, r.totalCost, eps)
    }

    @Test
    fun noStorageCostsPerZone() {
        val r = Analyzer.runFullAnalysis(data, 0.0, table, "G12w")
        // 05-01 is a holiday -> off-peak; 05-02 11:00 peak; 05-04 Saturday
        val low = r.zones.first { it.zone == "pozaszczytowa" }
        val high = r.zones.first { it.zone == "szczytowa" }
        assertEquals(3.0, low.poborZSieci, eps)
        assertEquals(7.3, low.oddanieDoSieci, eps)
        assertEquals(2.28, low.kosztPoboru, eps)
        assertEquals(2.7, high.kosztPoboru, eps)
        assertEquals(4.98, r.energyCost, eps)
        assertEquals(14.98, r.totalCost, eps)
        assertNull(r.unusedCredit)
        assertNull(low.magazynWStrefie)
        assertEquals(6.2, r.totalPoborPrzed, eps)
        assertEquals(8.0, r.totalOddaniePrzed, eps)
        // balancing inside the hour counts as savings
        assertEquals(0.7, r.savings, eps)
    }

    @Test
    fun storageWithEfficiency() {
        val r = Analyzer.runFullAnalysis(data, 5.0, table, "G12w", storageEfficiency = 0.9)
        val s = r.simulation
        assertEquals(5, s.size)
        assertEquals(1.0, s[0].poborZSieci, eps)
        assertEquals(2.5, s[1].oddanieDoMagazynu, eps)
        assertEquals(0.0, s[1].oddanieDoSieci, eps)
        assertEquals(2.25, s[1].stanMagazynu, eps)
        assertEquals(2.0, s[2].poborZMagazynu, eps)
        assertEquals(0.25, s[2].stanMagazynu, eps)
        assertEquals(0.25, s[3].poborZMagazynu, eps)
        assertEquals(2.25, s[3].poborZSieci, eps)
        assertEquals(4.8, s[4].oddanieDoMagazynu, eps)
        assertEquals(4.32, s[4].stanMagazynu, eps)
        assertEquals(6.2 - 3.25, r.savings, eps)
        assertEquals(0.76 + 2.25 * 1.08 + 10.0, r.totalCost, eps)
    }

    @Test
    fun storageCapacityLimitsCharging() {
        val input = listOf(rec(at(2024, 5, 2, 12), op = 10.0), rec(at(2024, 5, 2, 13), pp = 3.0))
        val s =
            Analyzer.runFullAnalysis(input, 2.0, table, "G12w", storageEfficiency = 0.8).simulation
        assertEquals(2.5, s[0].oddanieDoMagazynu, eps)
        assertEquals(7.5, s[0].oddanieDoSieci, eps)
        assertEquals(2.0, s[0].stanMagazynu, eps)
        assertEquals(2.0, s[1].poborZMagazynu, eps)
        assertEquals(1.0, s[1].poborZSieci, eps)
    }

    @Test
    fun storagePowerAndUsableCapacity() {
        val input =
            listOf(
                rec(at(2024, 5, 2, 12), op = 10.0),
                rec(at(2024, 5, 2, 13), op = 10.0),
                rec(at(2024, 5, 2, 20), pp = 3.0),
            )
        val s =
            Analyzer.runFullAnalysis(
                    input,
                    10.0,
                    table,
                    "G12w",
                    options = StorageOptions(usableFraction = 0.5, powerKw = 4.0),
                )
                .simulation
        assertEquals(4.0, s[0].oddanieDoMagazynu, eps) // power limit
        assertEquals(6.0, s[0].oddanieDoSieci, eps)
        assertEquals(1.0, s[1].oddanieDoMagazynu, eps) // usable 5 kWh reached
        assertEquals(5.0, s[1].stanMagazynu, eps)
        assertEquals(3.0, s[2].poborZMagazynu, eps)
    }

    @Test
    fun gridChargingInCheapZone() {
        val g12 = TariffTable.default()
        val input =
            listOf(
                rec(at(2024, 4, 3, 18), pp = 4.0), // Wednesday, day zone deficit
                rec(at(2024, 4, 4, 2)), // night zone: charge yesterday's deficit
                rec(at(2024, 4, 4, 18), pp = 3.0),
                rec(at(2024, 4, 4, 23), pp = 2.0), // night: no discharge, top up
            )
        val r =
            Analyzer.runFullAnalysis(
                input,
                5.0,
                g12,
                "G12",
                storageEfficiency = 0.8,
                options = StorageOptions(gridCharging = true),
            )
        val s = r.simulation
        assertEquals(0.0, s[0].zSieciDoMagazynu, eps)
        assertEquals(5.0, s[1].zSieciDoMagazynu, eps)
        assertEquals(5.0, s[1].poborZSieci, eps)
        assertEquals(4.0, s[1].stanMagazynu, eps)
        assertEquals(3.0, s[2].poborZMagazynu, eps)
        assertEquals(0.0, s[2].poborZSieci, eps)
        assertEquals(0.0, s[3].poborZMagazynu, eps)
        assertEquals(3.75, s[3].zSieciDoMagazynu, eps)
        assertEquals(2.0 + 3.75, s[3].poborZSieci, eps)
        // Only energy delivered from the storage counts as covered consumption.
        assertEquals(3.0, r.savings, eps)
        // Without grid charging nothing is bought for the storage.
        val plain = Analyzer.runFullAnalysis(input, 5.0, g12, "G12", storageEfficiency = 0.8)
        assertTrue(plain.simulation.all { it.zSieciDoMagazynu == 0.0 })
    }

    @Test
    fun gridChargingIgnoredForSingleZone() {
        val input = listOf(rec(at(2024, 4, 3, 18), pp = 4.0), rec(at(2024, 4, 4, 2)))
        val r =
            Analyzer.runFullAnalysis(
                input,
                5.0,
                TariffTable.default(),
                "G11",
                options = StorageOptions(gridCharging = true),
            )
        assertTrue(r.simulation.all { it.zSieciDoMagazynu == 0.0 })
    }

    @Test
    fun monthsAndCaseInsensitiveFixedFee() {
        val input = listOf(rec(at(2023, 11, 30, 10), pp = 1.0), rec(at(2024, 2, 1, 10)))
        val r = Analyzer.runFullAnalysis(input, 0.0, table, "g12w")
        assertEquals(4, r.months)
        assertEquals(40.0, r.fixedFees, eps)
    }

    @Test
    fun hoursWithoutZoneAreSkipped() {
        val r = Analyzer.runFullAnalysis(data, 0.0, table, "G11")
        assertTrue(r.zones.isEmpty())
        assertEquals(0.0, r.totalCost, eps)
        assertEquals(5, r.simulation.size)
        assertEquals(6.2, r.savings, eps)
    }

    @Test
    fun compareTariffsSortedWithBreakdown() {
        val res = Analyzer.compareTariffs(data, 0.0, TariffTable.default())
        assertEquals(3, res.size)
        assertEquals(res.sortedBy { it.totalCost }, res)
        val g12w = res.first { it.tariff == "G12w" }
        val single = Analyzer.runFullAnalysis(data, 0.0, TariffTable.default(), "G12w")
        assertEquals(single.totalCost, g12w.totalCost, eps)
        assertEquals(55.0302, g12w.fixedFees, eps)
        assertEquals(g12w.totalCost - g12w.fixedFees, g12w.energyCost, eps)
        assertTrue(Analyzer.compareTariffs(emptyList(), 0.0, TariffTable.default()).isEmpty())
    }

    @Test
    fun optimalCapacityArbitrageOnNetExportDay() {
        val input =
            listOf(
                rec(at(2024, 5, 2, 12), pp = 5.0, p = 5.0),
                rec(at(2024, 5, 2, 4), op = 10.0, o = 10.0),
            )
        assertEquals(5.0, Analyzer.optimalCapacity(input, table, "G12w"), eps)
    }

    @Test
    fun optimalCapacityExportDayDominates() {
        val input =
            listOf(
                rec(at(2024, 5, 4, 20), pp = 7.0, p = 7.0),
                rec(at(2024, 5, 4, 12), op = 20.0, o = 20.0),
                rec(at(2024, 5, 6, 12), pp = 3.0, p = 3.0),
            )
        assertEquals(7.0, Analyzer.optimalCapacity(input, table, "G12w"), eps)
        assertEquals(0.0, Analyzer.optimalCapacity(emptyList(), table, "G12w"), eps)
    }

    @Test
    fun dailyTrends() {
        val t = Analyzer.dailyTrends(data)
        assertEquals(3, t.totalDays)
        assertEquals(1, t.surplusDays) // only 05-04 (4.8 exported vs 0 imported)
        assertEquals(33.333, t.percent, 1e-3)
        assertEquals(0.0, Analyzer.dailyTrends(emptyList()).percent, 0.0)
    }
}
