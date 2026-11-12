package mx.velora.android.core.network

import mx.velora.android.api.models.ActivityClimbs
import mx.velora.android.api.models.ActivityDetail
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.api.models.MetricSource
import mx.velora.android.api.models.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El detalle y las subidas tal como los entrega la API (con nulos donde el contrato los permite). */
class DetailModelsTest {

    @Test
    fun readsAnActivityDetail() {
        val detail = VeloraApi.json.decodeFromString(
            ActivityDetail.serializer(),
            """
            {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:05:00Z",
             "localStartDate":"2026-11-10","subType":"MOUNTAIN_BIKE","distanceMeters":60250.5,"movingTimeSeconds":9000,
             "elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null,
             "timeZone":"America/Mexico_City","sourceType":"FIT","deviceId":null,"elevationLossMeters":880,
             "maxSpeedMps":15.2,"averageHeartRateBpm":148,"maxHeartRateBpm":181,"averagePowerWatts":null,
             "maxPowerWatts":null,"averageCadenceRpm":82,
             "metricsProvenance":{"distanceMeters":"DEVICE_REPORTED","elevationGainMeters":"DERIVED"},
             "hasTrack":true,"streamTypes":["TIME","LATITUDE","ALTITUDE","HEART_RATE"],"lapCount":3,
             "originalFile":{"format":"FIT","sizeBytes":123456,"importedAt":"2026-11-10T20:00:00Z"}}
            """.trimIndent(),
        )
        assertEquals(ActivitySubType.MOUNTAIN_BIKE, detail.subType)
        assertEquals(MetricSource.DERIVED, detail.metricsProvenance["elevationGainMeters"])
        assertEquals(listOf(StreamType.TIME, StreamType.LATITUDE, StreamType.ALTITUDE, StreamType.HEART_RATE), detail.streamTypes)
        assertNull(detail.averagePowerWatts)
    }

    @Test
    fun readsClimbsWithOpenEndedGradeBuckets() {
        val climbs = VeloraApi.json.decodeFromString(
            ActivityClimbs.serializer(),
            """
            {"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","algorithmVersion":"climbs/1","available":true,
             "climbs":[{"startDistanceMeters":1000,"endDistanceMeters":3500,"lengthMeters":2500,"startAltitudeMeters":2300,
                        "endAltitudeMeters":2450,"elevationGainMeters":150,"averageGradePercent":6.0,"maxGradePercent":9.5}],
             "gradeDistribution":[{"fromPercent":null,"toPercent":-8,"distanceMeters":200},
                                  {"fromPercent":-1,"toPercent":1,"distanceMeters":30000},
                                  {"fromPercent":8,"toPercent":null,"distanceMeters":500}]}
            """.trimIndent(),
        )
        assertEquals(3, climbs.gradeDistribution.size)
        assertNull(climbs.gradeDistribution.first().fromPercent)
        assertNull(climbs.gradeDistribution.last().toPercent)
    }
}
