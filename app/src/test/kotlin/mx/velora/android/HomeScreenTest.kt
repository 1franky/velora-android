package mx.velora.android

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.database.DashboardCacheEntity
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.home.HomeScreen
import mx.velora.android.home.HomeViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX")
class HomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val queries = CopyOnWriteArrayList<String>()

    /** Lo que responde la API al pedir un resumen; por omisión, noviembre con 3 rodadas. */
    @Volatile private var summary: () -> String = { summaryJson() }

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.url.encodedPath) {
            "/api/v1/auth/refresh" -> respond(TOKENS, HttpStatusCode.OK, json)
            else -> {
                queries += request.url.encodedQuery
                respond(summary(), HttpStatusCode.OK, json)
            }
        }
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

    private val repository = DashboardRepository(session, db.dashboard())

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun start() = compose.setContent { VeloraTheme { HomeScreen(viewModel { HomeViewModel(repository) }) } }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun showsThePeriodTotalsTheTrendAndTheLatestRide() {
        start()

        waitForText("Este mes: 120.5 km en 3 rodadas")
        compose.onAllNodesWithText("más que el mes pasado", substring = true, useUnmergedTree = true).onFirst().assertTextContains("21", substring = true)
        compose.onNodeWithText("5:00:00", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1,501 m", useUnmergedTree = true).assertExists()
        // El mes actual se lee de entrada; cada columna lo dice al lector de pantalla.
        compose.onNodeWithText("noviembre de 2026: 120.5 km en 3 rodadas").assertExists()
        compose.onNodeWithContentDescription("octubre de 2026: 99.6 km en 1 rodada").performScrollTo().performClick()
        compose.onNodeWithText("octubre de 2026: 99.6 km en 1 rodada").assertExists()
        compose.onNodeWithText("Vuelta al Ajusco", useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithText("Carretera", useUnmergedTree = true).assertExists()
        assertTrue(queries.single().contains("period=month"))
    }

    @Test
    fun changingThePeriodAsksForThatPeriod() {
        start()
        waitForText("Este mes: 120.5 km en 3 rodadas")
        summary = { summaryJson(period = "week", count = 0, distance = 0, previousDistance = 10_000) }

        compose.onNodeWithText("Semana").performClick()

        waitForText("Esta semana aún no tienes rodadas")
        compose.onAllNodesWithText("menos que la semana pasada", substring = true, useUnmergedTree = true).onFirst().assertTextContains("100", substring = true)
        assertTrue(queries.last().contains("period=week"))
    }

    @Test
    fun anAccountWithoutRidesSaysHowToStart() {
        summary = { summaryJson(latest = false, count = 0, distance = 0) }
        start()

        waitForText("Aún no tienes actividades")
    }

    @Test
    fun withoutCacheOrNetworkOffersToRetry() {
        summary = { throw IOException("sin red") }
        start()

        waitForText("No pudimos cargar tu resumen. Intenta de nuevo en unos momentos.")
        summary = { summaryJson() }
        compose.onNodeWithText("Reintentar").performClick()
        waitForText("Este mes: 120.5 km en 3 rodadas")
    }

    @Test
    fun withoutNetworkShowsTheSavedSummaryAndSaysFromWhen() {
        runBlocking {
            db.dashboard().upsert(
                DashboardCacheEntity("month", summaryJson(), fetchedAtEpochMs = 1_794_319_500_000),
            )
        }
        summary = { throw IOException("sin red") }
        start()

        waitForText("Este mes: 120.5 km en 3 rodadas")
        waitForText("Sin conexión con el servidor. Datos del", substring = true)
    }

    private fun waitForText(text: String, substring: Boolean) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private companion object {
        const val TOKENS =
            """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}"""

        fun summaryJson(
            period: String = "month",
            count: Int = 3,
            distance: Int = 120_500,
            previousDistance: Int = 99_600,
            latest: Boolean = true,
        ): String {
            // De diciembre de 2025 a noviembre de 2026, del más antiguo al más reciente.
            val months = (0L..11L).joinToString(",") { i ->
                val (c, d) = when (i) {
                    11L -> count to distance
                    10L -> 1 to 99_600
                    else -> 0 to 0
                }
                """{"month":"${YearMonth.of(2025, 12).plusMonths(i)}","activityCount":$c,"distanceMeters":$d,
                    "movingTimeSeconds":0,"elevationGainMeters":0}"""
            }
            val latestActivity = if (!latest) "null" else """
                {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:05:00Z",
                 "localStartDate":"2026-11-10","subType":"ROAD","distanceMeters":60250,"movingTimeSeconds":9000,
                 "elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null}"""
            return """
                {"period":"$period","timeZone":"America/Mexico_City",
                 "current":{"from":"2026-11-01","to":"2026-11-30","activityCount":$count,"distanceMeters":$distance,
                            "movingTimeSeconds":18000,"elevationGainMeters":1500.6},
                 "previous":{"from":"2026-10-01","to":"2026-10-31","activityCount":1,"distanceMeters":$previousDistance,
                             "movingTimeSeconds":18000,"elevationGainMeters":1500.6},
                 "monthlyTrend":[$months],"latestActivity":$latestActivity}
            """.trimIndent()
        }
    }
}
