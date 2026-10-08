package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port parity on a realistic dataset: 15 months of synthetic Enea CSVs
 * (`src/sharedTest/fixtures/enea`, `tools/testdata/generate_enea_csv.py`) analysed with the default
 * tariffs must give the same numbers as the Python eanalizer (`eanalizer-golden.json`,
 * `tools/testdata/eanalizer_golden.py`). Works on local files only.
 */
class GoldenDatasetTest {
    private val table = TariffTable.default()
    private val golden = Json.parseToJsonElement(fixture("eanalizer-golden.json")).jsonObject
    private val records = mergeRecords(listOf("2024", "2025").map { parseFile("enea/$it.csv") })
    private val eps = 1e-5

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    private fun fixture(name: String) = String(bytes(name), Charsets.UTF_8)

    private fun parseFile(name: String) = EneaCsvParser.parse(EneaCsvParser.decode(bytes(name)))

    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    /** Same synthetic prices as `eanalizer_golden.py`. */
    private fun rcePrice(ts: LocalDateTime) =
        ((ts.hour * 37 + ts.dayOfYear * 11) % 100 - 15) / 100.0

    private fun rcemPrice(m: YearMonth) = 0.20 + ((m.year * 12 + m.monthValue) % 7) * 0.05

    private fun assertResult(what: String, expected: JsonObject, r: AnalysisResult) {
        assertEquals("$what totalCost", expected.num("totalCost"), r.totalCost, eps)
        assertEquals("$what fixedFees", expected.num("fixedFees"), r.fixedFees, eps)
        assertEquals("$what savings", expected.num("savings"), r.savings, eps)
        assertEquals(
            "$what gridImport",
            expected.num("gridImport"),
            r.simulation.sumOf { it.poborZSieci },
            eps,
        )
        assertEquals(
            "$what gridExport",
            expected.num("gridExport"),
            r.simulation.sumOf { it.oddanieDoSieci },
            eps,
        )
        expected["unusedCredit"]?.let {
            assertEquals("$what unusedCredit", it.jsonPrimitive.double, r.unusedCredit!!, eps)
        }
        val zones = expected.obj("zones")
        assertEquals("$what zones", zones.keys, r.zones.map { it.zone }.toSet())
        for (z in r.zones) {
            val e = zones.obj(z.zone)
            assertEquals("$what ${z.zone} pobor", e.num("pobor"), z.poborZSieci, eps)
            assertEquals("$what ${z.zone} oddanie", e.num("oddanie"), z.oddanieDoSieci, eps)
            assertEquals("$what ${z.zone} cost", e.num("cost"), z.kosztPoboru, eps)
        }
    }

    @Test
    fun loadsAllFiles() {
        assertEquals(golden.getValue("records").jsonPrimitive.int, records.size)
        assertEquals(LocalDateTime.of(2024, 10, 1, 0, 0), records.first().timestamp)
        assertEquals(LocalDateTime.of(2025, 12, 31, 23, 0), records.last().timestamp)
        // The data gap is reported; the spring DST hour is not a real gap.
        val missing =
            Periods.findMissingHours(records).filterNot { Periods.isProbableDstSpringGap(it) }
        assertEquals((10..15).map { LocalDateTime.of(2025, 6, 10, it, 0) }, missing)
    }

    @Test
    fun monthlyAndDailyAggregatesMatchEanalizer() {
        val monthly = golden.obj("monthly")
        val rows = Aggregation.monthly(records)
        assertEquals(monthly.keys.toList(), rows.map { it.key })
        for (row in rows) {
            val e = monthly.getValue(row.key).jsonArray.map { it.jsonPrimitive.double }
            val actual = listOf(row.poborPrzed, row.oddaniePrzed, row.pobor, row.oddanie)
            e.zip(actual).forEach { (x, a) -> assertEquals(row.key, x, a, eps) }
        }
        val totals = golden.obj("totals")
        val total = Aggregation.total(rows)
        assertEquals(totals.num("pobor_przed"), total.poborPrzed, eps)
        assertEquals(totals.num("oddanie_przed"), total.oddaniePrzed, eps)
        assertEquals(totals.num("pobor"), total.pobor, eps)
        assertEquals(totals.num("oddanie"), total.oddanie, eps)
        // Daily rows add up to the same totals.
        val daily = Aggregation.daily(records)
        assertEquals(golden.getValue("days").jsonPrimitive.int, daily.size)
        assertEquals(total.pobor, daily.sumOf { it.pobor }, eps)
        val trends = Analyzer.dailyTrends(records)
        assertEquals(golden.getValue("days").jsonPrimitive.int, trends.totalDays)
        assertEquals(golden.getValue("surplusDays").jsonPrimitive.int, trends.surplusDays)
    }

    @Test
    fun tariffCostsMatchEanalizer() {
        for ((tariff, e) in golden.obj("tariffs")) {
            val g = e.jsonObject
            assertResult(
                "$tariff",
                g.obj("plain"),
                Analyzer.runFullAnalysis(records, 0.0, table, tariff)
            )
            assertResult(
                "$tariff nm0.8",
                g.obj("netMetering08"),
                Analyzer.runFullAnalysis(records, 0.0, table, tariff, netMeteringRatio = 0.8),
            )
            assertResult(
                "$tariff nm0.7",
                g.obj("netMetering07"),
                Analyzer.runFullAnalysis(records, 0.0, table, tariff, netMeteringRatio = 0.7),
            )
            val year = Periods.filter(records, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
            assertResult(
                "$tariff 2025",
                g.obj("year2025"),
                Analyzer.runFullAnalysis(year, 0.0, table, tariff)
            )
            assertEquals(
                "$tariff optimal capacity",
                g.num("optimalCapacity"),
                Analyzer.optimalCapacity(records, table, tariff),
                // eanalizer prints it with 3 decimals.
                0.0005,
            )
        }
    }

    @Test
    fun tariffComparisonRanksLikeEanalizer() {
        val expected =
            golden.obj("tariffs").entries.sortedBy {
                it.value.jsonObject.obj("plain").num("totalCost")
            }
        val actual = Analyzer.compareTariffs(records, 0.0, table)
        assertEquals(expected.map { it.key }, actual.map { it.tariff })
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.key, e.value.jsonObject.obj("plain").num("totalCost"), a.totalCost, eps)
        }
    }

    @Test
    fun storageSimulationMatchesEanalizer() {
        for ((tariff, e) in golden.obj("tariffs")) {
            assertResult(
                "$tariff storage",
                e.jsonObject.obj("storage10"),
                Analyzer.runFullAnalysis(records, 10.0, table, tariff, storageEfficiency = 0.9),
            )
        }
    }

    @Test
    fun netBillingMatchesEanalizer() {
        val rce = records.associate { it.timestamp to rcePrice(it.timestamp) }
        val rcem = NetBilling.monthsOf(records).associateWith { rcemPrice(it) }
        for ((tariff, e) in golden.obj("tariffs")) {
            val r = Analyzer.runFullAnalysis(records, 0.0, table, tariff)
            for ((key, valuation) in
                listOf("rcem" to NetBillingValuation.RCEM, "rce" to NetBillingValuation.RCE)) {
                val g = e.jsonObject.obj("netBilling_$key")
                val nb =
                    NetBilling.settle(
                        r.simulation,
                        table,
                        tariff,
                        rce,
                        rcem,
                        valuation,
                        r.fixedFees
                    )!!
                val what = "$tariff $key"
                assertEquals(what, g.num("calkowity_koszt"), nb.calkowityKoszt, eps)
                assertEquals(what, g.num("koszt_energii"), nb.kosztEnergii, eps)
                assertEquals(what, g.num("energia_do_zaplaty"), nb.energiaDoZaplaty, eps)
                assertEquals(what, g.num("koszt_dystrybucji"), nb.kosztDystrybucji, eps)
                assertEquals(what, g.num("wartosc_depozytu"), nb.wartoscDepozytu, eps)
                assertEquals(what, g.num("pokryte_depozytem"), nb.pokryteDepozytem, eps)
                assertEquals(what, g.num("zwrot_nadplaty"), nb.zwrotNadplaty, eps)
                assertEquals(what, g.num("przepadly_depozyt"), nb.przepadlyDepozyt, eps)
                assertEquals(what, g.num("depozyt_pozostaly"), nb.depozytPozostaly, eps)
                assertEquals(what, 0, nb.missingRceHours)
                assertEquals(what, emptyList<YearMonth>(), nb.missingRcemMonths)
            }
        }
    }
}
