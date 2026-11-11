package mx.velora.android.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mx.velora.android.api.apis.DefaultApi

/**
 * Punto de entrada al cliente generado del contrato (ADR-011). El servidor lo elige el usuario (decisión del
 * 11/11/2026): hasta desplegar Velora, la API corre en su computadora.
 */
object VeloraApi {

    /** Tolerante a campos nuevos: una API más reciente que la app no la rompe (los enums ya tienen caso desconocido). */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = ContractSerializers.module
    }

    /** `https://servidor` → `https://servidor/api/v1`, sin importar si traía diagonal final. */
    fun baseUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/') + "/api/v1"

    fun create(serverUrl: String, engine: HttpClientEngine = OkHttp.create()): DefaultApi =
        DefaultApi(
            baseUrl = baseUrl(serverUrl),
            httpClientEngine = engine,
            httpClientConfig = {
                it.install(ContentNegotiation) { json(json) }
                it.install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    // Subir un archivo grande por datos móviles puede tardar.
                    requestTimeoutMillis = 120_000
                }
            },
        )
}
