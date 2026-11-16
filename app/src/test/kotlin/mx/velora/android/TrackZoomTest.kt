package mx.velora.android

import mx.velora.android.map.nextTrackZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackZoomTest {

    @Test
    fun asksForMoreDetailOnlyWhenTheMapGetsCloser() {
        // Como trackZoomFor de la web: el zoom entero superior.
        assertEquals(15, nextTrackZoom(14.2, loaded = 14))
        assertEquals(17, nextTrackZoom(16.01, loaded = 14))
        // El que ya se tiene alcanza, también al alejarse.
        assertNull(nextTrackZoom(14.0, loaded = 14))
        assertNull(nextTrackZoom(11.5, loaded = 16))
        // Nunca más de lo que acepta la API.
        assertEquals(22, nextTrackZoom(23.7, loaded = 14))
        assertNull(nextTrackZoom(24.0, loaded = 22))
    }
}
