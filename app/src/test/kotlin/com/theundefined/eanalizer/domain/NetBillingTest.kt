package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors eanalizer `tests/test_netbilling.py`. */
class NetBillingTest {
    private val eps = 1e-9
    private val table =
        TariffTable.parseCsv("${TariffTable.HEADER}\nG11,stala,all,0,24,0.5,0.3,10.0\n")

    private fun sim(vararg rows: Triple<LocalDateTime, Double, Double>) =
        rows.map { (ts, p, o) -> SimulationRow(ts, p, o, 0.0, 0.0, 0.0) }

    private fun ym(y: Int, m: Int) = YearMonth.of(y, m)

    private fun settle(
        rows: List<SimulationRow>,
        rce: Map<LocalDateTime, Double> = emptyMap(),
        rcem: Map<YearMonth, Double> = emptyMap(),
        valuation: NetBillingValuation = NetBillingValuation.RCEM,
        fixedFee: Double = 0.0,
    ) = NetBilling.settle(rows, table, "G11", rce, rcem, valuation, fixedFee)!!

    private fun NetBillingResult.month(y: Int, m: Int) = months.first { it.month == ym(y, m) }

    @Test
    fun depositAvailableFromNextMonthAndCoversEnergyOnly() {
        val s =
            settle(
                sim(
                    Triple(at(2024, 5, 10, 12), 0.0, 100.0),
                    Triple(at(2024, 5, 10, 20), 10.0, 0.0),
                    Triple(at(2024, 6, 10, 20), 20.0, 0.0),
                ),
                rcem = mapOf(ym(2024, 5) to 0.3, ym(2024, 6) to 0.3),
            )
        assertEquals(0.0, s.month(2024, 5).pokryteDepozytem, eps)
        assertEquals(30.0, s.month(2024, 5).nowyDepozyt, eps)
        assertEquals(10.0, s.month(2024, 6).pokryteDepozytem, eps)
        assertEquals(0.0, s.month(2024, 6).energiaDoZaplaty, eps)
        assertEquals(9.0, s.kosztDystrybucji, eps)
        assertEquals(20.0, s.depozytPozostaly, eps)
        assertEquals(30.0, s.wartoscDepozytu, eps)
        assertEquals(5.0 + 9.0, s.calkowityKoszt, eps)
    }

    @Test
    fun coefficientFromFebruary2025() {
        val s =
            settle(
                sim(
                    Triple(at(2025, 1, 10, 12), 0.0, 100.0),
                    Triple(at(2025, 2, 10, 12), 0.0, 100.0)
                ),
                rcem = mapOf(ym(2025, 1) to 0.4, ym(2025, 2) to 0.4),
            )
        assertEquals(40.0, s.month(2025, 1).nowyDepozyt, eps)
        assertEquals(40.0 * 1.23, s.month(2025, 2).nowyDepozyt, eps)
    }

    @Test
    fun hourlyRceNegativePricesCountAsZero() {
        val h1 = at(2025, 5, 10, 11)
        val h2 = at(2025, 5, 10, 12)
        val s =
            settle(
                sim(Triple(h1, 0.0, 10.0), Triple(h2, 0.0, 10.0)),
                rce = mapOf(h1 to 0.2, h2 to -0.1),
                rcem = mapOf(ym(2025, 5) to 99.0),
                valuation = NetBillingValuation.RCE,
            )
        assertEquals(10.0 * 0.2 * 1.23, s.wartoscDepozytu, eps)
    }

    @Test
    fun hourlyRceFallsBackToRcemBeforeJuly2024() {
        val s =
            settle(
                sim(Triple(at(2024, 6, 10, 12), 0.0, 10.0), Triple(at(2024, 7, 10, 12), 0.0, 10.0)),
                rce = mapOf(at(2024, 7, 10, 12) to 0.5),
                rcem = mapOf(ym(2024, 6) to 0.3, ym(2024, 7) to 99.0),
                valuation = NetBillingValuation.RCE,
            )
        assertEquals(3.0, s.month(2024, 6).nowyDepozyt, eps)
        assertEquals(5.0, s.month(2024, 7).nowyDepozyt, eps)
    }

    @Test
    fun refundAfter12Months() {
        val s =
            settle(
                sim(Triple(at(2025, 1, 10, 12), 0.0, 250.0), Triple(at(2026, 1, 10, 20), 0.0, 0.0)),
                rcem = mapOf(ym(2025, 1) to 0.4),
            )
        assertEquals(20.0, s.zwrotNadplaty, eps)
        assertEquals(80.0, s.przepadlyDepozyt, eps)
        assertEquals(0.0, s.depozytPozostaly, eps)
        assertEquals(-20.0, s.calkowityKoszt, eps)
        assertEquals(13, s.months.size)
        assertEquals(20.0, s.month(2026, 1).zwrotNadplaty, eps)
    }

    @Test
    fun refundLimit30PercentForHourlyRceFromFebruary2025() {
        val h = at(2025, 3, 10, 12)
        val s =
            settle(
                sim(Triple(h, 0.0, 100.0), Triple(at(2026, 3, 10, 20), 0.0, 0.0)),
                rce = mapOf(h to 1.0),
                valuation = NetBillingValuation.RCE,
            )
        assertEquals(100.0 * 1.23 * 0.30, s.zwrotNadplaty, eps)
    }

    @Test
    fun noRefundForDepositsExpiringBeforeJuly2024() {
        val s =
            settle(
                sim(Triple(at(2023, 1, 10, 12), 0.0, 100.0), Triple(at(2024, 1, 10, 20), 0.0, 0.0)),
                rcem = mapOf(ym(2023, 1) to 0.5),
            )
        assertEquals(0.0, s.zwrotNadplaty, eps)
        assertEquals(50.0, s.przepadlyDepozyt, eps)
    }

    @Test
    fun oldestDepositUsedFirst() {
        val s =
            settle(
                sim(
                    Triple(at(2025, 1, 10, 12), 0.0, 100.0),
                    Triple(at(2025, 3, 10, 12), 0.0, 100.0),
                    Triple(at(2025, 12, 10, 20), 20.0, 0.0),
                    Triple(at(2026, 1, 10, 20), 0.0, 0.0),
                ),
                rcem = mapOf(ym(2025, 1) to 0.1, ym(2025, 3) to 0.1),
            )
        assertEquals(10.0, s.pokryteDepozytem, eps)
        assertEquals(0.0, s.zwrotNadplaty, eps)
        assertEquals(12.3, s.depozytPozostaly, eps)
    }

    @Test
    fun missingRcemIsReported() {
        val s = settle(sim(Triple(at(2025, 5, 10, 12), 0.0, 10.0)))
        assertEquals(listOf(ym(2025, 5)), s.missingRcemMonths)
        assertEquals(0.0, s.wartoscDepozytu, eps)
    }

    @Test
    fun missingRceHoursAreCounted() {
        val s =
            settle(
                sim(Triple(at(2025, 5, 10, 12), 0.0, 10.0), Triple(at(2025, 5, 10, 13), 1.0, 0.0)),
                valuation = NetBillingValuation.RCE,
            )
        assertEquals(1, s.missingRceHours)
    }

    @Test
    fun fixedFeeIncluded() {
        val s = settle(sim(Triple(at(2025, 5, 10, 20), 10.0, 0.0)), fixedFee = 10.0)
        assertEquals(5.0 + 3.0 + 10.0, s.calkowityKoszt, eps)
    }

    @Test
    fun emptySimulation() {
        assertNull(NetBilling.settle(emptyList(), table, "G11", emptyMap(), emptyMap()))
    }

    @Test
    fun withAnalyzerSimulation() {
        val data =
            listOf(
                HourlyRecord(at(2025, 5, 10, 12), 0.0, 100.0, 0.0, 100.0),
                HourlyRecord(at(2025, 6, 10, 20), 20.0, 0.0, 20.0, 0.0),
            )
        val a = Analyzer.runFullAnalysis(data, 0.0, table, "G11")
        val s =
            NetBilling.settle(
                a.simulation,
                table,
                "G11",
                emptyMap(),
                mapOf(ym(2025, 5) to 0.1, ym(2025, 6) to 0.1),
                fixedFee = a.fixedFees,
            )!!
        // Energy 10 zł covered by the 12.30 zł deposit; distribution 6 zł + 2 months of fees.
        assertEquals(6.0 + 20.0, s.calkowityKoszt, eps)
    }

    @Test
    fun monthsOf() {
        val recs =
            listOf(
                HourlyRecord(at(2024, 11, 3), 0.0, 0.0, 0.0, 0.0),
                HourlyRecord(at(2025, 2, 1), 0.0, 0.0, 0.0, 0.0),
            )
        assertEquals(
            listOf(ym(2024, 11), ym(2024, 12), ym(2025, 1), ym(2025, 2)),
            NetBilling.monthsOf(recs),
        )
    }

    @Test
    fun parseRcemHtmlUsesLatestCorrection() {
        val html =
            """
            <html><body>
            <table><tr><td>2025</td><td>cena [zł/MWh]**</td></tr>
            <tr><td>styczeń</td><td>RCEm</td><td>480,01</td><td>11.02.2025</td></tr>
            <tr><td>skorygowana RCEm*</td><td>-</td><td>-</td><td>-</td></tr>
            <tr><td>marzec***</td><td>RCEm</td><td>182,96</td><td>11.04.2025</td></tr>
            <tr><td>skorygowana RCEm*</td><td>178,84</td><td>11.03.2026</td><td>-2,25</td></tr>
            </table>
            <table><tr><td>2024</td><td>cena [zł/MWh]**</td></tr>
            <tr><td>grudzie&#324;</td><td>RCEm</td><td>470,23</td><td>11.01.2025</td></tr>
            </table>
            </body></html>
            """
        val prices = RcemParser.parse(html)
        assertEquals(setOf(ym(2025, 1), ym(2025, 3), ym(2024, 12)), prices.keys)
        assertEquals(0.48001, prices.getValue(ym(2025, 1)), eps)
        assertEquals(0.17884, prices.getValue(ym(2025, 3)), eps)
        assertEquals(0.47023, prices.getValue(ym(2024, 12)), eps)
    }
}
