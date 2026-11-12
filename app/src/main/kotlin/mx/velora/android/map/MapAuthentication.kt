package mx.velora.android.map

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * El mapa base de Velora exige sesión (ADR-015, ADR-024). MapLibre pide estilo, teselas, fuentes e iconos con su
 * propio cliente HTTP: aquí se agrega el Bearer **solo** a las solicitudes al host del mapa (nunca a un tercero) y, si
 * responde 401 porque el token venció, se renueva una vez y se reintenta.
 */
class MapAuthentication(
    private val mapOrigin: HttpUrl,
    private val token: suspend () -> String,
    private val renew: suspend (stale: String) -> String,
) : Interceptor, Authenticator {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isMapOrigin()) return chain.proceed(request)
        // Los hilos de OkHttp no son el principal: bloquear aquí no congela la interfaz.
        val current = runCatching { runBlocking { token() } }.getOrNull() ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(current))
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!request.url.isMapOrigin() || response.priorResponse != null) return null
        val stale = request.header(AUTHORIZATION)?.removePrefix("Bearer ") ?: return null
        val fresh = runCatching { runBlocking { renew(stale) } }.getOrNull() ?: return null
        return request.withBearer(fresh)
    }

    private fun HttpUrl.isMapOrigin() = scheme == mapOrigin.scheme && host == mapOrigin.host && port == mapOrigin.port

    private fun Request.withBearer(token: String) = newBuilder().header(AUTHORIZATION, "Bearer $token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
