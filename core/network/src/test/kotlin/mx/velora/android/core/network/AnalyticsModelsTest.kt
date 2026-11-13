package mx.velora.android.core.network

import mx.velora.android.api.models.Eddington
import mx.velora.android.api.models.Timeseries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Las respuestas de Rendimiento tal como las entrega la API, con los nulos que el contrato permite. */
class AnalyticsModelsTest {

    private fun <T> read(serializer: kotlinx.serialization.KSerializer<T>, json: String) = VeloraApi.json.decodeFromString(serializer, json)

    @Test
    fun timeseries() {
        read(Timeseries.serializer(), """
            {"granularity":"week","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"duplicatesExcluded":1,
             "distanceSources":{"deviceReported":3,"derived":2},
             "totals":{"activityCount":5,"distanceMeters":200000,"movingTimeSeconds":36000,"elevationGainMeters":3000},
             "buckets":[{"start":"2026-11-09","end":"2026-11-15","activityCount":2,"distanceMeters":80000,"movingTimeSeconds":14400,"elevationGainMeters":900}]}
        """)
    }

    @Test
    fun recordsWithoutSourceOrBiggestDay() {
        val records = read(Records.serializer(), """
            {"algorithmVersion":"records/1","includeIndoor":false,"minSpeedDistanceMeters":10000,"minSpeedMovingSeconds":1200,
             "records":[{"kind":"longest_distance","value":120500,"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
                         "title":"Vuelta","localStartDate":"2026-11-10","source":"DEVICE_REPORTED"},
                        {"kind":"most_elevation","value":1500,"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
                         "title":"Vuelta","localStartDate":"2026-11-10","source":null}],
             "biggestDay":null}
        """)
        assertEquals(Records.Kind.LONGEST_DISTANCE, records.records.first().kind)
        assertEquals(mx.velora.android.api.models.MetricSource.DEVICE_REPORTED, records.records.first().source)
        assertNull(records.records.last().source)
        assertNull(records.biggestDay)
    }

    @Test
    fun eddington() {
        read(Eddington.serializer(), """
            {"algorithmVersion":"eddington/1","includeIndoor":false,"year":2026,"daysWithRides":40,
             "overall":{"number":32,"daysForNext":20,"missingForNext":13},"thisYear":{"number":20,"daysForNext":5,"missingForNext":16}}
        """)
    }

    @Test
    fun heartRateWithoutMaximum() {
        val zones = read(HeartRateZones.serializer(), """
            {"algorithmVersion":"hr-zones/1","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"maxHeartRateBpm":null,
             "activitiesWithHeartRate":0,"activitiesWithoutHeartRate":3,"belowZonesSeconds":0,"zones":[]}
        """)
        assertNull(zones.maxHeartRateBpm)
    }

    @Test
    fun heartRateZoneFiveHasNoUpperBound() {
        val zones = read(HeartRateZones.serializer(), """
            {"algorithmVersion":"hr-zones/1","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"maxHeartRateBpm":185,
             "activitiesWithHeartRate":3,"activitiesWithoutHeartRate":0,"belowZonesSeconds":600,
             "zones":[{"zone":4,"fromBpm":148,"toBpm":166,"seconds":1200},{"zone":5,"fromBpm":167,"toBpm":null,"seconds":300}]}
        """)
        assertNull(zones.zones.last().toBpm)
        assertEquals(166, zones.zones.first().toBpm)
    }

    @Test
    fun trainingLoadWithoutMaximum() {
        val load = read(TrainingLoad.serializer(), """
            {"method":"edwards-trimp/1","estimated":true,"maxHeartRateBpm":null,"includeIndoor":false,"weeks":[]}
        """)
        assertNull(load.maxHeartRateBpm)
    }
}
