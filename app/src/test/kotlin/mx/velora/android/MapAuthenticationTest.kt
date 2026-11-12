package mx.velora.android

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mx.velora.android.map.MapAuthentication
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MapAuthenticationTest {

    private val map = MockWebServer()
    private val other = MockWebServer()
    private var token = "acceso-1"
    private val renewed = mutableListOf<String>()

    @Before
    fun start() {
        map.start()
        other.start()
    }

    @After
    fun stop() {
        map.close()
        other.close()
    }

    private fun client(): OkHttpClient {
        val auth = MapAuthentication(
            mapOrigin = map.url("/"),
            token = { token },
            renew = { stale ->
                renewed += stale
                token = "acceso-2"
                token
            },
        )
        return OkHttpClient.Builder().addInterceptor(auth).authenticator(auth).build()
    }

    @Test
    fun sendsTheTokenOnlyToTheMapHost() {
        map.enqueue(MockResponse.Builder().code(206).build())
        other.enqueue(MockResponse.Builder().code(200).build())

        client().newCall(Request.Builder().url(map.url("/mapa/mexico.pmtiles")).build()).execute().close()
        client().newCall(Request.Builder().url(other.url("/cualquier-cosa")).build()).execute().close()

        assertEquals("Bearer acceso-1", map.takeRequest().headers["Authorization"])
        assertNull(other.takeRequest().headers["Authorization"])
    }

    @Test
    fun renewsOnceWhenTheMapRejectsTheToken() {
        map.enqueue(MockResponse.Builder().code(401).build())
        map.enqueue(MockResponse.Builder().code(200).body("{}").build())

        val response = client().newCall(Request.Builder().url(map.url("/mapa/estilo/claro.json")).build()).execute()

        assertEquals(200, response.code)
        response.close()
        assertEquals(listOf("acceso-1"), renewed)
        map.takeRequest()
        assertEquals("Bearer acceso-2", map.takeRequest().headers["Authorization"])
    }

    @Test
    fun givesUpAfterOneRenewal() {
        map.enqueue(MockResponse.Builder().code(401).build())
        map.enqueue(MockResponse.Builder().code(401).build())

        val response = client().newCall(Request.Builder().url(map.url("/mapa/mexico.pmtiles")).build()).execute()

        assertEquals(401, response.code)
        response.close()
        assertEquals(1, renewed.size)
    }
}
