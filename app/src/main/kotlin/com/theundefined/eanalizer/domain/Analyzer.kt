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
    /** Part of [oddanieDoMagazynu] taken from the grid (included in [poborZSieci]). */
    val zSieciDoMagazynu: Double = 0.0,
)

/**
 * Storage behaviour beyond capacity and efficiency. [usableFraction] of the capacity can be used
 * (depth of discharge); [powerKw] limits charging and discharging per hour (0 = no limit). With
 * [gridCharging] the storage is also charged from the grid in the cheapest zone of a multi-zone
 * tariff, up to yesterday's deficit in the more expensive zones (a forecast that uses only past
 * data), and is not discharged in the cheapest zone.
 */
data class StorageOptions(
    val usableFraction: Double = 1.0,
    val powerKw: Double = 0.0,
    val gridCharging: Boolean = false,
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
     * [storageEfficiency] applied on charging, [options] for usable capacity, power and grid
     * charging, then prices consumption per zone. With [netMeteringRatio] exported energy becomes
     * credit consumed in zones ordered by price (most expensive first), leftover credit rolling
     * over to cheaper zones.
     */
    fun runFullAnalysis(
        records: List<HourlyRecord>,
        capacity: Double,
        table: TariffTable,
        tariff: String,
        netMeteringRatio: Double? = null,
        storageEfficiency: Double = 1.0,
        options: StorageOptions = StorageOptions(),
    ): AnalysisResult {
        val months =
            if (records.isEmpty()) 0
            else {
                val a = records.first().timestamp
                val b = records.last().timestamp
                (b.year - a.year) * 12 + (b.monthValue - a.monthValue) + 1
            }
        val usable = capacity * options.usableFraction.coerceIn(0.0, 1.0)
        val power = if (options.powerKw > 0) options.powerKw else Double.POSITIVE_INFINITY
        // Cheapest price per day type (index 1 = weekend), set only when cheaper than another zone.
        val cheapest =
            listOf(false, true).map { weekend ->
                val prices = table.hourlyZones(tariff, weekend).mapNotNull { it?.price }
                val min = prices.minOrNull()
                if (min != null && prices.any { it > min + 1e-9 }) min else null
            }
        var stan = 0.0
        var deficitYesterday = 0.0
        var deficitToday = 0.0
        var day: LocalDate? = null
        val sim = ArrayList<SimulationRow>(records.size)
        val acc = LinkedHashMap<String, ZoneAcc>()

        for (r in records) {
            val date = r.timestamp.toLocalDate()
            if (date != day) {
                if (day != null) deficitYesterday = deficitToday
                deficitToday = 0.0
                day = date
            }
            val zp = table.resolve(r.timestamp, tariff)
            val cheapHour =
                options.gridCharging &&
                    zp != null &&
                    run {
                        val weekend =
                            r.timestamp.dayOfWeek.value >= 6 || PolishHolidays.isHoliday(date)
                        val min = cheapest[if (weekend) 1 else 0]
                        min != null && zp.price <= min + 1e-9
                    }
            var zMag = 0.0
            var doMag = 0.0
            var zSieciDoMag = 0.0
            var zSieci = 0.0
            var doSieci = 0.0
            fun chargeRoom() =
                if (storageEfficiency > 0) (usable - stan).coerceAtLeast(0.0) / storageEfficiency
                else Double.POSITIVE_INFINITY
            if (r.oddaniePrzed > r.poborPrzed) {
                val nadwyzka = r.oddaniePrzed - r.poborPrzed
                doMag = minOf(nadwyzka, chargeRoom(), power)
                stan += doMag * storageEfficiency
                doSieci = nadwyzka - doMag
            } else if (r.poborPrzed > r.oddaniePrzed) {
                val niedobor = r.poborPrzed - r.oddaniePrzed
                if (options.gridCharging && !cheapHour) deficitToday += niedobor
                if (!cheapHour) zMag = minOf(niedobor, stan, power)
                stan -= zMag
                zSieci = niedobor - zMag
            }
            if (cheapHour && storageEfficiency > 0) {
                val target = minOf(usable, deficitYesterday)
                val need = (target - stan).coerceAtLeast(0.0) / storageEfficiency
                zSieciDoMag = minOf(need, chargeRoom(), (power - doMag).coerceAtLeast(0.0))
                stan += zSieciDoMag * storageEfficiency
                doMag += zSieciDoMag
                zSieci += zSieciDoMag
            }
            zp?.let {
                val z = acc.getOrPut(it.zone) { ZoneAcc(it.price) }
                z.pobor += zSieci
                z.oddanie += doSieci
                z.koszt += zSieci * z.price
            }
            sim += SimulationRow(r.timestamp, zSieci, doSieci, zMag, doMag, stan, zSieciDoMag)
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
            // Grid energy that went into the storage isn't consumption.
            savings =
                poborPrzed - zones.sumOf { it.poborZSieci } + sim.sumOf { it.zSieciDoMagazynu },
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
        options: StorageOptions = StorageOptions(),
    ): List<AnalysisResult> {
        if (records.isEmpty()) return emptyList()
        return table.tariffNames
            .map {
                runFullAnalysis(
                    records,
                    capacity,
                    table,
                    it,
                    netMeteringRatio,
                    storageEfficiency,
                    options,
                )
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
