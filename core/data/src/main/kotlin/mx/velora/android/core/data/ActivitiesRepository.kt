package mx.velora.android.core.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.ActivitySummary
import mx.velora.android.api.models.Bike
import mx.velora.android.core.database.ActivityDao
import mx.velora.android.core.database.ActivityEntity
import mx.velora.android.core.session.SessionManager

/** Filtros del historial, los mismos que la web. Distancias en kilómetros, como las escribe el usuario. */
data class ActivityFilters(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val subType: ActivitySubType? = null,
    val bikeId: UUID? = null,
    val minKm: Double? = null,
    val maxKm: Double? = null,
) {
    val active: Int get() = listOf(from, to, subType, bikeId, minKm, maxKm).count { it != null }
    val isEmpty: Boolean get() = active == 0
}

/** Una página: o llegó (con el cursor de la siguiente) o no, y por qué. */
sealed interface PageResult {
    data class Loaded(val items: List<ActivitySummary>, val nextCursor: String?) : PageResult

    data class Failed(val reason: RefreshResult) : PageResult

    /** La API rechazó los filtros (400). */
    data object InvalidFilters : PageResult
}

class ActivitiesRepository(private val session: SessionManager, private val dao: ActivityDao) {

    /** El historial sin filtros guardado en el teléfono, de la rodada más reciente a la más antigua. */
    fun cached(): Flow<List<ActivitySummary>> = dao.observeAll().map { rows -> rows.map { it.toSummary() } }

    /**
     * Pide una página sin filtros y la guarda. Reemplaza el tramo que cubre: si algo de ese tramo ya no está en la API,
     * se borró allá y se borra aquí. La primera página cubre todo lo más reciente; la última, todo lo más antiguo.
     */
    suspend fun syncPage(cursor: String?): PageResult {
        val result = page(ActivityFilters(), cursor)
        if (result is PageResult.Loaded) {
            val items = result.items
            val newest = if (cursor == null || items.isEmpty()) Long.MAX_VALUE else items.first().epochMs()
            // Con más páginas, una rodada con el mismo instante que la última puede venir en la siguiente: no se borra.
            val oldest = when {
                result.nextCursor == null -> Long.MIN_VALUE
                items.isEmpty() -> newest
                else -> items.last().epochMs() + 1
            }
            if (cursor == null || items.isNotEmpty()) dao.replaceRange(newest, oldest, items.map { it.toEntity() })
        }
        return result
    }

    /** Búsqueda con filtros: solo en la API, no se guarda. */
    suspend fun search(filters: ActivityFilters, cursor: String?): PageResult = page(filters, cursor)

    /** Las bicicletas para el filtro; null si no se pudieron cargar. */
    suspend fun bikes(): List<Bike>? {
        var bikes: List<Bike>? = null
        fetch {
            val response = session.call { listBikes() }
            if (response.success) bikes = response.body().items
            RefreshResult.UPDATED
        }
        return bikes
    }

    private suspend fun page(filters: ActivityFilters, cursor: String?): PageResult {
        var loaded: PageResult? = null
        val outcome = fetch {
            val response = session.call {
                listActivities(
                    from = filters.from,
                    to = filters.to,
                    bikeId = filters.bikeId,
                    subType = filters.subType,
                    minDistanceM = filters.minKm?.times(1000),
                    maxDistanceM = filters.maxKm?.times(1000),
                    cursor = cursor,
                    limit = PAGE_SIZE,
                )
            }
            when {
                response.status == 400 -> loaded = PageResult.InvalidFilters
                !response.success -> return@fetch RefreshResult.FAILED
                else -> response.body().let { loaded = PageResult.Loaded(it.items, it.nextCursor) }
            }
            RefreshResult.UPDATED
        }
        return loaded ?: PageResult.Failed(outcome)
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

private fun ActivitySummary.epochMs() = startedAt.toInstant().toEpochMilli()

private fun ActivitySummary.toEntity() = ActivityEntity(
    id = id.toString(),
    title = title,
    startedAt = startedAt.toString(),
    startedAtEpochMs = epochMs(),
    localStartDate = localStartDate.toString(),
    subType = subType.value,
    distanceMeters = distanceMeters,
    movingTimeSeconds = movingTimeSeconds,
    elapsedTimeSeconds = elapsedTimeSeconds,
    elevationGainMeters = elevationGainMeters,
    averageSpeedMps = averageSpeedMps,
    bikeId = bikeId?.toString(),
)

private fun ActivityEntity.toSummary() = ActivitySummary(
    id = UUID.fromString(id),
    title = title,
    startedAt = OffsetDateTime.parse(startedAt),
    localStartDate = LocalDate.parse(localStartDate),
    subType = ActivitySubType.entries.firstOrNull { it.value == subType } ?: ActivitySubType.OTHER,
    distanceMeters = distanceMeters,
    movingTimeSeconds = movingTimeSeconds,
    elapsedTimeSeconds = elapsedTimeSeconds,
    elevationGainMeters = elevationGainMeters,
    averageSpeedMps = averageSpeedMps,
    bikeId = bikeId?.let(UUID::fromString),
)
