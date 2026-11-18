package mx.velora.android.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mx.velora.android.R
import mx.velora.android.activities.FiltersSheet
import mx.velora.android.activities.WideDragHandle
import mx.velora.android.api.models.ExplorationSummary
import mx.velora.android.api.models.LargestSquare
import mx.velora.android.core.data.ExplorerFocus
import mx.velora.android.detail.Section
import mx.velora.android.home.OutlinedPanel
import mx.velora.android.ui.Formats

/**
 * Explorador, como en la web (ADR-017, ADR-018): todas las rodadas juntas en un mapa de calor y, a elección, las celdas
 * visitadas. Las cifras van en una hoja aparte para no tapar el mapa. [map] dibuja el mapa (MapLibre es nativo y no
 * corre en las pruebas de JVM); recibe el estado y avisa qué se ve cuando se detiene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel,
    onImport: () -> Unit,
    map: @Composable (state: ExplorerState, onViewport: (Viewport) -> Unit, modifier: Modifier) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bikes by viewModel.bikes.collectAsStateWithLifecycle()
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    // Sin rodadas en absoluto (no por los filtros): se invita a importar.
    val noRides = state.focus == Section.Ready(ExplorerFocus.Empty) && state.filters.isEmpty

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.nav_explorer), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                if (!state.offline && !noRides) {
                    OutlinedButton(onClick = { filtersOpen = true; viewModel.loadBikes() }) {
                        Text(
                            if (state.filters.isEmpty) {
                                stringResource(R.string.activities_filters)
                            } else {
                                pluralStringResource(R.plurals.activities_filters_active, state.filters.active, state.filters.active)
                            },
                        )
                    }
                }
            }
            if (!state.offline && !noRides) Layers(state.layer, viewModel::select)
        }
        when {
            state.offline -> Message(stringResource(R.string.explorer_unavailable)) {
                Button(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            }
            noRides -> Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.explorer_empty_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.explorer_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onImport) { Text(stringResource(R.string.explorer_empty_action)) }
            }
            else -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                map(state, viewModel::viewport, Modifier.fillMaxSize())
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    focusNote(state)?.let { Note(stringResource(it)) }
                    cellsNote(state)?.let { Note(stringResource(it)) }
                }
                if (state.focus == Section.Loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp)
                }
                FilledTonalButton(onClick = { statsOpen = true }, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text(stringResource(R.string.explorer_stats_open))
                }
            }
        }
    }

    if (filtersOpen) {
        FiltersSheet(
            current = state.filters,
            bikes = bikes,
            withDistance = false,
            onApply = {
                filtersOpen = false
                viewModel.apply(it)
            },
            onDismiss = { filtersOpen = false },
        )
    }

    if (statsOpen) {
        ModalBottomSheet(
            onDismissRequest = { statsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { WideDragHandle() },
        ) {
            Stats(
                state,
                onShowSquare = {
                    statsOpen = false
                    viewModel.showSquare()
                },
            )
        }
    }
}

/** Por qué el mapa no encuadra una rodada: con filtros, ninguna los cumple; sin ellos, no hay GPS. */
private fun focusNote(state: ExplorerState): Int? {
    val focus = (state.focus as? Section.Ready)?.value ?: return null
    return when {
        focus is ExplorerFocus.Bounds -> null
        !state.filters.isEmpty -> R.string.explorer_no_matches
        focus == ExplorerFocus.NoGps -> R.string.explorer_no_gps
        else -> null
    }
}

private fun cellsNote(state: ExplorerState): Int? = when {
    state.layer == ExplorerLayer.TRACKS -> null
    else -> when (state.note) {
        // Con filtros, se aclara que las celdas no los siguen (como la web).
        CellsNote.NONE -> if (state.filters.isEmpty) null else R.string.explorer_cells_all_time
        CellsNote.ZOOM_IN -> R.string.explorer_cells_zoom_in
        CellsNote.TRUNCATED -> R.string.explorer_cells_truncated
        CellsNote.UNAVAILABLE -> R.string.explorer_cells_unavailable
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Layers(selected: ExplorerLayer, onSelect: (ExplorerLayer) -> Unit) {
    val label = stringResource(R.string.explorer_layers)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().semantics { paneTitle = label }) {
        ExplorerLayer.entries.forEachIndexed { i, layer ->
            SegmentedButton(
                selected = layer == selected,
                onClick = { onSelect(layer) },
                shape = SegmentedButtonDefaults.itemShape(i, ExplorerLayer.entries.size),
                icon = {},
            ) { Text(stringResource(layer.label), maxLines = 1) }
        }
    }
}

/** Aviso sobre el mapa: fondo opaco para que se lea sobre cualquier calle. */
@Composable
private fun Note(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        action()
    }
}

/** Cuánto territorio has cubierto, en dos sistemas que no se comparan entre sí (ADR-018). */
@Composable
private fun Stats(state: ExplorerState, onShowSquare: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.explorer_stats_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        when (val summary = state.summary) {
            Section.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            is Section.Ready -> SummaryCards(summary.value, (state.square as? Section.Ready)?.value)
            else -> Text(stringResource(R.string.explorer_stats_failed), color = MaterialTheme.colorScheme.error)
        }
        if (state.squareBbox != null) {
            OutlinedButton(onClick = onShowSquare) { Text(stringResource(R.string.explorer_show_square)) }
        }
    }
}

@Composable
private fun SummaryCards(summary: ExplorationSummary, square: LargestSquare?) {
    val hasSquare = square != null && square.propertySize > 0
    Stat(
        stringResource(R.string.explorer_stats_squares),
        Formats.count(summary.metricGrid.cellCount),
        stringResource(R.string.explorer_stats_squares_hint, Formats.decimal(summary.metricGrid.areaKm2, 0)),
    )
    Stat(
        stringResource(R.string.explorer_stats_largest_square),
        if (hasSquare) stringResource(R.string.explorer_stats_square_value, Formats.count(square!!.propertySize)) else Formats.MISSING,
        if (hasSquare) {
            stringResource(R.string.explorer_stats_square_hint, Formats.count(square!!.propertySize * square.propertySize))
        } else {
            stringResource(R.string.explorer_stats_no_square)
        },
    )
    Stat(
        stringResource(R.string.explorer_stats_hexagons),
        Formats.count(summary.h3.cellCount),
        stringResource(R.string.explorer_stats_hexagons_hint, Formats.decimal(summary.h3.approxAreaKm2, 1)),
    )
    Text(
        if (summary.pendingActivities > 0) {
            pluralStringResource(R.plurals.explorer_stats_pending, summary.pendingActivities, Formats.count(summary.pendingActivities))
        } else {
            stringResource(R.string.explorer_stats_all_time)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Stat(term: String, value: String, hint: String) {
    OutlinedPanel {
        Column(modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(term, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
