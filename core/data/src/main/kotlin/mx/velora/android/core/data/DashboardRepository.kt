package mx.velora.android.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import mx.velora.android.api.models.DashboardSummary
import mx.velora.android.core.database.DashboardCacheEntity
import mx.velora.android.core.database.DashboardDao
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionExpiredException
import mx.velora.android.core.session.SessionManager

/** Periodos de Inicio, con su nombre en la API. */
enum class Period(val api: String) {
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
    ALL("all"),
}

/** Un resumen y cuándo se descargó: si viene de la caché, la pantalla dice de cuándo es. */
data class Cached<T>(val value: T, val fetchedAtEpochMs: Long)

/** Resultado de pedir datos frescos. Los datos llegan por el Flow de la caché, no por aquí. */
enum class RefreshResult {
    UPDATED,

    /** Sin conexión o el servidor no respondió. */
    OFFLINE,

    /** La API respondió un error. */
    FAILED,

    /** Ya no hay sesión: la app vuelve al login sola. */
    SESSION_ENDED,
}

class DashboardRepository(
    private val session: SessionManager,
    private val dao: DashboardDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun summary(period: Period): Flow<Cached<DashboardSummary>?> =
        dao.observe(period.api).map { cached ->
            cached?.let { entity ->
                // Una caché que ya no corresponde al contrato (app actualizada) equivale a no tener caché.
                decode(entity.json)?.let { Cached(it, entity.fetchedAtEpochMs) }
            }
        }

    suspend fun refresh(period: Period): RefreshResult = fetch {
        val response = session.call { getDashboardSummary(period.api, null) }
        if (!response.success) return@fetch RefreshResult.FAILED
        val summary = response.body()
        dao.upsert(DashboardCacheEntity(period.api, VeloraApi.json.encodeToString(DashboardSummary.serializer(), summary), now()))
        RefreshResult.UPDATED
    }

    private fun decode(json: String): DashboardSummary? =
        try {
            VeloraApi.json.decodeFromString(DashboardSummary.serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** Traduce las fallas de una descarga a [RefreshResult]. */
internal suspend fun fetch(block: suspend () -> RefreshResult): RefreshResult =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: SessionExpiredException) {
        RefreshResult.SESSION_ENDED
    } catch (_: IOException) {
        RefreshResult.OFFLINE
    } catch (_: Exception) {
        // Un cuerpo que no corresponde al contrato (Ktor lo envuelve en su propia excepción de conversión).
        RefreshResult.FAILED
    }
