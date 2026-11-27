package me.mallows.komado.input

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VideoViewportTest {
    @Test
    fun rejectsLetterboxingAndMapsImageCenter() {
        val viewport = assertNotNull(VideoViewport.create(1000.0, 1000.0, 1920.0, 1080.0, 1.0, 0.0, 0.0, 7))
        assertNull(viewport.normalize(500.0, 100.0))
        assertNull(viewport.normalize(500.0, 900.0))
        assertEquals(NormalizedPoint(0.5, 0.5, 7), viewport.normalize(500.0, 500.0))
        val topLeft = assertNotNull(viewport.normalize(0.0, 218.75))
        assertEquals(0.0, topLeft.x, 1e-12)
        assertEquals(0.0, topLeft.y, 1e-12)
        assertEquals(7, topLeft.captureRevision)
        assertNull(viewport.normalize(1000.0, 500.0))
    }

    @Test
    fun mapsPortraitViewportAndRejectsVerticalMargins() {
        val viewport = assertNotNull(VideoViewport.create(1080.0, 1920.0, 1000.0, 1000.0, 1.0, 0.0, 0.0, 12))
        assertNull(viewport.normalize(540.0, 419.0))
        assertNull(viewport.normalize(540.0, 1500.0))
        assertEquals(NormalizedPoint(0.5, 0.5, 12), viewport.normalize(540.0, 960.0))
    }

    @Test
    fun accountsForZoomAndPanBeforeNormalizing() {
        val viewport = assertNotNull(VideoViewport.create(1000.0, 1000.0, 1000.0, 1000.0, 2.0, -250.0, 0.0, 8))
        assertEquals(NormalizedPoint(0.5, 0.5, 8), viewport.normalize(250.0, 500.0))
        assertEquals(NormalizedPoint(0.375, 0.25, 8), viewport.normalize(0.0, 0.0))
        assertNull(viewport.normalize(-1.0, 500.0))
        assertNull(viewport.normalize(500.0, 1000.0))
    }

    @Test
    fun clampsCursorToVisibleVideoAfterZoomAndPan() {
        val viewport = assertNotNull(VideoViewport.create(1000.0, 1000.0, 1920.0, 1080.0, 1.0, 0.0, 0.0, 7))
        val upper = assertNotNull(viewport.clampCursor(-100.0, -100.0))
        val lower = assertNotNull(viewport.clampCursor(2000.0, 2000.0))
        assertNotNull(viewport.normalize(upper.first.toFloat().toDouble(), upper.second.toFloat().toDouble()))
        assertNotNull(viewport.normalize(lower.first.toFloat().toDouble(), lower.second.toFloat().toDouble()))
        val fractional = assertNotNull(VideoViewport.create(1001.0, 1000.0, 1920.0, 1080.0, 1.0, 0.0, 0.0, 7))
        val edge = assertNotNull(fractional.clampCursor(-10.0, 2000.0))
        assertNotNull(fractional.normalize(edge.first.toFloat().toDouble(), edge.second.toFloat().toDouble()))
        val offscreen = assertNotNull(VideoViewport.create(100.0, 100.0, 10.0, 10.0, 1.0, 500.0, 0.0, 7))
        assertNull(offscreen.clampCursor(0.0, 0.0))
        assertNull(viewport.clampCursor(Double.NaN, 2.0))
        val zoomed = assertNotNull(VideoViewport.create(1000.0, 1000.0, 1000.0, 1000.0, 2.0, -250.0, 0.0, 8))
        val visible = assertNotNull(zoomed.clampCursor(-10.0, 1500.0))
        assertNotNull(zoomed.normalize(visible.first, visible.second))
    }

    @Test
    fun rejectsNonFiniteInputsAndInvalidGeometry() {
        val viewport = assertNotNull(VideoViewport.create(100.0, 100.0, 10.0, 10.0, 1.0, 0.0, 0.0, 0))
        assertNull(viewport.normalize(Double.NaN, 2.0))
        assertNull(viewport.normalize(2.0, Double.POSITIVE_INFINITY))
        assertNull(VideoViewport.create(0.0, 100.0, 10.0, 10.0, 1.0, 0.0, 0.0, 0))
        assertNull(VideoViewport.create(100.0, 100.0, 10.0, 10.0, 0.0, 0.0, 0.0, 0))
        assertNull(VideoViewport.create(100.0, 100.0, 10.0, 10.0, 1.0, Double.NaN, 0.0, 0))
        assertNull(VideoViewport.create(100.0, 100.0, 10.0, 10.0, 1.0, 0.0, 0.0, -1))
        assertNull(VideoViewport.create(Double.MAX_VALUE, 100.0, 10.0, 10.0, Double.MAX_VALUE, 0.0, 0.0, 0))
    }
}
