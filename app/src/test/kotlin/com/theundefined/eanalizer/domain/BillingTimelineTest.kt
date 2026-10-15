package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingTimelineTest {
    private val eps = 1e-6
    private val table = TariffTable.default()
    private val records =
        mergeRecords(
            listOf("2024", "2025").map {
                val bytes =
                    checkNotNull(javaClass.getResourceAsStream("/enea/$it.csv")).use { s ->
                        s.readBytes()
                    }
                EneaCsvParser.parse(EneaCsvParser.decode(bytes))
            }
        )
    private val from = LocalDate.of(2025, 3, 10)
    private val to = LocalDate.of(2025, 12, 31)

    private fun rcem(m: YearMonth) = 0.20 + ((m.year * 12 + m.monthValue) % 7) * 0.05

    private fun settler(start: LocalDate): (List<SimulationRow>, Double) -> NetBillingResult? {
        val month = start.withDayOfMonth(1)
        val history =
            Analyzer.runFullAnalysis(
                    Periods.filter(records, month.minusMonths(12), month.minusDays(1)),
                    0.0,
                    table,
                    "G12w",
                )
                .simulation
        val prices = NetBilling.monthsOf(records).associateWith { rcem(it) }
        return { sim, fee ->
            NetBilling.settle(
                sim,
                table,
                "G12w",
                emptyMap(),
                prices,
                fixedFee = fee,
                history = history,
            )
        }
    }

    @Test
    fun lastPointMatchesToDateWithoutSettlement() {
        val b = Billing.toDate(records, from, to, table, "G12w")!!
        val t = Billing.timeline(b.result.simulation, from, to, table, "G12w")
        assertEquals(b.days, t.size)
        assertEquals(b.total, t.last().cost, eps)
        assertNull(t.last().credit)
        // Costs only grow without a settlement of exported energy.
        assertTrue(t.zipWithNext().all { (a, c) -> c.cost >= a.cost - eps })
    }

    @Test
    fun lastPointMatchesToDateWithNetMetering() {
        val b = Billing.toDate(records, from, to, table, "G12w", 0.8)!!
        val t = Billing.timeline(b.result.simulation, from, to, table, "G12w", 0.8)
        assertEquals(b.days, t.size)
        assertEquals(b.total, t.last().cost, eps)
        assertEquals(b.result.unusedCredit!!, t.last().credit!!, eps)
        // Each day equals settling the range up to that day.
        val mid = from.plusDays(100)
        val bMid = Billing.toDate(records, from, mid, table, "G12w", 0.8)!!
        val p = t.first { it.date == mid }
        assertEquals(bMid.total, p.cost, eps)
        assertEquals(bMid.result.unusedCredit!!, p.credit!!, eps)
    }

    @Test
    fun lastPointMatchesToDateWithNetBilling() {
        val settle = settler(from)
        val b = Billing.toDate(records, from, to, table, "G12w", settle = settle)!!
        val t = Billing.timeline(b.result.simulation, from, to, table, "G12w", settle = settle)
        assertEquals(b.total, t.last().cost, eps)
        assertEquals(b.netBilling!!.depozytPozostaly, t.last().credit!!, eps)
        // Every 7th day plus the last one.
        assertEquals(from, t.first().date)
        assertEquals(from.plusDays(7), t[1].date)
        assertEquals(to, t.last().date)
        assertEquals((b.days - 1) / 7 + 1 + if ((b.days - 1) % 7 == 0) 0 else 1, t.size)
    }

    @Test
    fun daysWithoutDataCarryThePreviousValues() {
        val g11 =
            TariffTable.parseCsv(
                "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\n" +
                    "G11,stala,all,0,24,0.5,0.5,30.0"
            )
        val recs = listOf(rec(at(2024, 6, 1, 10), 0.0, 5.0), rec(at(2024, 6, 4, 10), 2.0))
        val d1 = LocalDate.of(2024, 6, 1)
        val sim = Analyzer.runFullAnalysis(recs, 0.0, g11, "G11", 1.0).simulation
        val t = Billing.timeline(sim, d1, LocalDate.of(2024, 6, 4), g11, "G11", 1.0)
        assertEquals(listOf(5.0, 5.0, 5.0, 3.0), t.map { it.credit })
        // Only the prorated fee (1 zł a day) until the import is covered by the credit.
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), t.map { it.cost })
    }
}
