package com.theundefined.eanalizer.domain

import java.time.LocalDate
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

    /** All downloaded hourly meter data (eanalizer column names). */
    fun hourly(records: List<HourlyRecord>): String = buildString {
        line("timestamp", "pobor_przed", "oddanie_przed", "pobor", "oddanie")
        records.forEach {
            line(
                TS.format(it.timestamp),
                num(it.poborPrzed),
                num(it.oddaniePrzed),
                num(it.pobor),
                num(it.oddanie),
            )
        }
    }

    /**
     * Spreadsheet with all hourly data plus daily/monthly sums, and - for the analysed period - the
     * storage simulation and the monthly net-billing settlement when available.
     */
    fun workbook(
        records: List<HourlyRecord>,
        simulation: List<SimulationRow>? = null,
        netBilling: NetBillingResult? = null,
    ): List<XlsxSheet> = buildList {
        val meter =
            listOf("pobór przed [kWh]", "oddanie przed [kWh]", "pobór [kWh]", "oddanie [kWh]")
        add(
            XlsxSheet(
                "Godzinowe",
                sequenceOf(listOf<Any?>("czas") + meter) +
                    records.asSequence().map {
                        listOf(it.timestamp, it.poborPrzed, it.oddaniePrzed, it.pobor, it.oddanie)
                    },
            )
        )
        fun aggregates(name: String, key: String, rows: List<AggregateRow>) =
            XlsxSheet(
                name,
                sequenceOf(listOf<Any?>(key) + meter) +
                    rows.asSequence().map {
                        listOf(
                            if (it.key.length == 10) LocalDate.parse(it.key) else it.key,
                            it.poborPrzed,
                            it.oddaniePrzed,
                            it.pobor,
                            it.oddanie,
                        )
                    },
            )
        add(aggregates("Dzienne", "dzień", Aggregation.daily(records)))
        add(aggregates("Miesięczne", "miesiąc", Aggregation.monthly(records)))
        if (simulation != null) {
            add(
                XlsxSheet(
                    "Symulacja",
                    sequenceOf(
                        listOf<Any?>(
                            "czas",
                            "pobór z sieci [kWh]",
                            "oddanie do sieci [kWh]",
                            "pobór z magazynu [kWh]",
                            "oddanie do magazynu [kWh]",
                            "stan magazynu [kWh]",
                        )
                    ) +
                        simulation.asSequence().map {
                            listOf(
                                it.timestamp,
                                it.poborZSieci,
                                it.oddanieDoSieci,
                                it.poborZMagazynu,
                                it.oddanieDoMagazynu,
                                it.stanMagazynu,
                            )
                        },
                )
            )
        }
        if (netBilling != null) {
            add(
                XlsxSheet(
                    "Net-billing",
                    sequenceOf(
                        listOf<Any?>(
                            "miesiąc",
                            "pobór [kWh]",
                            "oddanie [kWh]",
                            "energia czynna [zł]",
                            "pokryte z depozytu [zł]",
                            "energia do zapłaty [zł]",
                            "dystrybucja [zł]",
                            "nowy depozyt [zł]",
                            "zwrot nadpłaty [zł]",
                            "saldo depozytu [zł]",
                        )
                    ) +
                        netBilling.months.asSequence().map {
                            listOf(
                                it.month.toString(),
                                it.pobor,
                                it.oddanie,
                                it.kosztEnergii,
                                it.pokryteDepozytem,
                                it.energiaDoZaplaty,
                                it.kosztDystrybucji,
                                it.nowyDepozyt,
                                it.zwrotNadplaty,
                                it.saldoDepozytu,
                            )
                        },
                )
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
