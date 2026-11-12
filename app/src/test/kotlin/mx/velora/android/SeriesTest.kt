package mx.velora.android

import mx.velora.android.detail.ChartSeriesKey
import mx.velora.android.detail.nearestIndex
import mx.velora.android.detail.prepareSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTest {

    @Test
    fun keepsShortRidesAsTheyAreAndConvertsSpeed() {
        val prepared = prepareSeries(
            mapOf(
                "TIME" to listOf(0.0, 1000.0, 2000.0),
                "DISTANCE" to listOf(0.0, 5.0, 10.0),
                "SPEED" to listOf(5.0, null, 10.0),
                "HEART_RATE" to listOf(null, null, null),
            ),
            sampleCount = 3,
        )

        assertEquals(listOf(0.0, 0.005, 0.01), prepared.distanceKm)
        assertEquals(listOf(0.0, 1.0, 2.0), prepared.elapsedSeconds)
        // Sin datos de FC no hay panel de FC.
        assertEquals(listOf(ChartSeriesKey.SPEED), prepared.series.map { it.key })
        val speed = prepared.series.single()
        assertEquals(listOf(18.0, null, 36.0), speed.values)
        assertEquals(18.0, speed.min, 1e-9)
        assertEquals(36.0, speed.max, 1e-9)
    }

    @Test
    fun longRidesAreAveragedButKeepTheirPeaks() {
        val altitude = List(10_000) { if (it == 5_001) 3_000.0 else 2_000.0 }
        val prepared = prepareSeries(mapOf("ALTITUDE" to altitude, "TIME" to List(10_000) { it * 1000.0 }), 10_000, maxPoints = 100)

        val series = prepared.series.single()
        assertEquals(100, series.values.size)
        assertEquals(3_000.0, series.max, 0.0)
        assertTrue(series.values.all { it!! < 2_100 })
        assertEquals(100, prepared.elapsedSeconds!!.size)
        assertNull(prepared.distanceKm)
    }

    @Test
    fun findsTheNearestPointSkippingGaps() {
        val axis = listOf(0.0, null, 2.0, 3.0)
        assertEquals(0, nearestIndex(axis, 0.9))
        assertEquals(2, nearestIndex(axis, 1.6))
        assertEquals(3, nearestIndex(axis, 99.0))
        assertEquals(-1, nearestIndex(listOf(null, null), 1.0))
    }
}
