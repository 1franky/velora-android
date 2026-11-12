package mx.velora.android.map

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mx.velora.android.R
import mx.velora.android.api.models.ActivityTrack
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** Arranque único de MapLibre con el cliente HTTP que manda el Bearer al host del mapa (ADR-024). */
object VeloraMap {
    @Volatile private var started = false

    @Synchronized
    fun start(context: Context, session: SessionManager, mapUrl: String) {
        if (started) return
        MapLibre.getInstance(context.applicationContext)
        val auth = MapAuthentication(mapUrl.toHttpUrl(), token = { session.accessToken() }, renew = { session.renewAccessToken(it) })
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor(auth).authenticator(auth).build())
        started = true
    }
}

/** Colores del recorrido, los mismos de la web (`TRACK_COLORS`): el acento, con un borde que lo separa de las calles. */
private data class TrackColors(val line: String, val casing: String)

private val LIGHT = TrackColors(line = "#c76a0e", casing = "#ffffff")
private val DARK = TrackColors(line = "#f2a03d", casing = "#0d141c")

/**
 * El recorrido sobre el mapa base de Velora, encuadrado completo. Sin gestos: dentro de una pantalla que se desplaza,
 * arrastrar el mapa competiría con el desplazamiento. Sin marcas de inicio ni fin (privacidad, ADR-016).
 */
@Composable
fun ActivityMap(title: String, track: ActivityTrack, session: SessionManager, mapUrl: String) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) DARK else LIGHT
    val styleUrl = "${mapUrl.trimEnd('/')}/mapa/estilo/${if (dark) "oscuro" else "claro"}.json"
    val geoJson = remember(track) { VeloraApi.json.encodeToString(ActivityTrack.serializer(), track) }
    val label = stringResource(R.string.map_of, title)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        VeloraMap.start(context, session, mapUrl)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.setAllGesturesEnabled(false)
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    style.addSource(GeoJsonSource(SOURCE, geoJson))
                    style.addLayer(
                        LineLayer("recorrido-borde", SOURCE).withProperties(
                            PropertyFactory.lineColor(colors.casing),
                            PropertyFactory.lineWidth(7f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    style.addLayer(
                        LineLayer("recorrido", SOURCE).withProperties(
                            PropertyFactory.lineColor(colors.line),
                            PropertyFactory.lineWidth(4f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    // bbox: [oeste, sur, este, norte].
                    val (west, south, east, north) = track.bbox
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), PADDING))
                }
            }
            mapView
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .semantics { contentDescription = label },
    )
}

private const val SOURCE = "recorrido"
private const val PADDING = 48
