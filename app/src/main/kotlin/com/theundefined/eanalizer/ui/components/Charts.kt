package com.theundefined.eanalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import com.theundefined.eanalizer.domain.Aggregation
import com.theundefined.eanalizer.domain.ZonePrice
import com.theundefined.eanalizer.domain.ZoneVolume
import com.theundefined.eanalizer.ui.kwh
import com.theundefined.eanalizer.ui.zl

/** Fixed categorical pair (validated for CVD separation), stepped per light/dark surface. */
@Composable
internal fun seriesColors(): Pair<Color, Color> =
    if (isSystemInDarkTheme()) Color(0xFF3987E5) to Color(0xFFD95926)
    else Color(0xFF2A78D6) to Color(0xFFEB6834)

/**
 * Mirrored bar chart: consumption (before balancing) above the zero line, export below. Rows split
 * by tariff zone ([AggregateRow.zones]) are stacked, the cheapest zone at the zero line and darker
 * shades for pricier zones; [tariff] names the tariff in the zone legend. Tapping a bar shows its
 * values above the chart; first/last keys are printed under it.
 */
@Composable
fun ImportExportChart(
    rows: List<AggregateRow>,
    modifier: Modifier = Modifier,
    tariff: String? = null,
    underBars: (@Composable () -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    val (importColor, exportColor) = seriesColors()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(rows) { mutableStateOf<Int?>(null) }
    val maxUp = rows.maxOf { it.poborPrzed }.coerceAtLeast(0.001)
    val maxDown = rows.maxOf { it.oddaniePrzed }
    val total = maxUp + maxDown
    val zoneAlpha = remember(rows) { zoneAlpha(rows) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(importColor, stringResource(R.string.legend_consumption))
            LegendItem(exportColor, stringResource(R.string.legend_export))
        }
        if (zoneAlpha.size > 1)
            ZoneLegend(rows, zoneAlpha, tariff, listOf(importColor, exportColor))
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
        sel?.zones?.forEach { z ->
            MutedText(
                stringResource(
                    R.string.chart_value,
                    zoneLabel(z.zone),
                    kwh(z.poborPrzed),
                    kwh(z.oddaniePrzed),
                )
            )
        }
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
                // Segments from the zero line outwards; only the outermost one is rounded.
                val parts =
                    r.zones.ifEmpty { listOf(ZoneVolume("", 0.0, r.poborPrzed, r.oddaniePrzed)) }
                var upEnd = 0f
                var downEnd = 0f
                val lastUp = parts.indexOfLast { it.poborPrzed > 0 }
                val lastDown = parts.indexOfLast { it.oddaniePrzed > 0 }
                parts.forEachIndexed { j, z ->
                    val shade = alpha * (zoneAlpha[z.zone] ?: 1f)
                    val up = (z.poborPrzed / total * size.height).toFloat()
                    if (up > 0f) {
                        drawPath(
                            roundedBar(
                                x,
                                zeroY - upEnd - up,
                                barW,
                                up,
                                if (j == lastUp) radius else 0f,
                                roundTop = true,
                            ),
                            importColor.copy(alpha = shade),
                        )
                        upEnd += up
                    }
                    val down = (z.oddaniePrzed / total * size.height).toFloat()
                    if (down > 0f) {
                        drawPath(
                            roundedBar(
                                x,
                                zeroY + downEnd,
                                barW,
                                down,
                                if (j == lastDown) radius else 0f,
                                roundTop = false,
                            ),
                            exportColor.copy(alpha = shade),
                        )
                        downEnd += down
                    }
                }
            }
            drawLine(gridColor, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        }
        underBars?.invoke()
        Row(modifier = Modifier.fillMaxWidth()) {
            MutedText(rows.first().key)
            Box(Modifier.weight(1f))
            if (rows.size > 1) MutedText(rows.last().key)
        }
    }
}

/** Alpha per zone of split [rows]: the cheapest zone lightest, the priciest fully opaque. */
private fun zoneAlpha(rows: List<AggregateRow>): Map<String, Float> {
    val zones =
        rows.flatMap { it.zones }.distinctBy { it.zone }.sortedBy { it.price }.map { it.zone }
    return zones
        .mapIndexed { i, z -> z to if (zones.size < 2) 1f else 0.4f + 0.6f * i / (zones.size - 1) }
        .toMap()
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

/** Tariff zones of a split [ImportExportChart]: both series' shades, name and price per kWh. */
@Composable
private fun ZoneLegend(
    rows: List<AggregateRow>,
    zoneAlpha: Map<String, Float>,
    tariff: String?,
    colors: List<Color>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (tariff != null) MutedText(stringResource(R.string.chart_zones, tariff))
        rows
            .flatMap { it.zones }
            .distinctBy { it.zone }
            .sortedBy { it.price }
            .forEach { z ->
                val a = zoneAlpha[z.zone] ?: 1f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        colors.forEach { Swatch(it.copy(alpha = a)) }
                    }
                    MutedText(
                        if (z.zone == Aggregation.UNKNOWN_ZONE) zoneLabel(z.zone)
                        else stringResource(R.string.chart_zone_item, z.zone, zl(z.price))
                    )
                }
            }
    }
}

@Composable
private fun zoneLabel(zone: String): String =
    if (zone == Aggregation.UNKNOWN_ZONE) stringResource(R.string.chart_zone_unknown) else zone

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
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
 * Fixed categorical palette (validated for adjacent CVD separation), stepped per light/dark
 * surface. Slots 1-2 equal [seriesColors].
 */
@Composable
internal fun categoricalColors(): List<Color> =
    if (isSystemInDarkTheme())
        listOf(
            Color(0xFF3987E5),
            Color(0xFFD95926),
            Color(0xFF199E70),
            Color(0xFFC98500),
            Color(0xFFD55181),
            Color(0xFF008300),
            Color(0xFF9085E9),
            Color(0xFFE66767),
        )
    else
        listOf(
            Color(0xFF2A78D6),
            Color(0xFFEB6834),
            Color(0xFF1BAF7A),
            Color(0xFFEDA100),
            Color(0xFFE87BA4),
            Color(0xFF008300),
            Color(0xFF4A3AA7),
            Color(0xFFE34948),
        )

/** One series of [GroupedZoneBarChart]: a row (or null = no data) per key. */
data class BarSeries(val label: String, val color: Color, val rows: List<AggregateRow?>)

/**
 * Several series side by side per key (e.g. the same month of several years), each bar stacked by
 * tariff zone ([AggregateRow.zones], cheapest at the bottom, more opaque = pricier). [export] plots
 * the export instead of the consumption (both before balancing). Tapping a group shows its values
 * per series and zone.
 */
@Composable
fun GroupedZoneBarChart(
    keys: List<String>,
    series: List<BarSeries>,
    export: Boolean,
    modifier: Modifier = Modifier,
    tariff: String? = null,
) {
    if (keys.isEmpty() || series.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(keys, series) { mutableStateOf<Int?>(null) }
    fun total(r: AggregateRow) = if (export) r.oddaniePrzed else r.poborPrzed
    fun part(z: ZoneVolume) = if (export) z.oddaniePrzed else z.poborPrzed
    val allRows = remember(series) { series.flatMap { it.rows.filterNotNull() } }
    val max = (allRows.maxOfOrNull(::total) ?: 0.0).coerceAtLeast(0.001)
    val zoneAlpha = remember(allRows) { zoneAlpha(allRows) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            series.forEach { LegendItem(it.color, it.label) }
        }
        if (zoneAlpha.size > 1) ZoneLegend(allRows, zoneAlpha, tariff, series.map { it.color })
        val sel = selected
        if (sel == null) MutedText(stringResource(R.string.chart_hint))
        else {
            MutedText(keys[sel])
            val unknown = zoneLabel(Aggregation.UNKNOWN_ZONE)
            series.forEach { s ->
                val r = s.rows[sel]
                val zones =
                    r?.zones
                        ?.filter { part(it) > 0 }
                        ?.joinToString("") {
                            val name = if (it.zone == Aggregation.UNKNOWN_ZONE) unknown else it.zone
                            " · $name ${kwh(part(it))}"
                        }
                MutedText("${s.label}: ${r?.let { kwh(total(it)) } ?: "—"}${zones.orEmpty()}")
            }
        }
        Canvas(
            modifier =
                Modifier.fillMaxWidth().height(180.dp).pointerInput(keys) {
                    detectTapGestures { pos ->
                        val i = (pos.x / size.width * keys.size).toInt().coerceIn(0, keys.lastIndex)
                        selected = if (selected == i) null else i
                    }
                }
        ) {
            val slot = size.width / keys.size
            val groupGap = 4.dp.toPx()
            val barGap = if (series.size > 1) 1.dp.toPx() else 0f
            val barW =
                ((slot - groupGap - barGap * (series.size - 1)) / series.size).coerceAtLeast(1f)
            val radius = minOf(3.dp.toPx(), barW / 2)
            keys.indices.forEach { i ->
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                series.forEachIndexed { j, s ->
                    val r = s.rows[i] ?: return@forEachIndexed
                    val x = i * slot + groupGap / 2 + j * (barW + barGap)
                    val parts =
                        r.zones.ifEmpty {
                            listOf(ZoneVolume("", 0.0, r.poborPrzed, r.oddaniePrzed))
                        }
                    val last = parts.indexOfLast { part(it) > 0 }
                    var end = 0f
                    parts.forEachIndexed { k, z ->
                        val h = (part(z) / max * size.height).toFloat()
                        if (h <= 0f) return@forEachIndexed
                        drawPath(
                            roundedBar(
                                x,
                                size.height - end - h,
                                barW,
                                h,
                                if (k == last) radius else 0f,
                                roundTop = true,
                            ),
                            s.color.copy(alpha = alpha * (zoneAlpha[z.zone] ?: 1f)),
                        )
                        end += h
                    }
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

/** Width of the row labels in [HeatmapChart] (rows added under it should start after it). */
internal val HeatmapLabelWidth = 64.dp

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
    underCells: (@Composable () -> Unit)? = null,
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
                MutedText(rowLabels[r], modifier = Modifier.width(HeatmapLabelWidth))
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
        underCells?.invoke()
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.width(HeatmapLabelWidth))
            listOf(0, 6, 12, 18).forEach { h -> MutedText("$h:00", modifier = Modifier.weight(1f)) }
        }
    }
}

/**
 * Neutral shade (alpha of `onSurface`) per zone name, darker = more expensive, so the zones don't
 * compete with the blue/orange data colours.
 */
fun zoneShades(zones: List<ZonePrice?>): Map<String, Float> {
    val sorted = zones.filterNotNull().distinctBy { it.zone }.sortedBy { it.price }
    return sorted
        .mapIndexed { i, z ->
            z.zone to if (sorted.size == 1) 0.3f else 0.12f + 0.48f * i / (sorted.size - 1)
        }
        .toMap()
}

/** 24 hourly segments shaded by tariff zone (see [zoneShades]); unmatched hours stay empty. */
@Composable
fun TariffZoneStrip(
    zones: List<ZonePrice?>,
    shades: Map<String, Float>,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.fillMaxWidth().height(12.dp)) {
        val cell = size.width / zones.size
        val gap = 2.dp.toPx()
        zones.forEachIndexed { h, z ->
            if (z == null) return@forEachIndexed
            // Gap only where the zone changes, so a zone reads as one block.
            val left = if (h > 0 && zones[h - 1]?.zone != z.zone) gap / 2 else 0f
            val right = if (h < zones.lastIndex && zones[h + 1]?.zone != z.zone) gap / 2 else 0f
            drawRect(
                base.copy(alpha = shades[z.zone] ?: 0.3f),
                topLeft = Offset(h * cell + left, 0f),
                size = Size(cell - left - right, size.height),
            )
        }
    }
}

/** One line per zone: swatch, name, hours (e.g. `22–6, 13–15`) and price per kWh. */
@Composable
fun TariffZoneLegend(zones: List<ZonePrice?>, shades: Map<String, Float>) {
    val base = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        zones
            .filterNotNull()
            .distinctBy { it.zone }
            .sortedBy { it.price }
            .forEach { z ->
                LegendItem(
                    base.copy(alpha = shades[z.zone] ?: 0.3f),
                    stringResource(
                        R.string.tariff_zone_legend,
                        z.zone,
                        zoneHours(zones, z.zone),
                        zl(z.price),
                    ),
                )
            }
    }
}

/** Hour ranges of [zone], a range crossing midnight joined into one (`22–6`). */
internal fun zoneHours(zones: List<ZonePrice?>, zone: String): String {
    val runs = mutableListOf<IntArray>()
    zones.forEachIndexed { h, z ->
        if (z?.zone != zone) return@forEachIndexed
        val last = runs.lastOrNull()
        if (last != null && last[1] == h) last[1] = h + 1 else runs += intArrayOf(h, h + 1)
    }
    if (runs.size > 1 && runs.first()[0] == 0 && runs.last()[1] == zones.size) {
        runs.last()[1] = runs.first()[1]
        runs.removeAt(0)
        runs.add(0, runs.removeAt(runs.lastIndex))
    }
    return runs.joinToString(", ") { (a, b) -> "$a–$b" }
}

/**
 * One bar per key, negative values below the zero line in the error colour. Tapping a bar calls
 * [onSelect] (or toggles the hint when [selected] is managed internally); [label] renders the
 * selected bar's description, first/last keys are printed under the chart.
 */
@Composable
fun ValueBarChart(
    keys: List<String>,
    values: List<Double>,
    label: @Composable (Int) -> String,
    hint: String,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    if (keys.isEmpty()) return
    val positive = MaterialTheme.colorScheme.primary
    val negative = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    var own by remember(keys, values) { mutableStateOf<Int?>(null) }
    val sel = if (onSelect != null) selected else own
    val maxUp = values.maxOf { it }.coerceAtLeast(0.0)
    val maxDown = (-values.minOf { it }).coerceAtLeast(0.0)
    val total = (maxUp + maxDown).coerceAtLeast(0.001)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MutedText(if (sel != null) label(sel) else hint)
        Canvas(
            modifier =
                Modifier.fillMaxWidth().height(140.dp).pointerInput(keys, onSelect) {
                    detectTapGestures { pos ->
                        val i = (pos.x / size.width * keys.size).toInt().coerceIn(0, keys.lastIndex)
                        if (onSelect != null) onSelect(i) else own = if (own == i) null else i
                    }
                }
        ) {
            val zeroY = size.height * (maxUp / total).toFloat()
            val slot = size.width / keys.size
            val gap = if (slot > 12.dp.toPx()) 4.dp.toPx() else 1.dp.toPx()
            val barW = (slot - gap).coerceAtLeast(1f)
            val radius = minOf(4.dp.toPx(), barW / 2)
            values.forEachIndexed { i, v ->
                val alpha = if (sel == null || sel == i) 1f else 0.4f
                val h = (kotlin.math.abs(v) / total * size.height).toFloat()
                if (h <= 0f) return@forEachIndexed
                val x = i * slot + gap / 2
                if (v > 0)
                    drawPath(
                        roundedBar(x, zeroY - h, barW, h, radius, roundTop = true),
                        positive.copy(alpha = alpha),
                    )
                else
                    drawPath(
                        roundedBar(x, zeroY, barW, h, radius, roundTop = false),
                        negative.copy(alpha = alpha),
                    )
            }
            drawLine(gridColor, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            MutedText(keys.first())
            Box(Modifier.weight(1f))
            if (keys.size > 1) MutedText(keys.last())
        }
    }
}
