package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.LoadAnalysis
import com.theundefined.eanalizer.domain.Peaks
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.zl

private fun watts(kw: Double) = num(kw * 1000, 0) + " W"

private fun kw(v: Double) = num(v, 2) + " kW"

private fun pct(v: Double) = num(v * 100, 0) + "%"

private fun hourLabel(r: HourlyRecord) =
    "%s %02d:00".format(r.timestamp.toLocalDate(), r.timestamp.hour)

/** Base (standby) load and peak hours of the selected period. */
@Composable
fun PowerScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_power), onBack) {
        if (a == null) {
            item {
                SectionCard {
                    if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else MutedText(stringResource(R.string.no_data_in_period))
                }
            }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        item {
            SectionCard(title = stringResource(R.string.baseload_title)) {
                val b = a.baseLoad
                if (b == null) {
                    MutedText(stringResource(R.string.baseload_no_data))
                    return@SectionCard
                }
                ValueRow(stringResource(R.string.baseload_power), watts(b.kw), emphasized = true)
                ValueRow(stringResource(R.string.baseload_annual), kwh(b.annualKwh))
                ValueRow(
                    stringResource(R.string.baseload_cost, a.inputs.tariff),
                    zl(b.annualCost),
                    emphasized = true,
                )
                ValueRow(stringResource(R.string.baseload_share), pct(b.share))
                if (b.months.size > 1) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val w = listOf(1.2f, 1f, 1f)
                    TableRow(
                        listOf(
                            stringResource(R.string.month),
                            stringResource(R.string.baseload_power_short),
                            stringResource(R.string.baseload_days),
                        ),
                        header = true,
                        weights = w,
                    )
                    b.months.forEach { m ->
                        TableRow(
                            listOf(m.month.toString(), watts(m.kw), m.days.toString()),
                            weights = w,
                        )
                    }
                }
                MutedText(
                    stringResource(
                        R.string.baseload_hint,
                        LoadAnalysis.NIGHT_HOURS.first,
                        LoadAnalysis.NIGHT_HOURS.last + 1,
                    )
                )
            }
        }
        item {
            SectionCard(title = stringResource(R.string.peaks_title)) {
                ContractedPowerField(state, viewModel)
                PeaksContent(a.peaks, a.inputs.records, state.reportPrefs.contractedPowerKw)
            }
        }
    }
}

@Composable
fun ContractedPowerField(state: UiState, viewModel: EanalizerViewModel) {
    DecimalField(
        label = stringResource(R.string.contracted_power),
        value = state.reportPrefs.contractedPowerKw,
        modifier = Modifier.fillMaxWidth(),
        valid = { it >= 0 },
    ) { v ->
        viewModel.updateReportPrefs { it.copy(contractedPowerKw = v) }
    }
}

@Composable
private fun PeaksContent(p: Peaks, records: List<HourlyRecord>, contracted: Double) {
    MutedText(stringResource(R.string.peaks_hint))
    p.top.firstOrNull()?.let {
        ValueRow(stringResource(R.string.peaks_max), kw(it.poborPrzed), emphasized = true)
    }
    if (contracted > 0) {
        val max = p.top.firstOrNull()?.poborPrzed ?: 0.0
        ValueRow(stringResource(R.string.peaks_of_contracted), pct(max / contracted))
        listOf(0.5, 0.8).forEach { s ->
            ValueRow(
                stringResource(R.string.peaks_hours_above, pct(s)),
                LoadAnalysis.hoursAbove(records, contracted, s).toString(),
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.peaks_top))
    p.top.forEach { r -> ValueRow(hourLabel(r), kw(r.poborPrzed)) }
    if (p.monthly.size > 1) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(stringResource(R.string.peaks_monthly))
        val w = listOf(1f, 1.6f, 1f)
        p.monthly.forEach { m ->
            TableRow(
                listOf(m.month.toString(), hourLabel(m.record), kw(m.record.poborPrzed)),
                weights = w,
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(R.string.peaks_histogram))
    val limits = Peaks.PEAK_BUCKETS_KW
    val total = p.histogram.sum().coerceAtLeast(1)
    p.histogram.forEachIndexed { i, n ->
        val label =
            if (i < limits.size) stringResource(R.string.peaks_up_to, num(limits[i], 1))
            else stringResource(R.string.peaks_above, num(limits.last(), 1))
        ValueRow(label, "$n h (${pct(n.toDouble() / total)})")
    }
}

/** What-if: heat pump and electric car added to the meter data. */
@Composable
fun ExtraLoadScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val a = state.analysis
    val rp = state.reportPrefs
    val load = rp.extraLoad()
    LaunchedEffect(a, load) { if (a != null) viewModel.loadExtraLoad() }
    SubScreen(stringResource(R.string.screen_extraload), onBack) {
        item { MutedText(stringResource(R.string.extraload_info)) }
        if (a == null) {
            item {
                SectionCard {
                    if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else MutedText(stringResource(R.string.no_data_in_period))
                }
            }
            return@SubScreen
        }
        item { PeriodInfo(a) }
        item {
            SectionCard(title = stringResource(R.string.extraload_heatpump)) {
                DecimalField(
                    label = stringResource(R.string.extraload_heatpump_kwh),
                    value = rp.heatPumpKwh,
                    modifier = Modifier.fillMaxWidth(),
                    valid = { it >= 0 },
                ) { v ->
                    viewModel.updateReportPrefs { it.copy(heatPumpKwh = v) }
                }
                MutedText(stringResource(R.string.extraload_heatpump_hint))
            }
        }
        item {
            SectionCard(title = stringResource(R.string.extraload_ev)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(
                        label = stringResource(R.string.extraload_ev_km),
                        value = rp.evKmPerDay,
                        modifier = Modifier.weight(1f),
                        valid = { it >= 0 },
                    ) { v ->
                        viewModel.updateReportPrefs { it.copy(evKmPerDay = v) }
                    }
                    DecimalField(
                        label = stringResource(R.string.extraload_ev_consumption),
                        value = rp.evKwhPer100Km,
                        modifier = Modifier.weight(1f),
                        valid = { it > 0 },
                    ) { v ->
                        viewModel.updateReportPrefs { it.copy(evKwhPer100Km = v) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField(
                        label = stringResource(R.string.extraload_ev_power),
                        value = rp.evChargeKw,
                        modifier = Modifier.weight(1f),
                        valid = { it > 0 },
                    ) { v ->
                        viewModel.updateReportPrefs { it.copy(evChargeKw = v) }
                    }
                    DecimalField(
                        label = stringResource(R.string.extraload_ev_start),
                        value = rp.evStartHour.toDouble(),
                        modifier = Modifier.weight(1f),
                        valid = { it in 0.0..23.0 && it == Math.floor(it) },
                    ) { v ->
                        viewModel.updateReportPrefs { it.copy(evStartHour = v.toInt()) }
                    }
                }
                if (load.evDailyKwh > 0)
                    MutedText(stringResource(R.string.extraload_ev_daily, kwh(load.evDailyKwh)))
                MutedText(stringResource(R.string.extraload_ev_hint))
            }
        }
        val s = state.extraLoad
        if (load.isEmpty) return@SubScreen
        if (s.loading || s.forAnalysis !== a || s.load != load) {
            item { SectionCard { LinearProgressIndicator(Modifier.fillMaxWidth()) } }
            return@SubScreen
        }
        val days = java.time.temporal.ChronoUnit.DAYS.between(a.from, a.to).toInt() + 1
        val scale = 365.0 / days
        val current = s.rows.firstOrNull { it.tariff == a.inputs.tariff }
        val cheapestBefore = s.rows.minByOrNull { it.before }
        val cheapestAfter = s.rows.firstOrNull()
        item {
            SectionCard(title = stringResource(R.string.extraload_result)) {
                ValueRow(stringResource(R.string.extraload_added), kwh(s.addedKwh))
                ValueRow(stringResource(R.string.extraload_added_year), kwh(s.addedKwh * scale))
                if (current != null) {
                    val diff = current.after - current.before
                    ValueRow(
                        stringResource(R.string.extraload_extra_cost, current.tariff),
                        zl(diff),
                        emphasized = true,
                    )
                    ValueRow(stringResource(R.string.extraload_extra_cost_year), zl(diff * scale))
                    if (s.addedKwh > 0)
                        ValueRow(
                            stringResource(R.string.extraload_per_kwh),
                            num(diff / s.addedKwh, 2) + " zł/kWh",
                        )
                    if (load.evDailyKwh > 0 && load.heatPumpAnnualKwh <= 0)
                        ValueRow(
                            stringResource(R.string.extraload_per_100km),
                            zl(diff / s.addedKwh * load.evKwhPer100Km),
                        )
                }
                if (
                    cheapestBefore != null &&
                        cheapestAfter != null &&
                        cheapestBefore.tariff != cheapestAfter.tariff
                )
                    MutedText(
                        stringResource(
                            R.string.extraload_tariff_changes,
                            cheapestBefore.tariff,
                            cheapestAfter.tariff,
                        )
                    )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val w = listOf(0.9f, 1f, 1f, 1f)
                TableRow(
                    listOf(
                        stringResource(R.string.tariff),
                        stringResource(R.string.extraload_before),
                        stringResource(R.string.extraload_after),
                        stringResource(R.string.extraload_diff),
                    ),
                    header = true,
                    weights = w,
                )
                s.rows.forEach { r ->
                    TableRow(
                        listOf(
                            (if (r.tariff == a.inputs.tariff) "• " else "") + r.tariff,
                            zl(r.before),
                            zl(r.after),
                            zl(r.after - r.before),
                        ),
                        weights = w,
                    )
                }
                MutedText(stringResource(R.string.extraload_result_hint))
            }
        }
    }
}
