package mx.velora.android.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import mx.velora.android.R

/** Servidor, correo y contraseña. Los errores se anuncian al lector de pantalla en cuanto aparecen. */
@Composable
fun LoginScreen(viewModel: LoginViewModel, sessionExpired: Boolean) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 32.dp).semantics { heading() },
                )
                Text(stringResource(R.string.login_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sessionExpired && viewModel.error == null) {
                    Text(stringResource(R.string.login_expired), style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedTextField(
                    value = viewModel.server,
                    onValueChange = { viewModel.server = it },
                    label = { Text(stringResource(R.string.login_server)) },
                    supportingText = { Text(stringResource(R.string.login_server_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = viewModel.email,
                    onValueChange = { viewModel.email = it },
                    label = { Text(stringResource(R.string.login_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                )
                OutlinedTextField(
                    value = viewModel.password,
                    onValueChange = { viewModel.password = it },
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                    trailingIcon = {
                        val description = stringResource(
                            if (showPassword) R.string.login_hide_password_description else R.string.login_show_password_description,
                        )
                        TextButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.semantics { contentDescription = description },
                        ) {
                            Text(stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                viewModel.error?.let { error ->
                    Text(
                        errorMessage(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = viewModel::submit,
                    enabled = !viewModel.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (viewModel.submitting) R.string.login_submitting else R.string.login_submit))
                }
            }
        }
    }
}

@Composable
private fun errorMessage(error: LoginError): String = when (error) {
    LoginError.Required -> stringResource(R.string.login_error_required)
    LoginError.InvalidServer -> stringResource(R.string.login_error_server)
    LoginError.CleartextNotAllowed -> stringResource(R.string.login_error_cleartext)
    LoginError.InvalidCredentials -> stringResource(R.string.login_error_credentials)
    is LoginError.TooManyAttempts -> pluralStringResource(R.plurals.login_error_too_many, error.minutes, error.minutes)
    LoginError.InvalidInput -> stringResource(R.string.login_error_input)
    LoginError.Unreachable -> stringResource(R.string.login_error_unreachable)
    LoginError.Unavailable -> stringResource(R.string.login_error_unavailable)
}
