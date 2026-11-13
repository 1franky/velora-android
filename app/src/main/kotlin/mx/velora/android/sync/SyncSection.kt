package mx.velora.android.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mx.velora.android.R
import mx.velora.android.core.data.RefreshResult
import mx.velora.android.core.data.Synchronizer
import mx.velora.android.core.database.SyncStateEntity
import mx.velora.android.ui.Formats

class SyncViewModel(private val synchronizer: Synchronizer) : ViewModel() {
    val state: StateFlow<SyncStateEntity?> =
        synchronizer.state().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Resultado de la sincronización que pidió el usuario en esta pantalla (no el de las automáticas). */
    private val _result = MutableStateFlow<RefreshResult?>(null)
    val result: StateFlow<RefreshResult?> = _result

    fun syncNow() {
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            _result.value = null
            _result.value = synchronizer.sync()
            _running.value = false
        }
    }
}

/** Configuración › Sincronización: cuándo fue la última y un botón para hacerla ahora. */
@Composable
fun SyncSection(viewModel: SyncViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_sync),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Text(stringResource(R.string.settings_sync_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            state?.lastSuccessEpochMs?.let { stringResource(R.string.settings_sync_last, Formats.dateTime(it)) }
                ?: stringResource(R.string.settings_sync_never),
        )
        OutlinedButton(onClick = viewModel::syncNow, enabled = !running) {
            Text(stringResource(if (running) R.string.settings_sync_running else R.string.settings_sync_now))
        }
        val message = when (result) {
            RefreshResult.UPDATED -> R.string.settings_sync_done
            RefreshResult.OFFLINE -> R.string.settings_sync_offline
            RefreshResult.FAILED -> R.string.settings_sync_failed
            // Sin sesión la app vuelve sola al login.
            RefreshResult.SESSION_ENDED, null -> null
        }
        message?.let {
            Text(
                stringResource(it),
                color = if (result == RefreshResult.UPDATED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
