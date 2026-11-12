package mx.velora.android.detail

/**
 * Series de una rodada listas para graficar, como `prepareSeries` de la web (`velora-web/src/lib/activities/series.ts`).
 * La API entrega una muestra por segundo: una rodada de 4 h son ~14 000 por serie. En un teléfono bastan [MAX_POINTS]:
 * se promedia por tramos, pero el mínimo y el máximo salen de todas las muestras para no esconder picos. Sin posiciones:
 * las gráficas no las necesitan.
 */
enum class ChartSeriesKey { ALTITUDE, SPEED, HEART_RATE, POWER, CADENCE, TEMPERATURE }

data class ChartSeries(val key: ChartSeriesKey, val values: List<Double?>, val min: Double, val max: Double)

data class PreparedSeries(
    /** Kilómetros en cada punto; null si la rodada no tiene distancia (p. ej. rodillo sin sensor). */
    val distanceKm: List<Double?>?,
    /** Segundos desde el inicio en cada punto; null sin serie de tiempo. */
    val elapsedSeconds: List<Double?>?,
    val series: List<ChartSeries>,
) {
    val isEmpty: Boolean get() = series.isEmpty() || (distanceKm == null && elapsedSeconds == null)
}

const val MAX_POINTS = 600

fun prepareSeries(streams: Map<String, List<Double?>>, sampleCount: Int, maxPoints: Int = MAX_POINTS): PreparedSeries {
    val ranges = buckets(sampleCount, maxPoints)
    fun usable(name: String) = streams[name]?.takeIf { values -> values.any { it != null && it.isFinite() } }

    val series = ChartSeriesKey.entries.mapNotNull { key ->
        val raw = usable(key.name) ?: return@mapNotNull null
        // m/s → km/h; lo demás ya está en su unidad de presentación.
        val convert: (Double) -> Double = if (key == ChartSeriesKey.SPEED) { v -> v * 3.6 } else { v -> v }
        val finite = raw.filterNotNull().filter { it.isFinite() }
        ChartSeries(
            key = key,
            values = ranges.map { range -> mean(raw, range)?.let(convert) },
            min = convert(finite.min()),
            max = convert(finite.max()),
        )
    }
    return PreparedSeries(
        distanceKm = usable("DISTANCE")?.let { distance -> ranges.map { nearestToMiddle(distance, it)?.div(1000) } },
        elapsedSeconds = usable("TIME")?.let { time -> ranges.map { nearestToMiddle(time, it)?.div(1000) } },
        series = series,
    )
}

/** Índice del punto más cercano a [x], ignorando huecos; -1 si no hay ninguno. */
fun nearestIndex(axis: List<Double?>, x: Double): Int {
    var best = -1
    var bestDistance = Double.MAX_VALUE
    axis.forEachIndexed { i, value ->
        if (value != null) {
            val distance = kotlin.math.abs(value - x)
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
    }
    return best
}

/** Tramos [inicio, fin) de muestras. */
private fun buckets(sampleCount: Int, maxPoints: Int): List<IntRange> {
    if (sampleCount <= maxPoints) return (0 until sampleCount).map { it until it + 1 }
    val size = sampleCount.toDouble() / maxPoints
    return (0 until maxPoints).map { b -> (b * size).toInt() until ((b + 1) * size).toInt() }
}

private fun mean(values: List<Double?>, range: IntRange): Double? {
    var sum = 0.0
    var count = 0
    for (i in range) {
        val value = values.getOrNull(i)
        if (value != null && value.isFinite()) {
            sum += value
            count++
        }
    }
    return if (count > 0) sum / count else null
}

/** Distancia y tiempo no se promedian: se toma la muestra con dato más cercana al centro del tramo. */
private fun nearestToMiddle(values: List<Double?>, range: IntRange): Double? {
    if (range.isEmpty()) return null
    val middle = (range.first + range.last) / 2
    for (offset in 0..(range.last - range.first)) {
        for (i in intArrayOf(middle - offset, middle + offset)) {
            if (i in range) values.getOrNull(i)?.let { return it }
        }
    }
    return null
}
