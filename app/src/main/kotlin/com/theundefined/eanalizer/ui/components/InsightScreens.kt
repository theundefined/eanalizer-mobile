package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.local.SettlementMode
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.domain.StorageEconomics
import com.theundefined.eanalizer.ui.Analysis
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.zl

/** "Okres analizy: …" line shown on every report based on the selected period. */
@Composable
fun PeriodInfo(a: Analysis) {
    MutedText(
        stringResource(R.string.analysis_period, a.from.toString(), a.to.toString(), a.recordCount)
    )
}

@Composable
private fun EmptyOrLoading(state: UiState) {
    SectionCard {
        if (state.analyzing || state.loadingLocal) LinearProgressIndicator(Modifier.fillMaxWidth())
        else MutedText(stringResource(R.string.no_data_in_period))
    }
}

private fun pct(v: Double) = num(v * 100, 0) + "%"

// ---- Monthly bills ----------------------------------------------------------------------------

@Composable
fun BillsScreen(state: UiState, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_bills), onBack) {
        item { MutedText(stringResource(R.string.bills_info)) }
        if (a == null || a.bills.isEmpty()) {
            item { EmptyOrLoading(state) }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        if (state.prefs.mode == SettlementMode.NET_METERING) {
            item {
                Text(
                    stringResource(R.string.bills_net_metering),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val bills = a.bills.reversed()
        items(bills.size, key = { bills[it].month.toString() }) { i ->
            val b = bills[i]
            SectionCard(title = b.month.toString()) {
                val w = listOf(1.3f, 1f, 1f, 1f)
                TableRow(
                    listOf(
                        stringResource(R.string.zone),
                        stringResource(R.string.from_grid),
                        stringResource(R.string.bills_energy),
                        stringResource(R.string.bills_distribution),
                    ),
                    header = true,
                    weights = w,
                )
                b.lines.forEach { l ->
                    TableRow(
                        listOf(l.zone, kwh(l.kwh), zl(l.energyCost), zl(l.distCost)),
                        weights = w,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ValueRow(stringResource(R.string.bills_energy_total), zl(b.energyCost))
                b.coveredByDeposit?.let {
                    if (it > 0) ValueRow(stringResource(R.string.nb_covered), "-" + zl(it))
                }
                ValueRow(stringResource(R.string.bills_distribution_total), zl(b.distCost))
                ValueRow(stringResource(R.string.bills_fixed), zl(b.fixedFee))
                b.refund?.let {
                    if (it > 0) ValueRow(stringResource(R.string.nb_refund), "-" + zl(it))
                }
                ValueRow(stringResource(R.string.bills_total), zl(b.total), emphasized = true)
            }
        }
    }
}

// ---- Storage profitability --------------------------------------------------------------------

@Composable
fun StorageScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val a = state.analysis
    LaunchedEffect(a) { if (a != null) viewModel.loadStorage() }
    SubScreen(stringResource(R.string.screen_storage), onBack) {
        item { MutedText(stringResource(R.string.storage_info)) }
        if (a == null) {
            item { EmptyOrLoading(state) }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        item {
            SectionCard {
                DecimalField(
                    label = stringResource(R.string.storage_price),
                    value = state.reportPrefs.storagePricePerKwh,
                    modifier = Modifier.fillMaxWidth(),
                    valid = { it >= 0 },
                ) { v ->
                    viewModel.updateReportPrefs { it.copy(storagePricePerKwh = v) }
                }
                MutedText(stringResource(R.string.storage_price_hint))
            }
        }
        val s = state.storage
        if (s.loading || s.forAnalysis !== a) {
            item { SectionCard { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            return@SubScreen
        }
        val scenarios =
            StorageEconomics.scenarios(
                s.costs.keys,
                java.time.temporal.ChronoUnit.DAYS.between(a.from, a.to).toInt() + 1,
                state.reportPrefs.storagePricePerKwh,
            ) {
                s.costs.getValue(it)
            }
        val best = scenarios.filter { it.paybackYears != null }.minByOrNull { it.paybackYears!! }
        item {
            SectionCard {
                val w = listOf(0.9f, 1.1f, 1.1f, 0.9f)
                TableRow(
                    listOf(
                        stringResource(R.string.storage_capacity_col),
                        stringResource(R.string.storage_cost_col),
                        stringResource(R.string.storage_savings_col),
                        stringResource(R.string.storage_payback_col),
                    ),
                    header = true,
                    weights = w,
                )
                scenarios.forEach { sc ->
                    val marks = buildString {
                        if (sc.capacity == state.prefs.capacity) append(" •")
                        if (sc == best) append(" ★")
                    }
                    TableRow(
                        listOf(
                            num(sc.capacity, 1) + " kWh" + marks,
                            zl(sc.cost),
                            if (sc.capacity == 0.0) "—" else zl(sc.annualSavings),
                            sc.paybackYears?.let { stringResource(R.string.years, num(it, 1)) }
                                ?: "—",
                        ),
                        weights = w,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MutedText(stringResource(R.string.storage_legend))
            }
        }
    }
}

// ---- Year over year ---------------------------------------------------------------------------

@Composable
fun YearOverYearScreen(state: UiState, onBack: () -> Unit) {
    val years = state.years
    SubScreen(stringResource(R.string.screen_yoy), onBack) {
        item { MutedText(stringResource(R.string.yoy_info)) }
        if (years.isEmpty()) {
            item { EmptyOrLoading(state) }
            return@SubScreen
        }
        item {
            var yearA by rememberSaveable { mutableIntStateOf(years[0].year) }
            var yearB by rememberSaveable {
                mutableIntStateOf(years.getOrNull(1)?.year ?: years[0].year)
            }
            var exportSeries by rememberSaveable { mutableStateOf(false) }
            val a = years.firstOrNull { it.year == yearA } ?: years[0]
            val b = years.firstOrNull { it.year == yearB } ?: years.last()
            fun value(r: AggregateRow?) =
                r?.let { if (exportSeries) it.oddaniePrzed else it.poborPrzed }
            SectionCard {
                YearChips(stringResource(R.string.yoy_year_a), years.map { it.year }, yearA) {
                    yearA = it
                }
                YearChips(stringResource(R.string.yoy_year_b), years.map { it.year }, yearB) {
                    yearB = it
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { i, exp ->
                        SegmentedButton(
                            selected = exportSeries == exp,
                            onClick = { exportSeries = exp },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) {
                            Text(
                                stringResource(
                                    if (exp) R.string.legend_export else R.string.legend_consumption
                                )
                            )
                        }
                    }
                }
                val monthNames = (1..12).map { java.time.Month.of(it).shortName() }
                PairedBarChart(
                    keys = monthNames,
                    a = a.months.map(::value),
                    b = b.months.map(::value),
                    labelA = a.year.toString(),
                    labelB = b.year.toString(),
                    format = ::kwh,
                )
                val w = listOf(0.8f, 1f, 1f, 0.8f)
                TableRow(
                    listOf(
                        stringResource(R.string.month),
                        a.year.toString(),
                        b.year.toString(),
                        stringResource(R.string.yoy_change),
                    ),
                    header = true,
                    weights = w,
                )
                (0 until 12).forEach { m ->
                    val va = value(a.months[m])
                    val vb = value(b.months[m])
                    if (va == null && vb == null) return@forEach
                    TableRow(
                        listOf(
                            monthNames[m],
                            va?.let(::kwh) ?: "—",
                            vb?.let(::kwh) ?: "—",
                            change(va, vb),
                        ),
                        weights = w,
                    )
                }
                val ta = if (exportSeries) a.oddaniePrzed else a.poborPrzed
                val tb = if (exportSeries) b.oddaniePrzed else b.poborPrzed
                TableRow(listOf("Σ", kwh(ta), kwh(tb), change(ta, tb)), header = true, weights = w)
                MutedText(stringResource(R.string.yoy_partial))
            }
        }
    }
}

/** Relative change of [a] vs [b] (b is the reference). */
private fun change(a: Double?, b: Double?): String =
    if (a == null || b == null || b == 0.0) "—"
    else (if (a >= b) "+" else "") + num((a - b) / b * 100, 0) + "%"

private fun java.time.Month.shortName(): String =
    getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, java.util.Locale.getDefault())

@Composable
private fun YearChips(label: String, years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        MutedText(label)
        years.forEach { y ->
            FilterChip(
                selected = y == selected,
                onClick = { onSelect(y) },
                label = { Text(y.toString()) },
            )
        }
    }
}

// ---- Daily profile & heatmap ------------------------------------------------------------------

@Composable
fun ProfileScreen(state: UiState, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_profile), onBack) {
        item { MutedText(stringResource(R.string.profile_info)) }
        if (a == null) {
            item { EmptyOrLoading(state) }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        item {
            var weekends by rememberSaveable { mutableStateOf(false) }
            SectionCard(title = stringResource(R.string.profile_day)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { i, w ->
                        SegmentedButton(
                            selected = weekends == w,
                            onClick = { weekends = w },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) {
                            Text(
                                stringResource(
                                    if (w) R.string.profile_weekends else R.string.profile_workdays
                                )
                            )
                        }
                    }
                }
                val hours = if (weekends) a.dayProfile.weekends else a.dayProfile.workdays
                ImportExportChart(
                    remember(hours) {
                        hours.map {
                            AggregateRow("%02d:00".format(it.hour), it.pobor, it.oddanie, 0.0, 0.0)
                        }
                    }
                )
                MutedText(stringResource(R.string.profile_unit))
            }
        }
        item {
            SectionCard(title = stringResource(R.string.profile_heatmap)) {
                HeatmapChart(
                    rowLabels = a.heatmap.months.map { it.toString() },
                    values = a.heatmap.values,
                    maxAbs = a.heatmap.maxAbs,
                    format = { kwh(it) },
                )
            }
        }
    }
}

// ---- Self-consumption -------------------------------------------------------------------------

@Composable
fun SelfUseScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_selfuse), onBack) {
        item { MutedText(stringResource(R.string.selfuse_info)) }
        if (a == null) {
            item { EmptyOrLoading(state) }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        item {
            SectionCard(title = stringResource(R.string.pv_installation)) {
                PvProductionField(state, viewModel)
            }
        }
        val annual = state.reportPrefs.pvAnnualKwh
        val months = a.selfUseUnit.map { it.copy(production = it.production * annual) }
        val balanced = months.sumOf { it.balanced }
        item {
            SectionCard(title = stringResource(R.string.selfuse_balancing)) {
                ValueRow(stringResource(R.string.selfuse_balanced), kwh(balanced))
                MutedText(stringResource(R.string.selfuse_balanced_hint))
            }
        }
        if (annual <= 0) return@SubScreen
        val prod = months.sumOf { it.production }
        val self = months.sumOf { it.selfUse }
        val pobor = months.sumOf { it.pobor }
        item {
            SectionCard(title = stringResource(R.string.selfuse_estimate)) {
                ValueRow(stringResource(R.string.selfuse_production), kwh(prod))
                ValueRow(stringResource(R.string.selfuse_self), kwh(self))
                ValueRow(stringResource(R.string.selfuse_export), kwh(months.sumOf { it.oddanie }))
                ValueRow(
                    stringResource(R.string.selfuse_share),
                    if (prod > 0) pct(self / prod) else "—",
                    emphasized = true,
                )
                ValueRow(
                    stringResource(R.string.selfuse_coverage),
                    if (self + pobor > 0) pct(self / (self + pobor)) else "—",
                    emphasized = true,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val w = listOf(1f, 1f, 1f, 0.8f, 0.8f)
                TableRow(
                    listOf(
                        stringResource(R.string.month),
                        stringResource(R.string.selfuse_production_short),
                        stringResource(R.string.selfuse_self_short),
                        stringResource(R.string.selfuse_share_short),
                        stringResource(R.string.selfuse_coverage_short),
                    ),
                    header = true,
                    weights = w,
                )
                months.forEach { m ->
                    TableRow(
                        listOf(
                            m.month.toString(),
                            num(m.production, 0),
                            num(m.selfUse, 0),
                            pct(m.selfUseShare),
                            pct(m.coverage),
                        ),
                        weights = w,
                    )
                }
                MutedText(stringResource(R.string.selfuse_estimate_hint))
            }
        }
    }
}

/** Annual PV production field (shared by Settings and the self-consumption screen). */
@Composable
fun PvProductionField(state: UiState, viewModel: EanalizerViewModel) {
    DecimalField(
        label = stringResource(R.string.pv_annual),
        value = state.reportPrefs.pvAnnualKwh,
        modifier = Modifier.fillMaxWidth(),
        valid = { it >= 0 },
    ) { v ->
        viewModel.updateReportPrefs { it.copy(pvAnnualKwh = v) }
    }
    MutedText(stringResource(R.string.pv_annual_hint))
}

// ---- Dynamic tariff (card on the comparison screen) -------------------------------------------

@Composable
fun DynamicTariffCard(state: UiState, viewModel: EanalizerViewModel) {
    val a = state.analysis ?: return
    val d = state.dynamic
    val margin = state.reportPrefs.dynamicMargin
    val current = d.forAnalysis === a && d.margin == margin
    SectionCard(title = stringResource(R.string.dynamic_title)) {
        MutedText(stringResource(R.string.dynamic_info, a.inputs.tariff))
        DecimalField(
            label = stringResource(R.string.dynamic_margin),
            value = margin,
            modifier = Modifier.fillMaxWidth(),
            valid = { it >= 0 && it < 5 },
        ) { v ->
            viewModel.updateReportPrefs { it.copy(dynamicMargin = v) }
        }
        val r = d.result
        when {
            d.loading -> {
                MutedText(stringResource(R.string.rce_progress, d.done, d.total))
                LinearProgressIndicator(
                    progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            r != null && current -> {
                ValueRow(stringResource(R.string.total_cost), zl(r.totalCost), emphasized = true)
                val cheapest = a.comparison.firstOrNull()
                if (cheapest != null)
                    MutedText(
                        stringResource(
                            R.string.dynamic_vs,
                            cheapest.tariff,
                            (if (r.totalCost >= cheapest.totalCost) "+" else "") +
                                zl(r.totalCost - cheapest.totalCost),
                        )
                    )
                val nb = r.netBilling
                if (nb != null) {
                    ValueRow(stringResource(R.string.nb_energy_cost), zl(nb.kosztEnergii))
                    ValueRow(stringResource(R.string.nb_covered), zl(nb.pokryteDepozytem))
                    ValueRow(stringResource(R.string.nb_distribution), zl(nb.kosztDystrybucji))
                } else {
                    ValueRow(stringResource(R.string.energy_cost), zl(r.energyCost))
                    ValueRow(stringResource(R.string.nb_distribution), zl(r.distCost))
                }
                ValueRow(stringResource(R.string.fixed_fees, a.result.months), zl(r.fixedFees))
                if (r.missingHours > 0 || d.failedDays > 0)
                    Text(
                        stringResource(R.string.dynamic_missing, r.missingHours),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                MutedText(stringResource(R.string.dynamic_estimate))
            }
            r != null ->
                OutlinedButton(onClick = { viewModel.loadDynamic() }) {
                    Text(stringResource(R.string.recalculate))
                }
            else ->
                Button(onClick = { viewModel.loadDynamic() }) {
                    Text(stringResource(R.string.dynamic_load))
                }
        }
    }
}
