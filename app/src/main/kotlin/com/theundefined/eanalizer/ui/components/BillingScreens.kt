package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.local.SettlementMode
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.zl
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Amount due since the last settlement date (main screen); asks to set the date when missing. */
@Composable
fun BillingCard(state: UiState, onSetDate: () -> Unit) {
    val date = state.prefs.billingDate
    SectionCard(title = stringResource(R.string.billing_title)) {
        if (date == null) {
            MutedText(stringResource(R.string.billing_unset))
            TextButton(onClick = onSetDate) { Text(stringResource(R.string.billing_set_date)) }
            return@SectionCard
        }
        val a = state.analysis
        val b = a?.sinceBilling
        if (b == null) {
            if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
            else MutedText(stringResource(R.string.billing_no_data, date))
            return@SectionCard
        }
        MutedText(
            stringResource(
                R.string.billing_range,
                b.from.toString(),
                b.to.toString(),
                b.days,
                b.result.tariff,
            )
        )
        if (b.from.toString() != date)
            MutedText(stringResource(R.string.billing_data_from, date, b.from.toString()))
        ValueRow(stringResource(R.string.from_grid), kwh(b.result.zones.sumOf { it.poborZSieci }))
        ValueRow(stringResource(R.string.to_grid), kwh(b.result.zones.sumOf { it.oddanieDoSieci }))
        val nb = b.netBilling
        if (nb != null) {
            ValueRow(stringResource(R.string.nb_energy_cost), zl(nb.kosztEnergii))
            ValueRow(stringResource(R.string.nb_covered), "-" + zl(nb.pokryteDepozytem))
            ValueRow(stringResource(R.string.nb_distribution), zl(nb.kosztDystrybucji))
        } else {
            ValueRow(stringResource(R.string.billing_usage_cost), zl(b.usageCost))
        }
        ValueRow(stringResource(R.string.billing_fixed_fees), zl(b.fixedFees))
        if (nb != null && nb.zwrotNadplaty > 0)
            ValueRow(stringResource(R.string.nb_refund), "-" + zl(nb.zwrotNadplaty))
        b.result.unusedCredit?.let { ValueRow(stringResource(R.string.unused_credit), kwh(it)) }
        ValueRow(stringResource(R.string.billing_total), zl(b.total), emphasized = true)
        val paid = state.reportPrefs.billingPaid
        if (paid > 0) {
            ValueRow(stringResource(R.string.billing_paid), "-" + zl(paid))
            val due = b.total - paid
            ValueRow(
                stringResource(if (due >= 0) R.string.billing_due else R.string.billing_overpaid),
                zl(kotlin.math.abs(due)),
                emphasized = true,
            )
        }
        if (nb != null) ValueRow(stringResource(R.string.nb_deposit_left), zl(nb.depozytPozostaly))
        if (nb != null && a.pricesUnavailable)
            Text(
                stringResource(R.string.nb_prices_unavailable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        MutedText(stringResource(R.string.billing_hint))
    }
}

/** Settlement date and payments since then (Settings). */
@Composable
fun BillingSettings(state: UiState, viewModel: EanalizerViewModel) {
    var pick by remember { mutableStateOf(false) }
    val date = state.prefs.billingDate
    ValueRow(stringResource(R.string.billing_date), date ?: "—")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pick = true }) {
            Text(stringResource(R.string.billing_set_date))
        }
        if (date != null)
            TextButton(onClick = { viewModel.updatePrefs { it.copy(billingDate = null) } }) {
                Text(stringResource(R.string.billing_clear))
            }
    }
    DecimalField(
        label = stringResource(R.string.billing_paid_field),
        value = state.reportPrefs.billingPaid,
        modifier = Modifier.fillMaxWidth(),
        valid = { it >= 0 },
    ) { v ->
        viewModel.updateReportPrefs { it.copy(billingPaid = v) }
    }
    MutedText(stringResource(R.string.billing_settings_hint))
    if (pick) {
        DateDialog(
            initial = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            max = state.dataEnd ?: LocalDate.now(),
            onDismiss = { pick = false },
        ) { d ->
            pick = false
            viewModel.updatePrefs { it.copy(billingDate = d.toString()) }
        }
    }
}

/** Single date picker up to [max] (dates as UTC midnight millis). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    initial: LocalDate?,
    max: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial?.millis(),
            initialDisplayedMonthMillis = (initial ?: max).millis(),
            yearRange = 2015..max.year,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.date() <= max
                },
        )
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (selected != null) onConfirm(selected.date()) },
                enabled = selected != null,
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    stringResource(R.string.billing_date),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/** Net-billing deposits at the end of the data and what happens to them. */
@Composable
fun DepositScreen(state: UiState, onBack: () -> Unit) {
    val a = state.analysis
    SubScreen(stringResource(R.string.screen_deposit), onBack) {
        item { MutedText(stringResource(R.string.deposit_info)) }
        if (state.prefs.mode != SettlementMode.NET_BILLING) {
            item { SectionCard { MutedText(stringResource(R.string.deposit_not_net_billing)) } }
            return@SubScreen
        }
        val nb = a?.depositsNow
        if (a == null || nb == null) {
            item {
                SectionCard {
                    if (state.analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else MutedText(stringResource(R.string.no_data_in_period))
                }
            }
            return@SubScreen
        }
        item { MutedText(stringResource(R.string.deposit_state_at, state.dataEnd.toString())) }
        item {
            SectionCard(title = stringResource(R.string.deposit_now)) {
                ValueRow(stringResource(R.string.nb_deposit_left), zl(nb.depozytPozostaly), true)
                if (nb.deposits.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val w = listOf(1.2f, 1.2f, 1f, 1f)
                    TableRow(
                        listOf(
                            stringResource(R.string.deposit_from),
                            stringResource(R.string.deposit_valid_to),
                            stringResource(R.string.deposit_left),
                            stringResource(R.string.deposit_max_refund),
                        ),
                        header = true,
                        weights = w,
                    )
                    nb.deposits.forEach { d ->
                        TableRow(
                            listOf(
                                d.from.minusMonths(1).toString(),
                                d.to.toString(),
                                zl(d.left),
                                zl(d.maxRefund),
                            ),
                            weights = w,
                        )
                    }
                    MutedText(stringResource(R.string.deposit_table_hint))
                }
                if (nb.missingRcemMonths.isNotEmpty())
                    Text(
                        stringResource(
                            R.string.nb_missing_rcem,
                            nb.missingRcemMonths.joinToString(", "),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        val f = a.depositForecast
        if (f.isEmpty()) return@SubScreen
        item {
            SectionCard(title = stringResource(R.string.deposit_forecast)) {
                ValueRow(stringResource(R.string.deposit_forecast_used), zl(f.sumOf { it.covered }))
                ValueRow(stringResource(R.string.nb_refund), zl(f.sumOf { it.refund }), true)
                ValueRow(stringResource(R.string.nb_expired), zl(f.sumOf { it.lost }), true)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val w = listOf(1.1f, 1f, 1f, 1f, 1f)
                TableRow(
                    listOf(
                        stringResource(R.string.month),
                        stringResource(R.string.deposit_expected),
                        stringResource(R.string.deposit_expiring),
                        stringResource(R.string.deposit_refund_short),
                        stringResource(R.string.deposit_balance),
                    ),
                    header = true,
                    weights = w,
                )
                f.forEach { m ->
                    TableRow(
                        listOf(
                            m.month.toString(),
                            zl(m.expectedCost),
                            zl(m.expiring),
                            zl(m.refund),
                            zl(m.balance),
                        ),
                        weights = w,
                    )
                }
                MutedText(stringResource(R.string.deposit_forecast_hint))
            }
        }
    }
}
