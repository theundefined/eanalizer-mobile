package com.theundefined.eanalizer.domain

import java.time.LocalDateTime

/**
 * One hour of meter data (kWh). `*Przed` = before hourly balancing (raw), `pobor`/`oddanie` = after
 * balancing (net). [timestamp] is floored to the full hour.
 */
data class HourlyRecord(
    val timestamp: LocalDateTime,
    val poborPrzed: Double,
    val oddaniePrzed: Double,
    val pobor: Double,
    val oddanie: Double,
)

/** Concatenates parsed files, sorts by time and drops duplicate timestamps (last one wins). */
fun mergeRecords(files: List<List<HourlyRecord>>): List<HourlyRecord> {
    val byTs = LinkedHashMap<LocalDateTime, HourlyRecord>()
    files.forEach { file -> file.forEach { byTs[it.timestamp] = it } }
    return byTs.values.sortedBy { it.timestamp }
}
