package mx.velora.android.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mx.velora.android.R
import mx.velora.android.api.models.ActivitySummary
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.home.label
import mx.velora.android.ui.Formats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(viewModel: ActivitiesViewModel, onOpen: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bikes by viewModel.bikes.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val listLabel = stringResource(R.string.activities_list_label)

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = listLabel },
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        ) {
            item(key = "cabecera") { Header(state.filters, onFilters = { showFilters = true; viewModel.loadBikes() }) }
            item(key = "estado") { Status(state, onRetry = viewModel::refresh, onClear = { viewModel.apply(ActivityFilters()) }) }
            items(state.items, key = { it.id.toString() }) { activity ->
                ActivityRow(activity, onOpen = { onOpen(activity.id.toString()) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            item(key = "pie") { Footer(state, onMore = viewModel::loadMore) }
        }
    }

    if (showFilters) {
        FiltersSheet(
            current = state.filters,
            bikes = bikes,
            onApply = {
                showFilters = false
                viewModel.apply(it)
            },
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun Header(filters: ActivityFilters, onFilters: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.nav_activities),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        OutlinedButton(onClick = onFilters) {
            Text(
                if (filters.isEmpty) {
                    stringResource(R.string.activities_filters)
                } else {
                    pluralStringResource(R.plurals.activities_filters_active, filters.active, filters.active)
                },
            )
        }
    }
}

@Composable
private fun Status(state: ActivitiesState, onRetry: () -> Unit, onClear: () -> Unit) {
    val filtered = !state.filters.isEmpty
    Column(modifier = Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.pending || (state.loading && state.items.isEmpty() && state.error == null) -> Loading(R.string.activities_loading)
            state.error == ListError.INVALID_FILTERS -> Problem(R.string.activities_invalid, R.string.activities_filter_clear, onClear)
            state.error != null && state.items.isEmpty() -> Problem(R.string.activities_unavailable, R.string.retry, onRetry)
            state.error == ListError.OFFLINE -> Note(R.string.activities_offline)
            state.error == ListError.FAILED -> Note(R.string.activities_unavailable)
            state.items.isEmpty() && filtered -> {
                Empty(R.string.activities_no_matches_title, R.string.activities_no_matches_body)
                OutlinedButton(onClick = onClear) { Text(stringResource(R.string.activities_filter_clear)) }
            }
            state.items.isEmpty() -> Empty(R.string.activities_empty_title, R.string.activities_empty_body)
            else -> Text(
                pluralStringResource(R.plurals.activities_count, state.items.size, Formats.count(state.items.size)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Loading(text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Problem(message: Int, action: Int, onAction: () -> Unit) {
    Text(
        stringResource(message),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Button(onClick = onAction) { Text(stringResource(action)) }
}

@Composable
private fun Note(message: Int) {
    Text(
        stringResource(message),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Empty(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(stringResource(body), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ActivityRow(activity: ActivitySummary, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 14.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(activity.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Formats.weekdayDate(activity.localStartDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(activity.subType.label), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(R.string.activity_distance, Formats.distance(activity.distanceMeters))
            Metric(R.string.activity_moving_time, Formats.duration(activity.movingTimeSeconds.toLong()))
            Metric(R.string.activity_elevation, Formats.elevation(activity.elevationGainMeters))
            Metric(R.string.activity_speed, Formats.speed(activity.averageSpeedMps))
        }
    }
}

@Composable
private fun Metric(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Footer(state: ActivitiesState, onMore: () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.moreFailed) {
            Text(
                stringResource(R.string.activities_more_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.canLoadMore && state.items.isNotEmpty()) {
            OutlinedButton(onClick = onMore, enabled = !state.loadingMore) {
                Text(stringResource(if (state.loadingMore) R.string.activities_loading_more else R.string.activities_show_more))
            }
        }
    }
}
