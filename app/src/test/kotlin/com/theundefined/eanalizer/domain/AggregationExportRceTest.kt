package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregationExportRceTest {
    private val eps = 1e-9

    @Test
    fun monthlySumsPerMonth() {
        val data =
            listOf(
                rec(at(2024, 6, 1, 4), 3.0, 0.0, 3.0, 0.0),
                rec(at(2024, 5, 1, 4), 1.0, 0.0, 1.0, 0.0),
                rec(at(2024, 5, 15, 10), 2.0, 1.0, 1.5, 0.5),
            )
        val m = Aggregation.monthly(data)
        assertEquals(listOf("2024-05", "2024-06"), m.map { it.key })
        assertEquals(AggregateRow("2024-05", 3.0, 1.0, 2.5, 0.5), m[0])
        assertEquals(3.0, m[1].poborPrzed, eps)
        assertTrue(Aggregation.monthly(emptyList()).isEmpty())
        assertEquals(AggregateRow("RAZEM", 6.0, 1.0, 5.5, 0.5), Aggregation.total(m))
    }

    @Test
    fun dailySums() {
        val d = Aggregation.daily(TestData.records)
        assertEquals(listOf("2024-05-01", "2024-05-02", "2024-05-04"), d.map { it.key })
        assertEquals(3.5, d[0].poborPrzed, eps)
        assertEquals(2.5, d[0].oddanie, eps)
    }

    @Test
    fun exportSimulation() {
        val rows = listOf(SimulationRow(at(2024, 5, 1, 4), 1.0, 0.0, 0.12345, 2.5, 1234.5678))
        assertEquals(
            "timestamp;pobor_z_sieci;oddanie_do_sieci;pobor_z_magazynu;oddanie_do_magazynu;stan_magazynu\n" +
                "2024-05-01 04:00:00;1,000;0,000;0,123;2,500;1234,568\n",
            CsvExport.simulation(rows),
        )
    }

    @Test
    fun exportAggregates() {
        assertEquals(
            "miesiac;pobor_przed;oddanie_przed;pobor;oddanie\n2024-05;3,000;1,000;2,500;0,500\n",
            CsvExport.aggregates(listOf(AggregateRow("2024-05", 3.0, 1.0, 2.5, 0.5))),
        )
        assertEquals(
            "date;pobor_przed;oddanie_przed;pobor;oddanie\n2024-05-01;0,000;0,000;0,000;-1,000\n",
            CsvExport.aggregates(listOf(AggregateRow("2024-05-01", 0.0, 0.0, 0.0, -1.0))),
        )
    }

    @Test
    fun hourlyResamplingMatchesPandas() {
        val entries =
            listOf(
                "2024-07-01 00:15:00" to 400.0,
                "2024-07-01 00:30:00" to 400.0,
                "2024-07-01 00:45:00" to 400.0,
                "2024-07-01 01:00:00" to 400.0,
                "2024-07-01 01:15:00" to 800.0,
                "2024-07-01 01:30:00" to 800.0,
                "2024-07-01 01:45:00" to 800.0,
                "2024-07-01 02:00:00" to 800.0,
                "2024-10-27 02:15:00a" to 100.0,
            )
        val p = RceAnalysis.hourlyPrices(entries)
        assertEquals(0.4, p.getValue(at(2024, 7, 1, 0)), eps)
        assertEquals(0.7, p.getValue(at(2024, 7, 1, 1)), eps)
        assertEquals(0.8, p.getValue(at(2024, 7, 1, 2)), eps)
        assertEquals(0.1, p.getValue(at(2024, 10, 27, 2)), eps)
    }

    @Test
    fun rceAnalysis() {
        // Port of test_rce_fetching_and_analysis.
        val recs =
            listOf(
                TestData.records[0].copy(timestamp = at(2024, 7, 1, 0)),
                TestData.records[1].copy(timestamp = at(2024, 7, 1, 1)),
                rec(at(2024, 7, 1, 5), p = 1.0),
            )
        val prices = mapOf(at(2024, 7, 1, 0) to 0.4, at(2024, 7, 1, 1) to 0.7)
        val r = RceAnalysis.run(recs, prices)
        assertEquals(0.4, r.cost, eps)
        assertEquals(1.75, r.revenue, eps)
        assertEquals(1.35, r.balance, eps)
        assertEquals(1, r.missingHours)
    }
}
