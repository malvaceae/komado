package me.mallows.komado

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.PointerClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoomableVideoViewTest {
    @Test fun zoomPanAndResetAffectVideoWithinViewport() {
        var renderer: RemoteVideoRenderer? = null
        var viewport: ZoomableVideoView? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    renderer = RemoteVideoRenderer(activity)
                    viewport = ZoomableVideoView(activity, renderer!!.view)
                    val root = FrameLayout(activity)
                    root.addView(viewport, FrameLayout.LayoutParams(800, 800))
                    activity.setContentView(root)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val view = requireNotNull(viewport)
                    val video = requireNotNull(renderer).view
                    assertTrue(view.clipChildren)
                    assertEquals(800, video.width)
                    assertTrue(view.setFrameSize(400, 400, 0))
                    val clicks = mutableListOf<PointerClick>()
                    val rightClicks = mutableListOf<PointerClick>()
                    val mapper = TapClickMapper()
                    view.onVideoTap = { x, y -> view.mapTap(x, y, 1, mapper)?.let(clicks::add) }
                    view.onVideoLongPress = { x, y -> view.mapTap(x, y, 1, mapper, PointerClick.Button.RIGHT)?.let(rightClicks::add) }
                    val tapTime = SystemClock.uptimeMillis()
                    val tapDown = MotionEvent.obtain(tapTime, tapTime, MotionEvent.ACTION_DOWN, 400f, 400f, 0)
                    val tapUp = MotionEvent.obtain(tapTime, tapTime + 20, MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { view.dispatchTouchEvent(tapDown); view.dispatchTouchEvent(tapUp) }
                    finally { tapDown.recycle(); tapUp.recycle() }
                    assertEquals(1, clicks.size)
                    assertEquals(500_000, clicks.single().x)
                    assertEquals(500_000, clicks.single().y)
                    val longTime = SystemClock.uptimeMillis()
                    val longDown = MotionEvent.obtain(longTime, longTime, MotionEvent.ACTION_DOWN, 400f, 400f, 0)
                    val longUp = MotionEvent.obtain(longTime, longTime + ViewConfiguration.getLongPressTimeout() + 10,
                        MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { view.dispatchTouchEvent(longDown); view.dispatchTouchEvent(longUp) }
                    finally { longDown.recycle(); longUp.recycle() }
                    assertEquals(1, clicks.size)
                    assertEquals(PointerClick.Button.RIGHT, rightClicks.single().button)
                    assertEquals(500_000, rightClicks.single().x)
                    val movedTime = SystemClock.uptimeMillis()
                    val movedDown = MotionEvent.obtain(movedTime, movedTime, MotionEvent.ACTION_DOWN, 300f, 400f, 0)
                    val movedMove = MotionEvent.obtain(movedTime, movedTime + 20, MotionEvent.ACTION_MOVE, 400f, 400f, 0)
                    val movedUp = MotionEvent.obtain(movedTime, movedTime + ViewConfiguration.getLongPressTimeout() + 10,
                        MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { listOf(movedDown, movedMove, movedUp).forEach { view.dispatchTouchEvent(it) } }
                    finally { listOf(movedDown, movedMove, movedUp).forEach { it.recycle() } }
                    assertEquals(1, clicks.size)
                    assertEquals(1, rightClicks.size)
                    assertNull(view.mapTap(400f, 400f, null, mapper))
                    val cancelTime = SystemClock.uptimeMillis()
                    val cancelDown = MotionEvent.obtain(cancelTime, cancelTime, MotionEvent.ACTION_DOWN, 400f, 400f, 0)
                    val cancel = MotionEvent.obtain(cancelTime, cancelTime + 20, MotionEvent.ACTION_CANCEL, 400f, 400f, 0)
                    val cancelledUp = MotionEvent.obtain(cancelTime, cancelTime + 30, MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { listOf(cancelDown, cancel, cancelledUp).forEach { view.dispatchTouchEvent(it) } }
                    finally { listOf(cancelDown, cancel, cancelledUp).forEach { it.recycle() } }
                    assertEquals("Cancelled touch must not produce a click", 1, clicks.size)
                    val jumpTime = SystemClock.uptimeMillis()
                    val jumpDown = MotionEvent.obtain(jumpTime, jumpTime, MotionEvent.ACTION_DOWN, 300f, 400f, 0)
                    val jumpUp = MotionEvent.obtain(jumpTime, jumpTime + 20, MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { view.dispatchTouchEvent(jumpDown); view.dispatchTouchEvent(jumpUp) }
                    finally { jumpDown.recycle(); jumpUp.recycle() }
                    assertEquals("A moved touch must not produce a click", 1, clicks.size)
                    assertEquals("A moved touch must not produce a right click", 1, rightClicks.size)
                    assertTrue(view.setFrameSize(400, 400, 90))
                    assertNull(view.mapTap(400f, 400f, 1, mapper))
                    assertTrue(view.setFrameSize(400, 400, 0))
                    view.zoomBy(2.0)
                    assertEquals(2f, video.scaleX, 0.001f)
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 150f, 0)
                    val move = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, 180f, 150f, 0)
                    val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 180f, 150f, 0)
                    try {
                        view.dispatchTouchEvent(down)
                        view.dispatchTouchEvent(move)
                        view.dispatchTouchEvent(up)
                    } finally {
                        down.recycle(); move.recycle(); up.recycle()
                    }
                    assertTrue(video.translationX > 0f)
                    assertTrue(video.translationX <= 400f)
                    assertEquals("Panning must not produce a click", 1, clicks.size)
                    val zoomTapTime = SystemClock.uptimeMillis()
                    val zoomTapDown = MotionEvent.obtain(zoomTapTime, zoomTapTime, MotionEvent.ACTION_DOWN, 400f, 400f, 0)
                    val zoomTapUp = MotionEvent.obtain(zoomTapTime, zoomTapTime + 20, MotionEvent.ACTION_UP, 400f, 400f, 0)
                    try { view.dispatchTouchEvent(zoomTapDown); view.dispatchTouchEvent(zoomTapUp) }
                    finally { zoomTapDown.recycle(); zoomTapUp.recycle() }
                    assertEquals(2, clicks.size)
                    assertEquals(450_000, clicks.last().x)
                    assertEquals(500_000, clicks.last().y)
                    view.resetZoom()
                    assertEquals(1f, video.scaleX, 0.001f)
                    assertEquals(0f, video.translationX, 0.001f)
                    val pinchTime = SystemClock.uptimeMillis()
                    val first = MotionEvent.obtain(pinchTime, pinchTime, MotionEvent.ACTION_DOWN, 150f, 400f, 0)
                    val second = twoPointers(pinchTime, pinchTime + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 150f, 650f)
                    val expand1 = twoPointers(pinchTime, pinchTime + 30, MotionEvent.ACTION_MOVE, 100f, 700f)
                    val expand2 = twoPointers(pinchTime, pinchTime + 50, MotionEvent.ACTION_MOVE, 50f, 750f)
                    val expand3 = twoPointers(pinchTime, pinchTime + 60, MotionEvent.ACTION_MOVE, 0f, 800f)
                    val lift = twoPointers(pinchTime, pinchTime + 70, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f, 800f)
                    val last = MotionEvent.obtain(pinchTime, pinchTime + 90, MotionEvent.ACTION_UP, 0f, 400f, 0)
                    try {
                        listOf(first, second, expand1, expand2, expand3, lift, last).forEach { view.dispatchTouchEvent(it) }
                    } finally {
                        listOf(first, second, expand1, expand2, expand3, lift, last).forEach { it.recycle() }
                    }
                    assertTrue("Pinch did not enlarge the video", video.scaleX > 1f)
                    assertEquals("Pinching must not produce a click", 2, clicks.size)
                    renderer!!.close()
                    renderer = null
                }
            }
        } finally {
            renderer?.let { InstrumentationRegistry.getInstrumentation().runOnMainSync { it.close() } }
        }
    }

    private fun twoPointers(downTime: Long, time: Long, action: Int, left: Float, right: Float): MotionEvent {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(left, right).map { x -> MotionEvent.PointerCoords().apply { this.x = x; y = 400f; pressure = 1f; size = 1f } }.toTypedArray()
        return MotionEvent.obtain(downTime, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
