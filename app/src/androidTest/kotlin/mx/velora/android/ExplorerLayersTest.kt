package mx.velora.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mx.velora.android.explorer.addExplorerLayers
import mx.velora.android.explorer.squareGeoJson
import mx.velora.android.map.LIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * MapLibre valida capas y expresiones al agregarlas, en tiempo de ejecución: una expresión mal formada solo fallaría
 * al abrir el explorador. Aquí se agregan sobre un estilo vacío (sin red) y se revisan el orden y que las celdas se
 * puedan reemplazar.
 */
@RunWith(AndroidJUnit4::class)
class ExplorerLayersTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theExplorerLayersAreValidAndInOrder() {
        val done = CountDownLatch(1)
        val outcome = AtomicReference<Result<List<String>>>()
        compose.setContent {
            AndroidView(
                factory = { context ->
                    MapLibre.getInstance(context.applicationContext)
                    MapView(context).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[]}""")) { style ->
                                outcome.set(
                                    runCatching {
                                        addExplorerLayers(style, LIGHT, "http://servidor/api/v1/geo/heatmap/{z}/{x}/{y}", EMPTY, squareGeoJson(null))
                                        style.getSourceAs<GeoJsonSource>("celdas")!!.setGeoJson(CELLS)
                                        style.getSourceAs<GeoJsonSource>("mayor-cuadrado")!!.setGeoJson(squareGeoJson(listOf(-99.0, 19.0, -98.9, 19.1)))
                                        style.layers.map { it.id }.filterNot { it.startsWith("org.maplibre.") }
                                    },
                                )
                                done.countDown()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        assertTrue("el estilo no cargó", done.await(30, TimeUnit.SECONDS))
        val layers = outcome.get().getOrThrow()
        // Las celdas debajo del mapa de calor; el contorno del cuadrado, encima de todo (sin las capas propias de MapLibre).
        assertEquals(listOf("celdas-relleno", "celdas-borde", "mapa-de-calor", "mayor-cuadrado"), layers)
    }

    private companion object {
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        const val CELLS = """
            {"type":"FeatureCollection","grid":"MX1KM","truncated":false,"features":[
              {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-99.2,19.3],[-99.19,19.3],[-99.19,19.31],[-99.2,19.31],[-99.2,19.3]]]},
               "properties":{"activityCount":4}}]}"""
    }
}
