package mx.velora.android.core.data

import mx.velora.android.api.models.ActivitySubType
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

    /** Hay rodadas, pero ninguna reciente con GPS (p. ej., todas en rodillo): la vista queda en México. */
    data object NoGps : ExplorerFocus

    /** Aún no hay rodadas. */
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

    /** La vista empieza en la zona de la rodada más reciente con GPS, como en la web. */
    suspend fun focus(): Remote<ExplorerFocus> {
        val items = when (val page = remote(session) { listActivities(null, null, null, null, null, null, null, FOCUS_CANDIDATES) }) {
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
