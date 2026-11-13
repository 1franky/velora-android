package mx.velora.android.performance

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.YearMonth
import mx.velora.android.R
import mx.velora.android.api.models.Eddington
import mx.velora.android.api.models.MetricSource
import mx.velora.android.api.models.Timeseries
import mx.velora.android.api.models.TimeseriesBucket
import mx.velora.android.core.data.Grouping
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.core.network.HeartRateZones
import mx.velora.android.core.network.Records
import mx.velora.android.core.network.TrainingLoad
import mx.velora.android.detail.Section
import mx.velora.android.home.OutlinedPanel
import mx.velora.android.ui.BarChart
import mx.velora.android.ui.Formats

/** Rendimiento, como en la web: tendencias, récords, Eddington, zonas cardiacas y carga estimada (ADR-020). */
@Composable
fun PerformanceScreen(viewModel: PerformanceViewModel, onOpenActivity: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nav_performance), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.performance_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Options(state, onGroup = viewModel::group, onIndoor = viewModel::includeIndoor)
        if (state.offline) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.performance_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Button(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            }
            return@Column
        }
        SectionView(state.trends) { Trends(it, state.grouping) }
        SectionView(state.records) { RecordsView(it, onOpenActivity) }
        SectionView(state.eddington) { EddingtonView(it) }
        SectionView(state.heartRate) { HeartRateView(it) }
        SectionView(state.load) { LoadView(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Options(state: PerformanceState, onGroup: (Grouping) -> Unit, onIndoor: (Boolean) -> Unit) {
    val groupingLabel = stringResource(R.string.performance_grouping)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.semantics { paneTitle = groupingLabel }) {
            Grouping.entries.forEachIndexed { i, grouping ->
                SegmentedButton(
                    selected = grouping == state.grouping,
                    onClick = { onGroup(grouping) },
                    shape = SegmentedButtonDefaults.itemShape(i, Grouping.entries.size),
                    icon = {},
                ) { Text(stringResource(grouping.label)) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.includeIndoor, role = Role.Switch, onValueChange = onIndoor)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.performance_indoor))
            Switch(checked = state.includeIndoor, onCheckedChange = null)
        }
    }
}

@Composable
private fun <T> SectionView(section: Section<T>, content: @Composable (T) -> Unit) {
    when (section) {
        Section.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        is Section.Ready -> content(section.value)
        Section.Absent -> Unit
        Section.Failed -> Text(stringResource(R.string.performance_section_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Title(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Trends(data: Timeseries, grouping: Grouping) {
    val buckets = data.buckets
    var selected by rememberSaveable(grouping, buckets.size) { mutableIntStateOf((buckets.size - 1).coerceAtLeast(0)) }
    val count = buckets.size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when (grouping) {
                Grouping.WEEK -> pluralStringResource(R.plurals.performance_range_week, count, count)
                Grouping.MONTH -> pluralStringResource(R.plurals.performance_range_month, count, count)
                Grouping.YEAR -> pluralStringResource(R.plurals.performance_range_year, count, count)
            },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        val totals = data.totals
        listOf(
            listOf(
                R.string.performance_total_distance to Formats.distance(totals.distanceMeters),
                R.string.home_total_moving_time to Formats.duration(totals.movingTimeSeconds.toLong()),
            ),
            listOf(
                R.string.home_total_elevation to Formats.elevation(totals.elevationGainMeters),
                R.string.performance_total_rides to Formats.count(totals.activityCount),
            ),
        ).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (buckets.isNotEmpty()) {
            val readouts = buckets.map { readout(it, grouping) }
            // Etiquetas espaciadas para que quepan, contando desde la más reciente.
            val every = when (grouping) {
                Grouping.WEEK -> 4
                Grouping.MONTH -> 3
                Grouping.YEAR -> if (count > 6) 2 else 1
            }
            BarChart(
                values = buckets.map { it.distanceMeters },
                labels = buckets.mapIndexed { i, b -> if ((count - 1 - i) % every == 0) shortLabel(b, grouping) else null },
                readouts = readouts,
                selected = selected.coerceIn(0, count - 1),
                onSelect = { selected = it },
                label = stringResource(R.string.performance_chart_label),
                emphasized = count - 1,
            )
        }
        Note(stringResource(if (data.includeIndoor) R.string.performance_indoor_included else R.string.performance_indoor_excluded))
        Note(
            stringResource(
                R.string.performance_sources,
                pluralStringResource(R.plurals.performance_sources_device, data.distanceSources.deviceReported, data.distanceSources.deviceReported),
                pluralStringResource(R.plurals.performance_sources_derived, data.distanceSources.derived, data.distanceSources.derived),
            ),
        )
        if (data.duplicatesExcluded > 0) {
            Note(pluralStringResource(R.plurals.performance_duplicates, data.duplicatesExcluded, data.duplicatesExcluded))
        }
    }
}

@Composable
private fun readout(bucket: TimeseriesBucket, grouping: Grouping): String {
    val period = when (grouping) {
        Grouping.WEEK -> stringResource(R.string.performance_week_of, Formats.dayMonthYear(bucket.start))
        Grouping.MONTH -> Formats.longMonth(YearMonth.from(bucket.start))
        Grouping.YEAR -> bucket.start.year.toString()
    }
    return stringResource(
        R.string.performance_readout,
        period,
        Formats.distance(bucket.distanceMeters),
        pluralStringResource(R.plurals.home_rides, bucket.activityCount, Formats.count(bucket.activityCount)),
        Formats.elevation(bucket.elevationGainMeters),
    )
}

private fun shortLabel(bucket: TimeseriesBucket, grouping: Grouping): String = when (grouping) {
    Grouping.WEEK -> Formats.dayMonth(bucket.start)
    Grouping.MONTH -> Formats.shortMonth(YearMonth.from(bucket.start))
    Grouping.YEAR -> bucket.start.year.toString()
}

@Composable
private fun RecordsView(data: Records, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(R.string.records_title)
        if (data.records.isEmpty() && data.biggestDay == null) {
            Note(stringResource(R.string.records_empty))
        } else {
            OutlinedPanel {
                Column {
                    data.records.forEachIndexed { i, record ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(record.activityId.toString()) }
                                .padding(16.dp)
                                .semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(stringResource(record.kind.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(record.formatted(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${record.title}, ${Formats.weekdayDate(record.localStartDate)}")
                            record.source?.let {
                                Text(
                                    stringResource(if (it == MetricSource.DERIVED) R.string.records_source_derived else R.string.records_source_device),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    data.biggestDay?.let { day ->
                        if (data.records.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}) {
                            Text(stringResource(R.string.records_biggest_day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Formats.distance(day.distanceMeters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("${Formats.weekdayDate(day.date)}, ${pluralStringResource(R.plurals.records_biggest_day_rides, day.activityCount, day.activityCount)}")
                        }
                    }
                }
            }
        }
        Note(
            stringResource(
                R.string.records_rules,
                Formats.decimal(data.minSpeedDistanceMeters / 1000, 0),
                Formats.decimal(data.minSpeedMovingSeconds / 60, 0),
            ),
        )
    }
}

@get:StringRes
private val Records.Kind.label: Int
    get() = when (this) {
        Records.Kind.LONGEST_DISTANCE -> R.string.records_longest_distance
        Records.Kind.MOST_ELEVATION -> R.string.records_most_elevation
        Records.Kind.LONGEST_MOVING_TIME -> R.string.records_longest_moving_time
        Records.Kind.FASTEST_AVERAGE_SPEED -> R.string.records_fastest_average_speed
    }

private fun Records.PersonalRecord.formatted(): String = when (kind) {
    Records.Kind.LONGEST_DISTANCE -> Formats.distance(value)
    Records.Kind.MOST_ELEVATION -> Formats.elevation(value)
    Records.Kind.LONGEST_MOVING_TIME -> Formats.duration(value.toLong())
    Records.Kind.FASTEST_AVERAGE_SPEED -> Formats.speed(value)
}

@Composable
private fun EddingtonView(data: Eddington) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(R.string.eddington_title)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(stringResource(R.string.eddington_overall) to data.overall, stringResource(R.string.eddington_this_year, data.year) to data.thisYear)
                .forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Formats.count(value.number), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        val next = value.number + 1
                        Text(
                            pluralStringResource(R.plurals.eddington_next, value.missingForNext, value.missingForNext, next),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
        }
        Note(pluralStringResource(R.plurals.eddington_explain, data.overall.number, data.overall.number))
    }
}

@Composable
private fun HeartRateView(data: HeartRateZones) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(R.string.hr_title)
        val max = data.maxHeartRateBpm
        when {
            max == null -> Note(stringResource(R.string.hr_no_maximum))
            data.activitiesWithHeartRate == 0 -> Note(stringResource(R.string.hr_no_data))
            else -> {
                val total = data.zones.sumOf { it.seconds }.takeIf { it > 0 } ?: 1.0
                val bar = LocalVeloraColors.current.chart
                val track = MaterialTheme.colorScheme.surfaceVariant
                data.zones.forEach { zone ->
                    val share = zone.seconds / total
                    // Cada barra lleva su valor escrito: la información no depende del largo ni del color.
                    Column(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${stringResource(R.string.hr_zone, zone.zone)}, " +
                                    (zone.toBpm?.let { stringResource(R.string.hr_range, zone.fromBpm, it) } ?: stringResource(R.string.hr_from, zone.fromBpm)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${Formats.duration(zone.seconds.toLong())}, ${Formats.percent(share)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(track, RoundedCornerShape(5.dp))) {
                            Box(modifier = Modifier.fillMaxWidth(share.toFloat()).fillMaxHeight().background(bar, RoundedCornerShape(5.dp)))
                        }
                    }
                }
                Note(stringResource(R.string.hr_explain, max, Formats.duration(data.belowZonesSeconds.toLong())))
                if (data.activitiesWithoutHeartRate > 0) {
                    Note(pluralStringResource(R.plurals.hr_without, data.activitiesWithoutHeartRate, data.activitiesWithoutHeartRate))
                }
                Note(stringResource(R.string.hr_not_medical))
            }
        }
    }
}

@Composable
private fun LoadView(data: TrainingLoad) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(R.string.load_title)
        if (data.maxHeartRateBpm == null) {
            Note(stringResource(R.string.load_no_maximum))
            return@Column
        }
        val weeks = data.weeks
        if (weeks.isNotEmpty()) {
            var selected by rememberSaveable(weeks.size) { mutableIntStateOf(weeks.size - 1) }
            BarChart(
                values = weeks.map { it.load },
                labels = weeks.mapIndexed { i, w -> if ((weeks.size - 1 - i) % 4 == 0) Formats.dayMonth(w.start) else null },
                readouts = weeks.map { w ->
                    stringResource(
                        R.string.load_readout,
                        stringResource(R.string.performance_week_of, Formats.dayMonthYear(w.start)),
                        Formats.decimal(w.load, 0),
                        pluralStringResource(R.plurals.load_rides_with_hr, w.activitiesWithHeartRate, w.activitiesWithHeartRate),
                    )
                },
                selected = selected.coerceIn(0, weeks.size - 1),
                onSelect = { selected = it },
                label = stringResource(R.string.load_chart),
                emphasized = weeks.size - 1,
            )
        }
        Note(stringResource(R.string.load_method))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@get:StringRes
private val Grouping.label: Int
    get() = when (this) {
        Grouping.WEEK -> R.string.performance_week
        Grouping.MONTH -> R.string.performance_month
        Grouping.YEAR -> R.string.performance_year
    }
