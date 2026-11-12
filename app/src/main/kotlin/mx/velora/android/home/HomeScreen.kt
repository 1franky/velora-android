package mx.velora.android.home

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mx.velora.android.R
import mx.velora.android.VeloraIcons
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.ActivitySummary
import mx.velora.android.api.models.DashboardSummary
import mx.velora.android.core.data.Period
import mx.velora.android.core.data.RefreshResult
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.ui.Change
import mx.velora.android.ui.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = state.refreshing && state.summary != null,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.nav_home),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            PeriodSelector(state.period, viewModel::select)
            Status(state)
            Body(state, onRetry = viewModel::refresh)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    val label = stringResource(R.string.home_periods)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.semantics { paneTitle = label }) {
        Period.entries.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                icon = {},
            ) {
                Text(stringResource(period.label))
            }
        }
    }
}

/** Datos guardados mostrados sin poder actualizar: se dice de cuándo son. */
@Composable
private fun Status(state: HomeState) {
    val cached = state.summary ?: return
    val message = when (state.lastRefresh) {
        RefreshResult.OFFLINE -> R.string.home_offline
        RefreshResult.FAILED -> R.string.home_stale
        else -> return
    }
    Text(
        stringResource(message, Formats.dateTime(cached.fetchedAtEpochMs)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Body(state: HomeState, onRetry: () -> Unit) {
    val cached = state.summary
    when {
        cached != null ->
            if (cached.value.latestActivity == null) Empty() else Summary(cached.value, state.period)
        state.reading || state.lastRefresh == null || state.refreshing -> Loading()
        state.lastRefresh == RefreshResult.SESSION_ENDED -> Unit
        else -> Unavailable(onRetry)
    }
}

@Composable
private fun Loading() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(stringResource(R.string.home_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Unavailable(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_unavailable),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun Empty() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.home_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Summary(summary: DashboardSummary, period: Period) {
    val current = summary.current
    val previous = summary.previous
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Headline(summary, period)
        Delta(period, current.distanceMeters, previous?.distanceMeters)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Total(
            R.string.home_total_moving_time,
            Formats.duration(current.movingTimeSeconds.toLong()),
        ) { Delta(period, current.movingTimeSeconds.toDouble(), previous?.movingTimeSeconds?.toDouble()) }
        Total(R.string.home_total_elevation, Formats.elevation(current.elevationGainMeters)) {
            Delta(period, current.elevationGainMeters, previous?.elevationGainMeters)
        }
    }
    MonthlyTrend(summary.monthlyTrend)
    summary.latestActivity?.let { LatestActivity(it) }
}

@Composable
private fun Headline(summary: DashboardSummary, period: Period) {
    val current = summary.current
    val text = if (current.activityCount == 0 && period != Period.ALL) {
        buildAnnotatedString { append(stringResource(period.noRides)) }
    } else {
        val distance = Formats.distance(current.distanceMeters)
        val rides = pluralStringResource(R.plurals.home_rides, current.activityCount, Formats.count(current.activityCount))
        val full = stringResource(period.headline, distance, rides)
        val start = full.indexOf(distance)
        buildAnnotatedString {
            append(full)
            if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + distance.length)
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

/** Cambio contra el periodo anterior: ícono y texto, nunca solo color (como en la web). */
@Composable
private fun Delta(period: Period, current: Double, previous: Double?) {
    val previousLabel = period.previous?.let { stringResource(it) } ?: return
    val colors = LocalVeloraColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, color, text) = when (val change = Change.between(current, previous)) {
        Change.None -> return
        is Change.Up -> Triple(VeloraIcons.TrendUp, colors.positive, stringResource(R.string.home_change_up, Formats.percent(change.ratio), previousLabel))
        is Change.Down ->
            Triple(VeloraIcons.TrendDown, MaterialTheme.colorScheme.error, stringResource(R.string.home_change_down, Formats.percent(change.ratio), previousLabel))
        Change.Same -> Triple(VeloraIcons.Same, muted, stringResource(R.string.home_change_same, previousLabel))
        Change.FromZero -> Triple(VeloraIcons.TrendUp, muted, stringResource(R.string.home_change_from_zero, previousLabel))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Total(@StringRes label: Int, value: String, delta: @Composable () -> Unit) {
    OutlinedPanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            delta()
        }
    }
}

@Composable
private fun LatestActivity(activity: ActivitySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.home_latest_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedPanel {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(activity.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(Formats.weekdayDate(activity.localStartDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(activity.subType.label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Metric(R.string.activity_distance, Formats.distance(activity.distanceMeters))
                    Metric(R.string.activity_moving_time, Formats.duration(activity.movingTimeSeconds.toLong()))
                    Metric(R.string.activity_elevation, Formats.elevation(activity.elevationGainMeters))
                }
            }
        }
    }
}

@Composable
private fun Metric(@StringRes label: Int, value: String) {
    Column(modifier = Modifier.widthIn(min = 64.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun OutlinedPanel(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@get:StringRes
private val Period.label: Int
    get() = when (this) {
        Period.WEEK -> R.string.home_period_week
        Period.MONTH -> R.string.home_period_month
        Period.YEAR -> R.string.home_period_year
        Period.ALL -> R.string.home_period_all
    }

@get:StringRes
private val Period.headline: Int
    get() = when (this) {
        Period.WEEK -> R.string.home_headline_week
        Period.MONTH -> R.string.home_headline_month
        Period.YEAR -> R.string.home_headline_year
        Period.ALL -> R.string.home_headline_all
    }

@get:StringRes
private val Period.noRides: Int
    get() = when (this) {
        Period.WEEK -> R.string.home_no_rides_week
        Period.MONTH -> R.string.home_no_rides_month
        Period.YEAR, Period.ALL -> R.string.home_no_rides_year
    }

/** "la semana pasada"…; null en "Todo", que no tiene periodo anterior. */
private val Period.previous: Int?
    get() = when (this) {
        Period.WEEK -> R.string.home_previous_week
        Period.MONTH -> R.string.home_previous_month
        Period.YEAR -> R.string.home_previous_year
        Period.ALL -> null
    }

@get:StringRes
internal val ActivitySubType.label: Int
    get() = when (this) {
        ActivitySubType.MOUNTAIN_BIKE -> R.string.activity_type_mountain_bike
        ActivitySubType.ROAD -> R.string.activity_type_road
        ActivitySubType.GRAVEL -> R.string.activity_type_gravel
        ActivitySubType.INDOOR -> R.string.activity_type_indoor
        ActivitySubType.OTHER -> R.string.activity_type_other
    }
