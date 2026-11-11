package mx.velora.android.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeloraApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun buildsTheBaseUrlFromWhatTheUserTypes() {
        assertEquals("http://10.0.2.2:8090/api/v1", VeloraApi.baseUrl(" http://10.0.2.2:8090/ "))
        assertEquals("https://velora.example/api/v1", VeloraApi.baseUrl("https://velora.example"))
    }

    @Test
    fun readsTheProfileWithTheBearerTokenAndOptionalFields() = runTest {
        var authorization: String? = null
        var path: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            path = request.url.encodedPath
            respond(
                // Como lo devuelve la API: FC máxima nula y un campo que la app aún no conoce.
                """
                {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"propietario@example.com",
                 "preferences":{"locale":"es-MX","timeZone":"America/Mexico_City","distanceUnit":"km",
                 "elevationUnit":"m","trackPrivacyMeters":300,"maxHeartRateBpm":null,"nuevoCampo":1}}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val api = VeloraApi.create("http://servidor:8090", engine)
        api.setBearerToken("token-de-prueba")

        val response = api.getMe()

        assertTrue(response.success)
        val me = response.body()
        assertEquals("propietario@example.com", me.email)
        assertEquals(300, me.preferences.trackPrivacyMeters)
        assertNull(me.preferences.maxHeartRateBpm)
        assertEquals("6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10", me.id.toString())
        assertEquals("/api/v1/me", path)
        assertEquals("Bearer token-de-prueba", authorization)
    }

    @Test
    fun readsDatesAndInstantsOfAnActivityPage() = runTest {
        val engine = MockEngine {
            respond(
                """
                {"items":[{"id":"a0000000-0000-4000-8000-000000000001","title":"Rodada","startedAt":"2026-11-08T13:00:00Z",
                 "localStartDate":"2026-11-08","subType":"GRAVEL","distanceMeters":42195.5,"movingTimeSeconds":5400,
                 "elapsedTimeSeconds":6000,"elevationGainMeters":null,"averageSpeedMps":7.8,"bikeId":null}],
                 "nextCursor":null}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val page = VeloraApi.create("http://servidor", engine)
            .listActivities(null, null, null, null, null, null, null, null)
            .body()
        val ride = page.items.single()
        assertEquals("2026-11-08", ride.localStartDate.toString())
        assertEquals(2026, ride.startedAt.year)
        assertEquals(42195.5, ride.distanceMeters!!, 0.0)
        assertNull(ride.elevationGainMeters)
    }

    @Test
    fun aProblemResponseIsNotASuccess() = runTest {
        val engine = MockEngine {
            respond(
                """{"title":"No autenticado","status":401,"detail":"El token expiró."}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val response = VeloraApi.create("http://servidor", engine).getMe()
        assertEquals(401, response.status)
        assertTrue(!response.success)
    }
}
