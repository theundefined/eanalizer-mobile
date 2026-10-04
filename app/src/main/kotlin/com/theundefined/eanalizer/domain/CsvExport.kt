package com.theundefined.eanalizer.domain

import java.time.format.DateTimeFormatter
import java.util.Locale

/** CSV export compatible with eanalizer (`;` separator, `,` decimal, 3 decimals). */
object CsvExport {
    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun simulation(rows: List<SimulationRow>): String = buildString {
        line(
            "timestamp",
            "pobor_z_sieci",
            "oddanie_do_sieci",
            "pobor_z_magazynu",
            "oddanie_do_magazynu",
            "stan_magazynu"
        )
        rows.forEach {
            line(
                TS.format(it.timestamp),
                num(it.poborZSieci),
                num(it.oddanieDoSieci),
                num(it.poborZMagazynu),
                num(it.oddanieDoMagazynu),
                num(it.stanMagazynu),
            )
        }
    }

    /** First column is `date` for daily keys, `miesiac` for monthly (`yyyy-MM`) keys. */
    fun aggregates(rows: List<AggregateRow>): String = buildString {
        val keyCol = if (rows.isNotEmpty() && rows.all { it.key.length == 7 }) "miesiac" else "date"
        line(keyCol, "pobor_przed", "oddanie_przed", "pobor", "oddanie")
        rows.forEach {
            line(it.key, num(it.poborPrzed), num(it.oddaniePrzed), num(it.pobor), num(it.oddanie))
        }
    }

    internal fun num(v: Double): String = String.format(Locale.ROOT, "%.3f", v).replace('.', ',')

    private fun StringBuilder.line(vararg cells: String) {
        append(cells.joinToString(";")).append('\n')
    }
}
