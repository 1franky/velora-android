package mx.velora.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mx.velora.android.core.designsystem.VeloraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VeloraApplication
        setContent { VeloraTheme { VeloraRoot(app.session, app.allowCleartext, app.dashboard) } }
    }
}
