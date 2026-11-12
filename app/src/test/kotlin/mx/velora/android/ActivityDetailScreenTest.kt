package mx.velora.android

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.ActivityDetailRepository
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.detail.ActivityDetailScreen
import mx.velora.android.detail.ActivityDetailViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h2400dp")
class ActivityDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var detailStatus = HttpStatusCode.OK
    @Volatile private var offline = false
    @Volatile private var lapsFail = false

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val path = request.url.encodedPath
        if (path == "/api/v1/auth/refresh") return@MockEngine respond(TOKENS, HttpStatusCode.OK, json)
        if (offline) throw IOException("sin red")
        requests += path + "?" + request.url.encodedQuery
        when {
            path.endsWith("/streams") -> respond(STREAMS, HttpStatusCode.OK, json)
            path.endsWith("/laps") ->
                if (lapsFail) respond("""{"status":500,"title":"x"}""", HttpStatusCode.InternalServerError, json) else respond(LAPS, HttpStatusCode.OK, json)
            path.endsWith("/climbs") -> respond(CLIMBS, HttpStatusCode.OK, json)
            detailStatus != HttpStatusCode.OK -> respond("""{"status":404,"title":"No encontrada"}""", detailStatus, json)
            else -> respond(DETAIL, HttpStatusCode.OK, json)
        }
    }

    private val repository = ActivityDetailRepository(
        SessionManager(
            store = object : SessionStore {
                var saved: StoredSession? = StoredSession("http://servidor", "propietario@example.com", "r0")
                override suspend fun read() = saved
                override suspend fun write(session: StoredSession) { saved = session }
                override suspend fun clear() { saved = null }
                override suspend fun lastServerUrl() = "http://servidor"
            },
            appScope = scope,
            apiFor = { VeloraApi.create(it, engine) },
        ).also { runBlocking { it.restore() } },
    )

    @After fun tearDown() = scope.cancel()

    private var back = false

    private fun start() = compose.setContent {
        VeloraTheme {
            ActivityDetailScreen(
                viewModel { ActivityDetailViewModel(repository, UUID.fromString(id)) },
                backLabel = R.string.detail_back,
                onBack = { back = true },
            )
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun showsMetricsChartsLapsAndClimbs() {
        start()

        waitForText("Vuelta al Ajusco")
        // Solo las métricas presentes; la de origen calculado lo dice.
        compose.onNodeWithText("60.3 km", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("148 ppm", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Calculada por Velora", useUnmergedTree = true).assertExists()
        assertTrue(compose.onAllNodesWithText("Potencia media", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("mar, 10 nov 2026, 07:05", useUnmergedTree = true).assertExists()

        waitForText("Gráficas")
        compose.onNodeWithText("mín. 2,300 m, máx. 2,450 m", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Altitud: mín. 2,300 m, máx. 2,450 m").performScrollTo().performTouchInput { click(center) }
        waitForText("En el km", substring = true)

        waitForText("Vuelta 2")
        waitForText("Del 1.0 km al 3.5 km")
        compose.onNodeWithText("Subida fuerte (más de 8 %)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Importada de un archivo FIT de 121 kB el", substring = true, useUnmergedTree = true).assertExists()

        compose.onNodeWithText("Volver a actividades").performClick()
        assertTrue(back)
        // Las gráficas nunca piden posiciones.
        assertFalse(requests.any { it.contains("LATITUDE") || it.contains("LONGITUDE") })
    }

    @Test
    fun aFailingSectionDoesNotHideTheRest() {
        lapsFail = true
        start()

        waitForText("No pudimos cargar las vueltas.")
        compose.onNodeWithText("60.3 km", useUnmergedTree = true).assertExists()
        waitForText("Del 1.0 km al 3.5 km")
    }

    @Test
    fun aDeletedRideAndNoNetworkAreExplained() {
        detailStatus = HttpStatusCode.NotFound
        start()
        waitForText("Esta rodada ya no existe. Quizá la borraste desde otro dispositivo.")
    }

    @Test
    fun withoutNetworkOffersToRetry() {
        offline = true
        start()
        waitForText("Sin conexión con el servidor. El detalle de una rodada necesita conexión.")
        offline = false
        compose.onNodeWithText("Reintentar").performClick()
        waitForText("Vuelta al Ajusco")
    }

    private companion object {
        const val TOKENS =
            """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}"""

        const val DETAIL = """
            {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:05:00Z",
             "localStartDate":"2026-11-10","subType":"MOUNTAIN_BIKE","distanceMeters":60250.5,"movingTimeSeconds":9000,
             "elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null,
             "timeZone":"America/Mexico_City","sourceType":"FIT","deviceId":null,"elevationLossMeters":880,
             "maxSpeedMps":15.2,"averageHeartRateBpm":148,"maxHeartRateBpm":181,"averagePowerWatts":null,
             "maxPowerWatts":null,"averageCadenceRpm":82,
             "metricsProvenance":{"distanceMeters":"DEVICE_REPORTED","elevationGainMeters":"DERIVED"},
             "hasTrack":true,"streamTypes":["TIME","DISTANCE","ALTITUDE","HEART_RATE"],"lapCount":2,
             "originalFile":{"format":"FIT","sizeBytes":123456,"importedAt":"2026-11-10T20:00:00Z"}}"""

        const val STREAMS = """
            {"sampleCount":4,"streams":{"TIME":[0,1000,2000,3000],"DISTANCE":[0,1000,2000,3000],
             "ALTITUDE":[2300,2350,null,2450],"HEART_RATE":[120,140,150,160]}}"""

        const val LAPS = """
            {"items":[{"lapNumber":1,"startedAt":null,"elapsedTimeSeconds":4800,"timerTimeSeconds":4500,"distanceMeters":30000,
                       "averageSpeedMps":6.6,"maxSpeedMps":14,"averageHeartRateBpm":145,"maxHeartRateBpm":175,
                       "averageCadenceRpm":80,"averagePowerWatts":null,"maxPowerWatts":null},
                      {"lapNumber":2,"startedAt":null,"elapsedTimeSeconds":4800,"timerTimeSeconds":4500,"distanceMeters":30250,
                       "averageSpeedMps":6.7,"maxSpeedMps":15,"averageHeartRateBpm":151,"maxHeartRateBpm":181,
                       "averageCadenceRpm":84,"averagePowerWatts":null,"maxPowerWatts":null}]}"""

        const val CLIMBS = """
            {"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","algorithmVersion":"climbs/1","available":true,
             "climbs":[{"startDistanceMeters":1000,"endDistanceMeters":3500,"lengthMeters":2500,"startAltitudeMeters":2300,
                        "endAltitudeMeters":2450,"elevationGainMeters":150,"averageGradePercent":6.0,"maxGradePercent":9.5}],
             "gradeDistribution":[{"fromPercent":null,"toPercent":-8,"distanceMeters":200},
                                  {"fromPercent":-8,"toPercent":-4,"distanceMeters":1000},
                                  {"fromPercent":-4,"toPercent":-1,"distanceMeters":5000},
                                  {"fromPercent":-1,"toPercent":1,"distanceMeters":30000},
                                  {"fromPercent":1,"toPercent":4,"distanceMeters":15000},
                                  {"fromPercent":4,"toPercent":8,"distanceMeters":8500},
                                  {"fromPercent":8,"toPercent":null,"distanceMeters":550}]}"""
    }
}
