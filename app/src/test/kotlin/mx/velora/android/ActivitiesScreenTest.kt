package mx.velora.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.activities.ActivitiesScreen
import mx.velora.android.activities.ActivitiesViewModel
import mx.velora.android.activities.validated
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h1600dp")
class ActivitiesScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val queries = CopyOnWriteArrayList<String>()

    /** Días de octubre que devuelve la API, por cursor; con filtros, [filtered]. */
    @Volatile private var pages: Map<String?, Pair<List<Int>, String?>> = mapOf(null to (listOf(30, 29) to "c1"), "c1" to (listOf(20) to null))
    @Volatile private var filtered: List<Int> = listOf(12)
    @Volatile private var offline = false

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (request.url.encodedPath == "/api/v1/auth/refresh") return@MockEngine respond(TOKENS, HttpStatusCode.OK, json)
        if (offline) throw IOException("sin red")
        if (request.url.encodedPath == "/api/v1/bikes") {
            return@MockEngine respond(
                """{"items":[{"id":"b1a1c0de-0000-4000-8000-000000000001","name":"Gravel de diario","bikeType":"GRAVEL",
                    "brand":null,"model":null,"weightKg":null,"active":true,"createdAt":"2026-01-01T00:00:00Z"}]}""",
                HttpStatusCode.OK,
                json,
            )
        }
        queries += request.url.encodedQuery
        val (days, next) = if (request.url.parameters["subType"] != null) {
            filtered to null
        } else {
            pages.getValue(request.url.parameters["cursor"])
        }
        respond(
            """{"items":[${days.joinToString(",") { ride(it) }}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""",
            HttpStatusCode.OK,
            json,
        )
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

    private val repository = ActivitiesRepository(session, db.activities())

    // La base en memoria no se cierra: el ViewModel vive hasta que la regla de Compose destruye la actividad, después de
    // @After, y una página aún en camino escribiría en una conexión cerrada.
    @After
    fun tearDown() = scope.cancel()

    private fun start() = compose.setContent { VeloraTheme { ActivitiesScreen(viewModel { ActivitiesViewModel(repository) }) } }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun listsRidesAndLoadsMore() {
        start()

        waitForText("Rodada del 30")
        compose.onNodeWithText("2 actividades en pantalla").assertExists()
        assertEquals(2, compose.onAllNodesWithText("40.0 km", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Mostrar más").performScrollTo().performClick()

        waitForText("Rodada del 20")
        waitForText("3 actividades en pantalla")
        compose.onAllNodesWithText("Mostrar más").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun withoutNetworkShowsTheSavedRides() {
        runBlocking { repository.syncPage(null) }
        offline = true
        start()

        waitForText("Sin conexión con el servidor. Estas son las rodadas guardadas en el teléfono.")
        compose.onNodeWithText("Rodada del 30", useUnmergedTree = true).assertExists()
    }

    @Test
    fun withoutCacheOrNetworkOffersToRetry() {
        offline = true
        start()

        waitForText("No pudimos cargar tus actividades. Intenta de nuevo en unos momentos.")
        offline = false
        compose.onNodeWithText("Reintentar").performClick()
        waitForText("Rodada del 30")
    }

    @Test
    fun filtersByTypeAndClearsThem() {
        start()
        waitForText("Rodada del 30")

        compose.onNodeWithText("Filtros").performClick()
        compose.onNodeWithText("Gravel").performClick()
        waitForText("Gravel de diario")
        compose.onNode(hasSetTextAction() and hasText("Distancia mínima (km)")).performTextInput("10,5")
        compose.onNodeWithText("Aplicar filtros").performScrollTo().performClick()

        waitForText("Rodada del 12")
        compose.onNodeWithText("Filtros (2 activos)").assertExists()
        assertTrue(queries.last().contains("subType=GRAVEL"))
        assertTrue(queries.last().contains("minDistanceM=10500.0"))
        // Lo filtrado no se mezcla con el historial guardado.
        compose.onAllNodesWithText("Rodada del 30", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

        filtered = emptyList()
        compose.onNodeWithText("Filtros (2 activos)").performClick()
        compose.onNodeWithText("Aplicar filtros").performScrollTo().performClick()
        waitForText("Ninguna actividad coincide con los filtros")
        compose.onNodeWithText("Quitar filtros").performClick()
        waitForText("Rodada del 30")
    }

    @Test
    fun anAccountWithoutRidesSaysHowToStart() {
        pages = mapOf(null to (emptyList<Int>() to null))
        start()

        waitForText("Aún no tienes actividades")
    }

    @Test
    fun filterValuesAreChecked() {
        val base = ActivityFilters()
        assertEquals(base.copy(minKm = 10.5, maxKm = 80.0), validated(base, "10,5", " 80 "))
        assertEquals(base, validated(base, "", ""))
        assertNull(validated(base, "diez", ""))
        assertNull(validated(base, "-1", ""))
        assertNull(validated(base, "50", "20"))
        assertNull(validated(base.copy(from = LocalDate.of(2026, 11, 10), to = LocalDate.of(2026, 11, 1)), "", ""))
    }

    private companion object {
        const val TOKENS =
            """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}"""

        fun ride(day: Int) = """
            {"id":"00000000-0000-4000-8000-0000000000%02d","title":"Rodada del %d","startedAt":"2026-10-%02dT13:00:00Z",
             "localStartDate":"2026-10-%02d","subType":"ROAD","distanceMeters":40000,"movingTimeSeconds":5400,
             "elapsedTimeSeconds":6000,"elevationGainMeters":300,"averageSpeedMps":7.4,"bikeId":null}
        """.trimIndent().format(day, day, day, day)
    }
}
