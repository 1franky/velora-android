package mx.velora.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.velora.android.core.designsystem.VeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "es-rMX")
class VeloraAppTest {

    @get:Rule val compose = createComposeRule()

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun startsAtHomeAndNavigatesBetweenSections() {
        compose.setContent { VeloraTheme { VeloraApp() } }

        compose.onNodeWithText("Tus totales de la semana, el mes y el año, y tu rodada más reciente.").assertExists()
        // "Inicio" aparece dos veces: en la barra y como título de la pantalla.
        assertEquals(2, compose.onAllNodesWithText("Inicio").fetchSemanticsNodes().size)

        compose.onNodeWithText("Rendimiento").performClick()
        compose.onNodeWithText("Tu distancia y desnivel por semana y por mes, tus récords y tu número de Eddington.")
            .assertExists()
        compose.onAllNodesWithText("Rendimiento").filterToOne(heading).assertExists()
    }

    @Test
    fun theBottomBarMarksTheCurrentSection() {
        compose.setContent { VeloraTheme { VeloraApp() } }
        val bar = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

        compose.onAllNodesWithText("Configuración").filterToOne(bar).assertIsNotSelected()
        compose.onAllNodesWithText("Configuración").filterToOne(bar).performClick()
        compose.onAllNodesWithText("Configuración").filterToOne(bar).assertIsSelected()
        compose.onAllNodesWithText("Inicio").filterToOne(bar).assertIsNotSelected()
    }
}
