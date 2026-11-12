package mx.velora.android.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mx.velora.android.api.models.ActivitySummary
import mx.velora.android.api.models.Bike
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.core.data.PageResult
import mx.velora.android.core.data.RefreshResult

enum class ListError { OFFLINE, FAILED, INVALID_FILTERS }

data class ActivitiesState(
    val filters: ActivityFilters = ActivityFilters(),
    val items: List<ActivitySummary> = emptyList(),
    /** Aún no se lee la caché ni llega la primera página: no hay nada que decidir. */
    val pending: Boolean = true,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    /** Falla de la primera página (o de la actualización). */
    val error: ListError? = null,
    val moreFailed: Boolean = false,
)

/**
 * Sin filtros, la lista sale de la caché (se ve sin conexión) y cada página descargada la actualiza. Con filtros, la
 * lista vive en memoria y solo viene de la API, como en la web.
 */
class ActivitiesViewModel(private val repository: ActivitiesRepository) : ViewModel() {

    private data class Ui(
        val filters: ActivityFilters = ActivityFilters(),
        /** Resultado de la búsqueda con filtros; null mientras llega la primera página (sin filtros se usa la caché). */
        val searched: List<ActivitySummary>? = null,
        val nextCursor: String? = null,
        val firstPageDone: Boolean = false,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: ListError? = null,
        val moreFailed: Boolean = false,
    )

    private val ui = MutableStateFlow(Ui())
    private var job: Job? = null

    private val _bikes = MutableStateFlow<List<Bike>?>(null)
    /** Para el filtro de bicicleta; null mientras no se cargan o si fallaron. */
    val bikes: StateFlow<List<Bike>?> = _bikes

    val state: StateFlow<ActivitiesState> =
        combine(repository.cached().map<List<ActivitySummary>, List<ActivitySummary>?> { it }.onStart { emit(null) }, ui) { cached, ui ->
            val filtered = !ui.filters.isEmpty
            ActivitiesState(
                filters = ui.filters,
                items = if (filtered) ui.searched.orEmpty() else cached.orEmpty(),
                pending = if (filtered) {
                    ui.searched == null
                } else {
                    cached == null || (cached.isEmpty() && !ui.firstPageDone && ui.error == null)
                },
                loading = ui.loading,
                loadingMore = ui.loadingMore,
                canLoadMore = ui.nextCursor != null && ui.error == null,
                error = ui.error,
                moreFailed = ui.moreFailed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesState())

    init {
        refresh()
    }

    fun refresh() = load(ui.value.filters)

    fun apply(filters: ActivityFilters) = load(filters)

    fun loadMore() {
        val current = ui.value
        val cursor = current.nextCursor ?: return
        if (current.loadingMore || current.loading) return
        job = viewModelScope.launch {
            ui.update { it.copy(loadingMore = true, moreFailed = false) }
            val result = fetch(current.filters, cursor)
            ui.update { state ->
                when (result) {
                    is PageResult.Loaded -> state.copy(
                        loadingMore = false,
                        nextCursor = result.nextCursor,
                        searched = state.searched?.plus(result.items),
                    )
                    else -> state.copy(loadingMore = false, moreFailed = true)
                }
            }
        }
    }

    fun loadBikes() {
        if (_bikes.value != null) return
        viewModelScope.launch { _bikes.value = repository.bikes() }
    }

    private fun load(filters: ActivityFilters) {
        job?.cancel()
        ui.update {
            it.copy(
                filters = filters,
                // Al actualizar con los mismos filtros, la lista sigue a la vista mientras llega la nueva.
                searched = it.searched.takeIf { _ -> it.filters == filters && !filters.isEmpty },
                loading = true,
                loadingMore = false,
                moreFailed = false,
                error = null,
            )
        }
        job = viewModelScope.launch {
            val result = fetch(filters, cursor = null)
            ui.update { state ->
                when (result) {
                    is PageResult.Loaded -> state.copy(
                        loading = false,
                        firstPageDone = true,
                        nextCursor = result.nextCursor,
                        searched = if (filters.isEmpty) null else result.items,
                    )
                    is PageResult.Failed -> state.copy(
                        loading = false,
                        firstPageDone = true,
                        nextCursor = null,
                        error = if (result.reason == RefreshResult.OFFLINE) ListError.OFFLINE else ListError.FAILED,
                        searched = if (filters.isEmpty) null else emptyList(),
                    )
                    PageResult.InvalidFilters -> state.copy(
                        loading = false,
                        firstPageDone = true,
                        nextCursor = null,
                        error = ListError.INVALID_FILTERS,
                        searched = emptyList(),
                    )
                }
            }
        }
    }

    private suspend fun fetch(filters: ActivityFilters, cursor: String?): PageResult =
        if (filters.isEmpty) repository.syncPage(cursor) else repository.search(filters, cursor)
}
