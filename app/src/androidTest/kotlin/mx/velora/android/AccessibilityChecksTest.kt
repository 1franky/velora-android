package mx.velora.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import mx.velora.android.activities.ActivitiesScreen
import mx.velora.android.activities.ActivitiesViewModel
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ActivityDetailRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.data.ImportsRepository
import mx.velora.android.core.data.PerformanceRepository
import mx.velora.android.core.data.Synchronizer
import mx.velora.android.core.database.ActivityEntity
import mx.velora.android.core.database.DashboardCacheEntity
import mx.velora.android.core.database.ImportEntity
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.detail.ActivityDetailScreen
import mx.velora.android.detail.ActivityDetailViewModel
import mx.velora.android.home.HomeScreen
import mx.velora.android.home.HomeViewModel
import mx.velora.android.imports.ImportScreen
import mx.velora.android.performance.PerformanceScreen
import mx.velora.android.performance.PerformanceViewModel
import mx.velora.android.imports.ImportViewModel
import mx.velora.android.settings.SettingsScreen
import mx.velora.android.sync.SyncSection
import mx.velora.android.sync.SyncViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Revisiones automáticas de accesibilidad (Accessibility Test Framework de Google) sobre cada pantalla, en tema claro y
 * oscuro: contraste, tamaño de las áreas táctiles, etiquetas y elementos duplicados. Corre en un emulador o teléfono
 * (`./gradlew :app:connectedDebugAndroidTest`): Robolectric no construye el árbol de accesibilidad de Compose.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VeloraDatabase.inMemory(context)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** Una API que responde el detalle de una rodada; todo lo demás, sin conexión (las pantallas usan su caché). */
    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/v1/auth/refresh" -> respond(TOKENS, HttpStatusCode.OK, json)
            path.endsWith("/streams") -> respond(STREAMS, HttpStatusCode.OK, json)
            path.endsWith("/laps") -> respond(LAPS, HttpStatusCode.OK, json)
            path.endsWith("/climbs") -> respond(CLIMBS, HttpStatusCode.OK, json)
            path.endsWith("/track") -> respond("""{"status":404,"title":"Oculto"}""", HttpStatusCode.NotFound, json)
            path == "/api/v1/activities/$RIDE" -> respond(DETAIL, HttpStatusCode.OK, json)
            path == "/api/v1/analytics/timeseries" -> respond(TIMESERIES, HttpStatusCode.OK, json)
            path == "/api/v1/analytics/records" -> respond(RECORDS, HttpStatusCode.OK, json)
            path == "/api/v1/analytics/eddington" -> respond(EDDINGTON, HttpStatusCode.OK, json)
            path == "/api/v1/analytics/heart-rate" -> respond(HEART_RATE, HttpStatusCode.OK, json)
            path == "/api/v1/analytics/training-load" -> respond(LOAD, HttpStatusCode.OK, json)
            else -> throw IOException("sin red")
        }
    }

    private fun session(signedIn: Boolean) = SessionManager(
        store = object : SessionStore {
            var saved: StoredSession? = if (signedIn) StoredSession("https://velora.example", "propietario@example.com", "r0") else null
            override suspend fun read() = saved
            override suspend fun write(session: StoredSession) { saved = session }
            override suspend fun clear() { saved = null }
            override suspend fun lastServerUrl() = "https://velora.example"
        },
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    private fun check(dark: Boolean, waitFor: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.enableAccessibilityChecks()
        compose.setContent {
            VeloraTheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(waitFor, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun login(dark: Boolean) {
        val session = session(signedIn = false)
        check(dark, "Iniciar sesión") {
            VeloraRoot(
                session,
                allowCleartext = false,
                DashboardRepository(session, db.dashboard()),
                ActivitiesRepository(session, db.activities()),
                ActivityDetailRepository(session),
                ImportsRepository(session, db.imports()),
                Synchronizer(DashboardRepository(session, db.dashboard()), ActivitiesRepository(session, db.activities()), db.sync()), PerformanceRepository(session)
            )
        }
    }

    @Test fun login() = login(dark = false)

    @Test fun loginDark() = login(dark = true)

    private fun home(dark: Boolean) {
        runBlocking { db.dashboard().upsert(DashboardCacheEntity("month", SUMMARY, 1_794_319_500_000)) }
        val repository = DashboardRepository(session(signedIn = true), db.dashboard())
        check(dark, "Este mes") { HomeScreen(viewModel { HomeViewModel(repository) }, onOpenActivity = {}) }
    }

    @Test fun home() = home(dark = false)

    @Test fun homeDark() = home(dark = true)

    private fun activities(dark: Boolean) {
        runBlocking {
            db.activities().upsert(
                (1..3).map { day ->
                    ActivityEntity(
                        UUID.nameUUIDFromBytes(byteArrayOf(day.toByte())).toString(), "Rodada $day", "2026-10-0${day}T13:00:00Z",
                        1_790_000_000_000 + day * 86_400_000L, "2026-10-0$day", "ROAD", 40_000.0, 5_400, 6_000, 300.0, 7.4, null,
                    )
                },
            )
        }
        val repository = ActivitiesRepository(session(signedIn = true), db.activities())
        check(dark, "Rodada 1") { ActivitiesScreen(viewModel { ActivitiesViewModel(repository) }, onOpen = {}) }
    }

    @Test fun activities() = activities(dark = false)

    @Test fun activitiesDark() = activities(dark = true)

    private fun detail(dark: Boolean) {
        val repository = ActivityDetailRepository(session(signedIn = true))
        check(dark, "Del 1.0 km") {
            ActivityDetailScreen(
                viewModel { ActivityDetailViewModel(repository, UUID.fromString(RIDE)) },
                backLabel = R.string.detail_back,
                onBack = {},
                // MapLibre necesita red y GPU: en su lugar, un espacio del mismo tamaño con su etiqueta.
                map = { title, _ ->
                    Box(Modifier.fillMaxWidth().height(260.dp).semantics { contentDescription = "Mapa de «$title»" })
                },
            )
        }
    }

    @Test fun detail() = detail(dark = false)

    @Test fun detailDark() = detail(dark = true)

    private fun imports(dark: Boolean) {
        runBlocking {
            listOf("WAITING" to null, "COMPLETED" to null, "REJECTED" to "MALFORMED_FILE").forEachIndexed { i, (state, error) ->
                db.imports().upsert(
                    ImportEntity(
                        id = "$i", fileName = "rodada-$i.fit", sizeBytes = 120_000, localPath = null, bikeId = null, state = state,
                        activityId = if (state == "COMPLETED") RIDE else null, errorCode = error,
                        warnings = if (state == "COMPLETED") "GPS_JUMPS:2" else null, createdAtEpochMs = i.toLong(),
                    ),
                )
            }
        }
        val session = session(signedIn = true)
        val imports = ImportsRepository(session, db.imports())
        val activities = ActivitiesRepository(session, db.activities())
        check(dark, "rodada-2.fit") {
            ImportScreen(viewModel { ImportViewModel(imports, activities) { _, _ -> } }, onBack = {}, onOpenActivity = {})
        }
    }

    @Test fun imports() = imports(dark = false)

    @Test fun importsDark() = imports(dark = true)

    private fun settings(dark: Boolean) {
        val session = session(signedIn = true)
        val synchronizer = Synchronizer(DashboardRepository(session, db.dashboard()), ActivitiesRepository(session, db.activities()), db.sync())
        check(dark, "Sincronizar ahora") {
            SettingsScreen("propietario@example.com", "https://velora.example", onLogout = {}) {
                SyncSection(viewModel { SyncViewModel(synchronizer) })
            }
        }
    }

    @Test fun settings() = settings(dark = false)

    @Test fun settingsDark() = settings(dark = true)

    private fun performance(dark: Boolean) {
        val repository = PerformanceRepository(session(signedIn = true))
        check(dark, "Carga de entrenamiento") {
            PerformanceScreen(viewModel { PerformanceViewModel(repository) }, onOpenActivity = {})
        }
    }

    @Test fun performance() = performance(dark = false)

    @Test fun performanceDark() = performance(dark = true)

    /** Control: si esto no falla, las revisiones no están corriendo y el resto no demuestra nada. */
    @Test
    fun theChecksCatchAnInaccessibleScreen() {
        compose.enableAccessibilityChecks()
        compose.setContent {
            Column(Modifier.background(Color.White)) {
                Box(Modifier.size(10.dp).background(Color.LightGray).clickable {})
                Text("casi invisible", color = Color(0xFFEEEEEE))
            }
        }
        compose.waitForIdle()
        val failed = runCatching { compose.onRoot().tryPerformAccessibilityChecks() }.isFailure
        assertTrue("la revisión de accesibilidad no detectó nada", failed)
    }

    private companion object {
        const val RIDE = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"

        const val TOKENS =
            """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}"""

        val SUMMARY = """
            {"period":"month","timeZone":"America/Mexico_City",
             "current":{"from":"2026-11-01","to":"2026-11-30","activityCount":3,"distanceMeters":120500,"movingTimeSeconds":18000,"elevationGainMeters":1500},
             "previous":{"from":"2026-10-01","to":"2026-10-31","activityCount":1,"distanceMeters":99600,"movingTimeSeconds":18000,"elevationGainMeters":1500},
             "monthlyTrend":[${(0L..11L).joinToString(",") { """{"month":"${java.time.YearMonth.of(2025, 12).plusMonths(it)}","activityCount":${it % 3},"distanceMeters":${it * 10_000},"movingTimeSeconds":0,"elevationGainMeters":0}""" }}],
             "latestActivity":{"id":"$RIDE","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:05:00Z","localStartDate":"2026-11-10",
               "subType":"ROAD","distanceMeters":60250,"movingTimeSeconds":9000,"elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null}}
        """.trimIndent()

        val TIMESERIES = """
            {"granularity":"week","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"duplicatesExcluded":0,
             "distanceSources":{"deviceReported":3,"derived":2},
             "totals":{"activityCount":5,"distanceMeters":200000,"movingTimeSeconds":36000,"elevationGainMeters":3000},
             "buckets":[${(0L..11L).joinToString(",") { """{"start":"${java.time.LocalDate.of(2026, 8, 24).plusWeeks(it)}","end":"${java.time.LocalDate.of(2026, 8, 30).plusWeeks(it)}","activityCount":1,"distanceMeters":${it * 8000},"movingTimeSeconds":3600,"elevationGainMeters":200}""" }}]}
        """.trimIndent()

        const val RECORDS = """
            {"algorithmVersion":"records/1","includeIndoor":false,"minSpeedDistanceMeters":10000,"minSpeedMovingSeconds":1200,
             "records":[{"kind":"longest_distance","value":120500,"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10",
                         "title":"Vuelta al Ajusco","localStartDate":"2026-11-10","source":"DEVICE_REPORTED"}],
             "biggestDay":{"date":"2026-11-10","distanceMeters":130000,"activityCount":2}}"""

        const val EDDINGTON = """
            {"algorithmVersion":"eddington/1","includeIndoor":false,"year":2026,"daysWithRides":40,
             "overall":{"number":32,"daysForNext":20,"missingForNext":13},"thisYear":{"number":20,"daysForNext":5,"missingForNext":16}}"""

        const val HEART_RATE = """
            {"algorithmVersion":"hr-zones/1","from":"2026-08-24","to":"2026-11-15","includeIndoor":false,"maxHeartRateBpm":185,
             "activitiesWithHeartRate":3,"activitiesWithoutHeartRate":1,"belowZonesSeconds":600,
             "zones":[{"zone":1,"fromBpm":93,"toBpm":111,"seconds":600},{"zone":2,"fromBpm":112,"toBpm":129,"seconds":1800},
                      {"zone":3,"fromBpm":130,"toBpm":147,"seconds":2400},{"zone":4,"fromBpm":148,"toBpm":166,"seconds":1200},
                      {"zone":5,"fromBpm":167,"toBpm":null,"seconds":300}]}"""

        val LOAD = """
            {"method":"edwards-trimp/1","estimated":true,"maxHeartRateBpm":185,"includeIndoor":false,
             "weeks":[${(0L..11L).joinToString(",") { """{"start":"${java.time.LocalDate.of(2026, 8, 24).plusWeeks(it)}","end":"${java.time.LocalDate.of(2026, 8, 30).plusWeeks(it)}","load":${it * 20},"activitiesWithHeartRate":1,"activitiesWithoutHeartRate":0}""" }}]}
        """.trimIndent()

        const val DETAIL = """
            {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","title":"Vuelta al Ajusco","startedAt":"2026-11-10T13:05:00Z",
             "localStartDate":"2026-11-10","subType":"MOUNTAIN_BIKE","distanceMeters":60250.5,"movingTimeSeconds":9000,
             "elapsedTimeSeconds":9600,"elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null,
             "timeZone":"America/Mexico_City","sourceType":"FIT","deviceId":null,"elevationLossMeters":880,
             "maxSpeedMps":15.2,"averageHeartRateBpm":148,"maxHeartRateBpm":181,"averagePowerWatts":null,
             "maxPowerWatts":null,"averageCadenceRpm":82,"metricsProvenance":{"elevationGainMeters":"DERIVED"},
             "hasTrack":true,"streamTypes":["TIME","DISTANCE","ALTITUDE","HEART_RATE"],"lapCount":1,
             "originalFile":{"format":"FIT","sizeBytes":123456,"importedAt":"2026-11-10T20:00:00Z"}}"""

        const val STREAMS = """
            {"sampleCount":4,"streams":{"TIME":[0,1000,2000,3000],"DISTANCE":[0,1000,2000,3000],
             "ALTITUDE":[2300,2350,2400,2450],"HEART_RATE":[120,140,150,160]}}"""

        const val LAPS = """
            {"items":[{"lapNumber":1,"startedAt":null,"elapsedTimeSeconds":4800,"timerTimeSeconds":4500,"distanceMeters":30000,
                       "averageSpeedMps":6.6,"maxSpeedMps":14,"averageHeartRateBpm":145,"maxHeartRateBpm":175,
                       "averageCadenceRpm":80,"averagePowerWatts":null,"maxPowerWatts":null}]}"""

        const val CLIMBS = """
            {"activityId":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","algorithmVersion":"climbs/1","available":true,
             "climbs":[{"startDistanceMeters":1000,"endDistanceMeters":3500,"lengthMeters":2500,"startAltitudeMeters":2300,
                        "endAltitudeMeters":2450,"elevationGainMeters":150,"averageGradePercent":6.0,"maxGradePercent":9.5}],
             "gradeDistribution":[{"fromPercent":null,"toPercent":-8,"distanceMeters":200},{"fromPercent":-8,"toPercent":-4,"distanceMeters":1000},
                                  {"fromPercent":-4,"toPercent":-1,"distanceMeters":5000},{"fromPercent":-1,"toPercent":1,"distanceMeters":30000},
                                  {"fromPercent":1,"toPercent":4,"distanceMeters":15000},{"fromPercent":4,"toPercent":8,"distanceMeters":8500},
                                  {"fromPercent":8,"toPercent":null,"distanceMeters":550}]}"""
    }
}
