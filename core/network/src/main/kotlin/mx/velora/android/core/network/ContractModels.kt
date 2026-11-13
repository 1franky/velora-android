package mx.velora.android.core.network

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mx.velora.android.api.models.MetricSource

/*
 * Modelos del contrato escritos a mano. El contrato (OpenAPI 3.1) declara campos nulos con `[integer, 'null']` u
 * `oneOf: [X, null]` dentro de esquemas con `additionalProperties: false`; openapi-generator 7.14 los genera como
 * obligatorios y un null de la API rompía la lectura. Mismos nombres y tipos que el contrato; los campos que este
 * marca como nulos, nulos aquí.
 */

/** Metros recorridos en un rango de pendiente (`ActivityClimbs.gradeDistribution`). */
@Serializable
data class GradeBucket(
    /** null en el extremo abierto de bajada. */
    val fromPercent: Double? = null,
    /** null en el extremo abierto de subida. */
    val toPercent: Double? = null,
    val distanceMeters: Double,
)

/** `GET /analytics/records` (`records/1`). */
@Serializable
data class Records(
    val algorithmVersion: String,
    val includeIndoor: Boolean,
    val minSpeedDistanceMeters: Double,
    val minSpeedMovingSeconds: Double,
    val records: List<PersonalRecord>,
    /** null si no hay rodadas. */
    val biggestDay: BiggestDay? = null,
) {
    @Serializable
    data class PersonalRecord(
        val kind: Kind,
        /** Metros, metros, segundos o m/s según [kind]. */
        val value: Double,
        @Contextual val activityId: UUID,
        val title: String,
        @Contextual val localStartDate: LocalDate,
        /** null si el valor no tiene origen registrado. */
        val source: MetricSource? = null,
    )

    @Serializable
    enum class Kind {
        @SerialName("longest_distance") LONGEST_DISTANCE,
        @SerialName("most_elevation") MOST_ELEVATION,
        @SerialName("longest_moving_time") LONGEST_MOVING_TIME,
        @SerialName("fastest_average_speed") FASTEST_AVERAGE_SPEED,
    }

    @Serializable
    data class BiggestDay(@Contextual val date: LocalDate, val distanceMeters: Double, val activityCount: Int)
}

/** `GET /analytics/heart-rate` (`hr-zones/1`). */
@Serializable
data class HeartRateZones(
    val algorithmVersion: String,
    @Contextual val from: LocalDate,
    @Contextual val to: LocalDate,
    val includeIndoor: Boolean,
    /** null si el usuario no indicó su FC máxima: entonces no hay zonas. */
    val maxHeartRateBpm: Int? = null,
    val activitiesWithHeartRate: Int,
    val activitiesWithoutHeartRate: Int,
    val belowZonesSeconds: Double,
    val zones: List<Zone>,
) {
    @Serializable
    data class Zone(
        val zone: Int,
        val fromBpm: Int,
        /** Incluido; null en la zona 5. */
        val toBpm: Int? = null,
        val seconds: Double,
    )
}

/** `GET /analytics/training-load` (`edwards-trimp/1`): siempre una estimación. */
@Serializable
data class TrainingLoad(
    val method: String,
    val estimated: Boolean,
    /** null si el usuario no indicó su FC máxima. */
    val maxHeartRateBpm: Int? = null,
    val includeIndoor: Boolean,
    val weeks: List<Week>,
) {
    @Serializable
    data class Week(
        @Contextual val start: LocalDate,
        @Contextual val end: LocalDate,
        /** Puntos TRIMP. */
        val load: Double,
        val activitiesWithHeartRate: Int,
        val activitiesWithoutHeartRate: Int,
    )
}
