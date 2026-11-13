package mx.velora.android.core.data

import mx.velora.android.api.models.Eddington
import mx.velora.android.api.models.Timeseries
import mx.velora.android.core.network.HeartRateZones
import mx.velora.android.core.network.Records
import mx.velora.android.core.network.TrainingLoad
import mx.velora.android.core.session.SessionManager

/** Agrupación de las tendencias, con su nombre en la API. */
enum class Grouping(val api: String) {
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
}

/**
 * Rendimiento (ADR-020): tendencias, récords, Eddington, zonas cardiacas y carga estimada. Se calcula en la API sobre
 * todo el historial y no se guarda en el teléfono: sin conexión, la pantalla lo dice. Los periodos son los que la API
 * usa por omisión (los mismos que la web).
 */
class PerformanceRepository(private val session: SessionManager) {

    suspend fun timeseries(grouping: Grouping, includeIndoor: Boolean): Remote<Timeseries> =
        remote(session) { getTimeseries(grouping.api, null, null, includeIndoor, null, null) }

    suspend fun records(includeIndoor: Boolean): Remote<Records> = remote(session) { getRecords(includeIndoor, null) }

    suspend fun eddington(includeIndoor: Boolean): Remote<Eddington> = remote(session) { getEddington(includeIndoor, null) }

    suspend fun heartRate(includeIndoor: Boolean): Remote<HeartRateZones> =
        remote(session) { getHeartRateZones(null, null, includeIndoor, null) }

    suspend fun trainingLoad(includeIndoor: Boolean): Remote<TrainingLoad> =
        remote(session) { getTrainingLoad(TRAINING_WEEKS, includeIndoor, null) }

    companion object {
        /** Las mismas 12 semanas que la web. */
        const val TRAINING_WEEKS = 12
    }
}
