package mx.velora.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.velora.android.api.models.ActivityClimbs
import mx.velora.android.api.models.ActivityDetail
import mx.velora.android.api.models.ActivityLap
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.ActivityTrack
import mx.velora.android.core.data.ActivityDetailRepository
import mx.velora.android.core.data.Remote

/** Cada sección se carga por su lado: si las vueltas fallan, las métricas y las gráficas se ven igual. */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>

    data class Ready<T>(val value: T) : Section<T>

    /** No aplica a esta rodada (p. ej. sin vueltas). */
    data object Absent : Section<Nothing>

    data object Failed : Section<Nothing>
}

/** El recorrido para el mapa. */
sealed interface MapSection {
    data object Loading : MapSection

    data class Ready(val track: ActivityTrack) : MapSection

    /** Sin recorrido (rodillo, sin GPS). */
    data object None : MapSection

    /** La privacidad de inicio y fin oculta todo el recorrido. */
    data object Hidden : MapSection

    data object Failed : MapSection
}

data class DetailState(
    val detail: Remote<ActivityDetail>? = null,
    val map: MapSection = MapSection.Loading,
    val charts: Section<PreparedSeries> = Section.Loading,
    val laps: Section<List<ActivityLap>> = Section.Loading,
    val climbs: Section<ActivityClimbs> = Section.Loading,
)

class ActivityDetailViewModel(private val repository: ActivityDetailRepository, private val id: UUID) : ViewModel() {

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = DetailState()
        viewModelScope.launch {
            val detail = repository.detail(id)
            _state.update { it.copy(detail = detail) }
            val activity = (detail as? Remote.Ok)?.value ?: return@launch
            val map = async {
                if (!activity.hasTrack || activity.subType == ActivitySubType.INDOOR) {
                    MapSection.None
                } else {
                    when (val result = repository.track(id)) {
                        is Remote.Ok -> MapSection.Ready(result.value)
                        Remote.NotFound -> MapSection.Hidden
                        is Remote.Failed -> MapSection.Failed
                    }
                }
            }
            val charts = async {
                when (val result = repository.streams(id)) {
                    // Preparar 14 000 muestras por serie es trabajo de CPU: fuera del hilo principal.
                    is Remote.Ok -> Section.Ready(
                        withContext(Dispatchers.Default) {
                            prepareSeries(result.value.streams, result.value.sampleCount)
                        },
                    ).takeUnless { it.value.isEmpty } ?: Section.Absent
                    else -> Section.Failed
                }
            }
            val laps = async {
                if (activity.lapCount == 0) {
                    Section.Absent
                } else {
                    when (val result = repository.laps(id)) {
                        is Remote.Ok -> if (result.value.isEmpty()) Section.Absent else Section.Ready(result.value)
                        else -> Section.Failed
                    }
                }
            }
            val climbs = async {
                when (val result = repository.climbs(id)) {
                    is Remote.Ok -> if (result.value.available) Section.Ready(result.value) else Section.Absent
                    else -> Section.Failed
                }
            }
            _state.update { it.copy(map = map.await()) }
            _state.update { it.copy(charts = charts.await()) }
            _state.update { it.copy(laps = laps.await(), climbs = climbs.await()) }
        }
    }
}
