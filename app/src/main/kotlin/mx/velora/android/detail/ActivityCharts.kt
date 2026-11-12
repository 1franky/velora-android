package mx.velora.android.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mx.velora.android.R
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.ui.Formats

private enum class Axis(@StringRes val label: Int) {
    DISTANCE(R.string.charts_axis_distance),
    TIME(R.string.charts_axis_time),
}

/**
 * Series de la rodada en paneles apilados con un eje horizontal compartido (como la web: nunca dos escalas en un panel).
 * Tocar o deslizar sobre cualquier panel mueve un mismo cursor en todos; su valor se lee en el encabezado de cada uno.
 * Sin cursor, el encabezado muestra mínimo y máximo: ningún dato depende de tocar la gráfica.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityCharts(data: PreparedSeries) {
    val axes = listOfNotNull(Axis.DISTANCE.takeIf { data.distanceKm != null }, Axis.TIME.takeIf { data.elapsedSeconds != null })
    var axis by rememberSaveable { mutableStateOf(axes.first()) }
    var index by remember { mutableStateOf<Int?>(null) }
    val xValues = (if (axis == Axis.DISTANCE) data.distanceKm else data.elapsedSeconds).orEmpty()
    val finiteX = xValues.filterNotNull()
    val xMin = finiteX.minOrNull() ?: 0.0
    val xMax = (finiteX.maxOrNull() ?: 1.0).let { if (it == xMin) xMin + 1 else it }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.charts_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (axes.size > 1) {
            val label = stringResource(R.string.charts_axis)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.semantics { paneTitle = label }) {
                axes.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = option == axis,
                        onClick = {
                            axis = option
                            index = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, axes.size),
                        icon = {},
                    ) { Text(stringResource(option.label)) }
                }
            }
        }
        val position = index?.let { xValues.getOrNull(it) }
        Text(
            when {
                position == null -> stringResource(R.string.charts_hint)
                axis == Axis.DISTANCE -> stringResource(R.string.charts_at_distance, Formats.decimal(position, 1))
                else -> stringResource(R.string.charts_at_time, Formats.duration(position.toLong()))
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        data.series.forEach { series ->
            Panel(series, xValues, xMin, xMax, index) { x ->
                index = nearestIndex(xValues, xMin + x * (xMax - xMin)).takeIf { it >= 0 }
            }
        }
    }
}

@Composable
private fun Panel(
    series: ChartSeries,
    xValues: List<Double?>,
    xMin: Double,
    xMax: Double,
    index: Int?,
    onScrub: (fraction: Double) -> Unit,
) {
    val name = stringResource(series.key.label)
    val range = stringResource(R.string.charts_range, value(series.key, series.min), value(series.key, series.max))
    val reading = index?.let { i -> series.values.getOrNull(i)?.let { value(series.key, it) } ?: stringResource(R.string.charts_no_value) }
    val line = LocalVeloraColors.current.chart
    val grid = MaterialTheme.colorScheme.outline
    val cursor = MaterialTheme.colorScheme.onSurface
    // Un poco de aire arriba y abajo para que el trazo no toque los bordes.
    val span = (series.max - series.min).let { if (it == 0.0) 1.0 else it }
    val yMin = series.min - span * 0.08
    val yMax = series.max + span * 0.08

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(reading ?: range, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .semantics { contentDescription = "$name: $range" }
                .pointerInput(xValues) { detectTapGestures { onScrub((it.x / size.width).toDouble().coerceIn(0.0, 1.0)) } }
                .pointerInput(xValues) {
                    detectHorizontalDragGestures { change, _ -> onScrub((change.position.x / size.width).toDouble().coerceIn(0.0, 1.0)) }
                },
        ) {
            fun x(v: Double) = ((v - xMin) / (xMax - xMin) * size.width).toFloat()
            fun y(v: Double) = (size.height - (v - yMin) / (yMax - yMin) * size.height).toFloat()
            // Guías recesivas: arriba y abajo del panel.
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            // Un hueco en los datos (sin sensor o sin distancia) corta el trazo: no se inventa una línea.
            val path = Path()
            var drawing = false
            series.values.forEachIndexed { i, value ->
                val xv = xValues.getOrNull(i)
                if (value == null || xv == null) {
                    drawing = false
                } else if (!drawing) {
                    path.moveTo(x(xv), y(value))
                    drawing = true
                } else {
                    path.lineTo(x(xv), y(value))
                }
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            index?.let { i -> xValues.getOrNull(i) }?.let { xv ->
                drawLine(cursor, Offset(x(xv), 0f), Offset(x(xv), size.height), strokeWidth = 1.dp.toPx())
                series.values.getOrNull(index)?.let { v ->
                    drawCircle(line, radius = 4.dp.toPx(), center = Offset(x(xv), y(v)))
                }
            }
        }
    }
}

@get:StringRes
private val ChartSeriesKey.label: Int
    get() = when (this) {
        ChartSeriesKey.ALTITUDE -> R.string.charts_series_altitude
        ChartSeriesKey.SPEED -> R.string.charts_series_speed
        ChartSeriesKey.HEART_RATE -> R.string.charts_series_heart_rate
        ChartSeriesKey.POWER -> R.string.charts_series_power
        ChartSeriesKey.CADENCE -> R.string.charts_series_cadence
        ChartSeriesKey.TEMPERATURE -> R.string.charts_series_temperature
    }

/** Valor con su unidad, con los decimales de la web: velocidad con uno, lo demás entero. */
private fun value(key: ChartSeriesKey, v: Double): String = when (key) {
    ChartSeriesKey.ALTITUDE -> "${Formats.decimal(v, 0)} m"
    ChartSeriesKey.SPEED -> "${Formats.decimal(v, 1)} km/h"
    ChartSeriesKey.HEART_RATE -> "${Formats.decimal(v, 0)} ppm"
    ChartSeriesKey.POWER -> "${Formats.decimal(v, 0)} W"
    ChartSeriesKey.CADENCE -> "${Formats.decimal(v, 0)} rpm"
    ChartSeriesKey.TEMPERATURE -> "${Formats.decimal(v, 0)} °C"
}
