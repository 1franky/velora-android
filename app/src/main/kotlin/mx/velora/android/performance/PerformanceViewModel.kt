package mx.velora.android.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mx.velora.android.api.models.Eddington
import mx.velora.android.api.models.Timeseries
import mx.velora.android.core.data.Grouping
import mx.velora.android.core.data.PerformanceRepository
import mx.velora.android.core.data.RefreshResult
import mx.velora.android.core.data.Remote
import mx.velora.android.core.network.HeartRateZones
import mx.velora.android.core.network.Records
import mx.velora.android.core.network.TrainingLoad
import mx.velora.android.detail.Section

data class PerformanceState(
    val grouping: Grouping = Grouping.WEEK,
    val includeIndoor: Boolean = false,
    val trends: Section<Timeseries> = Section.Loading,
    val records: Section<Records> = Section.Loading,
    val eddington: Section<Eddington> = Section.Loading,
    val heartRate: Section<HeartRateZones> = Section.Loading,
    val load: Section<TrainingLoad> = Section.Loading,
    /** Ninguna sección llegó porque no hay conexión: la pantalla lo dice una vez, no en cada sección. */
    val offline: Boolean = false,
)

/** Cada sección se pide por su lado: si una falla, las demás se ven. Cambiar la agrupación solo vuelve a pedir tendencias. */
class PerformanceViewModel(private val repository: PerformanceRepository) : ViewModel() {

    private val _state = MutableStateFlow(PerformanceState())
    val state: StateFlow<PerformanceState> = _state.asStateFlow()
    private var jobs = emptyList<Job>()

    init {
        load()
    }

    fun group(grouping: Grouping) {
        if (grouping == _state.value.grouping) return
        _state.update { it.copy(grouping = grouping, trends = Section.Loading) }
        viewModelScope.launch {
            val trends = section(repository.timeseries(grouping, _state.value.includeIndoor))
            _state.update { if (it.grouping == grouping) it.copy(trends = trends) else it }
        }
    }

    fun includeIndoor(include: Boolean) {
        if (include == _state.value.includeIndoor) return
        _state.update { it.copy(includeIndoor = include) }
        load()
    }

    fun load() {
        jobs.forEach { it.cancel() }
        val (grouping, indoor) = _state.value.let { it.grouping to it.includeIndoor }
        _state.update { PerformanceState(grouping = grouping, includeIndoor = indoor) }
        val results = mutableListOf<Remote<*>>()
        fun <T> launch(request: suspend () -> Remote<T>, apply: (PerformanceState, Section<T>) -> PerformanceState) =
            viewModelScope.launch {
                val result = request()
                synchronized(results) { results += result }
                _state.update { state ->
                    val offline = synchronized(results) {
                        results.size == SECTIONS && results.all { it is Remote.Failed && it.reason == RefreshResult.OFFLINE }
                    }
                    apply(state, section(result)).copy(offline = offline)
                }
            }
        jobs = listOf(
            launch({ repository.timeseries(grouping, indoor) }) { s, v -> s.copy(trends = v) },
            launch({ repository.records(indoor) }) { s, v -> s.copy(records = v) },
            launch({ repository.eddington(indoor) }) { s, v -> s.copy(eddington = v) },
            launch({ repository.heartRate(indoor) }) { s, v -> s.copy(heartRate = v) },
            launch({ repository.trainingLoad(indoor) }) { s, v -> s.copy(load = v) },
        )
    }

    private fun <T> section(result: Remote<T>): Section<T> = when (result) {
        is Remote.Ok -> Section.Ready(result.value)
        else -> Section.Failed
    }

    private companion object {
        const val SECTIONS = 5
    }
}
