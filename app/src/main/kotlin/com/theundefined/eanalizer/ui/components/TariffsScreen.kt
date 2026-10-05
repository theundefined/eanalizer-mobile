package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.DayType
import com.theundefined.eanalizer.domain.TariffTable
import com.theundefined.eanalizer.domain.TariffZone
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.UiState
import com.theundefined.eanalizer.ui.num
import com.theundefined.eanalizer.ui.parseDecimal

@Composable
fun TariffsScreen(state: UiState, viewModel: EanalizerViewModel, onBack: () -> Unit) {
    val zones = state.tariffs.zones
    // index into zones, -1 = new zone, null = closed
    var editing by remember { mutableStateOf<Int?>(null) }

    SubScreen(
        stringResource(R.string.screen_tariffs),
        onBack,
        actions = {
            IconButton(onClick = { editing = -1 }) {
                Icon(Icons.Default.Add, stringResource(R.string.add))
            }
            IconButton(onClick = { viewModel.resetTariffs() }) {
                Icon(Icons.Default.RestartAlt, stringResource(R.string.reset_tariffs))
            }
        },
    ) {
        item { MutedText(stringResource(R.string.tariffs_info)) }
        state.tariffs.tariffNames.forEach { name ->
            item(key = name) {
                SectionCard(title = name) {
                    zones
                        .withIndex()
                        .filter { it.value.tariff == name }
                        .forEach { (i, z) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(
                                            R.string.zone_hours,
                                            z.zoneName,
                                            z.startHour,
                                            z.endHour
                                        ) + " · " + stringResource(dayLabel(z.dayType)),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    MutedText(
                                        stringResource(
                                            R.string.zone_prices,
                                            num(z.energyPrice, 4),
                                            num(z.distPrice, 4),
                                            num(z.distFee),
                                        )
                                    )
                                }
                                IconButton(onClick = { editing = i }) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.edit))
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.saveTariffs(
                                            TariffTable(zones.filterIndexed { j, _ -> j != i })
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                                }
                            }
                        }
                }
            }
        }
    }

    editing?.let { idx ->
        ZoneDialog(
            initial = zones.getOrNull(idx),
            onDismiss = { editing = null },
            onSave = { z ->
                val updated = zones.toMutableList()
                if (idx in zones.indices) updated[idx] = z else updated += z
                viewModel.saveTariffs(TariffTable(updated))
                editing = null
            },
        )
    }
}

private fun dayLabel(d: DayType) =
    when (d) {
        DayType.ALL -> R.string.day_all
        DayType.WEEKDAY -> R.string.day_weekday
        DayType.WEEKEND -> R.string.day_weekend
    }

@Composable
private fun ZoneDialog(initial: TariffZone?, onDismiss: () -> Unit, onSave: (TariffZone) -> Unit) {
    var tariff by remember { mutableStateOf(initial?.tariff ?: "") }
    var zone by remember { mutableStateOf(initial?.zoneName ?: "") }
    var dayType by remember { mutableStateOf(initial?.dayType ?: DayType.ALL) }
    var start by remember { mutableStateOf(initial?.startHour?.toString() ?: "0") }
    var end by remember { mutableStateOf(initial?.endHour?.toString() ?: "24") }
    var energy by remember { mutableStateOf(initial?.energyPrice?.toString() ?: "") }
    var dist by remember { mutableStateOf(initial?.distPrice?.toString() ?: "") }
    var fee by remember { mutableStateOf(initial?.distFee?.toString() ?: "") }

    val startH = start.trim().toIntOrNull()?.takeIf { it in 0..24 }
    val endH = end.trim().toIntOrNull()?.takeIf { it in 0..24 }
    val e = parseDecimal(energy)?.takeIf { it >= 0 }
    val d = parseDecimal(dist)?.takeIf { it >= 0 }
    val f = parseDecimal(fee)?.takeIf { it >= 0 }
    val valid =
        tariff.isNotBlank() &&
            zone.isNotBlank() &&
            !tariff.contains(',') &&
            !zone.contains(',') &&
            startH != null &&
            endH != null &&
            startH != endH &&
            e != null &&
            d != null &&
            f != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add else R.string.edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Field(stringResource(R.string.tariff_name), tariff, tariff.isNotBlank()) {
                    tariff = it
                }
                Field(stringResource(R.string.zone_name), zone, zone.isNotBlank()) { zone = it }
                MutedText(stringResource(R.string.day_type))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayType.entries.forEach { t ->
                        FilterChip(
                            selected = dayType == t,
                            onClick = { dayType = t },
                            label = { Text(stringResource(dayLabel(t)), maxLines = 1) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        stringResource(R.string.hour_from),
                        start,
                        startH != null,
                        Modifier.weight(1f),
                        true
                    ) {
                        start = it
                    }
                    Field(
                        stringResource(R.string.hour_to),
                        end,
                        endH != null,
                        Modifier.weight(1f),
                        true
                    ) {
                        end = it
                    }
                }
                Field(stringResource(R.string.energy_price), energy, e != null, numeric = true) {
                    energy = it
                }
                Field(stringResource(R.string.dist_price), dist, d != null, numeric = true) {
                    dist = it
                }
                Field(stringResource(R.string.dist_fee), fee, f != null, numeric = true) {
                    fee = it
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        TariffZone(
                            tariff.trim(),
                            zone.trim(),
                            dayType,
                            startH!!,
                            endH!!,
                            e!!,
                            d!!,
                            f!!
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    ok: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        isError = !ok,
        singleLine = true,
        keyboardOptions =
            if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal)
            else KeyboardOptions.Default,
        modifier = modifier,
    )
}
