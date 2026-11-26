package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.PointerClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapClickMapperTest {
    @Test fun mapsFittedVideoAndIgnoresMarginsOrUnknownCapture() {
        val mapper = TapClickMapper()
        assertNull(mapper.click(100.0, 100.0, 400, 800, 400, 200, 0, 1))
        assertNull(mapper.click(200.0, 400.0, 400, 800, 400, 200, 0, null))
        assertNull(mapper.click(200.0, 400.0, 400, 800, 400, 200, 90, 1))
        val first = mapper.click(200.0, 400.0, 400, 800, 400, 200, 0, 1)
        assertEquals(PointerClick(1, 1, 500_000, 500_000, PointerClick.Button.LEFT), first)
        assertNull(mapper.click(Double.NaN, 400.0, 400, 800, 400, 200, 0, 1))
        val second = mapper.click(0.0, 300.0, 400, 800, 400, 200, 0, 2)
        assertEquals(PointerClick(2, 2, 0, 0, PointerClick.Button.LEFT), second)
    }

    @Test fun mapsZoomedAndPannedVideoAndRejectsInvalidTransforms() {
        val mapper = TapClickMapper()
        val click = mapper.click(100.0, 400.0, 400, 800, 400, 200, 0, 1, zoom = 2.0, panX = 100.0)
        assertEquals(PointerClick(1, 1, 250_000, 500_000, PointerClick.Button.LEFT), click)
        assertNull(mapper.click(100.0, 400.0, 400, 800, 400, 200, 0, 1, zoom = Double.NaN))
        assertNull(mapper.click(100.0, 100.0, 400, 800, 400, 200, 0, 1, zoom = 2.0, panX = 100.0))
        val next = mapper.click(100.0, 400.0, 400, 800, 400, 200, 0, 1, zoom = 2.0, panX = 100.0)
        assertEquals(PointerClick(2, 1, 250_000, 500_000, PointerClick.Button.LEFT), next)
    }
}
