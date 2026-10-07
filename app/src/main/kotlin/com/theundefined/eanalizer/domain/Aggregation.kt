package com.theundefined.eanalizer.domain

import java.time.format.DateTimeFormatter

/** Summed volumes for a day (`yyyy-MM-dd`) or month (`yyyy-MM`). */
data class AggregateRow(
    val key: String,
    val poborPrzed: Double,
    val oddaniePrzed: Double,
    val pobor: Double,
    val oddanie: Double,
    /** Pre-balancing volumes split by tariff zone, cheapest first; empty = not split. */
    val zones: List<ZoneVolume> = emptyList(),
)

/** Pre-balancing volumes of one tariff zone ([price] = energy + distribution per kWh). */
data class ZoneVolume(
    val zone: String,
    val price: Double,
    val poborPrzed: Double,
    val oddaniePrzed: Double,
)

object Aggregation {
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    /** With [table] and [tariff] every row is also split by the tariff's zones. */
    fun daily(
        records: List<HourlyRecord>,
        table: TariffTable? = null,
        tariff: String? = null,
    ): List<AggregateRow> = group(records, DAY, zoneOf(table, tariff))

    fun monthly(
        records: List<HourlyRecord>,
        table: TariffTable? = null,
        tariff: String? = null,
    ): List<AggregateRow> = group(records, MONTH, zoneOf(table, tariff))

    /** Sum of all rows, keyed [key]. */
    fun total(rows: List<AggregateRow>, key: String = "RAZEM"): AggregateRow =
        AggregateRow(
            key,
            rows.sumOf { it.poborPrzed },
            rows.sumOf { it.oddaniePrzed },
            rows.sumOf { it.pobor },
            rows.sumOf { it.oddanie },
        )

    /** Zone resolver; null when there is nothing to split (no tariff or a single zone). */
    private fun zoneOf(table: TariffTable?, tariff: String?): ((HourlyRecord) -> ZonePrice?)? {
        if (table == null || tariff == null) return null
        val names =
            listOf(false, true).flatMap { table.hourlyZones(tariff, it) }.mapNotNull { it?.zone }
        if (names.distinct().size < 2) return null
        return { table.resolve(it.timestamp, tariff) }
    }

    private fun group(
        records: List<HourlyRecord>,
        fmt: DateTimeFormatter,
        zoneOf: ((HourlyRecord) -> ZonePrice?)?,
    ) =
        records
            .groupBy { fmt.format(it.timestamp) }
            .map { (k, rs) ->
                AggregateRow(
                    k,
                    rs.sumOf { it.poborPrzed },
                    rs.sumOf { it.oddaniePrzed },
                    rs.sumOf { it.pobor },
                    rs.sumOf { it.oddanie },
                    zones = zoneOf?.let { splitByZone(rs, it) } ?: emptyList(),
                )
            }
            .sortedBy { it.key }

    /** Hours outside every zone of the tariff are summed under [UNKNOWN_ZONE]. */
    private fun splitByZone(
        records: List<HourlyRecord>,
        zoneOf: (HourlyRecord) -> ZonePrice?,
    ): List<ZoneVolume> =
        records
            .groupBy { zoneOf(it) ?: ZonePrice(UNKNOWN_ZONE, Double.MAX_VALUE, 0.0) }
            .map { (z, rs) ->
                ZoneVolume(
                    z.zone,
                    z.price,
                    rs.sumOf { it.poborPrzed },
                    rs.sumOf { it.oddaniePrzed }
                )
            }
            .sortedBy { it.price }

    const val UNKNOWN_ZONE = "?"
}
