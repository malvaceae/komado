package me.mallows.komado

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
