package mx.velora.android.core.session

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mx.velora.android.api.apis.DefaultApi
import mx.velora.android.api.infrastructure.HttpResponse
import mx.velora.android.api.models.LoginRequest
import mx.velora.android.api.models.RefreshRequest
import mx.velora.android.api.models.TokenResponse
import mx.velora.android.core.network.VeloraApi

sealed interface SessionState {
    /** Leyendo la sesión guardada al abrir la app. */
    data object Restoring : SessionState

    data class SignedOut(val reason: SignOutReason? = null) : SessionState

    data class SignedIn(val serverUrl: String, val email: String) : SessionState
}

enum class SignOutReason {
    /** La API rechazó el refresh token: venció, se cerró la sesión en otro lado o se reutilizó un token rotado. */
    EXPIRED,
}

sealed interface LoginResult {
    data object Success : LoginResult

    data object InvalidCredentials : LoginResult

    /** Rate limiting del login (ADR-010); [retryAfterSeconds] viene de `Retry-After`. */
    data class TooManyAttempts(val retryAfterSeconds: Long?) : LoginResult

    /** La API rechazó el formato (p. ej. un correo inválido). */
    data object InvalidInput : LoginResult

    /** No hubo respuesta: dirección equivocada, servidor apagado o sin red. */
    data object Unreachable : LoginResult

    /** Respondió algo que no es Velora o un error del servidor. */
    data object Unavailable : LoginResult
}

/** Ya no hay sesión: la pantalla de login la reemplaza (el estado cambia a [SessionState.SignedOut]). */
class SessionExpiredException : IOException("La sesión terminó")

/** La API respondió un error al renovar los tokens; la sesión sigue y se puede reintentar. */
class ServiceUnavailableException(status: Int) : IOException("La API respondió $status al renovar la sesión")

/**
 * Dueño de la sesión (ADR-010, ADR-022). El access token vive solo en memoria; el refresh token, cifrado en el teléfono.
 * Las renovaciones se serializan: el refresh token es de un solo uso y reutilizarlo revoca toda la sesión, así que dos
 * pantallas que encuentran el token vencido a la vez no pueden renovar las dos.
 */
class SessionManager(
    private val store: SessionStore,
    /** Alcance de la app: cerrar sesión en el servidor sigue aunque la pantalla que lo pidió ya no exista. */
    private val appScope: CoroutineScope,
    private val apiFor: (serverUrl: String) -> DefaultApi = { VeloraApi.create(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var api: DefaultApi? = null
    private var serverUrl: String? = null
    private var email: String? = null
    private var refreshToken: String? = null

    @Volatile private var access: AccessToken? = null

    private class AccessToken(val value: String, val expiresAt: Long)

    /** Recupera la sesión guardada sin tocar la red: la app abre aunque no haya conexión. */
    suspend fun restore() {
        mutex.withLock {
            val saved = store.read()
            if (saved == null) {
                _state.value = SessionState.SignedOut()
                return
            }
            api = apiFor(saved.serverUrl)
            serverUrl = saved.serverUrl
            email = saved.email
            refreshToken = saved.refreshToken
            access = null
            _state.value = SessionState.SignedIn(saved.serverUrl, saved.email)
        }
    }

    suspend fun lastServerUrl(): String? = store.lastServerUrl()

    suspend fun login(serverUrl: String, email: String, password: String): LoginResult {
        val api = apiFor(serverUrl)
        val response = try {
            api.login(LoginRequest(email = email, password = password))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LoginResult.Unreachable
        }
        return when (response.status) {
            200 -> {
                val tokens = bodyOrNull(response) ?: return LoginResult.Unavailable
                mutex.withLock {
                    store.write(StoredSession(serverUrl, email, tokens.refreshToken))
                    this.api = api
                    this.serverUrl = serverUrl
                    this.email = email
                    adopt(api, tokens)
                    _state.value = SessionState.SignedIn(serverUrl, email)
                }
                LoginResult.Success
            }
            401 -> LoginResult.InvalidCredentials
            429 -> LoginResult.TooManyAttempts(response.response.headers["Retry-After"]?.trim()?.toLongOrNull())
            400 -> LoginResult.InvalidInput
            else -> LoginResult.Unavailable
        }
    }

    /**
     * Ejecuta una llamada autenticada. Renueva el access token si venció o está por vencer, y una vez más si la API
     * responde 401. Lanza [SessionExpiredException] si ya no hay sesión, o [IOException] si no hay red.
     */
    suspend fun <T : Any> call(request: suspend DefaultApi.() -> HttpResponse<T>): HttpResponse<T> {
        val token = validAccessToken()
        val api = api ?: throw SessionExpiredException()
        val response = api.request()
        if (response.status != 401) return response
        refresh(stale = token)
        return api.request()
    }

    /** Cierra la sesión en el teléfono de inmediato y avisa a la API cuando pueda (sin red, el token caduca solo). */
    suspend fun logout() {
        val (api, token) = mutex.withLock {
            val pending = api to refreshToken
            endLocked(reason = null)
            pending
        }
        if (api != null && token != null) {
            appScope.launch {
                try {
                    api.logout(RefreshRequest(token))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Sin conexión: la sesión ya no existe en el teléfono y el refresh token vence en 30 días.
                }
            }
        }
    }

    private suspend fun validAccessToken(): String {
        val current = access
        if (current != null && current.isFresh()) return current.value
        return refresh(stale = current?.value)
    }

    /** Renueva salvo que otra llamada ya lo haya hecho mientras esta esperaba el candado. */
    private suspend fun refresh(stale: String?): String = mutex.withLock {
        access?.let { if (it.value != stale && it.isFresh()) return it.value }
        val api = api ?: throw SessionExpiredException()
        val token = refreshToken ?: throw SessionExpiredException()
        val response = api.refreshTokens(RefreshRequest(token))
        if (response.status == 401) {
            endLocked(SignOutReason.EXPIRED)
            throw SessionExpiredException()
        }
        if (!response.success) throw ServiceUnavailableException(response.status)
        val tokens = bodyOrNull(response) ?: throw ServiceUnavailableException(response.status)
        // Se guarda antes de usarlo: el anterior ya no sirve.
        store.write(StoredSession(checkNotNull(serverUrl), checkNotNull(email), tokens.refreshToken))
        adopt(api, tokens)
        tokens.accessToken
    }

    private fun adopt(api: DefaultApi, tokens: TokenResponse) {
        refreshToken = tokens.refreshToken
        access = AccessToken(tokens.accessToken, now() + tokens.accessTokenExpiresIn * 1_000L)
        api.setBearerToken(tokens.accessToken)
    }

    private suspend fun endLocked(reason: SignOutReason?) {
        store.clear()
        api = null
        serverUrl = null
        email = null
        refreshToken = null
        access = null
        _state.value = SessionState.SignedOut(reason)
    }

    private fun AccessToken.isFresh() = expiresAt - now() > EXPIRY_MARGIN_MS

    private suspend fun bodyOrNull(response: HttpResponse<TokenResponse>): TokenResponse? =
        try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** Renovar un poco antes de que venza evita un 401 seguro en la siguiente llamada. */
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
