package mx.velora.android.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mx.velora.android.api.models.DashboardSummary
import mx.velora.android.core.data.Cached
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.data.Period
import mx.velora.android.core.data.RefreshResult

data class HomeState(
    val period: Period = Period.MONTH,
    /** Todavía no se lee la caché: no hay nada que decidir (ni vacío ni error). */
    val reading: Boolean = true,
    val summary: Cached<DashboardSummary>? = null,
    val refreshing: Boolean = false,
    /** Resultado de la última descarga; null mientras no termina la primera. */
    val lastRefresh: RefreshResult? = null,
)

/** Muestra la caché de inmediato y pide un resumen fresco al abrir, al cambiar de periodo y al deslizar. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repository: DashboardRepository) : ViewModel() {

    private val period = MutableStateFlow(Period.MONTH)
    private val refresh = MutableStateFlow(RefreshStatus())
    private var job: Job? = null

    private data class RefreshStatus(val running: Boolean = false, val result: RefreshResult? = null)

    val state: StateFlow<HomeState> =
        combine(
            period.flatMapLatest { selected ->
                repository.summary(selected).map { HomeState(period = selected, reading = false, summary = it) }
                    .onStart { emit(HomeState(period = selected)) }
            },
            refresh,
        ) { cache, status -> cache.copy(refreshing = status.running, lastRefresh = status.result) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        refresh()
    }

    fun select(selected: Period) {
        if (selected == period.value) return
        period.value = selected
        refresh()
    }

    fun refresh() {
        job?.cancel()
        val selected = period.value
        job = viewModelScope.launch {
            refresh.value = RefreshStatus(running = true)
            refresh.value = RefreshStatus(result = repository.refresh(selected))
        }
    }
}
