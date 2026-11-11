package mx.velora.android.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mx.velora.android.core.database.DashboardCacheEntity
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardRepositoryTest {

    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to SUMMARY }
    private val queries = mutableListOf<String>()

    private val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/api/v1/auth/refresh" -> respond(TOKENS, HttpStatusCode.OK, json)
            else -> {
                queries += request.url.encodedQuery
                val (status, body) = answer()
                respond(body, status, json)
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
        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    private val repository = DashboardRepository(session, db.dashboard(), now = { 42 })

    @After fun close() = db.close()

    @Test
    fun savesWhatTheApiSaysAndServesItFromTheCache() = runTest {
        assertNull(repository.summary(Period.WEEK).first())

        assertEquals(RefreshResult.UPDATED, repository.refresh(Period.WEEK))

        val cached = repository.summary(Period.WEEK).first()!!
        assertEquals(42, cached.fetchedAtEpochMs)
        assertEquals(3, cached.value.current.activityCount)
        assertEquals(120_500.0, cached.value.current.distanceMeters, 0.0)
        assertNull(cached.value.previous)
        assertEquals("Vuelta al Ajusco", cached.value.latestActivity?.title)
        assertEquals("period=week", queries.single())
        // Otro periodo no comparte caché.
        assertNull(repository.summary(Period.MONTH).first())
    }

    @Test
    fun withoutNetworkTheCacheStays() = runTest {
        repository.refresh(Period.MONTH)
        answer = { throw IOException("sin red") }

        assertEquals(RefreshResult.OFFLINE, repository.refresh(Period.MONTH))
        assertEquals(3, repository.summary(Period.MONTH).first()?.value?.current?.activityCount)
    }

    @Test
    fun apiErrorsAndForeignBodiesAreFailures() = runTest {
        answer = { HttpStatusCode.InternalServerError to """{"status":500,"title":"Error"}""" }
        assertEquals(RefreshResult.FAILED, repository.refresh(Period.MONTH))
        answer = { HttpStatusCode.OK to """{"no":"es un resumen"}""" }
        assertEquals(RefreshResult.FAILED, repository.refresh(Period.MONTH))
        assertNull(repository.summary(Period.MONTH).first())
    }

    @Test
    fun anOutdatedCacheIsIgnored() = runTest {
        db.dashboard().upsert(DashboardCacheEntity("month", """{"formato":"viejo"}""", 1))

        assertNull(repository.summary(Period.MONTH).first())
    }

    private companion object {
        const val TOKENS =
            """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}"""

        val SUMMARY = """
            {"period":"week","timeZone":"America/Mexico_City",
             "current":{"from":"2026-11-09","to":"2026-11-15","activityCount":3,"distanceMeters":120500,
                        "movingTimeSeconds":18000,"elevationGainMeters":1500.5},
             "previous":null,
             "monthlyTrend":[{"month":"2026-11","activityCount":3,"distanceMeters":120500,"movingTimeSeconds":18000,
                              "elevationGainMeters":1500.5}],
             "latestActivity":{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","title":"Vuelta al Ajusco",
                               "startedAt":"2026-11-10T13:05:00Z","localStartDate":"2026-11-10","subType":"ROAD",
                               "distanceMeters":60250,"movingTimeSeconds":9000,"elapsedTimeSeconds":9600,
                               "elevationGainMeters":900,"averageSpeedMps":6.69,"bikeId":null}}
        """.trimIndent()
    }
}
