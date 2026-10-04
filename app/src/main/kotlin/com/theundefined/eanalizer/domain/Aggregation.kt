package com.theundefined.eanalizer.domain

import java.time.format.DateTimeFormatter

/** Summed volumes for a day (`yyyy-MM-dd`) or month (`yyyy-MM`). */
data class AggregateRow(
    val key: String,
    val poborPrzed: Double,
    val oddaniePrzed: Double,
    val pobor: Double,
    val oddanie: Double,
)

object Aggregation {
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    fun daily(records: List<HourlyRecord>): List<AggregateRow> = group(records, DAY)

    fun monthly(records: List<HourlyRecord>): List<AggregateRow> = group(records, MONTH)

    /** Sum of all rows, keyed [key]. */
    fun total(rows: List<AggregateRow>, key: String = "RAZEM"): AggregateRow =
        AggregateRow(
            key,
            rows.sumOf { it.poborPrzed },
            rows.sumOf { it.oddaniePrzed },
            rows.sumOf { it.pobor },
            rows.sumOf { it.oddanie },
        )

    private fun group(records: List<HourlyRecord>, fmt: DateTimeFormatter) =
        records
            .groupBy { fmt.format(it.timestamp) }
            .map { (k, rs) ->
                AggregateRow(
                    k,
                    rs.sumOf { it.poborPrzed },
                    rs.sumOf { it.oddaniePrzed },
                    rs.sumOf { it.pobor },
                    rs.sumOf { it.oddanie },
                )
            }
            .sortedBy { it.key }
}
