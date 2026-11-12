package mx.velora.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import mx.velora.android.core.designsystem.VeloraTheme

class MainActivity : ComponentActivity() {

    /** Archivos que otra app compartió con Velora y aún no entran a la cola. */
    private val shared = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        val app = application as VeloraApplication
        setContent {
            VeloraTheme {
                VeloraRoot(
                    app.session,
                    app.allowCleartext,
                    app.dashboard,
                    app.activities,
                    app.details,
                    app.imports,
                    shared = shared.value,
                    onSharedHandled = { shared.value = emptyList() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** "Compartir" desde otra app: uno o varios archivos. El permiso de lectura dura mientras viva esta actividad. */
    private fun receive(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) shared.value = shared.value + uris
    }
}
