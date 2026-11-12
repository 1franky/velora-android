package mx.velora.android.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mx.velora.android.R
import mx.velora.android.VeloraIcons
import mx.velora.android.api.models.ActivityClimbs
import mx.velora.android.api.models.ActivityDetail
import mx.velora.android.api.models.ActivityLap
import mx.velora.android.api.models.ActivityTrack
import mx.velora.android.api.models.MetricSource
import mx.velora.android.core.data.RefreshResult
import mx.velora.android.core.data.Remote
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.home.OutlinedPanel
import mx.velora.android.home.label
import mx.velora.android.ui.Formats

/**
 * Detalle de una rodada: mapa, métricas, gráficas, vueltas y subidas. El mapa llega como parámetro: MapLibre es nativo
 * y no corre en las pruebas de JVM.
 */
@Composable
fun ActivityDetailScreen(
    viewModel: ActivityDetailViewModel,
    @StringRes backLabel: Int,
    onBack: () -> Unit,
    map: @Composable (title: String, track: ActivityTrack) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
            Icon(VeloraIcons.Back, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(backLabel), modifier = Modifier.padding(start = 4.dp))
        }
        when (val detail = state.detail) {
            null -> Loading(R.string.detail_loading)
            Remote.NotFound -> Problem(R.string.detail_not_found, onRetry = null)
            is Remote.Failed -> Problem(
                if (detail.reason == RefreshResult.OFFLINE) R.string.detail_offline else R.string.detail_unavailable,
                onRetry = viewModel::load,
            )
            is Remote.Ok -> {
                Header(detail.value)
                MapView(state.map, detail.value.title, map)
                Metrics(detail.value)
                SectionView(state.charts, R.string.charts_unavailable) { ActivityCharts(it) }
                SectionView(state.laps, R.string.laps_unavailable) { Laps(it) }
                SectionView(state.climbs, R.string.climbs_unavailable) { Climbs(it) }
                Source(detail.value)
            }
        }
    }
}

@Composable
private fun MapView(section: MapSection, title: String, map: @Composable (String, ActivityTrack) -> Unit) {
    when (section) {
        MapSection.Loading -> Loading(null)
        is MapSection.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            map(title, section.track)
            if (section.track.properties.trimmed) {
                Text(stringResource(R.string.map_trimmed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MapSection.Hidden -> Text(stringResource(R.string.map_hidden), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MapSection.Failed -> Text(stringResource(R.string.map_unavailable), color = MaterialTheme.colorScheme.error)
        MapSection.None -> Unit
    }
}

@Composable
private fun <T> SectionView(section: Section<T>, @StringRes failed: Int, content: @Composable (T) -> Unit) {
    when (section) {
        Section.Loading -> Loading(null)
        is Section.Ready -> content(section.value)
        Section.Absent -> Unit
        Section.Failed -> Text(stringResource(failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Loading(@StringRes text: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        text?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Problem(@StringRes message: Int, onRetry: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(message),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        onRetry?.let { Button(onClick = it) { Text(stringResource(R.string.retry)) } }
    }
}

@Composable
private fun Header(activity: ActivityDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(activity.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(
                    R.string.detail_started,
                    Formats.weekdayDate(activity.localStartDate),
                    Formats.time(activity.startedAt, activity.timeZone),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(activity.subType.label), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class Metric(@StringRes val label: Int, val value: String, val field: String)

@Composable
private fun Metrics(activity: ActivityDetail) {
    // Plantillas resueltas aquí: las lambdas de abajo no son composables.
    val bpmTemplate = stringResource(R.string.detail_bpm)
    val wattsTemplate = stringResource(R.string.detail_watts)
    val rpmTemplate = stringResource(R.string.detail_rpm)
    val bpm = { v: Int -> bpmTemplate.format(Formats.count(v)) }
    val watts = { v: Int -> wattsTemplate.format(Formats.count(v)) }
    val rpm = { v: Int -> rpmTemplate.format(Formats.count(v)) }
    // Solo las métricas presentes: un dato ausente no se muestra como cero.
    val metrics = listOfNotNull(
        activity.distanceMeters?.let { Metric(R.string.detail_distance, Formats.distance(it), "distanceMeters") },
        Metric(R.string.detail_moving_time, Formats.duration(activity.movingTimeSeconds.toLong()), "movingTimeSeconds"),
        Metric(R.string.detail_elapsed_time, Formats.duration(activity.elapsedTimeSeconds.toLong()), "elapsedTimeSeconds"),
        activity.elevationGainMeters?.let { Metric(R.string.detail_elevation_gain, Formats.elevation(it), "elevationGainMeters") },
        activity.elevationLossMeters?.let { Metric(R.string.detail_elevation_loss, Formats.elevation(it), "elevationLossMeters") },
        activity.averageSpeedMps?.let { Metric(R.string.detail_average_speed, Formats.speed(it), "averageSpeedMps") },
        activity.maxSpeedMps?.let { Metric(R.string.detail_max_speed, Formats.speed(it), "maxSpeedMps") },
        activity.averageHeartRateBpm?.let { Metric(R.string.detail_average_heart_rate, bpm(it), "averageHeartRateBpm") },
        activity.maxHeartRateBpm?.let { Metric(R.string.detail_max_heart_rate, bpm(it), "maxHeartRateBpm") },
        activity.averagePowerWatts?.let { Metric(R.string.detail_average_power, watts(it), "averagePowerWatts") },
        activity.maxPowerWatts?.let { Metric(R.string.detail_max_power, watts(it), "maxPowerWatts") },
        activity.averageCadenceRpm?.let { Metric(R.string.detail_average_cadence, rpm(it), "averageCadenceRpm") },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.detail_metrics), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        // Dos columnas que reparten el ancho disponible.
        metrics.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { metric ->
                    Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(stringResource(metric.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(metric.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (activity.metricsProvenance[metric.field] == MetricSource.DERIVED) {
                            Text(stringResource(R.string.detail_derived), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Laps(laps: List<ActivityLap>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.laps_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedPanel {
            Column {
                laps.forEachIndexed { i, lap ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LapRow(lap)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LapRow(lap: ActivityLap) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.laps_lap, lap.lapNumber), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Small(R.string.laps_distance, Formats.distance(lap.distanceMeters))
            Small(R.string.laps_time, Formats.duration((lap.timerTimeSeconds ?: lap.elapsedTimeSeconds)?.toLong()))
            Small(R.string.laps_speed, Formats.speed(lap.averageSpeedMps))
            lap.averageHeartRateBpm?.let { Small(R.string.laps_heart_rate, stringResource(R.string.detail_bpm, Formats.count(it))) }
            lap.averagePowerWatts?.let { Small(R.string.laps_power, stringResource(R.string.detail_watts, Formats.count(it))) }
            lap.averageCadenceRpm?.let { Small(R.string.laps_cadence, stringResource(R.string.detail_rpm, Formats.count(it))) }
        }
    }
}

@Composable
private fun Small(@StringRes label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Orden de la API: de la bajada fuerte a la subida fuerte. */
private val GRADE_BINS = listOf(
    R.string.climbs_bin_steep_down,
    R.string.climbs_bin_down,
    R.string.climbs_bin_gentle_down,
    R.string.climbs_bin_flat,
    R.string.climbs_bin_gentle_up,
    R.string.climbs_bin_up,
    R.string.climbs_bin_steep_up,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Climbs(data: ActivityClimbs) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.climbs_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (data.climbs.isEmpty()) {
            Text(stringResource(R.string.climbs_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            OutlinedPanel {
                Column {
                    data.climbs.forEachIndexed { i, climb ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.climbs_range, Formats.distance(climb.startDistanceMeters), Formats.distance(climb.endDistanceMeters)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Small(R.string.climbs_length, Formats.distance(climb.lengthMeters))
                                Small(R.string.climbs_gain, Formats.elevation(climb.elevationGainMeters))
                                Small(R.string.climbs_average, Formats.grade(climb.averageGradePercent))
                                Small(R.string.climbs_max, Formats.grade(climb.maxGradePercent))
                            }
                        }
                    }
                }
            }
        }
        val total = data.gradeDistribution.sumOf { it.distanceMeters }
        if (total > 0 && data.gradeDistribution.size == GRADE_BINS.size) {
            Text(stringResource(R.string.climbs_grades), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val bar = LocalVeloraColors.current.chart
            val track = MaterialTheme.colorScheme.surfaceVariant
            data.gradeDistribution.forEachIndexed { i, bucket ->
                val share = bucket.distanceMeters / total
                // Cada barra lleva su valor escrito: la información no depende del largo ni del color.
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(GRADE_BINS[i]), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Formats.distance(bucket.distanceMeters)}, ${Formats.percent(share)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(track, RoundedCornerShape(5.dp))) {
                        Box(modifier = Modifier.fillMaxWidth(share.toFloat()).fillMaxHeight().background(bar, RoundedCornerShape(5.dp)))
                    }
                }
            }
        }
        Text(stringResource(R.string.climbs_rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Source(activity: ActivityDetail) {
    val file = activity.originalFile
    Text(
        stringResource(
            R.string.detail_source,
            file.format.value,
            Formats.fileSize(file.sizeBytes.toLong()),
            Formats.date(file.importedAt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
