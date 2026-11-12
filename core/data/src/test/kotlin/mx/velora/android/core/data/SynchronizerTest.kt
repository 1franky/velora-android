package mx.velora.android.core.data

import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
class SynchronizerTest {

    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val paths = CopyOnWriteArrayList<String>()
    private var offline = false
    private var refreshStatus = HttpStatusCode.OK
    private var clock = 1_000L

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        if (path == "/api/v1/auth/refresh") {
            return@MockEngine if (refreshStatus == HttpStatusCode.OK) {
                respond(
                    """{"tokenType":"Bearer","accessToken":"a1","accessTokenExpiresIn":900,"refreshToken":"r1","refreshTokenExpiresIn":2592000}""",
                    HttpStatusCode.OK,
                    json,
                )
            } else {
                respond("""{"status":401,"title":"x"}""", HttpStatusCode.Unauthorized, json)
            }
        }
        if (offline) throw IOException("sin red")
        paths += path + "?" + request.url.encodedQuery
        when (path) {
            "/api/v1/activities" -> respond("""{"items":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond(
                """{"period":"${request.url.parameters["period"]}","timeZone":"America/Mexico_City","previous":null,
                    "monthlyTrend":[],"latestActivity":null,"current":{"from":null,"to":null,"activityCount":0,
                    "distanceMeters":0,"movingTimeSeconds":0,"elevationGainMeters":0}}""",
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
        appScope = CoroutineScope(Dispatchers.Unconfined),
        apiFor = { VeloraApi.create(it, engine) },
    ).also { runBlocking { it.restore() } }

    private val synchronizer = Synchronizer(
        DashboardRepository(session, db.dashboard()),
        ActivitiesRepository(session, db.activities()),
        db.sync(),
        now = { clock },
    )

    @After fun close() = db.close()

    @Test
    fun refreshesTheHistoryAndEveryPeriod() = runTest {
        assertEquals(RefreshResult.UPDATED, synchronizer.sync())

        assertEquals(
            listOf("/api/v1/activities", "/api/v1/dashboard/summary?period=week", "/api/v1/dashboard/summary?period=month",
                "/api/v1/dashboard/summary?period=year", "/api/v1/dashboard/summary?period=all"),
            paths.map { it.substringBefore("?limit").removeSuffix("?") },
        )
        val state = synchronizer.state().first()!!
        assertEquals(1_000L, state.lastSuccessEpochMs)
        assertEquals("UPDATED", state.lastResult)
    }

    @Test
    fun withoutNetworkKeepsTheLastSuccess() = runTest {
        synchronizer.sync()
        clock = 5_000L
        offline = true

        assertEquals(RefreshResult.OFFLINE, synchronizer.sync())

        val state = synchronizer.state().first()!!
        assertEquals(1_000L, state.lastSuccessEpochMs)
        assertEquals(5_000L, state.lastAttemptEpochMs)
        assertEquals("OFFLINE", state.lastResult)
    }

    @Test
    fun anEndedSessionStopsAndLeavesNoTrace() = runTest {
        refreshStatus = HttpStatusCode.Unauthorized

        assertEquals(RefreshResult.SESSION_ENDED, synchronizer.sync())

        assertEquals(emptyList<String>(), paths)
        assertNull(synchronizer.state().first())
    }
}
