package mx.velora.android.map

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mx.velora.android.R
import mx.velora.android.VeloraIcons

/**
 * Marco del mapa del detalle: una vista previa fija dentro de la pantalla que se desplaza y un botón que la abre a
 * pantalla completa, donde el mapa sí responde a gestos (acercar, mover, girar). [map] dibuja el mapa; recibe si debe
 * aceptar gestos. Separado de MapLibre, que es nativo y no corre en las pruebas de JVM.
 */
@Composable
fun ExpandableMap(title: String, map: @Composable (interactive: Boolean, modifier: Modifier) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        map(false, Modifier.fillMaxSize())
        FilledTonalIconButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(VeloraIcons.Expand, contentDescription = stringResource(R.string.map_fullscreen_open), modifier = Modifier.size(20.dp))
        }
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    map(true, Modifier.fillMaxSize())
                    Row(
                        modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalIconButton(onClick = { expanded = false }) {
                            Icon(
                                VeloraIcons.Close,
                                contentDescription = stringResource(R.string.map_fullscreen_close),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() },
                            )
                        }
                    }
                }
            }
        }
    }
}
