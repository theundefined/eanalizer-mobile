package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.domain.Aggregation
import com.theundefined.eanalizer.ui.Analysis
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.zl
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CompareScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_compare), onBack) {
        item { MutedText(stringResource(R.string.compare_info)) }
        if (a == null) return@SubScreen
        item {
            MutedText(
                stringResource(
                    R.string.analysis_period,
                    a.from.toString(),
                    a.to.toString(),
                    a.recordCount
                )
            )
        }
        val cheapest = a.comparison.firstOrNull()?.totalCost ?: 0.0
        a.comparison.forEachIndexed { i, row ->
            item(key = row.tariff) {
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            row.tariff,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            zl(row.totalCost),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (i == 0)
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.cheapest)) }
                        )
                    else
                        MutedText(stringResource(R.string.difference, zl(row.totalCost - cheapest)))
                    val nb = row.netBilling
                    if (nb != null) {
                        ValueRow(stringResource(R.string.nb_energy_to_pay), zl(nb.energiaDoZaplaty))
                        ValueRow(stringResource(R.string.nb_distribution), zl(nb.kosztDystrybucji))
                    } else {
                        ValueRow(
                            stringResource(R.string.energy_cost),
                            zl(row.totalCost - row.fixedFees)
                        )
                    }
                    ValueRow(
                        stringResource(R.string.fixed_fees, a.result.months),
                        zl(row.fixedFees)
                    )
                }
            }
        }
        item(key = "dynamic") { DynamicTariffCard(state, viewModel) }
    }
}

@Composable
fun RceScreen(state: UiState, onLoad: () -> Unit, onBack: () -> Unit) {
    val a = state.analysis
    val rce = state.rce
    SubScreen(stringResource(R.string.screen_rce), onBack) {
        item { MutedText(stringResource(R.string.rce_info)) }
        if (a == null) return@SubScreen
        item {
            SectionCard {
                if (rce.loading) {
                    MutedText(stringResource(R.string.rce_progress, rce.done, rce.total))
                    LinearProgressIndicator(
                        progress = { if (rce.total > 0) rce.done.toFloat() / rce.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Button(onClick = onLoad) {
                        Text(stringResource(R.string.rce_load, a.from.toString(), a.to.toString()))
                    }
                }
            }
        }
        val result = rce.result
        if (result != null && !rce.loading) {
            item {
                SectionCard(title = "${rce.from} – ${rce.to}") {
                    ValueRow(stringResource(R.string.rce_cost), zl(result.cost))
                    ValueRow(stringResource(R.string.rce_revenue), zl(result.revenue))
                    ValueRow(
                        stringResource(R.string.rce_balance),
                        zl(result.balance),
                        emphasized = true
                    )
                    if (result.missingHours > 0)
                        MutedText(stringResource(R.string.rce_missing, result.missingHours))
                    if (rce.failedDays > 0) {
                        Text(
                            stringResource(R.string.rce_failed_days, rce.failedDays),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonthlyScreen(state: UiState, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_monthly), onBack) {
        if (a == null) return@SubScreen
        item { PeriodInfo(a) }
        item { SectionCard { ImportExportChart(a.monthly, tariff = a.inputs.tariff) } }
        item { AggregateTable(a.monthly, stringResource(R.string.month)) }
        val nb = a.netBilling
        if (nb != null) {
            item {
                SectionCard(title = stringResource(R.string.nb_monthly)) {
                    val w = listOf(1.1f, 1f, 1f, 1f, 1f)
                    TableRow(
                        listOf(
                            stringResource(R.string.month),
                            stringResource(R.string.nb_energy_cost),
                            stringResource(R.string.nb_covered),
                            stringResource(R.string.nb_new_deposit),
                            stringResource(R.string.nb_balance),
                        ),
                        header = true,
                        weights = w,
                    )
                    nb.months.forEach { m ->
                        TableRow(
                            listOf(
                                m.month.toString(),
                                num(m.kosztEnergii),
                                num(m.pokryteDepozytem),
                                num(m.nowyDepozyt),
                                num(m.saldoDepozytu),
                            ),
                            weights = w,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DataScreen(state: UiState, onBack: () -> Unit) {
    val a = state.analysis
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var month by rememberSaveable { mutableStateOf<String?>(null) }
    val days = remember(a, year, month) { a?.let { dailyIn(it, year, month) }.orEmpty() }
    SubScreen(stringResource(R.string.screen_data), onBack) {
        item { MutedText(stringResource(R.string.data_files, state.dataYears.joinToString(", "))) }
        if (a == null) return@SubScreen
        item { PeriodInfo(a) }
        item {
            SectionCard {
                DailyRangePicker(
                    a,
                    year,
                    month,
                    {
                        year = it
                        month = null
                    },
                    { month = it }
                )
                ImportExportChart(days, tariff = a.inputs.tariff)
            }
        }
        item {
            SectionCard(title = stringResource(R.string.missing_hours_list, a.missingHours.size)) {
                if (a.missingHours.isEmpty()) MutedText(stringResource(R.string.no_missing_hours))
                a.missingHours.take(MAX_MISSING).forEach {
                    Text(
                        it.toString().replace('T', ' '),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (a.missingHours.size > MAX_MISSING)
                    MutedText(stringResource(R.string.and_more, a.missingHours.size - MAX_MISSING))
            }
        }
        item { AggregateTable(days.reversed(), "") }
    }
}

/**
 * Year ([year] null = the analysis period) and optionally one month ([month], `yyyy-MM`) of the
 * daily data.
 */
@Composable
private fun DailyRangePicker(
    a: Analysis,
    year: Int?,
    month: String?,
    onYear: (Int?) -> Unit,
    onMonth: (String?) -> Unit,
) {
    val years = remember(a) { a.dailyAll.map { it.key.take(4).toInt() }.distinct().sorted() }
    val months = remember(a, year) { dailyIn(a, year, null).map { it.key.take(7) }.distinct() }
    ChipRow {
        FilterChip(
            selected = year == null,
            onClick = { onYear(null) },
            label = { Text(stringResource(R.string.data_range_period)) },
        )
        years.forEach { y ->
            FilterChip(
                selected = year == y,
                onClick = { onYear(y) },
                label = { Text(y.toString()) },
            )
        }
    }
    if (months.size > 1)
        ChipRow {
            FilterChip(
                selected = month == null,
                onClick = { onMonth(null) },
                label = { Text(stringResource(R.string.data_range_all_months)) },
            )
            months.forEach { m ->
                val ym = YearMonth.parse(m)
                FilterChip(
                    selected = month == m,
                    onClick = { onMonth(m) },
                    label = {
                        Text(
                            ym.month.getDisplayName(
                                TextStyle.SHORT_STANDALONE,
                                Locale.getDefault()
                            ) + if (year == null) " ${ym.year % 100}" else ""
                        )
                    },
                )
            }
        }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** Days of [year] (null = the analysis period), narrowed to [month] (`yyyy-MM`) if given. */
private fun dailyIn(a: Analysis, year: Int?, month: String?): List<AggregateRow> {
    val days = if (year == null) a.daily else a.dailyAll.filter { it.key.startsWith("$year-") }
    return if (month == null) days else days.filter { it.key.startsWith("$month-") }
}

private const val MAX_MISSING = 50

/** Volumes per key; before balancing, with the post-balancing value in brackets. */
@Composable
private fun AggregateTable(rows: List<AggregateRow>, keyHeader: String) {
    SectionCard {
        MutedText(stringResource(R.string.before_after_hint))
        val w = listOf(1f, 1.3f, 1.3f)
        TableRow(
            listOf(
                keyHeader,
                stringResource(R.string.legend_consumption),
                stringResource(R.string.legend_export)
            ),
            header = true,
            weights = w,
        )
        (rows + Aggregation.total(rows, "Σ")).forEach { r ->
            TableRow(
                listOf(
                    r.key,
                    "${kwh(r.poborPrzed)} (${num(r.pobor, 1)})",
                    "${kwh(r.oddaniePrzed)} (${num(r.oddanie, 1)})",
                ),
                weights = w,
            )
        }
    }
}
