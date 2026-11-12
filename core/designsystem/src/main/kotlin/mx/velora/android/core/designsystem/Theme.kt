package mx.velora.android.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Identidad de Velora, la misma que la web: gris niebla de día, azul noche de sierra de noche y cempasúchil como único
 * acento. Mismos valores que los tokens de velora-web (globals.css), con los contrastes ya verificados (WCAG AA).
 */
object VeloraColors {
    val Niebla = Color(0xFFEEF1F3)
    val Superficie = Color(0xFFFFFFFF)
    val SuperficieHundida = Color(0xFFE2E7EB)
    val Linea = Color(0xFFC9D1D8)
    val Tinta = Color(0xFF1C2430)
    val TintaSuave = Color(0xFF4A5563)
    /** Acento para texto y botones con texto blanco (6.3:1). */
    val Acento = Color(0xFF9A4A06)
    /** Acento para elementos no textuales (gráficas, carriles). */
    val AcentoMedio = Color(0xFFC76A0E)
    val Negativo = Color(0xFFA3392A)
    val Positivo = Color(0xFF2F6B4F)
    /** Barras de contexto en gráficas (3.28:1 sobre blanco). */
    val GraficaTenue = Color(0xFF848F9D)

    val Noche = Color(0xFF121A24)
    val SuperficieNoche = Color(0xFF1B2532)
    val SuperficieHundidaNoche = Color(0xFF0D141C)
    val LineaNoche = Color(0xFF2C3A4A)
    val TintaNoche = Color(0xFFE6EBF0)
    val TintaSuaveNoche = Color(0xFFA7B2BF)
    val AcentoNoche = Color(0xFFF2A03D)
    val NegativoNoche = Color(0xFFF08A78)
    val PositivoNoche = Color(0xFF6FC59A)
    /** El acento oscuro (#F2A03D) es demasiado claro para un trazo. */
    val GraficaNoche = Color(0xFFD07C26)
    val GraficaTenueNoche = Color(0xFF6B7888)
}

/** Colores que Material 3 no tiene: gráficas y cambios positivos. Los mismos tokens que la web. */
@Immutable
data class VeloraExtraColors(
    /** El dato que importa en una gráfica (el mes actual). */
    val chart: Color,
    /** Datos de contexto en una gráfica. */
    val chartContext: Color,
    val positive: Color,
)

private val LightExtra = VeloraExtraColors(VeloraColors.AcentoMedio, VeloraColors.GraficaTenue, VeloraColors.Positivo)
private val DarkExtra = VeloraExtraColors(VeloraColors.GraficaNoche, VeloraColors.GraficaTenueNoche, VeloraColors.PositivoNoche)

val LocalVeloraColors = staticCompositionLocalOf { LightExtra }

private val Light: ColorScheme = lightColorScheme(
    primary = VeloraColors.Acento,
    onPrimary = Color.White,
    secondary = VeloraColors.AcentoMedio,
    onSecondary = VeloraColors.Tinta,
    // Indicador de la pestaña activa y chips: neutros, para que el cempasúchil siga siendo el único acento.
    secondaryContainer = VeloraColors.SuperficieHundida,
    onSecondaryContainer = VeloraColors.Tinta,
    background = VeloraColors.Niebla,
    onBackground = VeloraColors.Tinta,
    surface = VeloraColors.Superficie,
    onSurface = VeloraColors.Tinta,
    surfaceVariant = VeloraColors.SuperficieHundida,
    onSurfaceVariant = VeloraColors.TintaSuave,
    surfaceContainer = VeloraColors.Superficie,
    outline = VeloraColors.Linea,
    error = VeloraColors.Negativo,
    onError = Color.White,
)

private val Dark: ColorScheme = darkColorScheme(
    primary = VeloraColors.AcentoNoche,
    onPrimary = VeloraColors.Noche,
    secondary = VeloraColors.AcentoNoche,
    onSecondary = VeloraColors.Noche,
    secondaryContainer = VeloraColors.LineaNoche,
    onSecondaryContainer = VeloraColors.TintaNoche,
    background = VeloraColors.Noche,
    onBackground = VeloraColors.TintaNoche,
    surface = VeloraColors.SuperficieNoche,
    onSurface = VeloraColors.TintaNoche,
    surfaceVariant = VeloraColors.SuperficieHundidaNoche,
    onSurfaceVariant = VeloraColors.TintaSuaveNoche,
    surfaceContainer = VeloraColors.SuperficieNoche,
    outline = VeloraColors.LineaNoche,
    error = VeloraColors.NegativoNoche,
    onError = VeloraColors.Noche,
)

/** Sin color dinámico (Material You): la identidad de Velora es la misma en cualquier teléfono. */
@Composable
fun VeloraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVeloraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
    }
}
