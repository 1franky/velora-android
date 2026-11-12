package mx.velora.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.velora.android.core.data.ActivitiesRepository
import mx.velora.android.core.data.ActivityDetailRepository
import mx.velora.android.core.data.DashboardRepository
import mx.velora.android.core.data.ImportsRepository
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionState
import mx.velora.android.core.session.SignOutReason
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
@Config(sdk = [35], qualifiers = "es-rMX")
class LoginFlowTest {

    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryStore()
    private val paths = CopyOnWriteArrayList<String>()

    /** La API falsa: acepta la contraseña "correcta" y responde 429 a "insistente". */
    private val engine = MockEngine { request ->
        paths += request.url.encodedPath
        val body = request.body.toByteArray().decodeToString()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            request.url.encodedPath == "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
            // Inicio pide su resumen en cuanto aparece: una cuenta sin rodadas.
            request.url.encodedPath == "/api/v1/dashboard/summary" ->
                respond(
                    """{"period":"month","timeZone":"America/Mexico_City","previous":null,"monthlyTrend":[],
                        "latestActivity":null,"current":{"from":"2026-11-01","to":"2026-11-30","activityCount":0,
                        "distanceMeters":0,"movingTimeSeconds":0,"elevationGainMeters":0}}""",
                    HttpStatusCode.OK,
                    json,
                )
            body.contains("\"insistente\"") ->
                respond("{}", HttpStatusCode.TooManyRequests, headersOf("Retry-After" to listOf("120"), HttpHeaders.ContentType to listOf("application/json")))
            body.contains("\"correcta\"") ->
                respond(
                    """{"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,"refreshToken":"r","refreshTokenExpiresIn":2592000}""",
                    HttpStatusCode.OK,
                    json,
                )
            else -> respond("""{"status":401,"title":"No autorizado"}""", HttpStatusCode.Unauthorized, json)
        }
    }

    private val session = SessionManager(store, scope, apiFor = { VeloraApi.create(it, engine) })
    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun start(allowCleartext: Boolean = true) {
        runBlocking { session.restore() }
        compose.setContent { VeloraTheme { VeloraRoot(session, allowCleartext, DashboardRepository(session, db.dashboard()), ActivitiesRepository(session, db.activities()), ActivityDetailRepository(session), ImportsRepository(session, db.imports())) } }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun signIn(server: String, password: String) {
        field("Servidor de Velora").performTextReplacement(server)
        field("Correo electrónico").performTextReplacement("propietario@example.com")
        field("Contraseña").performTextReplacement(password)
        submit()
    }

    /** La pantalla de Robolectric es chica: el botón queda abajo del pliegue y hay que desplazarse para tocarlo. */
    private fun submit() {
        compose.onNodeWithText("Entrar").performScrollTo().performClick()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun startsWithTheDeployedServer() {
        start()
        field("Servidor de Velora").assert(hasText("https://velora-api.franciscolopez.uk"))
    }

    @Test
    fun asksForEverythingBeforeCallingTheApi() {
        start()
        submit()
        compose.onNodeWithText("Escribe el servidor, tu correo y tu contraseña.").assertExists()

        signIn("10.0.2.2:8090", "correcta")
        compose.onNodeWithText("Escribe la dirección completa del servidor, con http:// o https:// y sin ruta.").assertExists()
        assertTrue(paths.isEmpty())
    }

    @Test
    fun thePublishableVersionOnlyTalksHttps() {
        start(allowCleartext = false)
        signIn("http://192.168.1.20:8090", "correcta")
        compose.onNodeWithText("Usa una dirección https://: esta versión de la app no envía tu contraseña sin cifrar.").assertExists()
        assertTrue(paths.isEmpty())
    }

    @Test
    fun explainsWhyTheLoginFailed() {
        start()
        signIn("http://10.0.2.2:8090", "equivocada")
        waitForText("Correo o contraseña incorrectos.")

        field("Contraseña").performTextReplacement("insistente")
        submit()
        waitForText("Demasiados intentos. Espera 2 minutos antes de volver a intentarlo.")
    }

    @Test
    fun signsInShowsTheAccountAndSignsOut() {
        start()
        signIn("http://10.0.2.2:8090/", "correcta")

        waitForText("Aún no tienes actividades")
        assertEquals(StoredSession("http://10.0.2.2:8090", "propietario@example.com", "r"), store.session)

        val bar = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        compose.onAllNodesWithText("Configuración").filterToOne(bar).performClick()
        compose.onNodeWithText("propietario@example.com").assertExists()
        compose.onNodeWithText("http://10.0.2.2:8090").assertExists()

        compose.onNodeWithText("Cerrar sesión").performClick()
        waitForText("Iniciar sesión")
        assertNull(store.session)
        // El último servidor queda escrito; la contraseña no.
        field("Servidor de Velora").assertExists().assert(hasText("http://10.0.2.2:8090"))
        compose.waitUntil(5_000) { paths.contains("/api/v1/auth/logout") }
    }

    @Test
    fun saysWhenTheSessionEnded() {
        store.session = StoredSession("http://10.0.2.2:8090", "propietario@example.com", "revocado")
        start()

        // Inicio pide su resumen; la API rechaza el refresh token (401): la sesión termina y el login lo explica.
        waitForText("Tu sesión terminó. Vuelve a entrar para continuar.")
        assertEquals(SessionState.SignedOut(SignOutReason.EXPIRED), session.state.value)
        assertNull(store.session)
    }
}

private class MemoryStore : SessionStore {
    @Volatile var session: StoredSession? = null
    @Volatile private var last: String? = null

    override suspend fun read() = session

    override suspend fun write(session: StoredSession) {
        this.session = session
        last = session.serverUrl
    }

    override suspend fun clear() {
        session?.let { last = it.serverUrl }
        session = null
    }

    override suspend fun lastServerUrl() = last
}
