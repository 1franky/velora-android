package mx.velora.android.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mx.velora.android.R

/** Por ahora, la cuenta: a qué servidor y con qué correo estás conectado, y cerrar sesión. */
@Composable
fun SettingsScreen(email: String, serverUrl: String, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.settings_account),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Field(stringResource(R.string.settings_email), email)
        Field(stringResource(R.string.settings_server), serverUrl)
        OutlinedButton(onClick = onLogout, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_logout))
        }
        Text(stringResource(R.string.settings_logout_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
