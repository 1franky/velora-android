package mx.velora.android.core.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mx.velora.android.api.models.ActivitySubType
import mx.velora.android.core.database.VeloraDatabase
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.core.session.SessionStore
import mx.velora.android.core.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ActivitiesRepositoryTest {

    private val db = VeloraDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val queries = mutableListOf<String>()

    /** Respuestas por cursor (null = primera página). */
    private var pages: Map<String?, Pair<List<Int>, String?>> = emptyMap()
    private var status = HttpStatusCode.OK
    private var offline = false

    private val engine = MockEngine { request ->
        if (request.url.encodedPath == "/api/v1/auth/refresh") return@MockEngine respond(TOKENS, HttpStatusCode.OK, json)
        if (offline) throw IOException("sin red")
        queries += request.url.encodedQuery
        if (status != HttpStatusCode.OK) return@MockEngine respond("""{"status":${status.value},"title":"x"}""", status, json)
        if (request.url.encodedPath == "/api/v1/bikes") {
            return@MockEngine respond(
                """{"items":[{"id":"b1a1c0de-0000-4000-8000-000000000001","name":"Gravel de diario","bikeType":"GRAVEL",
                    "brand":null,"model":null,"weightKg":null,"active":true,"createdAt":"2026-01-01T00:00:00Z"}]}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val (days, next) = pages.getValue(request.url.parameters["cursor"])
        val items = days.joinToString(",") { ride(it) }
        respond("""{"items":[$items],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""", HttpStatusCode.OK, json)
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

    private val repository = ActivitiesRepository(session, db.activities())

    @After fun close() = db.close()

    private suspend fun cachedDays() = repository.cached().first().map { it.localStartDate.dayOfMonth }

    @Test
    fun savesPagesAndForgetsWhatTheServerDeleted() = runTest {
        pages = mapOf(null to (listOf(30, 29, 28) to "c1"), "c1" to (listOf(27, 26) to null))
        repository.syncPage(null)
        repository.syncPage("c1")
        assertEquals(listOf(30, 29, 28, 27, 26), cachedDays())

        // En el servidor se borró la del 29 y se importó una del 31.
        pages = mapOf(null to (listOf(31, 30, 28) to "c1"))
        val first = repository.syncPage(null)

        assertEquals("c1", (first as PageResult.Loaded).nextCursor)
        // Las del 27 y el 26 no se tocan: la primera página no llega hasta ellas.
        assertEquals(listOf(31, 30, 28, 27, 26), cachedDays())
        assertTrue(queries.first().contains("limit=50"))
    }

    @Test
    fun theLastPageClearsEverythingOlder() = runTest {
        pages = mapOf(null to (listOf(30, 29, 20) to null))
        repository.syncPage(null)
        pages = mapOf(null to (listOf(30) to null))

        repository.syncPage(null)

        assertEquals(listOf(30), cachedDays())
    }

    @Test
    fun filtersGoToTheApiInMetersAndAreNotSaved() = runTest {
        pages = mapOf(null to (listOf(12) to null))

        val result = repository.search(
            ActivityFilters(from = LocalDate.of(2026, 11, 1), subType = ActivitySubType.GRAVEL, minKm = 20.5, maxKm = 80.0),
            cursor = null,
        )

        assertEquals(1, (result as PageResult.Loaded).items.size)
        val query = queries.single()
        listOf("from=2026-11-01", "subType=GRAVEL", "minDistanceM=20500.0", "maxDistanceM=80000.0").forEach {
            assertTrue("$query sin $it", query.contains(it))
        }
        assertEquals(emptyList<Int>(), cachedDays())
    }

    @Test
    fun failuresKeepTheCache() = runTest {
        pages = mapOf(null to (listOf(30) to null))
        repository.syncPage(null)

        offline = true
        assertEquals(PageResult.Failed(RefreshResult.OFFLINE), repository.syncPage(null))
        offline = false
        status = HttpStatusCode.BadRequest
        assertEquals(PageResult.InvalidFilters, repository.search(ActivityFilters(minKm = 5.0), null))
        status = HttpStatusCode.InternalServerError
        assertEquals(PageResult.Failed(RefreshResult.FAILED), repository.syncPage(null))

        assertEquals(listOf(30), cachedDays())
    }

    @Test
    fun bikesForTheFilter() = runTest {
        assertEquals("Gravel de diario", repository.bikes()?.single()?.name)
        offline = true
        assertEquals(null, repository.bikes())
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
