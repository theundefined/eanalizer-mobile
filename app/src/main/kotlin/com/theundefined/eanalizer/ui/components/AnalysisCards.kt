package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.local.AnalysisPrefs
import com.theundefined.eanalizer.data.local.SettlementMode
import com.theundefined.eanalizer.domain.NetBillingResult
import com.theundefined.eanalizer.domain.NetBillingValuation
import com.theundefined.eanalizer.domain.Period
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.label
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.parseDecimal
import com.theundefined.eanalizer.ui.zl

@Composable
fun ParamsCard(state: UiState, onChange: ((AnalysisPrefs) -> AnalysisPrefs) -> Unit) {
    val prefs = state.prefs
    SectionCard(title = stringResource(R.string.params)) {
        MutedText(stringResource(R.string.period))
        ChipRow(Period.entries, prefs.period, { stringResource(it.label) }) { p ->
            onChange { it.copy(period = p) }
        }
        MutedText(stringResource(R.string.tariff))
        val selectedTariff =
            state.tariffs.tariffNames.firstOrNull { it.equals(prefs.tariff, ignoreCase = true) }
                ?: state.tariffs.tariffNames.firstOrNull()
        ChipRow(state.tariffs.tariffNames, selectedTariff, { it }) { t ->
            onChange { it.copy(tariff = t) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField(
                label = stringResource(R.string.storage_capacity),
                value = prefs.capacity,
                modifier = Modifier.weight(1f),
                valid = { it >= 0 },
            ) { v ->
                onChange { it.copy(capacity = v) }
            }
            DecimalField(
                label = stringResource(R.string.storage_efficiency),
                value = Math.round(prefs.efficiency * 1000) / 10.0,
                modifier = Modifier.weight(1f),
                valid = { it > 0 && it <= 100 },
            ) { v ->
                onChange { it.copy(efficiency = v / 100) }
            }
        }
        MutedText(stringResource(R.string.settlement))
        Segmented(
            SettlementMode.entries,
            prefs.mode,
            {
                stringResource(
                    when (it) {
                        SettlementMode.NONE -> R.string.settlement_none
                        SettlementMode.NET_METERING -> R.string.settlement_net_metering
                        SettlementMode.NET_BILLING -> R.string.settlement_net_billing
                    }
                )
            },
        ) { m ->
            onChange { it.copy(mode = m) }
        }
        when (prefs.mode) {
            SettlementMode.NET_METERING -> {
                MutedText(stringResource(R.string.net_metering_ratio))
                Segmented(listOf(0.7, 0.8), prefs.netMeteringRatio, { num(it, 1) }) { r ->
                    onChange { it.copy(netMeteringRatio = r) }
                }
            }
            SettlementMode.NET_BILLING -> {
                MutedText(stringResource(R.string.valuation))
                Segmented(
                    NetBillingValuation.entries,
                    prefs.valuation,
                    {
                        stringResource(
                            if (it == NetBillingValuation.RCEM) R.string.valuation_rcem
                            else R.string.valuation_rce
                        )
                    },
                ) { v ->
                    onChange { it.copy(valuation = v) }
                }
            }
            SettlementMode.NONE -> {}
        }
    }
}

@Composable
fun SummaryCard(state: UiState) {
    val a = state.analysis
    if (a == null) {
        SectionCard {
            if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
            else MutedText(stringResource(R.string.no_data_in_period))
        }
        return
    }
    val r = a.result
    SectionCard(title = stringResource(R.string.summary, r.tariff)) {
        if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
        MutedText(
            stringResource(
                R.string.analysis_period,
                a.from.toString(),
                a.to.toString(),
                a.recordCount
            )
        )
        val nb = a.netBilling
        ValueRow(
            stringResource(R.string.total_cost),
            zl(nb?.calkowityKoszt ?: r.totalCost),
            emphasized = true,
        )
        if (nb != null) NetBillingRows(nb, a.missingRcem.map { it.toString() }, a.pricesUnavailable)
        else {
            ValueRow(stringResource(R.string.energy_cost), zl(r.energyCost))
            ValueRow(stringResource(R.string.fixed_fees, r.months), zl(r.fixedFees))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        TableRow(
            listOf(
                stringResource(R.string.zone),
                stringResource(R.string.zone_price),
                stringResource(R.string.from_grid),
                stringResource(R.string.to_grid),
                stringResource(R.string.zone_cost),
            ),
            header = true,
            weights = listOf(1.4f, 0.8f, 1f, 1f, 1f),
        )
        r.zones.forEach { z ->
            TableRow(
                listOf(
                    z.zone,
                    num(z.price, 3),
                    kwh(z.poborZSieci),
                    kwh(z.oddanieDoSieci),
                    zl(z.kosztPoboru),
                ),
                weights = listOf(1.4f, 0.8f, 1f, 1f, 1f),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ValueRow(stringResource(R.string.consumption_before), kwh(r.totalPoborPrzed))
        ValueRow(stringResource(R.string.export_before), kwh(r.totalOddaniePrzed))
        if (state.prefs.capacity > 0) {
            ValueRow(stringResource(R.string.storage_savings), kwh(r.savings))
        }
        r.unusedCredit?.let { ValueRow(stringResource(R.string.unused_credit), kwh(it)) }
        ValueRow(stringResource(R.string.optimal_capacity), kwh(a.optimalCapacity))
        ValueRow(
            stringResource(R.string.surplus_days),
            "${a.trends.surplusDays} / ${a.trends.totalDays} (${num(a.trends.percent, 0)}%)",
        )
        if (a.missingHours.isNotEmpty()) {
            ValueRow(stringResource(R.string.missing_hours), a.missingHours.size.toString())
        }
    }
}

@Composable
fun NetBillingRows(nb: NetBillingResult, missingRcem: List<String>, pricesUnavailable: Boolean) {
    ValueRow(stringResource(R.string.nb_energy_cost), zl(nb.kosztEnergii))
    ValueRow(stringResource(R.string.nb_deposit_value), zl(nb.wartoscDepozytu))
    ValueRow(stringResource(R.string.nb_covered), zl(nb.pokryteDepozytem))
    ValueRow(stringResource(R.string.nb_energy_to_pay), zl(nb.energiaDoZaplaty))
    ValueRow(stringResource(R.string.nb_distribution), zl(nb.kosztDystrybucji))
    ValueRow(stringResource(R.string.fixed_fees, nb.months.size), zl(nb.oplatyStale))
    if (nb.zwrotNadplaty > 0)
        ValueRow(stringResource(R.string.nb_refund), "-" + zl(nb.zwrotNadplaty))
    if (nb.przepadlyDepozyt > 0)
        ValueRow(stringResource(R.string.nb_expired), zl(nb.przepadlyDepozyt))
    ValueRow(stringResource(R.string.nb_deposit_left), zl(nb.depozytPozostaly))
    val warn = MaterialTheme.colorScheme.error
    if (pricesUnavailable) {
        Text(
            stringResource(R.string.nb_prices_unavailable),
            color = warn,
            style = MaterialTheme.typography.bodySmall
        )
    } else if (missingRcem.isNotEmpty()) {
        Text(
            stringResource(R.string.nb_missing_rcem, missingRcem.joinToString(", ")),
            color = warn,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (nb.missingRceHours > 0) {
        Text(
            stringResource(R.string.nb_missing_rce, nb.missingRceHours),
            color = warn,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun <T> ChipRow(
    items: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
            )
        }
    }
}

@Composable
private fun <T> Segmented(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
            ) {
                Text(label(item), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Decimal input that reports only valid values; shows an error for invalid ones. */
@Composable
fun DecimalField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    valid: (Double) -> Boolean = { true },
    onValue: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatInput(value)) }
    LaunchedEffect(value) { if (parseDecimal(text) != value) text = formatInput(value) }
    val parsed = parseDecimal(text)
    val ok = parsed != null && valid(parsed)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val v = parseDecimal(it)
            if (v != null && valid(v)) onValue(v)
        },
        label = { Text(label, maxLines = 1) },
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun formatInput(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
    else java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
