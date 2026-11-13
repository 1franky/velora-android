package mx.velora.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.data.Synchronizer
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import mx.velora.android.settings.SettingsScreen
import mx.velora.android.sync.SyncSection
import mx.velora.android.sync.SyncViewModel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h1400dp")
class SyncSectionTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    @Volatile private var offline = false

    private val engine = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.encodedPath == "/api/v1/auth/refresh" -> respond(
                """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
            offline -> throw IOException("sin red")
            request.url.encodedPath == "/api/v1/activities" -> respond("""{"items":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond(
                """{"period":"month","timeZone":"America/Mexico_City","previous":null,"monthlyTrend":[],"latestActivity":null,
                    "current":{"from":null,"to":null,"activityCount":0,"distanceMeters":0,"movingTimeSeconds":0,"elevationGainMeters":0}}""",
                HttpStatusCode.OK,
                json,
            )
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

    private val synchronizer = Synchronizer(DashboardRepository(session, db.dashboard()), ActivitiesRepository(session, db.activities()), db.sync())

    @After fun tearDown() = scope.cancel()

    private fun start() = compose.setContent {
        VeloraTheme {
            SettingsScreen("propietario@example.com", "http://servidor", onLogout = {}) {
                SyncSection(viewModel { SyncViewModel(synchronizer) })
            }
        }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun syncsOnRequestAndSaysWhen() {
        start()
        waitForText("Aún no se sincroniza este teléfono.")

        compose.onNodeWithText("Sincronizar ahora").performClick()

        waitForText("Datos actualizados.")
        waitForText("Última sincronización:", substring = true)
    }

    @Test
    fun withoutNetworkItSaysSo() {
        offline = true
        start()

        compose.onNodeWithText("Sincronizar ahora").performClick()

        waitForText("Sin conexión con el servidor. Lo intentaremos de nuevo más tarde.")
        compose.onNodeWithText("Aún no se sincroniza este teléfono.").assertExists()
    }
}
