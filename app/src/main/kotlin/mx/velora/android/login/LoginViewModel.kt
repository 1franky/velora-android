package mx.velora.android.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import mx.velora.android.core.session.LoginResult
import mx.velora.android.core.session.ServerUrl
import mx.velora.android.core.session.SessionManager

sealed interface LoginError {
    data object Required : LoginError

    data object InvalidServer : LoginError

    data object CleartextNotAllowed : LoginError

    data object InvalidCredentials : LoginError

    data class TooManyAttempts(val minutes: Int) : LoginError

    data object InvalidInput : LoginError

    data object Unreachable : LoginError

    data object Unavailable : LoginError
}

class LoginViewModel(
    private val session: SessionManager,
    private val allowCleartext: Boolean,
    /** El servidor desplegado: el campo arranca con él si nunca se ha entrado a otro. */
    defaultServerUrl: String,
) : ViewModel() {

    var server by mutableStateOf(defaultServerUrl)
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var error by mutableStateOf<LoginError?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set

    init {
        // El último servidor usado tiene prioridad, para no escribirlo cada vez.
        viewModelScope.launch {
            val last = session.lastServerUrl()
            if (last != null && server == defaultServerUrl) server = last
        }
    }

    fun submit() {
        if (submitting) return
        if (server.isBlank() || email.isBlank() || password.isEmpty()) {
            error = LoginError.Required
            return
        }
        val url = when (val parsed = ServerUrl.parse(server, allowCleartext)) {
            is ServerUrl.Result.Valid -> parsed.url
            ServerUrl.Result.Invalid -> return run { error = LoginError.InvalidServer }
            ServerUrl.Result.CleartextNotAllowed -> return run { error = LoginError.CleartextNotAllowed }
        }
        server = url
        submitting = true
        error = null
        viewModelScope.launch {
            val result = session.login(url, email.trim(), password)
            submitting = false
            error = when (result) {
                LoginResult.Success -> null.also { password = "" }
                LoginResult.InvalidCredentials -> LoginError.InvalidCredentials
                is LoginResult.TooManyAttempts ->
                    LoginError.TooManyAttempts(((result.retryAfterSeconds ?: 60L) + 59).div(60).toInt().coerceAtLeast(1))
                LoginResult.InvalidInput -> LoginError.InvalidInput
                LoginResult.Unreachable -> LoginError.Unreachable
                LoginResult.Unavailable -> LoginError.Unavailable
            }
        }
    }
}
