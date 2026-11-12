package mx.velora.android.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mx.velora.android.R
import mx.velora.android.VeloraIcons
import mx.velora.android.api.models.Bike
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ImportState
import mx.velora.android.core.data.ImportsRepository
import mx.velora.android.core.data.LocalImportError
import mx.velora.android.core.database.ImportEntity
import mx.velora.android.home.OutlinedPanel
import mx.velora.android.ui.Formats

class ImportViewModel(
    private val imports: ImportsRepository,
    private val activities: ActivitiesRepository,
    private val intake: suspend (List<Uri>, UUID?) -> Unit,
) : ViewModel() {
    val queue: StateFlow<List<ImportEntity>> =
        imports.queue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _bikes = MutableStateFlow<List<Bike>?>(null)
    val bikes: StateFlow<List<Bike>?> = _bikes

    val bike = MutableStateFlow<UUID?>(null)

    init {
        viewModelScope.launch { _bikes.value = activities.bikes()?.filter { it.active } }
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val selected = bike.value
        viewModelScope.launch { intake(uris, selected) }
    }

    fun clearFinished() {
        viewModelScope.launch { imports.clearFinished() }
    }
}

/** Elegir archivos, ver su progreso y abrir la rodada importada. Mismos estados y mensajes que la web. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
    onOpenActivity: (String) -> Unit,
    /** Archivos compartidos desde otra app al abrir la pantalla. */
    shared: List<Uri> = emptyList(),
    onSharedHandled: () -> Unit = {},
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val bikes by viewModel.bikes.collectAsStateWithLifecycle()
    val bike by viewModel.bike.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.add(it) }
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) {
            viewModel.add(shared)
            onSharedHandled()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = onBack) {
            Icon(VeloraIcons.Back, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.detail_back), modifier = Modifier.padding(start = 4.dp))
        }
        Text(stringResource(R.string.imports_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.imports_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)

        bikes?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.imports_bike), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bike == null, onClick = { viewModel.bike.value = null }, label = { Text(stringResource(R.string.imports_no_bike)) })
                    list.forEach { item ->
                        FilterChip(selected = bike == item.id, onClick = { viewModel.bike.value = item.id }, label = { Text(item.name) })
                    }
                }
                Text(stringResource(R.string.imports_bike_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // El selector del sistema no conoce un tipo MIME de FIT: se muestran todos y la app revisa la extensión.
        Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.imports_choose))
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.imports_queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (queue.isEmpty()) {
                Text(stringResource(R.string.imports_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val done = queue.count { ImportState.valueOf(it.state).terminal }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.imports_summary, queue.size, done, queue.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (done > 0) TextButton(onClick = viewModel::clearFinished) { Text(stringResource(R.string.imports_clear)) }
                }
                OutlinedPanel {
                    Column {
                        queue.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            ImportRow(item, onOpenActivity)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportRow(item: ImportEntity, onOpen: (String) -> Unit) {
    val state = ImportState.valueOf(item.state)
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(item.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(state.label),
                color = when (state) {
                    ImportState.REJECTED, ImportState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (item.sizeBytes > 0) {
                Text(Formats.fileSize(item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.errorCode?.let { Text(stringResource(errorMessage(it)), style = MaterialTheme.typography.bodyMedium) }
            warnings(item.warnings).forEach { (res, count) ->
                Text(
                    if (res == null) stringResource(R.string.imports_warning_possible_duplicate) else pluralStringResource(res, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.possibleDuplicates > 0 && state == ImportState.COMPLETED) {
                Text(
                    pluralStringResource(R.plurals.imports_possible_duplicate, item.possibleDuplicates, item.possibleDuplicates),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item.activityId?.takeIf { state == ImportState.COMPLETED || state == ImportState.DUPLICATE }?.let { id ->
            TextButton(onClick = { onOpen(id) }) {
                Text(stringResource(if (state == ImportState.DUPLICATE) R.string.imports_view_existing else R.string.imports_view_activity))
            }
        }
    }
}

@get:StringRes
private val ImportState.label: Int
    get() = when (this) {
        ImportState.WAITING -> R.string.imports_status_waiting
        ImportState.UPLOADING -> R.string.imports_status_uploading
        ImportState.QUEUED -> R.string.imports_status_queued
        ImportState.PROCESSING -> R.string.imports_status_processing
        ImportState.COMPLETED -> R.string.imports_status_completed
        ImportState.DUPLICATE -> R.string.imports_status_duplicate
        ImportState.REJECTED -> R.string.imports_status_rejected
        ImportState.FAILED -> R.string.imports_status_failed
    }

@StringRes
private fun errorMessage(code: String): Int = when (code) {
    "EMPTY_FILE" -> R.string.imports_error_empty_file
    "UNSUPPORTED_FORMAT" -> R.string.imports_error_unsupported_format
    "MALFORMED_FILE" -> R.string.imports_error_malformed_file
    "FORBIDDEN_XML_CONSTRUCT" -> R.string.imports_error_forbidden_xml
    "TOO_MANY_POINTS" -> R.string.imports_error_too_many_points
    "INVALID_DATA" -> R.string.imports_error_invalid_data
    "INTERNAL_ERROR", "ACTIVITY_NOT_FOUND" -> R.string.imports_error_internal
    LocalImportError.TOO_LARGE -> R.string.imports_error_too_large
    LocalImportError.WRONG_EXTENSION -> R.string.imports_error_wrong_extension
    LocalImportError.INVALID -> R.string.imports_error_invalid
    LocalImportError.UNREADABLE -> R.string.imports_error_unreadable
    else -> R.string.imports_error_unknown
}

/** `CODIGO:n,...` → (plural, n); POSSIBLE_DUPLICATE no lleva conteo (plural null). */
private fun warnings(encoded: String?): List<Pair<Int?, Int>> =
    encoded.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { part ->
        val code = part.substringBefore(':')
        val count = part.substringAfter(':').toIntOrNull() ?: 0
        val res: Int? = when (code) {
            "INVALID_COORDINATES" -> R.plurals.imports_warning_invalid_coordinates
            "GPS_JUMPS" -> R.plurals.imports_warning_gps_jumps
            "ALTITUDE_JUMPS" -> R.plurals.imports_warning_altitude_jumps
            "LONG_PAUSES" -> R.plurals.imports_warning_long_pauses
            "NEGATIVE_VALUES" -> R.plurals.imports_warning_negative_values
            "NON_MONOTONIC_TIME" -> R.plurals.imports_warning_non_monotonic_time
            "POSSIBLE_DUPLICATE" -> null
            else -> return@mapNotNull null
        }
        res to count
    }
