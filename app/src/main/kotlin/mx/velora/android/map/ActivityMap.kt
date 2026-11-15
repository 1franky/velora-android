package mx.velora.android.map

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mx.velora.android.R
import mx.velora.android.api.models.ActivityTrack
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.detail.LngLat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
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
 * El recorrido sobre el mapa base de Velora. En el detalle es una vista previa encuadrada y sin gestos: dentro de una
 * pantalla que se desplaza, arrastrar el mapa competiría con el desplazamiento. Su botón lo abre a pantalla completa,
 * donde sí se puede acercar, mover y girar (ADR-024). Sin marcas de inicio ni fin (privacidad, ADR-016). [position] es
 * el punto señalado en las gráficas, como en la web.
 */
@Composable
fun ActivityMap(title: String, track: ActivityTrack, position: LngLat?, session: SessionManager, mapUrl: String) {
    ExpandableMap(title) { interactive, modifier ->
        TrackMap(title, track, position, session, mapUrl, interactive, modifier)
    }
}

@Composable
private fun TrackMap(
    title: String,
    track: ActivityTrack,
    position: LngLat?,
    session: SessionManager,
    mapUrl: String,
    interactive: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) DARK else LIGHT
    val styleUrl = "${mapUrl.trimEnd('/')}/mapa/estilo/${if (dark) "oscuro" else "claro"}.json"
    val geoJson = remember(track) { VeloraApi.json.encodeToString(ActivityTrack.serializer(), track) }
    val label = stringResource(R.string.map_of, title)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentPosition by rememberUpdatedState(position)

    val mapView = remember {
        VeloraMap.start(context, session, mapUrl)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        // Al agregarse, el observador recibe los eventos hasta el estado actual: un mapa abierto a pantalla completa
        // arranca y se reanuda de inmediato.
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
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                if (interactive) {
                    map.uiSettings.setAllGesturesEnabled(true)
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.uiSettings.isCompassEnabled = true
                } else {
                    map.uiSettings.setAllGesturesEnabled(false)
                }
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
                    style.addSource(GeoJsonSource(POSITION, positionGeoJson(currentPosition)))
                    style.addLayer(
                        CircleLayer(POSITION, POSITION).withProperties(
                            PropertyFactory.circleRadius(7f),
                            PropertyFactory.circleColor(colors.line),
                            PropertyFactory.circleStrokeColor(colors.casing),
                            PropertyFactory.circleStrokeWidth(3f),
                        ),
                    )
                    // bbox: [oeste, sur, este, norte].
                    val (west, south, east, north) = track.bbox
                    val padding = if (interactive) FULLSCREEN_PADDING else PADDING
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(north, east, south, west), padding))
                }
            }
            mapView
        },
        // Antes de que cargue el estilo no hay fuente: el estilo arranca con la posición vigente.
        update = { view ->
            // Leer el estado aquí (no en el callback) hace que AndroidView repita este bloque cuando cambia.
            val geoJson = positionGeoJson(currentPosition)
            view.getMapAsync { map -> map.style?.getSourceAs<GeoJsonSource>(POSITION)?.setGeoJson(geoJson) }
        },
        modifier = modifier.semantics { contentDescription = label },
    )
}

private const val SOURCE = "recorrido"
private const val POSITION = "posicion"

/** El punto como GeoJSON; sin punto, una colección vacía. */
internal fun positionGeoJson(position: LngLat?): String {
    val features = position?.let {
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]},"properties":{}}"""
    }.orEmpty()
    return """{"type":"FeatureCollection","features":[$features]}"""
}
private const val PADDING = 48
/** Más aire arriba: ahí van el botón de cerrar y el título. */
private const val FULLSCREEN_PADDING = 96
