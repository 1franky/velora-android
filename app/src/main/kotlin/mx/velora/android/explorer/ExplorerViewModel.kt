package mx.velora.android.explorer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mx.velora.android.R
import mx.velora.android.api.models.Bike
import mx.velora.android.api.models.ExplorationCells
import mx.velora.android.api.models.ExplorationSummary
import mx.velora.android.api.models.LargestSquare
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.core.data.CellGrid
import mx.velora.android.core.data.ExplorerFocus
import mx.velora.android.core.data.ExplorerRepository
import mx.velora.android.core.data.RefreshResult
import mx.velora.android.core.data.Remote
import mx.velora.android.detail.Section

/** Qué se dibuja además de los recorridos; las celdas solo desde [minZoom] (antes serían demasiadas), como la web. */
enum class ExplorerLayer(@StringRes val label: Int, val grid: CellGrid?, val minZoom: Double) {
    TRACKS(R.string.explorer_layer_tracks, null, 0.0),
    SQUARES(R.string.explorer_layer_squares, CellGrid.SQUARES, 7.0),
    HEXAGONS(R.string.explorer_layer_hexagons, CellGrid.HEXAGONS, 10.0),
}

enum class CellsNote { NONE, ZOOM_IN, TRUNCATED, UNAVAILABLE }

/** Lo que el mapa muestra: zoom y recuadro (oeste, sur, este, norte). */
data class Viewport(val zoom: Double, val bbox: List<Double>)

/** A dónde mover la cámara; [id] distinto = movimiento nuevo (el mapa aplica cada uno una sola vez). */
data class CameraTarget(val bbox: List<Double>, val maxZoom: Double, val id: Int)

data class ExplorerState(
    /** Filtros de las líneas del mapa (sin distancias: no aplican); las celdas y las cifras son de todo el historial. */
    val filters: ActivityFilters = ActivityFilters(),
    val focus: Section<ExplorerFocus> = Section.Loading,
    /** No hay conexión: sin servidor no hay teselas ni cifras, la pantalla lo dice una vez. */
    val offline: Boolean = false,
    val summary: Section<ExplorationSummary> = Section.Loading,
    val square: Section<LargestSquare> = Section.Loading,
    val layer: ExplorerLayer = ExplorerLayer.TRACKS,
    /** Celdas a dibujar (de la rejilla de [layer]); null, ninguna. */
    val cells: ExplorationCells? = null,
    val note: CellsNote = CellsNote.NONE,
    val camera: CameraTarget? = null,
) {
    /** Recuadro del mayor cuadrado, si hay uno. */
    val squareBbox: List<Double>?
        get() = (square as? Section.Ready)?.value?.takeIf { it.propertySize > 0 }?.bbox
}

/**
 * Explorador: encuadre inicial, cifras y celdas. Las celdas se piden para lo que se ve cada vez que el mapa se detiene;
 * una respuesta que llega después de otra vista o de otra capa se descarta.
 */
class ExplorerViewModel(private val repository: ExplorerRepository) : ViewModel() {

    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()
    private var jobs = emptyList<Job>()
    private var cellsJob: Job? = null
    private var viewport: Viewport? = null
    private var focusJob: Job? = null
    private val _bikes = MutableStateFlow<List<Bike>?>(null)

    /** Las bicicletas para el filtro; null mientras no se carguen (o si falló). */
    val bikes: StateFlow<List<Bike>?> = _bikes.asStateFlow()

    init {
        load()
    }

    fun load() {
        jobs.forEach { it.cancel() }
        _state.update { ExplorerState(filters = it.filters, layer = it.layer, camera = it.camera) }
        loadFocus()
        jobs = listOf(
            viewModelScope.launch { repository.summary().let { r -> _state.update { it.copy(summary = section(r)) } } },
            viewModelScope.launch { repository.largestSquare().let { r -> _state.update { it.copy(square = section(r)) } } },
        )
    }

    /** Nuevos filtros: otras líneas y la vista va a la rodada más reciente que los cumple. Las celdas no cambian. */
    fun apply(filters: ActivityFilters) {
        val lines = filters.copy(minKm = null, maxKm = null)
        if (lines == _state.value.filters) return
        _state.update { it.copy(filters = lines, focus = Section.Loading) }
        loadFocus()
    }

    fun loadBikes() {
        if (_bikes.value != null) return
        viewModelScope.launch { _bikes.value = repository.bikes() }
    }

    private fun loadFocus() {
        focusJob?.cancel()
        val filters = _state.value.filters
        focusJob = viewModelScope.launch {
            val focus = repository.focus(filters)
            _state.update { state ->
                if (state.filters != filters) return@update state
                when (focus) {
                    is Remote.Ok -> {
                        val bounds = focus.value as? ExplorerFocus.Bounds
                        state.copy(
                            focus = Section.Ready(focus.value),
                            offline = false,
                            camera = bounds?.let { CameraTarget(it.bbox, FOCUS_MAX_ZOOM, (state.camera?.id ?: 0) + 1) } ?: state.camera,
                        )
                    }
                    // Sin poder encuadrar, el mapa se queda donde está (al abrir, en México).
                    else -> state.copy(focus = Section.Failed, offline = (focus as? Remote.Failed)?.reason == RefreshResult.OFFLINE)
                }
            }
        }
    }

    fun select(layer: ExplorerLayer) {
        if (layer == _state.value.layer) return
        _state.update { it.copy(layer = layer) }
        refreshCells()
    }

    fun viewport(viewport: Viewport) {
        this.viewport = viewport
        refreshCells()
    }

    /** Lleva el mapa al mayor cuadrado, con los cuadros visibles. */
    fun showSquare() {
        val bbox = _state.value.squareBbox ?: return
        _state.update { it.copy(layer = ExplorerLayer.SQUARES, camera = CameraTarget(bbox, SQUARE_MAX_ZOOM, (it.camera?.id ?: 0) + 1)) }
        refreshCells()
    }

    private fun refreshCells() {
        cellsJob?.cancel()
        val layer = _state.value.layer
        val grid = layer.grid
        val view = viewport
        when {
            grid == null -> _state.update { it.copy(cells = null, note = CellsNote.NONE) }
            view == null -> _state.update { it.copy(cells = it.cells.takeIf { c -> c?.grid?.value == grid.api }) }
            view.zoom < layer.minZoom -> _state.update { it.copy(cells = null, note = CellsNote.ZOOM_IN) }
            else -> {
                // Mientras llegan, se conservan las de la misma rejilla (la vista cambió poco); las de otra, no.
                _state.update { it.copy(cells = it.cells.takeIf { c -> c?.grid?.value == grid.api }) }
                cellsJob = viewModelScope.launch {
                    val result = repository.cells(grid, view.bbox)
                    _state.update {
                        if (it.layer != layer) return@update it
                        when (result) {
                            is Remote.Ok -> it.copy(
                                cells = result.value,
                                note = if (result.value.truncated) CellsNote.TRUNCATED else CellsNote.NONE,
                            )
                            else -> it.copy(note = CellsNote.UNAVAILABLE)
                        }
                    }
                }
            }
        }
    }

    private fun <T> section(result: Remote<T>): Section<T> = if (result is Remote.Ok) Section.Ready(result.value) else Section.Failed

    companion object {
        /** Igual que la web: encuadrar una rodada sin acercarse de más. */
        const val FOCUS_MAX_ZOOM = 12.0
        const val SQUARE_MAX_ZOOM = 14.0
    }
}
