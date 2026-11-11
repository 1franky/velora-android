package mx.velora.android.core.session

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mx.velora.android.core.network.VeloraApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionManagerTest {

    private val server = "http://servidor:8090"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemorySessionStore()
    private var clock = 1_000_000L
    private val requests = mutableListOf<Pair<String, String>>()

    private fun tokens(n: Int, expiresIn: Int = 900) =
        """{"tokenType":"Bearer","accessToken":"acceso-$n","accessTokenExpiresIn":$expiresIn,
            "refreshToken":"renovacion-$n","refreshTokenExpiresIn":2592000}"""

    private val me =
        """{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"propietario@example.com",
            "preferences":{"locale":"es-MX","timeZone":"America/Mexico_City","distanceUnit":"km",
            "elevationUnit":"m","trackPrivacyMeters":0,"maxHeartRateBpm":null}}"""

    private fun TestScope.manager(
        handler: suspend MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData,
    ): SessionManager {
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            synchronized(requests) { requests += request.url.encodedPath to body }
            handler(request, body)
        }
        return SessionManager(store, backgroundScope, apiFor = { VeloraApi.create(it, engine) }, now = { clock })
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, json)

    private fun MockRequestHandleScope.status(code: HttpStatusCode, headers: io.ktor.http.Headers = json) =
        respond("""{"status":${code.value},"title":"error"}""", code, headers)

    @Test
    fun loginKeepsOnlyTheRefreshTokenAndSignsIn() = runTest {
        val session = manager { _, _ -> ok(tokens(1)) }

        assertEquals(LoginResult.Success, session.login(server, "propietario@example.com", "secreta"))

        assertEquals(SessionState.SignedIn(server, "propietario@example.com"), session.state.value)
        assertEquals(StoredSession(server, "propietario@example.com", "renovacion-1"), store.session)
        assertEquals("/api/v1/auth/login", requests.single().first)
        assertTrue(requests.single().second.contains("\"password\":\"secreta\""))
        assertFalse("la contraseña no se guarda", store.toString().contains("secreta"))
    }

    @Test
    fun loginTranslatesTheApiAnswers() = runTest {
        var answer: HttpStatusCode = HttpStatusCode.Unauthorized
        val session = manager { _, _ ->
            if (answer == HttpStatusCode.TooManyRequests) {
                status(answer, headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("90")))
            } else {
                status(answer)
            }
        }

        assertEquals(LoginResult.InvalidCredentials, session.login(server, "a@b.mx", "x"))
        answer = HttpStatusCode.TooManyRequests
        assertEquals(LoginResult.TooManyAttempts(90), session.login(server, "a@b.mx", "x"))
        answer = HttpStatusCode.BadRequest
        assertEquals(LoginResult.InvalidInput, session.login(server, "a@b.mx", "x"))
        answer = HttpStatusCode.InternalServerError
        assertEquals(LoginResult.Unavailable, session.login(server, "a@b.mx", "x"))
        assertNull(store.session)
    }

    @Test
    fun loginWithoutAnswerIsUnreachable() = runTest {
        val session = manager { _, _ -> throw IOException("Connection refused") }

        assertEquals(LoginResult.Unreachable, session.login(server, "a@b.mx", "x"))
    }

    @Test
    fun loginAgainstSomethingThatIsNotVeloraIsUnavailable() = runTest {
        val session = manager { _, _ -> respond("<html>hola</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }

        assertEquals(LoginResult.Unavailable, session.login(server, "a@b.mx", "x"))
        assertNull(store.session)
    }

    @Test
    fun restoringDoesNotTouchTheNetwork() = runTest {
        store.session = StoredSession(server, "propietario@example.com", "renovacion-0")
        val session = manager { _, _ -> fail("sin red al abrir"); error("") }

        session.restore()

        assertEquals(SessionState.SignedIn(server, "propietario@example.com"), session.state.value)
    }

    @Test
    fun concurrentCallsRefreshOnlyOnce() = runTest {
        store.session = StoredSession(server, "propietario@example.com", "renovacion-0")
        val refreshes = AtomicInteger()
        val session = manager { request, body ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    // Un refresh token es de un solo uso: reutilizarlo revocaría la sesión.
                    assertTrue(body.contains("renovacion-0"))
                    ok(tokens(refreshes.incrementAndGet()))
                }
                else -> {
                    assertEquals("Bearer acceso-1", request.headers[HttpHeaders.Authorization])
                    ok(me)
                }
            }
        }
        session.restore()

        val results = List(4) { async { session.call { getMe() } } }.awaitAll()

        assertTrue(results.all { it.status == 200 })
        assertEquals(1, refreshes.get())
        assertEquals("renovacion-1", store.session?.refreshToken)
    }

    @Test
    fun refreshesBeforeTheAccessTokenExpires() = runTest {
        var issued = 0
        val session = manager { request, _ ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login", "/api/v1/auth/refresh" -> ok(tokens(++issued))
                else -> ok(me)
            }
        }
        session.login(server, "propietario@example.com", "secreta")

        session.call { getMe() }
        clock += 880_000 // a 20 s de vencer
        session.call { getMe() }

        assertEquals(listOf("/api/v1/auth/login", "/api/v1/me", "/api/v1/auth/refresh", "/api/v1/me"), requests.map { it.first })
    }

    @Test
    fun aRejectedAccessTokenIsRenewedAndTheCallRetried() = runTest {
        var issued = 0
        val session = manager { request, _ ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login", "/api/v1/auth/refresh" -> ok(tokens(++issued))
                // La API olvidó el primer access token (p. ej. se reinició con otra base de datos).
                else ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer acceso-1") status(HttpStatusCode.Unauthorized) else ok(me)
            }
        }
        session.login(server, "propietario@example.com", "secreta")

        assertEquals(200, session.call { getMe() }.status)
        assertEquals("renovacion-2", store.session?.refreshToken)
    }

    @Test
    fun aRejectedRefreshTokenEndsTheSession() = runTest {
        store.session = StoredSession(server, "propietario@example.com", "renovacion-0")
        val session = manager { _, _ -> status(HttpStatusCode.Unauthorized) }
        session.restore()

        try {
            session.call { getMe() }
            fail("debía terminar la sesión")
        } catch (_: SessionExpiredException) {
        }

        assertEquals(SessionState.SignedOut(SignOutReason.EXPIRED), session.state.value)
        assertNull(store.session)
        assertEquals(server, store.lastServerUrl())
    }

    @Test
    fun withoutNetworkTheSessionSurvives() = runTest {
        store.session = StoredSession(server, "propietario@example.com", "renovacion-0")
        val session = manager { _, _ -> throw IOException("sin red") }
        session.restore()

        try {
            session.call { getMe() }
            fail("debía fallar sin red")
        } catch (e: IOException) {
            assertFalse(e is SessionExpiredException)
        }

        assertEquals(SessionState.SignedIn(server, "propietario@example.com"), session.state.value)
        assertEquals("renovacion-0", store.session?.refreshToken)
    }

    @Test
    fun logoutEndsLocallyAndTellsTheApi() = runTest {
        val told = CompletableDeferred<Unit>()
        val session = manager { request, _ ->
            if (request.url.encodedPath == "/api/v1/auth/logout") {
                told.complete(Unit)
                respond("", HttpStatusCode.NoContent)
            } else {
                ok(tokens(1))
            }
        }
        session.login(server, "propietario@example.com", "secreta")

        session.logout()
        // El aviso a la API corre aparte (en el alcance de la app), con el motor HTTP en sus propios hilos.
        withContext(Dispatchers.Default) { withTimeout(5_000) { told.await() } }

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.session)
        assertEquals("/api/v1/auth/logout", requests.last().first)
        assertTrue(requests.last().second.contains("renovacion-1"))
        try {
            session.call { getMe() }
            fail("sin sesión no hay llamadas")
        } catch (_: SessionExpiredException) {
        }
    }

    @Test
    fun logoutWithoutNetworkStillEndsTheSession() = runTest {
        var online = true
        val session = manager { _, _ -> if (online) ok(tokens(1)) else throw IOException("sin red") }
        session.login(server, "propietario@example.com", "secreta")
        online = false

        session.logout()
        advanceUntilIdle()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.session)
    }
}

private class InMemorySessionStore : SessionStore {
    private var lastServer: String? = null
    var session: StoredSession? = null
        set(value) {
            field = value
            value?.let { lastServer = it.serverUrl }
        }

    override suspend fun read() = session

    override suspend fun write(session: StoredSession) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
    }

    override suspend fun lastServerUrl() = lastServer

    override fun toString() = "$session $lastServer"
}
