package mx.velora.android.explorer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import mx.velora.android.R
import mx.velora.android.api.models.ExplorationCells
import mx.velora.android.core.data.ActivityFilters
import mx.velora.android.core.data.heatmapQuery
import mx.velora.android.core.network.VeloraApi
import mx.velora.android.core.session.SessionManager
import mx.velora.android.map.DARK
import mx.velora.android.map.LIGHT
import mx.velora.android.map.TrackColors
import mx.velora.android.map.rememberMapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource

/** Teselas del mapa de calor de la API de [serverUrl] (`/geo/heatmap/{z}/{x}/{y}`, ADR-017), con los filtros. */
fun heatmapTilesUrl(serverUrl: String, filters: ActivityFilters = ActivityFilters()): String {
    val base = VeloraApi.baseUrl(serverUrl) + "/geo/heatmap/{z}/{x}/{y}"
    val query = heatmapQuery(filters)
    return if (query.isEmpty()) base else "$base?$query"
}

/**
 * El mapa del explorador: el mapa de calor (recorridos translúcidos: donde se enciman muchos, el color se satura), las
 * celdas debajo y el contorno del mayor cuadrado, con los mismos colores y grosores que la web. Responde a gestos;
 * cada vez que se detiene avisa qué se ve, para pedir las celdas de esa zona.
 */
@Composable
fun ExplorerMap(
    state: ExplorerState,
    onViewport: (Viewport) -> Unit,
    session: SessionManager,
    mapUrl: String,
    tilesUrl: String,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = if (dark) DARK else LIGHT
    val styleUrl = "${mapUrl.trimEnd('/')}/mapa/estilo/${if (dark) "oscuro" else "claro"}.json"
    val label = stringResource(R.string.explorer_map_label)
    val mapView = rememberMapView(session, mapUrl)
    val reportViewport by rememberUpdatedState(onViewport)

    val cells = remember(state.cells) { cellsGeoJson(state.cells) }
    val square = remember(state.layer, state.squareBbox) {
        squareGeoJson(state.squareBbox.takeIf { state.layer == ExplorerLayer.SQUARES })
    }
    val target = state.camera
    val currentCells by rememberUpdatedState(cells)
    val currentSquare by rememberUpdatedState(square)
    val currentTarget by rememberUpdatedState(target)
    // Último movimiento de cámara aplicado: cada uno se aplica una sola vez.
    val applied = remember { intArrayOf(0) }
    // Teselas dibujadas: con otros filtros, la fuente se reemplaza (MapLibre no cambia las de una fuente existente).
    val drawnTiles = remember { arrayOf(tilesUrl) }

    AndroidView(
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.setAllGesturesEnabled(true)
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.setMaxZoomPreference(MAX_ZOOM)
                map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                    addExplorerLayers(style, colors, drawnTiles[0], currentCells, currentSquare)
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds(MEXICO), 0))
                    currentTarget?.let { map.goTo(it, applied, animate = false) }
                    map.addOnCameraIdleListener { reportViewport(map.viewport()) }
                    reportViewport(map.viewport())
                }
            }
            mapView
        },
        // Leer el estado aquí (no en los callbacks) hace que AndroidView repita este bloque cuando cambia.
        update = { view ->
            val nextCells = cells
            val nextSquare = square
            val nextTarget = target
            val nextTiles = tilesUrl
            view.getMapAsync { map ->
                val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
                if (nextTiles != drawnTiles[0]) {
                    drawnTiles[0] = nextTiles
                    replaceHeatmap(style, colors, nextTiles)
                }
                style.getSourceAs<GeoJsonSource>(CELLS)?.setGeoJson(nextCells)
                style.getSourceAs<GeoJsonSource>(SQUARE)?.setGeoJson(nextSquare)
                nextTarget?.let { map.goTo(it, applied, animate = true) }
            }
        },
        modifier = modifier.semantics { contentDescription = label },
    )
}

/**
 * Fuentes y capas del explorador sobre el mapa base, con los mismos colores y grosores que la web: el mapa de calor
 * (recorridos translúcidos: donde se enciman muchos, el color se satura), las celdas debajo y el contorno del mayor
 * cuadrado. [cells] y [square] son GeoJSON.
 */
internal fun addExplorerLayers(style: Style, colors: TrackColors, tilesUrl: String, cells: String, square: String) {
    style.addSource(heatmapSource(tilesUrl))
    style.addLayer(heatmapLayer(colors))
    // Las celdas van debajo de los recorridos, más opacas cuantas más rodadas pasaron por ellas.
    style.addSource(GeoJsonSource(CELLS, cells))
    style.addLayerBelow(
        FillLayer("$CELLS-relleno", CELLS).withProperties(
            PropertyFactory.fillColor(colors.line),
            PropertyFactory.fillOpacity(interpolate(linear(), get("activityCount"), stop(1, 0.18f), stop(10, 0.5f))),
        ),
        HEAT,
    )
    style.addLayerBelow(
        LineLayer("$CELLS-borde", CELLS).withProperties(
            PropertyFactory.lineColor(colors.line),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineWidth(0.75f),
        ),
        HEAT,
    )
    style.addSource(GeoJsonSource(SQUARE, square))
    style.addLayer(
        LineLayer(SQUARE, SQUARE).withProperties(
            PropertyFactory.lineColor(colors.casing),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
        ),
    )
}

/** Otros filtros: la misma capa con otras teselas, en el mismo lugar (encima de las celdas, debajo del cuadrado). */
internal fun replaceHeatmap(style: Style, colors: TrackColors, tilesUrl: String) {
    style.removeLayer(HEAT)
    style.removeSource(HEAT)
    style.addSource(heatmapSource(tilesUrl))
    style.addLayerBelow(heatmapLayer(colors), SQUARE)
}

private fun heatmapSource(tilesUrl: String) =
    VectorSource(HEAT, TileSet("2.1.0", tilesUrl).apply { minZoom = 0f; maxZoom = HEATMAP_MAX_ZOOM })

private fun heatmapLayer(colors: TrackColors) =
    LineLayer(HEAT, HEAT).withSourceLayer("recorridos").withProperties(
        PropertyFactory.lineColor(colors.line),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(interpolate(linear(), zoom(), stop(4, 0.5f), stop(12, 0.6f), stop(16, 0.7f))),
        PropertyFactory.lineWidth(interpolate(exponential(1.6f), zoom(), stop(4, 1.2f), stop(12, 2.5f), stop(16, 4.5f))),
    )

private fun MapLibreMap.goTo(target: CameraTarget, applied: IntArray, animate: Boolean) {
    if (target.id <= applied[0]) return
    applied[0] = target.id
    val camera = getCameraForLatLngBounds(bounds(target.bbox), IntArray(4) { PADDING }) ?: return
    val update = CameraUpdateFactory.newCameraPosition(
        org.maplibre.android.camera.CameraPosition.Builder(camera).zoom(minOf(camera.zoom, target.maxZoom)).build(),
    )
    if (animate) animateCamera(update) else moveCamera(update)
}

private fun MapLibreMap.viewport(): Viewport {
    val b = projection.visibleRegion.latLngBounds
    return Viewport(cameraPosition.zoom, clampBbox(listOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)))
}

/** bbox: [oeste, sur, este, norte]. */
private fun bounds(bbox: List<Double>): LatLngBounds = LatLngBounds.from(bbox[3], bbox[2], bbox[1], bbox[0])

/** Recuadro dentro del rango válido de la API; girado o muy alejado, MapLibre puede dar longitudes fuera de ±180. */
internal fun clampBbox(bbox: List<Double>): List<Double> = listOf(
    bbox[0].coerceIn(-180.0, 180.0),
    bbox[1].coerceIn(-90.0, 90.0),
    bbox[2].coerceIn(-180.0, 180.0),
    bbox[3].coerceIn(-90.0, 90.0),
)

internal fun cellsGeoJson(cells: ExplorationCells?): String =
    cells?.let { VeloraApi.json.encodeToString(ExplorationCells.serializer(), it) } ?: EMPTY

/** El contorno del mayor cuadrado como GeoJSON; sin cuadrado, una colección vacía. */
internal fun squareGeoJson(bbox: List<Double>?): String {
    if (bbox == null || bbox.size != 4) return EMPTY
    val (w, s, e, n) = bbox
    val ring = listOf(w to s, e to s, e to n, w to n, w to s).joinToString(",") { (x, y) -> "[$x,$y]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}]}"""
}

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
private const val HEAT = "mapa-de-calor"
private const val CELLS = "celdas"
private const val SQUARE = "mayor-cuadrado"

/** Zoom máximo de las teselas (contrato); más cerca, MapLibre amplía las del 16. */
private const val HEATMAP_MAX_ZOOM = 16f
private const val MAX_ZOOM = 18.0
private const val PADDING = 64

/** Vista inicial mientras se sabe dónde ruedas: México, como la web. */
private val MEXICO = listOf(-118.6, 14.3, -86.5, 32.8)
