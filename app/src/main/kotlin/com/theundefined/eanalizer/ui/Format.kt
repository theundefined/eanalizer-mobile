package com.theundefined.eanalizer.ui

import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.Period
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun kwh(v: Double): String = String.format(Locale.getDefault(), "%.1f kWh", v)

fun zl(v: Double): String = String.format(Locale.getDefault(), "%.2f zł", v)

fun num(v: Double, decimals: Int = 2): String =
    String.format(Locale.getDefault(), "%.${decimals}f", v)

fun dateTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Parses user input accepting both `,` and `.` as decimal separator. */
fun parseDecimal(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Parses a list of numbers separated by `;` or whitespace; null if any item is invalid. */
fun parseDecimalList(s: String): List<Double>? =
    s.split(';', ' ', '\n', '\t').filter { it.isNotBlank() }.map { parseDecimal(it) ?: return null }

/** Formats [values] for [parseDecimalList] (without trailing zeros). */
fun formatDecimalList(values: List<Double>): String =
    values.joinToString("; ") {
        java.math.BigDecimal(it.toString()).stripTrailingZeros().toPlainString().let { t ->
            if (Locale.getDefault().language == "pl") t.replace('.', ',') else t
        }
    }

val Period.label: Int
    get() =
        when (this) {
            Period.LAST_30_DAYS -> R.string.period_last_30
            Period.LAST_90_DAYS -> R.string.period_last_90
            Period.LAST_365_DAYS -> R.string.period_last_365
            Period.CURRENT_MONTH -> R.string.period_current_month
            Period.PREVIOUS_MONTH -> R.string.period_previous_month
            Period.CURRENT_YEAR -> R.string.period_current_year
            Period.PREVIOUS_YEAR -> R.string.period_previous_year
            Period.ALL -> R.string.period_all
            Period.CUSTOM -> R.string.period_custom
        }
