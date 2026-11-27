package me.mallows.komado

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoomableVideoViewTest {
    @Test fun mouseHoverCoalescesAndCancelsWhenLeaving() {
        var renderer: RemoteVideoRenderer? = null
        var viewport: ZoomableVideoView? = null
        val positions = mutableListOf<Pair<Float, Float>>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    renderer = RemoteVideoRenderer(activity)
                    viewport = ZoomableVideoView(activity, renderer!!.view).apply { onVideoHover = { x, y -> positions.add(x to y) } }
                    val root = FrameLayout(activity)
                    root.addView(viewport, FrameLayout.LayoutParams(800, 800))
                    activity.setContentView(root)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val view = requireNotNull(viewport)
                    val time = SystemClock.uptimeMillis()
                    val first = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f, 0).apply { source = InputDevice.SOURCE_MOUSE }
                    val second = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_HOVER_MOVE, 200f, 300f, 0).apply { source = InputDevice.SOURCE_MOUSE }
                    try { assertTrue(view.onHoverEvent(first)); assertTrue(view.onHoverEvent(second)) }
                    finally { first.recycle(); second.recycle() }
                }
                Thread.sleep(150)
                scenario.onActivity {
                    assertEquals(listOf(200f to 300f), positions)
                    val view = requireNotNull(viewport)
                    val time = SystemClock.uptimeMillis()
                    val move = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, 300f, 400f, 0).apply { source = InputDevice.SOURCE_MOUSE }
                    val exit = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_HOVER_EXIT, 300f, 400f, 0).apply { source = InputDevice.SOURCE_MOUSE }
                    try { view.onHoverEvent(move); view.onHoverEvent(exit) }
                    finally { move.recycle(); exit.recycle() }
                }
                Thread.sleep(150)
                scenario.onActivity { assertEquals(1, positions.size); renderer!!.close(); renderer = null }
            }
        } finally { renderer?.let { InstrumentationRegistry.getInstrumentation().runOnMainSync { it.close() } } }
    }

    @Test fun mouseButtonsAndDragDoNotBecomeTouchGestures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val renderer = RemoteVideoRenderer(activity)
                try {
                    val view = ZoomableVideoView(activity, renderer.view)
                    view.layout(0, 0, 800, 800)
                    val clicks = mutableListOf<PointerClick.Button>()
                    val phases = mutableListOf<PointerDrag.Phase>()
                    view.onVideoTap = { _, _ -> clicks.add(PointerClick.Button.LEFT) }
                    view.onMouseClick = { _, _, button -> clicks.add(button) }
                    view.onVideoLongPress = { _, _ -> throw AssertionError("Mouse hold became a touch long press") }
                    view.onVideoDrag = { phase, _, _ -> phases.add(phase); true }
                    val now = SystemClock.uptimeMillis()
                    fun mouse(time: Long, action: Int, x: Float, buttons: Int) {
                        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
                        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; y = 400f; pressure = 1f; size = 1f })
                        val event = MotionEvent.obtain(now, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                        try {
                            if (action == MotionEvent.ACTION_BUTTON_PRESS) view.onGenericMotionEvent(event)
                            else view.onTouchEvent(event)
                        } finally { event.recycle() }
                    }
                    mouse(now, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_SECONDARY)
                    mouse(now + 700, MotionEvent.ACTION_UP, 200f, 0)
                    mouse(now + 1000, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_TERTIARY)
                    mouse(now + 1020, MotionEvent.ACTION_UP, 200f, 0)
                    mouse(now + 2000, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_PRIMARY)
                    mouse(now + 2020, MotionEvent.ACTION_UP, 200f, 0)
                    assertEquals(listOf(PointerClick.Button.RIGHT, PointerClick.Button.MIDDLE, PointerClick.Button.LEFT), clicks)
                    mouse(now + 3000, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_PRIMARY)
                    mouse(now + 3020, MotionEvent.ACTION_MOVE, 300f, MotionEvent.BUTTON_PRIMARY)
                    mouse(now + 3040, MotionEvent.ACTION_CANCEL, 300f, 0)
                    assertEquals(listOf(PointerDrag.Phase.BEGIN, PointerDrag.Phase.MOVE, PointerDrag.Phase.END), phases)
                    mouse(now + 4000, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_SECONDARY)
                    mouse(now + 4020, MotionEvent.ACTION_MOVE, 300f, MotionEvent.BUTTON_SECONDARY)
                    mouse(now + 4040, MotionEvent.ACTION_UP, 300f, 0)
                    mouse(now + 5000, MotionEvent.ACTION_DOWN, 200f, MotionEvent.BUTTON_PRIMARY)
                    mouse(now + 5020, MotionEvent.ACTION_BUTTON_PRESS, 200f, MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY)
                    mouse(now + 5040, MotionEvent.ACTION_UP, 200f, 0)
                    assertEquals(3, clicks.size)
                    assertEquals(3, phases.size)
                } finally { renderer.close() }
            }
        }
    }

    @Test fun longPressMoveDragsAndCancelReleases() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val renderer = RemoteVideoRenderer(activity)
                try {
                    val view = ZoomableVideoView(activity, renderer.view)
                    view.layout(0, 0, 800, 800)
                    val phases = mutableListOf<PointerDrag.Phase>()
                    var clicks = 0
                    view.onVideoDrag = { phase, _, _ -> phases.add(phase); true }
                    view.onVideoTap = { _, _ -> clicks++ }
                    view.onVideoLongPress = { _, _ -> clicks++ }
                    val start = SystemClock.uptimeMillis()
                    fun gesture(time: Long, cancel: Boolean) {
                        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 200f, 400f, 0)
                        val moved = MotionEvent.obtain(time, time + ViewConfiguration.getLongPressTimeout() + 20, MotionEvent.ACTION_MOVE, 300f, 400f, 0)
                        val end = MotionEvent.obtain(time, time + ViewConfiguration.getLongPressTimeout() + 40,
                            if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 300f, 400f, 0)
                        try { listOf(down, moved, end).forEach { view.dispatchTouchEvent(it) } }
                        finally { listOf(down, moved, end).forEach { it.recycle() } }
                    }
                    gesture(start, false)
                    gesture(start + 2_000, true)
                    assertEquals(listOf(PointerDrag.Phase.BEGIN, PointerDrag.Phase.MOVE, PointerDrag.Phase.END,
                        PointerDrag.Phase.BEGIN, PointerDrag.Phase.MOVE, PointerDrag.Phase.END), phases)
                    assertEquals(0, clicks)
                    val positions = mutableListOf<Pair<PointerDrag.Phase, Float>>()
                    view.onVideoDrag = { phase, x, _ -> positions.add(phase to x); phase != PointerDrag.Phase.MOVE }
                    gesture(start + 4_000, false)
                    assertEquals(listOf(PointerDrag.Phase.BEGIN to 200f, PointerDrag.Phase.MOVE to 300f,
                        PointerDrag.Phase.END to 200f), positions)
                    assertEquals(0, clicks)
                } finally { renderer.close() }
            }
        }
    }

    @Test fun doubleTapSendsFirstClickThenDoubleClickWithoutCrossingGestures() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val renderer = RemoteVideoRenderer(activity)
                try {
                    val view = ZoomableVideoView(activity, renderer.view)
                    view.layout(0, 0, 800, 800)
                    view.setFrameSize(800, 800, 0)
                    val calls = mutableListOf<String>()
                    view.onVideoTap = { _, _ -> calls.add("single") }
                    view.onVideoDoubleTap = { _, _ -> calls.add("double") }
                    fun touch(down: Long, up: Long, x: Float) {
                        val first = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, 400f, 0)
                        val last = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, 400f, 0)
                        try { view.dispatchTouchEvent(first); view.dispatchTouchEvent(last) }
                        finally { first.recycle(); last.recycle() }
                    }
                    val now = SystemClock.uptimeMillis()
                    touch(now, now + 20, 400f)
                    touch(now + 80, now + 100, 400f)
                    assertEquals(listOf("single", "double"), calls)
                    touch(now + 500, now + 520, 400f)
                    touch(now + 580, now + 600, 700f)
                    assertEquals(listOf("single", "double", "single", "single"), calls)
                } finally { renderer.close() }
            }
        }
    }

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
                    val scrolls = mutableListOf<Pair<Float, Float>>()
                    view.onVideoScroll = { _, _, dx, dy -> scrolls.add(dx to dy) }
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
                    assertTrue("Pinching must not scroll", scrolls.isEmpty())
                    val beforeScroll = video.scaleX
                    val scrollTime = SystemClock.uptimeMillis()
                    val scrollDown = MotionEvent.obtain(scrollTime, scrollTime, MotionEvent.ACTION_DOWN, 200f, 400f, 0)
                    val scrollSecond = twoPointers(scrollTime, scrollTime + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 600f)
                    val scrollMove = twoPointers(scrollTime, scrollTime + 30, MotionEvent.ACTION_MOVE, 200f, 600f, 350f)
                    val scrollLift = twoPointers(scrollTime, scrollTime + 50, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 600f, 350f)
                    val scrollUp = MotionEvent.obtain(scrollTime, scrollTime + 70, MotionEvent.ACTION_UP, 200f, 350f, 0)
                    try { listOf(scrollDown, scrollSecond, scrollMove, scrollLift, scrollUp).forEach { view.dispatchTouchEvent(it) } }
                    finally { listOf(scrollDown, scrollSecond, scrollMove, scrollLift, scrollUp).forEach { it.recycle() } }
                    assertEquals(listOf(0f to -50f), scrolls)
                    assertEquals(beforeScroll, video.scaleX, 0.001f)
                    assertEquals("Scrolling must not produce a click", 2, clicks.size)
                    renderer!!.close()
                    renderer = null
                }
            }
        } finally {
            renderer?.let { InstrumentationRegistry.getInstrumentation().runOnMainSync { it.close() } }
        }
    }

    private fun twoPointers(downTime: Long, time: Long, action: Int, left: Float, right: Float, y: Float = 400f): MotionEvent {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(left, right).map { x -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f } }.toTypedArray()
        return MotionEvent.obtain(downTime, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
