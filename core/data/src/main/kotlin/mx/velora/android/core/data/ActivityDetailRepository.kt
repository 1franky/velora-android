package mx.velora.android.core.data

import java.util.UUID
import mx.velora.android.api.apis.DefaultApi
import mx.velora.android.api.infrastructure.HttpResponse
import mx.velora.android.api.models.ActivityClimbs
import mx.velora.android.api.models.ActivityDetail
import mx.velora.android.api.models.ActivityLap
import mx.velora.android.api.models.ActivityStreams
import mx.velora.android.api.models.ActivityTrack
import mx.velora.android.api.models.StreamType
import mx.velora.android.core.session.SessionManager

/** Resultado de una lectura que no se guarda en el teléfono. */
sealed interface Remote<out T> {
    data class Ok<T>(val value: T) : Remote<T>

    /** La actividad ya no existe (se borró en otro lado). */
    data object NotFound : Remote<Nothing>

    data class Failed(val reason: RefreshResult) : Remote<Nothing>
}

/** Detalle de una rodada: se pide a la API cada vez que se abre. */
class ActivityDetailRepository(private val session: SessionManager) {

    suspend fun detail(id: UUID): Remote<ActivityDetail> = load { getActivity(id) }

    /** Series para las gráficas, **sin posiciones**: las coordenadas solo se piden para el mapa, como recorrido. */
    suspend fun streams(id: UUID): Remote<ActivityStreams> = load { getActivityStreams(id, CHART_TYPES) }

    suspend fun laps(id: UUID): Remote<List<ActivityLap>> = when (val result = load { getActivityLaps(id) }) {
        is Remote.Ok -> Remote.Ok(result.value.items)
        is Remote.Failed -> result
        Remote.NotFound -> Remote.NotFound
    }

    suspend fun climbs(id: UUID): Remote<ActivityClimbs> = load { getActivityClimbs(id) }

    /**
     * Recorrido simplificado para [zoom], ya recortado por la privacidad de inicio y fin (ADR-016). [Remote.NotFound]
     * aquí significa que la privacidad lo oculta completo (la rodada es más corta que lo que se oculta).
     */
    suspend fun track(id: UUID, zoom: Int = TRACK_ZOOM): Remote<ActivityTrack> = load { getActivityTrack(id, zoom) }

    private suspend fun <T : Any> load(request: suspend DefaultApi.() -> HttpResponse<T>): Remote<T> {
        var value: Remote<T>? = null
        val outcome = fetch {
            val response = session.call(request)
            when {
                response.status == 404 -> value = Remote.NotFound
                !response.success -> return@fetch RefreshResult.FAILED
                else -> value = Remote.Ok(response.body())
            }
            RefreshResult.UPDATED
        }
        return value ?: Remote.Failed(outcome)
    }

    companion object {
        /** Detalle suficiente para un mapa de ~400 dp con el recorrido completo. */
        const val TRACK_ZOOM = 14

        val CHART_TYPES = listOf(
            StreamType.TIME,
            StreamType.DISTANCE,
            StreamType.ALTITUDE,
            StreamType.SPEED,
            StreamType.HEART_RATE,
            StreamType.POWER,
            StreamType.CADENCE,
            StreamType.TEMPERATURE,
        ).joinToString(",") { it.value }
    }
}
