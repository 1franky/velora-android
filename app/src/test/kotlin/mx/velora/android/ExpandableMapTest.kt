package mx.velora.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.velora.android.core.designsystem.VeloraTheme
import mx.velora.android.map.ExpandableMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX-w411dp-h2400dp")
class ExpandableMapTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun thePreviewIsFixedAndFullScreenAcceptsGestures() {
        compose.setContent {
            VeloraTheme {
                ExpandableMap("Vuelta al Ajusco") { interactive, modifier ->
                    Text(if (interactive) "Mapa con gestos" else "Vista previa sin gestos", modifier = modifier)
                }
            }
        }

        compose.onNodeWithText("Vista previa sin gestos").assertExists()
        compose.onAllNodesWithText("Mapa con gestos").assertCountEquals(0)

        compose.onNodeWithContentDescription("Ver el mapa en pantalla completa").performClick()
        compose.onNodeWithText("Mapa con gestos").assertExists()
        compose.onNodeWithText("Vuelta al Ajusco").assertExists()

        compose.onNodeWithContentDescription("Cerrar el mapa").performClick()
        compose.onAllNodesWithText("Mapa con gestos").assertCountEquals(0)
        compose.onNodeWithText("Vista previa sin gestos").assertExists()
    }
}
