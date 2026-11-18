package mx.velora.android

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import mx.velora.android.core.data.ExplorerFocus
import mx.velora.android.core.data.ExplorerRepository
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.detail.Section
import mx.velora.android.explorer.ExplorerLayer
import mx.velora.android.explorer.ExplorerScreen
import mx.velora.android.explorer.ExplorerState
import mx.velora.android.explorer.ExplorerViewModel
import mx.velora.android.explorer.Viewport
import mx.velora.android.explorer.clampBbox
import mx.velora.android.explorer.heatmapTilesUrl
import mx.velora.android.explorer.squareGeoJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h900dp")
class ExplorerScreenTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queries = CopyOnWriteArrayList<String>()
    @Volatile private var offline = false
    @Volatile private var rides = """[$INDOOR,$ROAD]"""
    @Volatile private var truncated = false
    @Volatile private var imported = false

    /** Lo último que recibió el mapa y cómo avisarle a la pantalla qué se ve. */
    @Volatile private var mapState: ExplorerState? = null
    @Volatile private var reportViewport: ((Viewport) -> Unit)? = null

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
        val body = when {
            path == "/api/v1/activities" -> """{"items":$rides,"nextCursor":null}"""
            path == "/api/v1/activities/$ROAD_ID/track" -> TRACK
            path == "/api/v1/geo/exploration/summary" -> SUMMARY
            path == "/api/v1/geo/exploration/largest-square" -> SQUARE
            path == "/api/v1/geo/exploration/cells" ->
                return@MockEngine respond(cells(request.url.parameters["grid"]!!), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/geo+json"))
            else -> return@MockEngine respond("""{"status":404,"title":"No existe"}""", HttpStatusCode.NotFound, json)
        }
        respond(body, HttpStatusCode.OK, json)
    }

    private fun cells(grid: String) = """
        {"type":"FeatureCollection","grid":"$grid","truncated":$truncated,"features":[
          {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-99.2,19.3],[-99.19,19.3],[-99.19,19.31],[-99.2,19.31],[-99.2,19.3]]]},
           "properties":{"activityCount":4}}]}"""

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
        VeloraTheme {
            ExplorerScreen(viewModel { ExplorerViewModel(ExplorerRepository(session)) }, onImport = { imported = true }) { state, onViewport, modifier ->
                mapState = state
                reportViewport = onViewport
                Box(modifier)
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForMap(condition: (ExplorerState) -> Boolean) =
        try {
            // Consultar el árbol sincroniza con Compose: deja correr el hilo principal, donde llegan las respuestas.
            compose.waitUntil(5_000) {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                mapState?.let(condition) == true
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Estado del mapa: $mapState; consultas: $queries", e)
        }

    @Test
    fun framesTheLatestRideWithGpsAndShowsTheTerritory() {
        start()

        // La más reciente es de rodillo: se encuadra la siguiente, con su recorrido burdo.
        waitForMap { it.camera != null }
        assertEquals(listOf(-99.3, 19.2, -99.1, 19.4), mapState!!.camera!!.bbox)
        assertTrue(queries.any { it.startsWith("/api/v1/activities/$ROAD_ID/track?zoom=8") })

        compose.onNodeWithText("Territorio explorado").performClick()
        waitForText("Cuadros de 1 km")
        compose.onNodeWithText("1,234", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1,234 km² recorridos en México", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("4 × 4 km", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("16 km² sin un solo cuadro sin visitar", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Unos 1,234.5 km² (cuadrícula H3, área aproximada)", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Calculando las celdas de 2 rodadas", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun cellsAreAskedOnlyWhenCloseEnough() {
        start()
        waitForMap { it.camera != null }

        compose.onNodeWithText("Cuadros").performClick()
        reportViewport!!(Viewport(5.0, listOf(-100.0, 18.0, -98.0, 20.0)))
        waitForText("Acércate para ver las celdas.")
        assertTrue(queries.none { it.startsWith("/api/v1/geo/exploration/cells") })

        truncated = true
        reportViewport!!(Viewport(12.0, listOf(-99.25, 19.25, -99.15, 19.35)))
        waitForMap { it.cells != null }
        assertEquals("/api/v1/geo/exploration/cells?grid=MX1KM&bbox=-99.25000%2C19.25000%2C-99.15000%2C19.35000", queries.last())
        waitForText("Hay demasiadas celdas", substring = true)

        // Otra rejilla: a los hexágonos les falta zoom en esta vista (desde el 10 sí).
        compose.onNodeWithText("Hexágonos").performClick()
        waitForMap { it.layer == ExplorerLayer.HEXAGONS && it.cells?.grid?.value == "H3" }

        compose.onNodeWithText("Recorridos").performClick()
        waitForMap { it.layer == ExplorerLayer.TRACKS && it.cells == null }
    }

    @Test
    fun theLargestSquareButtonFramesItWithTheSquaresVisible() {
        start()
        waitForMap { it.camera != null }
        val first = mapState!!.camera!!.id

        compose.onNodeWithText("Territorio explorado").performClick()
        waitForText("Ver mi mayor cuadrado")
        compose.onNodeWithText("Ver mi mayor cuadrado").performClick()

        waitForMap { (it.camera?.id ?: 0) > first }
        assertEquals(ExplorerLayer.SQUARES, mapState!!.layer)
        assertEquals(listOf(-99.25, 19.25, -99.2, 19.3), mapState!!.camera!!.bbox)
    }

    @Test
    fun withoutRidesInvitesToImport() {
        rides = "[]"
        start()

        waitForText("Aún no hay nada que explorar")
        compose.onNodeWithText("Importar actividades").performClick()
        assertTrue(imported)
        compose.onNodeWithText("Territorio explorado").assertDoesNotExist()
    }

    @Test
    fun withOnlyIndoorRidesTheMapStaysOnMexico() {
        rides = "[$INDOOR]"
        start()

        waitForText("no tienen GPS", substring = true)
        waitForMap { it.focus == Section.Ready(ExplorerFocus.NoGps) }
        assertNull(mapState!!.camera)
    }

    @Test
    fun withoutNetworkSaysSoAndRetries() {
        offline = true
        start()

        waitForText("necesita conexión", substring = true)
        offline = false
        compose.onNodeWithText("Reintentar").performClick()
        waitForMap { it.camera != null }
        assertNotNull(mapState)
    }

    @Test
    fun mapHelpers() {
        assertEquals("http://servidor/api/v1/geo/heatmap/{z}/{x}/{y}", heatmapTilesUrl("http://servidor/"))
        assertEquals(listOf(-180.0, -90.0, 180.0, 90.0), clampBbox(listOf(-200.0, -95.0, 190.0, 91.0)))
        assertTrue(squareGeoJson(null).contains("\"features\":[]"))
        assertTrue(squareGeoJson(listOf(-99.0, 19.0, -98.0, 20.0)).contains("[[[-99.0,19.0],[-98.0,19.0],[-98.0,20.0],[-99.0,20.0],[-99.0,19.0]]]"))
    }

    private companion object {
        const val ROAD_ID = "00000000-0000-4000-8000-000000000002"

        const val INDOOR = """
            {"id":"00000000-0000-4000-8000-000000000001","title":"Rodillo","startedAt":"2026-11-12T13:00:00Z",
             "localStartDate":"2026-11-12","subType":"INDOOR","distanceMeters":20000,"movingTimeSeconds":3600,
             "elapsedTimeSeconds":3600,"elevationGainMeters":0,"averageSpeedMps":5.5,"bikeId":null}"""

        const val ROAD = """
            {"id":"$ROAD_ID","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:00:00Z",
             "localStartDate":"2026-11-10","subType":"ROAD","distanceMeters":60000,"movingTimeSeconds":9000,
             "elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.7,"bikeId":null}"""

        const val TRACK = """
            {"type":"Feature","bbox":[-99.3,19.2,-99.1,19.4],
             "geometry":{"type":"MultiLineString","coordinates":[[[-99.3,19.2],[-99.2,19.3],[-99.1,19.4]]]},
             "properties":{"activityId":"$ROAD_ID","pointCount":900,"returnedPointCount":3,"zoom":8,"trimmed":false}}"""

        const val SUMMARY = """
            {"algorithmVersion":"exploration-cells/1","processedActivities":150,"pendingActivities":2,
             "h3":{"resolution":9,"cellCount":9876,"approxAreaKm2":1234.5},
             "metricGrid":{"cellSizeMeters":1000,"cellCount":1234,"areaKm2":1234}}"""

        const val SQUARE = """
            {"algorithmVersion":"largest-square/1","cellSizeMeters":1000,"size":4,"sideKm":4,"bbox":[-99.25,19.25,-99.2,19.3]}"""
    }
}
