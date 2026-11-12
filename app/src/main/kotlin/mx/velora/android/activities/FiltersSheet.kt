package mx.velora.android.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import mx.velora.android.R
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.Bike
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.home.label
import mx.velora.android.ui.Formats

/** Borrador de filtros: se aplica solo con "Aplicar filtros". Mismos campos que la web. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FiltersSheet(current: ActivityFilters, bikes: List<Bike>?, onApply: (ActivityFilters) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(current) }
    var minKm by remember { mutableStateOf(current.minKm?.let(::plain).orEmpty()) }
    var maxKm by remember { mutableStateOf(current.maxKm?.let(::plain).orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<DateField?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.activities_filters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )

            Section(R.string.activities_filter_type) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.subType == null,
                        onClick = { draft = draft.copy(subType = null) },
                        label = { Text(stringResource(R.string.activities_filter_any_type)) },
                    )
                    ActivitySubType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.subType == type,
                            onClick = { draft = draft.copy(subType = type) },
                            label = { Text(stringResource(type.label)) },
                        )
                    }
                }
            }

            Section(R.string.activities_filter_bike) {
                when {
                    bikes == null -> Text(stringResource(R.string.activities_filter_bikes_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.bikeId == null,
                            onClick = { draft = draft.copy(bikeId = null) },
                            label = { Text(stringResource(R.string.activities_filter_any_bike)) },
                        )
                        bikes.forEach { bike ->
                            FilterChip(
                                selected = draft.bikeId == bike.id,
                                onClick = { draft = draft.copy(bikeId = bike.id) },
                                label = { Text(bike.name) },
                            )
                        }
                    }
                }
            }

            DateField.entries.forEach { field ->
                val value = field.get(draft)
                val name = stringResource(field.label)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = field }) {
                        Text(
                            if (value == null) {
                                stringResource(R.string.activities_filter_date_any, name)
                            } else {
                                stringResource(R.string.activities_filter_date_value, name, Formats.date(value))
                            },
                        )
                    }
                    if (value != null) {
                        TextButton(onClick = { draft = field.set(draft, null) }) {
                            Text(stringResource(R.string.activities_filter_clear_date))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = minKm,
                onValueChange = { minKm = it },
                label = { Text(stringResource(R.string.activities_filter_min_km)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxKm,
                onValueChange = { maxKm = it },
                label = { Text(stringResource(R.string.activities_filter_max_km)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            if (invalid) {
                Text(
                    stringResource(R.string.activities_filter_check),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(
                onClick = {
                    val filters = validated(draft, minKm, maxKm)
                    invalid = filters == null
                    filters?.let(onApply)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.activities_filter_apply)) }
            OutlinedButton(onClick = { onApply(ActivityFilters()) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.activities_filter_clear))
            }
        }
    }

    picking?.let { field ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = field.get(draft)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    // El selector trabaja en UTC: la fecha elegida es la de calendario, sin zona.
                    val date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    draft = field.set(draft, date)
                    picking = null
                }) { Text(stringResource(R.string.date_picker_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.date_picker_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        content()
    }
}

private enum class DateField(val label: Int) {
    FROM(R.string.activities_filter_from),
    TO(R.string.activities_filter_to);

    fun get(filters: ActivityFilters): LocalDate? = if (this == FROM) filters.from else filters.to

    fun set(filters: ActivityFilters, date: LocalDate?) = if (this == FROM) filters.copy(from = date) else filters.copy(to = date)
}

/** Kilómetros con punto o coma decimal; null si algo no cuadra (números inválidos o rangos al revés). */
internal fun validated(draft: ActivityFilters, minKm: String, maxKm: String): ActivityFilters? {
    fun parse(text: String): Result<Double?> {
        val clean = text.trim().replace(',', '.')
        if (clean.isEmpty()) return Result.success(null)
        val value = clean.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() } ?: return Result.failure(IllegalArgumentException())
        return Result.success(value)
    }
    val min = parse(minKm).getOrElse { return null }
    val max = parse(maxKm).getOrElse { return null }
    if (min != null && max != null && min > max) return null
    if (draft.from != null && draft.to != null && draft.from!! > draft.to!!) return null
    return draft.copy(minKm = min, maxKm = max)
}

private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
