package mx.velora.android.core.network

import kotlinx.serialization.Serializable

/**
 * Metros recorridos en un rango de pendiente (`ActivityClimbs.gradeDistribution`). Escrito a mano: el contrato declara
 * los límites como `['number', 'null']` y el generador 7.14 los vuelve obligatorios, así que los extremos abiertos
 * (null) no se podían leer.
 */
@Serializable
data class GradeBucket(
    /** null en el extremo abierto de bajada. */
    val fromPercent: Double? = null,
    /** null en el extremo abierto de subida. */
    val toPercent: Double? = null,
    val distanceMeters: Double,
)
