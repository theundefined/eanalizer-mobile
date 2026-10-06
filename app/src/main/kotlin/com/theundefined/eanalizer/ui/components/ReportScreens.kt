package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.domain.Aggregation
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.zl

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
        item { SectionCard { ImportExportChart(a.monthly) } }
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
    SubScreen(stringResource(R.string.screen_data), onBack) {
        item { MutedText(stringResource(R.string.data_files, state.dataYears.joinToString(", "))) }
        if (a == null) return@SubScreen
        item { PeriodInfo(a) }
        item { SectionCard { ImportExportChart(a.daily) } }
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
        item { AggregateTable(a.daily.reversed(), "") }
    }
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
