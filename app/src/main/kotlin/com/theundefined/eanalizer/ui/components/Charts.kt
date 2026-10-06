package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.ui.kwh

/** Fixed categorical pair (validated for CVD separation), stepped per light/dark surface. */
@Composable
internal fun seriesColors(): Pair<Color, Color> =
    if (isSystemInDarkTheme()) Color(0xFF3987E5) to Color(0xFFD95926)
    else Color(0xFF2A78D6) to Color(0xFFEB6834)

/**
 * Mirrored bar chart: consumption (before balancing) above the zero line, export below. Tapping a
 * bar shows its values above the chart; first/last keys are printed under it.
 */
@Composable
fun ImportExportChart(rows: List<AggregateRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val (importColor, exportColor) = seriesColors()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(rows) { mutableStateOf<Int?>(null) }
    val maxUp = rows.maxOf { it.poborPrzed }.coerceAtLeast(0.001)
    val maxDown = rows.maxOf { it.oddaniePrzed }
    val total = maxUp + maxDown

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(importColor, stringResource(R.string.legend_consumption))
            LegendItem(exportColor, stringResource(R.string.legend_export))
        }
        val sel = selected?.let { rows.getOrNull(it) }
        MutedText(
            if (sel == null) stringResource(R.string.chart_hint)
            else
                stringResource(
                    R.string.chart_value,
                    sel.key,
                    kwh(sel.poborPrzed),
                    kwh(sel.oddaniePrzed),
                )
        )
        Canvas(
            modifier =
                Modifier.fillMaxWidth().height(180.dp).pointerInput(rows) {
                    detectTapGestures { pos ->
                        val i = (pos.x / size.width * rows.size).toInt().coerceIn(0, rows.lastIndex)
                        selected = if (selected == i) null else i
                    }
                }
        ) {
            val zeroY = size.height * (maxUp / total).toFloat()
            val slot = size.width / rows.size
            val gap = if (slot > 6.dp.toPx()) 2.dp.toPx() else 0f
            val barW = (slot - gap).coerceAtLeast(1f)
            val radius = minOf(4.dp.toPx(), barW / 2)
            rows.forEachIndexed { i, r ->
                val x = i * slot + gap / 2
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                val up = (r.poborPrzed / total * size.height).toFloat()
                if (up > 0f) {
                    drawPath(
                        roundedBar(x, zeroY - up, barW, up, radius, roundTop = true),
                        importColor.copy(alpha = alpha),
                    )
                }
                val down = (r.oddaniePrzed / total * size.height).toFloat()
                if (down > 0f) {
                    drawPath(
                        roundedBar(x, zeroY, barW, down, radius, roundTop = false),
                        exportColor.copy(alpha = alpha),
                    )
                }
            }
            drawLine(gridColor, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MutedText(rows.first().key)
            Box(Modifier.weight(1f))
            if (rows.size > 1) MutedText(rows.last().key)
        }
    }
}

/** Bar rounded only at its data end (top for upward bars, bottom for downward ones). */
private fun roundedBar(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
    roundTop: Boolean,
): Path {
    val rr = CornerRadius(minOf(r, h), minOf(r, h))
    val zero = CornerRadius.Zero
    return Path().apply {
        addRoundRect(
            RoundRect(
                left = x,
                top = y,
                right = x + w,
                bottom = y + h,
                topLeftCornerRadius = if (roundTop) rr else zero,
                topRightCornerRadius = if (roundTop) rr else zero,
                bottomLeftCornerRadius = if (roundTop) zero else rr,
                bottomRightCornerRadius = if (roundTop) zero else rr,
            )
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        MutedText(label)
    }
}

/**
 * Two series side by side per key (e.g. the same month of two years). Tapping a group shows its
 * values; [format] renders a value.
 */
@Composable
fun PairedBarChart(
    keys: List<String>,
    a: List<Double?>,
    b: List<Double?>,
    labelA: String,
    labelB: String,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    if (keys.isEmpty()) return
    val colorA = MaterialTheme.colorScheme.primary
    val colorB = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(keys, a, b) { mutableStateOf<Int?>(null) }
    val max = (a + b).filterNotNull().maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(colorA, labelA)
            LegendItem(colorB, labelB)
        }
        val sel = selected
        MutedText(
            if (sel == null) stringResource(R.string.chart_hint)
            else
                "${keys[sel]}: $labelA ${a[sel]?.let(format) ?: "—"}, " +
                    "$labelB ${b[sel]?.let(format) ?: "—"}"
        )
        Canvas(
            modifier =
                Modifier.fillMaxWidth().height(160.dp).pointerInput(keys) {
                    detectTapGestures { pos ->
                        val i = (pos.x / size.width * keys.size).toInt().coerceIn(0, keys.lastIndex)
                        selected = if (selected == i) null else i
                    }
                }
        ) {
            val slot = size.width / keys.size
            val gap = 3.dp.toPx()
            val barW = ((slot - gap) / 2).coerceAtLeast(1f)
            val radius = minOf(3.dp.toPx(), barW / 2)
            keys.indices.forEach { i ->
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                listOf(a[i] to colorA, b[i] to colorB).forEachIndexed { j, (v, c) ->
                    if (v == null || v <= 0) return@forEachIndexed
                    val h = (v / max * size.height).toFloat()
                    val x = i * slot + gap / 2 + j * barW
                    drawPath(
                        roundedBar(x, size.height - h, barW, h, radius, roundTop = true),
                        c.copy(alpha = alpha),
                    )
                }
            }
            drawLine(
                gridColor,
                Offset(0f, size.height),
                Offset(size.width, size.height),
                1.dp.toPx(),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MutedText(keys.first())
            Box(Modifier.weight(1f))
            if (keys.size > 1) MutedText(keys.last())
        }
    }
}

/**
 * Rows of 24 hourly cells: import (positive) in the consumption colour, export (negative) in the
 * export colour, intensity by magnitude. Tapping a cell shows its value.
 */
@Composable
fun HeatmapChart(
    rowLabels: List<String>,
    values: List<DoubleArray>,
    maxAbs: Double,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    val (importColor, exportColor) = seriesColors()
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.onSurface
    var selected by remember(values) { mutableStateOf<Pair<Int, Int>?>(null) }
    val scale = maxAbs.coerceAtLeast(0.001)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(importColor, stringResource(R.string.heatmap_import))
            LegendItem(exportColor, stringResource(R.string.heatmap_export))
        }
        val sel = selected
        MutedText(
            if (sel == null) stringResource(R.string.heatmap_hint)
            else
                stringResource(
                    R.string.heatmap_value,
                    rowLabels[sel.first],
                    sel.second,
                    format(values[sel.first][sel.second]),
                )
        )
        values.forEachIndexed { r, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText(rowLabels[r], modifier = Modifier.width(64.dp))
                Canvas(
                    modifier =
                        Modifier.weight(1f).height(16.dp).pointerInput(row) {
                            detectTapGestures { pos ->
                                val h = (pos.x / size.width * 24).toInt().coerceIn(0, 23)
                                selected = if (selected == r to h) null else r to h
                            }
                        }
                ) {
                    val cell = size.width / 24
                    row.forEachIndexed { h, v ->
                        val color =
                            when {
                                v.isNaN() -> empty
                                v >= 0 ->
                                    importColor.copy(
                                        alpha = (0.12 + 0.88 * minOf(1.0, v / scale)).toFloat()
                                    )
                                else ->
                                    exportColor.copy(
                                        alpha = (0.12 + 0.88 * minOf(1.0, -v / scale)).toFloat()
                                    )
                            }
                        val highlight = selected == r to h
                        drawRect(
                            color,
                            topLeft = Offset(h * cell + 0.5f, 0.5f),
                            size = Size(cell - 1f, size.height - 1f),
                        )
                        if (highlight)
                            drawRect(
                                outline,
                                topLeft = Offset(h * cell, 0f),
                                size = Size(cell, size.height),
                                style = Stroke(2.dp.toPx()),
                            )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(64.dp))
            listOf(0, 6, 12, 18).forEach { h -> MutedText("$h:00", modifier = Modifier.weight(1f)) }
        }
    }
}
