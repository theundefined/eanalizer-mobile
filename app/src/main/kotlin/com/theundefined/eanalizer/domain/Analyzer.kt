package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.time.LocalDateTime

/** Per-zone totals; net-metering fields are set only when a ratio is used. */
data class ZoneStats(
    val zone: String,
    val price: Double,
    val poborZSieci: Double,
    val oddanieDoSieci: Double,
    val kosztPoboru: Double,
    val magazynWStrefie: Double? = null,
    val kredytZPoprzedniej: Double? = null,
    val energiaDoOplacenia: Double? = null,
)

/** One simulated hour (kWh). */
data class SimulationRow(
    val timestamp: LocalDateTime,
    val poborZSieci: Double,
    val oddanieDoSieci: Double,
    val poborZMagazynu: Double,
    val oddanieDoMagazynu: Double,
    val stanMagazynu: Double,
)

data class AnalysisResult(
    val tariff: String,
    val zones: List<ZoneStats>,
    val totalCost: Double,
    val fixedFees: Double,
    val energyCost: Double,
    val savings: Double,
    val unusedCredit: Double?,
    val months: Int,
    val totalPoborPrzed: Double,
    val totalOddaniePrzed: Double,
    val simulation: List<SimulationRow>,
)

data class DailyTrends(val totalDays: Int, val surplusDays: Int) {
    val percent: Double
        get() = if (totalDays > 0) surplusDays * 100.0 / totalDays else 0.0
}

/** Port of eanalizer `core.py` analyses. */
object Analyzer {
    private class ZoneAcc(val price: Double) {
        var pobor = 0.0
        var oddanie = 0.0
        var koszt = 0.0
    }

    /**
     * Simulates a physical storage of [capacity] kWh (0 = no storage) on pre-balancing volumes,
     * [storageEfficiency] applied on charging, then prices consumption per zone. With
     * [netMeteringRatio] exported energy becomes credit consumed in zones ordered by price (most
     * expensive first), leftover credit rolling over to cheaper zones.
     */
    fun runFullAnalysis(
        records: List<HourlyRecord>,
        capacity: Double,
        table: TariffTable,
        tariff: String,
        netMeteringRatio: Double? = null,
        storageEfficiency: Double = 1.0,
    ): AnalysisResult {
        val months =
            if (records.isEmpty()) 0
            else {
                val a = records.first().timestamp
                val b = records.last().timestamp
                (b.year - a.year) * 12 + (b.monthValue - a.monthValue) + 1
            }
        var stan = 0.0
        val sim = ArrayList<SimulationRow>(records.size)
        val acc = LinkedHashMap<String, ZoneAcc>()

        for (r in records) {
            var zMag = 0.0
            var doMag = 0.0
            var zSieci = 0.0
            var doSieci = 0.0
            if (r.oddaniePrzed > r.poborPrzed) {
                val nadwyzka = r.oddaniePrzed - r.poborPrzed
                val wolne = capacity - stan
                val potrzebne =
                    if (storageEfficiency > 0) wolne / storageEfficiency
                    else Double.POSITIVE_INFINITY
                doMag = minOf(nadwyzka, potrzebne)
                stan += doMag * storageEfficiency
                doSieci = nadwyzka - doMag
            } else if (r.poborPrzed > r.oddaniePrzed) {
                val niedobor = r.poborPrzed - r.oddaniePrzed
                zMag = minOf(niedobor, stan)
                stan -= zMag
                zSieci = niedobor - zMag
            }
            table.resolve(r.timestamp, tariff)?.let { zp ->
                val z = acc.getOrPut(zp.zone) { ZoneAcc(zp.price) }
                z.pobor += zSieci
                z.oddanie += doSieci
                z.koszt += zSieci * z.price
            }
            sim += SimulationRow(r.timestamp, zSieci, doSieci, zMag, doMag, stan)
        }

        val zones: List<ZoneStats>
        var energyCost: Double
        var unused: Double? = null
        if (netMeteringRatio != null) {
            energyCost = 0.0
            var rollover = 0.0
            val byName = HashMap<String, ZoneStats>()
            for ((name, z) in acc.entries.sortedByDescending { it.value.price }) {
                val magazyn = z.oddanie * netMeteringRatio
                val kredyt = magazyn + rollover
                val doZaplaty = maxOf(0.0, z.pobor - kredyt)
                val koszt = doZaplaty * z.price
                energyCost += koszt
                byName[name] =
                    ZoneStats(
                        name,
                        z.price,
                        z.pobor,
                        z.oddanie,
                        koszt,
                        magazyn,
                        rollover,
                        doZaplaty
                    )
                rollover = maxOf(0.0, kredyt - z.pobor)
            }
            unused = rollover
            zones = acc.keys.map { byName.getValue(it) }
        } else {
            zones = acc.map { (name, z) -> ZoneStats(name, z.price, z.pobor, z.oddanie, z.koszt) }
            energyCost = zones.sumOf { it.kosztPoboru }
        }
        val fixed = table.fixedFee(tariff) * months
        val poborPrzed = records.sumOf { it.poborPrzed }
        return AnalysisResult(
            tariff = tariff,
            zones = zones,
            totalCost = energyCost + fixed,
            fixedFees = fixed,
            energyCost = energyCost,
            savings = poborPrzed - zones.sumOf { it.poborZSieci },
            unusedCredit = unused,
            months = months,
            totalPoborPrzed = poborPrzed,
            totalOddaniePrzed = records.sumOf { it.oddaniePrzed },
            simulation = sim,
        )
    }

    /** Runs [runFullAnalysis] for every tariff in [table], cheapest first. */
    fun compareTariffs(
        records: List<HourlyRecord>,
        capacity: Double,
        table: TariffTable,
        netMeteringRatio: Double? = null,
        storageEfficiency: Double = 1.0,
    ): List<AnalysisResult> {
        if (records.isEmpty()) return emptyList()
        return table.tariffNames
            .map {
                runFullAnalysis(records, capacity, table, it, netMeteringRatio, storageEfficiency)
            }
            .sortedBy { it.totalCost }
    }

    /**
     * Suggested storage size: max of (a) the largest daily post-balancing consumption on a
     * net-export day and (b) the largest daily pre-balancing consumption in the most expensive zone
     * of [tariff].
     */
    fun optimalCapacity(records: List<HourlyRecord>, table: TariffTable, tariff: String): Double {
        if (records.isEmpty()) return 0.0
        val byDay = records.groupBy { it.timestamp.toLocalDate() }
        val forExport =
            byDay.values
                .filter { day -> day.sumOf { it.oddanie } > day.sumOf { it.pobor } }
                .maxOfOrNull { day -> day.sumOf { it.pobor } } ?: 0.0
        val expensive =
            table.zones
                .filter { it.tariff.equals(tariff, ignoreCase = true) }
                .maxByOrNull { it.energyPrice + it.distPrice }
                ?.zoneName
        val forArbitrage =
            if (expensive == null) 0.0
            else
                records
                    .filter { table.resolve(it.timestamp, tariff)?.zone == expensive }
                    .groupBy { it.timestamp.toLocalDate() }
                    .values
                    .maxOfOrNull { day -> day.sumOf { it.poborPrzed } } ?: 0.0
        return maxOf(forExport, forArbitrage)
    }

    /** Days where post-balancing export exceeded import. */
    fun dailyTrends(records: List<HourlyRecord>): DailyTrends {
        val days: Map<LocalDate, List<HourlyRecord>> =
            records.groupBy { it.timestamp.toLocalDate() }
        return DailyTrends(
            days.size,
            days.values.count { d -> d.sumOf { it.oddanie } > d.sumOf { it.pobor } },
        )
    }
}
