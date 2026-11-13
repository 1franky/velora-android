package mx.velora.android.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import mx.velora.android.R
import mx.velora.android.api.models.MonthlyTotals
import mx.velora.android.ui.BarChart
import mx.velora.android.ui.Formats

/**
 * Distancia por mes de los últimos 12 meses (como en la web): el mes actual en cempasúchil, los demás en gris de
 * contexto. La gráfica es accesible por construcción (ver [BarChart]).
 */
@Composable
fun MonthlyTrend(months: List<MonthlyTotals>) {
    if (months.isEmpty()) return
    val last = months.lastIndex
    var selected by rememberSaveable(months.first().month) { mutableIntStateOf(last) }
    val readouts = months.map { readout(it) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_trend_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        BarChart(
            values = months.map { it.distanceMeters },
            // Uno de cada tres meses, contando desde el actual: cabe en un teléfono y el actual siempre se lee.
            labels = months.mapIndexed { i, m -> if ((last - i) % 3 == 0) Formats.shortMonth(YearMonth.parse(m.month)) else null },
            readouts = readouts,
            selected = selected,
            onSelect = { selected = it },
            label = stringResource(R.string.home_trend_label),
            emphasized = last,
            previousLabel = stringResource(R.string.home_trend_previous),
            nextLabel = stringResource(R.string.home_trend_next),
        )
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
