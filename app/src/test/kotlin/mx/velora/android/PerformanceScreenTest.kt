package mx.velora.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.PerformanceRepository
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.performance.PerformanceScreen
import mx.velora.android.performance.PerformanceViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h2600dp")
class PerformanceScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queries = CopyOnWriteArrayList<String>()
    @Volatile private var offline = false
    @Volatile private var maxHeartRate: Int? = null
    @Volatile private var opened: String? = null

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val path = request.url.encodedPath
        if (path == "/api/v1/auth/refresh") {
            return@MockEngine respond(
                """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
        }
        if (offline) throw IOException("sin red")
        queries += path + "?" + request.url.encodedQuery
        val max = maxHeartRate?.toString() ?: "null"
        val body = when (path) {
            "/api/v1/analytics/timeseries" -> {
                val granularity = request.url.parameters["granularity"]
                """{"granularity":"$granularity","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"duplicatesExcluded":1,
                    "distanceSources":{"deviceReported":3,"derived":2},
                    "totals":{"activityCount":5,"distanceMeters":200000,"movingTimeSeconds":36000,"elevationGainMeters":3000},
                    "buckets":[{"start":"2026-11-02","end":"2026-11-08","activityCount":3,"distanceMeters":120000,"movingTimeSeconds":21600,"elevationGainMeters":2100},
                               {"start":"2026-11-09","end":"2026-11-15","activityCount":2,"distanceMeters":80000,"movingTimeSeconds":14400,"elevationGainMeters":900}]}"""
            }
            "/api/v1/analytics/records" ->
                """{"algorithmVersion":"records/1","includeIndoor":false,"minSpeedDistanceMeters":10000,"minSpeedMovingSeconds":1200,
                    "records":[{"kind":"longest_distance","value":120500,"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
                                "title":"Vuelta al Ajusco","localStartDate":"2026-11-10","source":"DEVICE_REPORTED"}],
                    "biggestDay":{"date":"2026-11-10","distanceMeters":130000,"activityCount":2}}"""
            "/api/v1/analytics/eddington" ->
                """{"algorithmVersion":"eddington/1","includeIndoor":false,"year":2026,"daysWithRides":40,
                    "overall":{"number":32,"daysForNext":20,"missingForNext":13},"thisYear":{"number":20,"daysForNext":5,"missingForNext":1}}"""
            "/api/v1/analytics/heart-rate" ->
                """{"algorithmVersion":"hr-zones/1","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"maxHeartRateBpm":$max,
                    "activitiesWithHeartRate":${if (maxHeartRate == null) 0 else 3},"activitiesWithoutHeartRate":1,"belowZonesSeconds":600,
                    "zones":${if (maxHeartRate == null) "[]" else """[{"zone":4,"fromBpm":148,"toBpm":166,"seconds":1200},{"zone":5,"fromBpm":167,"toBpm":null,"seconds":300}]"""}}"""
            else ->
                """{"method":"edwards-trimp/1","estimated":true,"maxHeartRateBpm":$max,"includeIndoor":false,
                    "weeks":${if (maxHeartRate == null) "[]" else """[{"start":"2026-11-09","end":"2026-11-15","load":245,"activitiesWithHeartRate":2,"activitiesWithoutHeartRate":0}]"""}}"""
        }
        respond(body, HttpStatusCode.OK, json)
    }

    private val session = SessionManager(
        store = object : SessionStore {
            var saved: StoredSession? = StoredSession("http://servidor", "propietario@example.com", "r0")
            override suspend fun read() = saved
            override suspend fun write(session: StoredSession) { saved = session }
            override suspend fun clear() { saved = null }
            override suspend fun lastServerUrl() = "http://servidor"
        },
        appScope = scope,
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    @After fun tearDown() = scope.cancel()

    private fun start() = compose.setContent {
        VeloraTheme { PerformanceScreen(viewModel { PerformanceViewModel(PerformanceRepository(session)) }, onOpenActivity = { opened = it }) }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun showsTrendsRecordsAndEddington() {
        start()

        waitForText("Últimas 2 semanas")
        compose.onNodeWithText("200.0 km", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Semana del 9 nov 2026: 80.0 km en 2 rodadas, 900 m de desnivel positivo").assertExists()
        compose.onNodeWithText("Una rodada parece duplicada y se contó una sola vez.").assertExists()
        waitForText("Rodada más larga")
        compose.onNodeWithText("120.5 km", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Mejor día", useUnmergedTree = true).assertExists()
        waitForText("Te faltan 13 días de 33 km o más para llegar a 33.")
        waitForText("Te falta 1 día de 21 km o más para llegar a 21.")
        // Sin FC máxima no hay zonas ni carga: se dice qué falta.
        waitForText("Para ver tu tiempo en cada zona necesitamos tu FC máxima", substring = true)
        waitForText("La carga se estima con tus zonas cardiacas", substring = true)

        compose.onNodeWithText("Rodada más larga", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10", opened)
    }

    @Test
    fun withMaximumHeartRateShowsZonesAndLoad() {
        maxHeartRate = 185
        start()

        waitForText("Zona 5, 167 ppm o más")
        compose.onNodeWithText("Zona 4, 148–166 ppm", useUnmergedTree = true).assertExists()
        waitForText("Semana del 9 nov 2026: 245 puntos de 2 rodadas con FC")
        compose.onNodeWithText("Es una guía de entrenamiento, no una evaluación médica.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun changingTheGroupingAsksOnlyForTheTrends() {
        start()
        waitForText("Rodada más larga")
        val before = queries.size

        compose.onNodeWithText("Mes").performClick()

        compose.waitUntil(5_000) { queries.any { it.contains("granularity=month") } }
        assertEquals(before + 1, queries.size)
        assertTrue(queries.last().startsWith("/api/v1/analytics/timeseries"))
    }

    @Test
    fun withoutNetworkSaysSoOnceAndRetries() {
        offline = true
        start()

        waitForText("Rendimiento se calcula en el servidor", substring = true)
        offline = false
        compose.onNodeWithText("Reintentar").performClick()
        waitForText("Rodada más larga")
    }
}
