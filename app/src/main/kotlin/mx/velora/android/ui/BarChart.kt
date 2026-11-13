package mx.velora.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mx.velora.android.R
import mx.velora.android.core.designsystem.LocalVeloraColors
import mx.velora.android.home.OutlinedPanel

/**
 * Barras de una sola serie (dataviz: un eje, sin leyenda; el título la nombra). La barra [emphasized] va en
 * cempasúchil y su etiqueta en negritas (no solo color); las demás, en gris de contexto.
 *
 * Accesible por construcción: es **un solo** elemento táctil (barras de ~29 dp serían difíciles de tocar). Tocar o
 * deslizar elige la barra más cercana y su lectura se escribe arriba; con lector de pantalla, la gráfica anuncia la
 * lectura elegida como estado y las acciones "anterior" y "siguiente" recorren las demás. Verificado con ATF
 * (`AccessibilityChecksTest`).
 *
 * @param labels etiqueta bajo cada barra; null deja el espacio vacío (no un texto vacío).
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String?>,
    readouts: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: String,
    emphasized: Int?,
    previousLabel: String = stringResource(R.string.chart_previous),
    nextLabel: String = stringResource(R.string.chart_next),
    height: Dp = 160.dp,
) {
    if (values.isEmpty()) return
    val last = values.lastIndex
    val colors = LocalVeloraColors.current
    val max = values.max().takeIf { it > 0 } ?: 1.0
    val pick = { x: Float, width: Int -> onSelect((x / width * values.size).toInt().coerceIn(0, last)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(readouts[selected], style = MaterialTheme.typography.bodyLarge)
        OutlinedPanel {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height)
                        .pointerInput(values.size) { detectTapGestures { pick(it.x, size.width) } }
                        .pointerInput(values.size) {
                            detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width) }
                        }
                        .semantics {
                            contentDescription = label
                            stateDescription = readouts[selected]
                            customActions = listOf(
                                CustomAccessibilityAction(previousLabel) { (selected > 0).also { if (it) onSelect(selected - 1) } },
                                CustomAccessibilityAction(nextLabel) { (selected < last).also { if (it) onSelect(selected + 1) } },
                            )
                        },
                ) {
                    values.forEachIndexed { index, value ->
                        Box(
                            contentAlignment = Alignment.BottomCenter,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(
                                    if (index == selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                ),
                        ) {
                            if (value > 0) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = if (values.size > 20) 1.dp else 4.dp)
                                        .fillMaxWidth()
                                        // Un valor pequeño sigue viéndose.
                                        .fillMaxHeight((value / max).toFloat().coerceAtLeast(0.02f))
                                        .background(
                                            if (index == emphasized) colors.chart else colors.chartContext,
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                        ),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                // Las etiquetas son decorativas para el lector de pantalla: cada lectura ya dice el periodo completo.
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics {}) {
                    labels.forEachIndexed { index, text ->
                        if (text != null) {
                            Text(
                                text,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (index == emphasized) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
