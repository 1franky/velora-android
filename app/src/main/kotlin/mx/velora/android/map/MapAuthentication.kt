package mx.velora.android.map

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * El mapa base de Velora exige sesión (ADR-015, ADR-024), y las teselas del mapa de calor del explorador salen de la
 * API (ADR-017). MapLibre pide estilo, teselas, fuentes e iconos con su propio cliente HTTP: aquí se agrega el Bearer
 * **solo** a las solicitudes al host del mapa y al de la API con la que hay sesión (nunca a un tercero) y, si responde
 * 401 porque el token venció, se renueva una vez y se reintenta.
 *
 * @param apiOrigin el servidor de la sesión vigente; se lee en cada solicitud porque se puede cambiar en el login.
 */
class MapAuthentication(
    private val mapOrigin: HttpUrl,
    private val token: suspend () -> String,
    private val renew: suspend (stale: String) -> String,
    private val apiOrigin: () -> HttpUrl? = { null },
) : Interceptor, Authenticator {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isOurs()) return chain.proceed(request)
        // Los hilos de OkHttp no son el principal: bloquear aquí no congela la interfaz.
        val current = runCatching { runBlocking { token() } }.getOrNull() ?: return chain.proceed(request)
        return chain.proceed(request.withBearer(current))
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (!request.url.isOurs() || response.priorResponse != null) return null
        val stale = request.header(AUTHORIZATION)?.removePrefix("Bearer ") ?: return null
        val fresh = runCatching { runBlocking { renew(stale) } }.getOrNull() ?: return null
        return request.withBearer(fresh)
    }

    private fun HttpUrl.isOurs() = sameOrigin(mapOrigin) || apiOrigin()?.let { sameOrigin(it) } == true

    private fun HttpUrl.sameOrigin(other: HttpUrl) = scheme == other.scheme && host == other.host && port == other.port

    private fun Request.withBearer(token: String) = newBuilder().header(AUTHORIZATION, "Bearer $token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
