package mx.velora.android.core.data

import kotlinx.coroutines.flow.Flow
import mx.velora.android.core.database.SyncDao
import mx.velora.android.core.database.SyncStateEntity

/**
 * Actualiza lo que el teléfono guarda para verse sin conexión: el resumen de Inicio (los cuatro periodos) y la primera
 * página del historial. La API es la fuente de verdad (07-android-application.md §4); las importaciones tienen su
 * propio trabajo.
 */
class Synchronizer(
    private val dashboard: DashboardRepository,
    private val activities: ActivitiesRepository,
    private val dao: SyncDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun state(): Flow<SyncStateEntity?> = dao.observe()

    suspend fun sync(): RefreshResult {
        val results = buildList {
            // El historial primero: si la sesión terminó, no tiene caso seguir.
            add(
                when (val page = activities.syncPage(null)) {
                    is PageResult.Loaded -> RefreshResult.UPDATED
                    is PageResult.Failed -> page.reason
                    PageResult.InvalidFilters -> RefreshResult.FAILED
                },
            )
            if (last() != RefreshResult.SESSION_ENDED) Period.entries.forEach { add(dashboard.refresh(it)) }
        }
        // El peor resultado manda: la sesión terminada, luego sin red, luego un error de la API.
        val outcome = listOf(RefreshResult.SESSION_ENDED, RefreshResult.OFFLINE, RefreshResult.FAILED).firstOrNull { it in results }
            ?: RefreshResult.UPDATED
        if (outcome != RefreshResult.SESSION_ENDED) {
            val previous = dao.get()
            val time = now()
            dao.upsert(
                SyncStateEntity(
                    lastSuccessEpochMs = if (outcome == RefreshResult.UPDATED) time else previous?.lastSuccessEpochMs,
                    lastAttemptEpochMs = time,
                    lastResult = outcome.name,
                ),
            )
        }
        return outcome
    }
}
