package mx.velora.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import mx.velora.android.R
import mx.velora.android.api.models.MonthlyTotals
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.ui.Formats

/**
 * Distancia por mes de los últimos 12 meses (como en la web). El mes actual va en cempasúchil y su etiqueta en negritas
 * (no solo color); los demás, en gris de contexto. Tocar una columna escribe su mes arriba; cada columna también lo
 * dice al lector de pantalla.
 */
@Composable
fun MonthlyTrend(months: List<MonthlyTotals>) {
    if (months.isEmpty()) return
    val last = months.lastIndex
    var selected by rememberSaveable(months.first().month) { mutableIntStateOf(last) }
    val colors = LocalVeloraColors.current
    val max = months.maxOf { it.distanceMeters }.takeIf { it > 0 } ?: 1.0
    // Uno de cada tres meses, contando desde el actual: cabe en un teléfono y el actual siempre se lee.
    val labelled = { index: Int -> (last - index) % 3 == 0 }
    val readouts = months.map { readout(it) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_trend_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(readouts[selected], style = MaterialTheme.typography.bodyLarge)
        OutlinedPanel {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(160.dp).selectableGroup()) {
                    months.forEachIndexed { index, month ->
                        val fraction = (month.distanceMeters / max).toFloat()
                        Box(
                            contentAlignment = Alignment.BottomCenter,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (index == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                )
                                .selectable(selected = index == selected, role = Role.Tab, onClick = { selected = index })
                                .semantics { contentDescription = readouts[index] },
                        ) {
                            if (month.distanceMeters > 0) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .fillMaxWidth()
                                        // Un mes con poca distancia sigue viéndose.
                                        .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                                        .background(
                                            if (index == last) colors.chart else colors.chartContext,
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                        ),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    months.forEachIndexed { index, month ->
                        Text(
                            if (labelled(index)) Formats.shortMonth(YearMonth.parse(month.month)) else "",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (index == last) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun readout(month: MonthlyTotals): String =
    stringResource(
        R.string.home_trend_readout,
        Formats.longMonth(YearMonth.parse(month.month)),
        Formats.distance(month.distanceMeters),
        pluralStringResource(R.plurals.home_rides, month.activityCount, Formats.count(month.activityCount)),
    )
