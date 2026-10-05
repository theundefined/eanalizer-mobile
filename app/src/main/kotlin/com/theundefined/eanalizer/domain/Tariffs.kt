package com.theundefined.eanalizer.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DayType(val csv: String) {
    @SerialName("all") ALL("all"),
    @SerialName("weekday") WEEKDAY("weekday"),
    @SerialName("weekend") WEEKEND("weekend");

    companion object {
        fun parse(s: String): DayType? = entries.firstOrNull { it.csv.equals(s.trim(), true) }
    }
}

/** One row of the tariff table; hours are `[startHour, endHour)`, overnight when start > end. */
@Serializable
data class TariffZone(
    val tariff: String,
    val zoneName: String,
    val dayType: DayType,
    val startHour: Int,
    val endHour: Int,
    val energyPrice: Double,
    val distPrice: Double,
    val distFee: Double,
) {
    fun covers(hour: Int): Boolean =
        (startHour < endHour && hour >= startHour && hour < endHour) ||
            (startHour > endHour && (hour >= startHour || hour < endHour))
}

/** Resolved zone with its prices (zł/kWh). */
data class ZonePrice(val zone: String, val energyPrice: Double, val distPrice: Double) {
    val price: Double
        get() = energyPrice + distPrice
}

/** Tariff definitions (port of eanalizer `TariffManager`). */
class TariffTable(val zones: List<TariffZone>) {
    /** Distinct tariff names in table order. */
    val tariffNames: List<String> = zones.map { it.tariff }.distinct()

    private fun rules(tariff: String) = zones.filter { it.tariff.equals(tariff, ignoreCase = true) }

    /**
     * Zone for [ts]: weekends and Polish holidays use `weekend` rows, a tariff with any `all` rows
     * always uses those; first matching row wins. Null if nothing matches.
     */
    fun resolve(ts: LocalDateTime, tariff: String): ZonePrice? {
        val rules = rules(tariff)
        val dayType =
            when {
                rules.any { it.dayType == DayType.ALL } -> DayType.ALL
                ts.dayOfWeek == DayOfWeek.SATURDAY ||
                    ts.dayOfWeek == DayOfWeek.SUNDAY ||
                    PolishHolidays.isHoliday(ts.toLocalDate()) -> DayType.WEEKEND
                else -> DayType.WEEKDAY
            }
        return rules
            .firstOrNull { it.dayType == dayType && it.covers(ts.hour) }
            ?.let { ZonePrice(it.zoneName, it.energyPrice, it.distPrice) }
    }

    /** Monthly fixed fee (first row of the tariff), 0 when unknown. */
    fun fixedFee(tariff: String): Double = rules(tariff).firstOrNull()?.distFee ?: 0.0

    fun toCsv(): String = buildString {
        append(HEADER).append('\n')
        zones.forEach { z ->
            append(
                    listOf(
                            z.tariff,
                            z.zoneName,
                            z.dayType.csv,
                            z.startHour,
                            z.endHour,
                            num(z.energyPrice),
                            num(z.distPrice),
                            num(z.distFee),
                        )
                        .joinToString(",")
                )
                .append('\n')
        }
    }

    override fun equals(other: Any?): Boolean = other is TariffTable && other.zones == zones

    override fun hashCode(): Int = zones.hashCode()

    companion object {
        const val HEADER =
            "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee"

        /** Content of eanalizer `config/tariffs.csv` (gross prices, ENEA 2026). */
        val DEFAULT_CSV: String =
            """
            tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee
            G11,stala,all,0,24,0.61254,0.35547,43.4682
            G12,nocna,all,22,6,0.414387,0.165681,46.1004
            G12,nocna,all,13,15,0.414387,0.165681,46.1004
            G12,dzienna,all,6,13,0.710817,0.395199,46.1004
            G12,dzienna,all,15,22,0.710817,0.395199,46.1004
            G12w,pozaszczytowa,weekday,0,6,0.426195,0.153381,55.0302
            G12w,szczytowa,weekday,6,21,0.801714,0.385728,55.0302
            G12w,pozaszczytowa,weekday,21,24,0.426195,0.153381,55.0302
            G12w,pozaszczytowa,weekend,0,24,0.426195,0.153381,55.0302
            """
                .trimIndent() + "\n"

        /**
         * Previous default with wrong zone hours (G12 without the 13-15 night zone, G12w peak until
         * 22). A stored table equal to it is replaced with [default].
         */
        private val LEGACY_DEFAULT_CSV: String =
            """
            tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee
            G11,stala,all,0,24,0.61254,0.35547,43.4682
            G12,nocna,all,22,6,0.414387,0.165681,46.1004
            G12,dzienna,all,6,22,0.710817,0.395199,46.1004
            G12w,pozaszczytowa,weekday,0,6,0.426195,0.153381,55.0302
            G12w,szczytowa,weekday,6,22,0.801714,0.385728,55.0302
            G12w,pozaszczytowa,weekday,22,24,0.426195,0.153381,55.0302
            G12w,pozaszczytowa,weekend,0,24,0.426195,0.153381,55.0302
            """
                .trimIndent()

        fun isLegacyDefault(table: TariffTable): Boolean = table == parseCsv(LEGACY_DEFAULT_CSV)

        fun default(): TariffTable = parseCsv(DEFAULT_CSV)

        /**
         * Parses a tariff CSV. Columns are located by header names (falls back to the default order
         * when there is no header); invalid rows are skipped.
         */
        fun parseCsv(text: String): TariffTable {
            val lines = text.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return TariffTable(emptyList())
            val names = HEADER.split(',')
            val first = lines[0].split(',').map { it.trim().lowercase() }
            val hasHeader = "tariff" in first
            val idx = if (hasHeader) names.map { first.indexOf(it) } else names.indices.toList()
            if (idx.any { it < 0 }) return TariffTable(emptyList())
            val zones =
                lines.drop(if (hasHeader) 1 else 0).mapNotNull { line ->
                    val f = line.split(',').map { it.trim() }
                    if (f.size <= idx.max()) return@mapNotNull null
                    fun s(i: Int) = f[idx[i]]
                    fun d(i: Int) = s(i).toDoubleOrNull()
                    TariffZone(
                        tariff = s(0).takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                        zoneName = s(1).takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                        dayType = DayType.parse(s(2)) ?: return@mapNotNull null,
                        startHour = s(3).toIntOrNull() ?: return@mapNotNull null,
                        endHour = s(4).toIntOrNull() ?: return@mapNotNull null,
                        energyPrice = d(5) ?: return@mapNotNull null,
                        distPrice = d(6) ?: return@mapNotNull null,
                        distFee = d(7) ?: return@mapNotNull null,
                    )
                }
            return TariffTable(zones)
        }

        private fun num(v: Double): String =
            if (v.isFinite()) BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
            else v.toString()
    }
}
