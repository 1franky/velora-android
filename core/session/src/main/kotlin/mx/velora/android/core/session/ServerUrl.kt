package mx.velora.android.core.session

import java.net.URI
import java.net.URISyntaxException

/** Dirección del servidor de Velora tal como la escribe el usuario en el login. */
object ServerUrl {

    sealed interface Result {
        data class Valid(val url: String) : Result

        /** Falta el esquema, el host, o trae ruta de más (p. ej. `/api/v1`, que la app agrega sola). */
        data object Invalid : Result

        /** `http://` en una versión que solo permite conexiones cifradas. */
        data object CleartextNotAllowed : Result
    }

    /** Normaliza a `esquema://host[:puerto]`, sin diagonal final. */
    fun parse(input: String, allowCleartext: Boolean): Result {
        val uri = try {
            URI(input.trim().trimEnd('/'))
        } catch (_: URISyntaxException) {
            return Result.Invalid
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid
        if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return Result.Invalid
        }
        if (!uri.path.isNullOrEmpty()) return Result.Invalid
        if (scheme == "http" && !allowCleartext) return Result.CleartextNotAllowed
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return Result.Valid("$scheme://${uri.host.lowercase()}$port")
    }
}
