package me.mallows.komado.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoZoomTest {
    @Test fun zoomKeepsFocusAndPanWithinVideoBounds() {
        val zoom = VideoZoom()
        assertTrue(zoom.layout(400, 800, 400, 200, 0))
        zoom.zoom(2.0, 100.0, 400.0)
        assertEquals(2.0, zoom.scale)
        assertEquals(100.0, zoom.panX)
        assertEquals(0.0, zoom.panY)
        zoom.pan(1000.0, 1000.0)
        assertEquals(200.0, zoom.panX)
        assertEquals(0.0, zoom.panY)
        zoom.zoom(100.0, 200.0, 400.0)
        assertEquals(4.0, zoom.scale)
        zoom.reset()
        assertEquals(1.0, zoom.scale)
        assertEquals(0.0, zoom.panX)
        assertEquals(0.0, zoom.panY)
    }

    @Test fun handlesRotationResizeAndInvalidInputs() {
        val zoom = VideoZoom()
        assertTrue(zoom.layout(400, 800, 400, 200, 90))
        zoom.zoom(2.0, 200.0, 400.0)
        zoom.pan(1000.0, -1000.0)
        assertEquals(200.0, zoom.panX)
        assertEquals(-400.0, zoom.panY)
        zoom.pan(Double.NaN, 1.0)
        zoom.zoom(Double.POSITIVE_INFINITY, 0.0, 0.0)
        assertEquals(200.0, zoom.panX)
        assertTrue(zoom.layout(800, 400, 400, 200, 90))
        assertEquals(0.0, zoom.panX)
        assertEquals(-200.0, zoom.panY)
        assertFalse(zoom.layout(0, 400, 400, 200, 0))
        assertEquals(1.0, zoom.scale)
        assertEquals(0.0, zoom.panY)
    }
}
