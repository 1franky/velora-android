package mx.velora.android.core.data

import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.Bike
import mx.velora.android.api.models.ExplorationCells
import mx.velora.android.api.models.ExplorationSummary
import mx.velora.android.api.models.LargestSquare
import mx.velora.android.core.session.SessionManager

/** Rejilla de celdas de exploración, con su nombre en la API (ADR-018). */
enum class CellGrid(val api: String) {
    /** Cuadros de 1 km (EPSG:6372, solo México). */
    SQUARES("MX1KM"),

    /** Hexágonos H3 de resolución 9. */
    HEXAGONS("H3"),
}

/** Dónde empieza la vista del explorador. */
sealed interface ExplorerFocus {
    /** Recuadro (oeste, sur, este, norte), ya recortado por la privacidad, de la rodada más reciente con GPS. */
    data class Bounds(val bbox: List<Double>) : ExplorerFocus

    /** Hay rodadas (que cumplen los filtros), pero ninguna reciente con GPS (p. ej., en rodillo): la vista queda en México. */
    data object NoGps : ExplorerFocus

    /** Ninguna rodada (que cumpla los filtros). */
    data object Empty : ExplorerFocus
}

/**
 * Explorador (ADR-017, ADR-018), como en la web: el mapa de calor llega en teselas que MapLibre pide directo a la API;
 * aquí, lo demás. Nada se guarda en el teléfono: sin conexión, la pantalla lo dice.
 */
class ExplorerRepository(private val session: SessionManager) {

    suspend fun summary(): Remote<ExplorationSummary> = remote(session) { getExplorationSummary() }

    suspend fun largestSquare(): Remote<LargestSquare> = remote(session) { getLargestSquare() }

    /** Celdas visitadas dentro de [bbox] (oeste, sur, este, norte); hasta 5 000, primero las más recorridas. */
    suspend fun cells(grid: CellGrid, bbox: List<Double>): Remote<ExplorationCells> =
        remote(session) { listExplorationCells(bboxParam(bbox), grid.api) }

    /** Las bicicletas para el filtro; null si no se pudieron cargar. */
    suspend fun bikes(): List<Bike>? = loadBikes(session)

    /**
     * La vista empieza en la zona de la rodada más reciente con GPS que cumple [filters], como en la web. Las distancias
     * no aplican al mapa de calor y se ignoran.
     */
    suspend fun focus(filters: ActivityFilters = ActivityFilters()): Remote<ExplorerFocus> {
        val page = remote(session) {
            listActivities(filters.from, filters.to, filters.bikeId, filters.subType, null, null, null, FOCUS_CANDIDATES)
        }
        val items = when (page) {
            is Remote.Ok -> page.value.items
            Remote.NotFound -> return Remote.NotFound
            is Remote.Failed -> return page
        }
        if (items.isEmpty()) return Remote.Ok(ExplorerFocus.Empty)
        val ride = items.firstOrNull { it.subType != ActivitySubType.INDOOR } ?: return Remote.Ok(ExplorerFocus.NoGps)
        return when (val track = remote(session) { getActivityTrack(ride.id, FOCUS_ZOOM) }) {
            is Remote.Ok -> Remote.Ok(ExplorerFocus.Bounds(track.value.bbox))
            // Sin recorrido (o todo oculto por la privacidad): no hay zona que encuadrar.
            Remote.NotFound -> Remote.Ok(ExplorerFocus.NoGps)
            is Remote.Failed -> track
        }
    }

    companion object {
        /** Recorrido burdo: solo se usa su recuadro. */
        const val FOCUS_ZOOM = 8
        const val FOCUS_CANDIDATES = 20

        /** `oeste,sur,este,norte` con 5 decimales (~1 m), como la web. */
        fun bboxParam(bbox: List<Double>): String = bbox.joinToString(",") { String.format(java.util.Locale.ROOT, "%.5f", it) }
    }
}

/** Consulta de las teselas del mapa de calor (`from`, `to`, `subType`, `bikeId`), como `heatmapQuery` de la web. */
fun heatmapQuery(filters: ActivityFilters): String = buildList {
    filters.from?.let { add("from=$it") }
    filters.to?.let { add("to=$it") }
    filters.subType?.let { add("subType=${it.value}") }
    filters.bikeId?.let { add("bikeId=$it") }
}.joinToString("&")
