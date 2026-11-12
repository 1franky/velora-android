package mx.velora.android.core.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDetailRepositoryTest {

    private val id = UUID.fromString("6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val queries = mutableListOf<String>()
    private var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }

    private val engine = MockEngine { request ->
        if (request.url.encodedPath == "/api/v1/auth/refresh") {
            return@MockEngine respond(
                """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
        }
        queries += request.url.encodedPath + "?" + request.url.encodedQuery
        val (status, body) = answer()
        respond(body, status, json)
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
            appScope = CoroutineScope(Dispatchers.Unconfined),
            apiFor = { VeloraApi.create(it, engine) },
        ).also { runBlocking { it.restore() } },
    )

    @Test
    fun streamsNeverAskForPositions() = runTest {
        answer = { HttpStatusCode.OK to """{"sampleCount":2,"streams":{"ALTITUDE":[2300,null],"TIME":[0,1000]}}""" }

        val result = repository.streams(id)

        assertEquals(listOf(2300.0, null), (result as Remote.Ok).value.streams["ALTITUDE"])
        val query = java.net.URLDecoder.decode(queries.single(), "UTF-8")
        assertTrue(query, query.contains("types=TIME,DISTANCE,ALTITUDE"))
        assertFalse(query.contains("LATITUDE"))
        assertFalse(query.contains("LONGITUDE"))
    }

    @Test
    fun aMissingActivityAndFailuresAreTold() = runTest {
        answer = { HttpStatusCode.NotFound to """{"status":404,"title":"No encontrada"}""" }
        assertEquals(Remote.NotFound, repository.detail(id))
        answer = { HttpStatusCode.InternalServerError to """{"status":500,"title":"Error"}""" }
        assertEquals(Remote.Failed(RefreshResult.FAILED), repository.laps(id))
        answer = { throw IOException("sin red") }
        assertEquals(Remote.Failed(RefreshResult.OFFLINE), repository.climbs(id))
    }
}
