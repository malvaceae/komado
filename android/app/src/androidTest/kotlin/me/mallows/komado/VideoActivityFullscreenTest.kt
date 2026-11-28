package me.mallows.komado

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoActivityFullscreenTest {
    @Test fun lastFrameRemainsVisibleWhenFullscreenChangesWithoutNewFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factory = MediaFactory.create(instrumentation.targetContext)
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("still-frame", source)
        val rendered = CountDownLatch(1)
        var started = false
        try {
            ActivityScenario.launch(VideoActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewport = requireNotNull(find(activity.window.decorView, ZoomableVideoView::class.java))
                    val renderer = RemoteVideoRenderer(activity)
                    viewport.removeAllViews()
                    viewport.addView(renderer.view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    field(activity, "renderer").set(activity, renderer)
                    field(activity, "active").setBoolean(activity, true)
                    field(activity, "firstFrameShown").setBoolean(activity, true)
                    activity.javaClass.getDeclaredMethod("updateControlAvailability").apply { isAccessible = true }.invoke(activity)
                    viewport.setFrameSize(320, 240, 0)
                    renderer.view.addFrameListener({ rendered.countDown() }, 0f)
                    renderer.show(track)
                }
                source.capturerObserver.onCapturerStarted(true)
                started = true
                fun visible(): Boolean {
                    val origin = IntArray(2)
                    var x = 0
                    var y = 0
                    scenario.onActivity { activity ->
                        val view = requireNotNull(find(activity.window.decorView, ZoomableVideoView::class.java))
                        view.getLocationOnScreen(origin)
                        x = origin[0] + view.width / 2
                        y = origin[1] + view.height / 2
                    }
                    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return false
                    return try {
                        if (x !in 0 until screenshot.width || y !in 0 until screenshot.height) false else {
                            val pixel = screenshot.getPixel(x, y)
                            Color.red(pixel) > 150 && Color.red(pixel) > Color.blue(pixel) + 40
                        }
                    } finally { screenshot.recycle() }
                }
                var firstFrameVisible = false
                for (i in 0 until 50) {
                    val buffer = JavaI420Buffer.allocate(320, 240)
                    for (index in 0 until buffer.dataY.capacity()) buffer.dataY.put(index, 100.toByte())
                    for (index in 0 until buffer.dataU.capacity()) buffer.dataU.put(index, 90.toByte())
                    for (index in 0 until buffer.dataV.capacity()) buffer.dataV.put(index, 220.toByte())
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                    Thread.sleep(100)
                    if (rendered.await(100, TimeUnit.MILLISECONDS) && visible()) { firstFrameVisible = true; break }
                }
                assertTrue("The test frame was not displayed", firstFrameVisible)
                var originalHeight = 0
                scenario.onActivity { activity ->
                    originalHeight = requireNotNull(find(activity.window.decorView, ZoomableVideoView::class.java)).height
                    assertTrue(requireNotNull(button(activity.window.decorView, activity.getString(R.string.fullscreen))).performClick())
                }
                waitFor {
                    var expanded = false
                    scenario.onActivity { activity -> expanded = requireNotNull(find(activity.window.decorView, ZoomableVideoView::class.java)).height > originalHeight }
                    expanded
                }
                waitFor { visible() }
                scenario.onActivity { activity ->
                    assertTrue(requireNotNull(button(activity.window.decorView, activity.getString(R.string.exit_fullscreen))).performClick())
                }
                waitFor {
                    var restored = false
                    scenario.onActivity { activity -> restored = requireNotNull(find(activity.window.decorView, ZoomableVideoView::class.java)).height == originalHeight }
                    restored
                }
                waitFor { visible() }
                scenario.onActivity { activity ->
                    (field(activity, "renderer").get(activity) as RemoteVideoRenderer).show(null)
                }
            }
        } finally {
            if (started) source.capturerObserver.onCapturerStopped()
            track.dispose(); source.dispose(); factory.dispose()
        }
    }

    @Test fun fullscreenExpandsVideoAndCanBeExitedAfterRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.CONTROL_MODE, true)
        ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
            try {
                var originalHeight = 0
                var hadStatusBar = false
                var hadNavigationBar = false
                scenario.onActivity { activity ->
                    field(activity, "active").setBoolean(activity, true)
                    field(activity, "firstFrameShown").setBoolean(activity, true)
                    field(activity, "captureRevision").set(activity, 1L)
                    activity.javaClass.getDeclaredMethod("updateControlAvailability").apply { isAccessible = true }.invoke(activity)
                }
                waitFor {
                    var ready = false
                    scenario.onActivity {
                        val root = it.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(root)
                        originalHeight = find(root, ZoomableVideoView::class.java)?.height ?: 0
                        if (insets != null && originalHeight > 0) {
                            hadStatusBar = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                            hadNavigationBar = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                            ready = true
                        }
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val enter = requireNotNull(button(activity.window.decorView, activity.getString(R.string.fullscreen)))
                    assertTrue(enter.isEnabled)
                    assertTrue(enter.performClick())
                }
                waitFor {
                    var expanded = false
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val video = find(root, ZoomableVideoView::class.java)
                        val exit = button(root, activity.getString(R.string.exit_fullscreen))
                        val controls = find(root, VideoControlsScrollView::class.java)
                        val insets = ViewCompat.getRootWindowInsets(root)
                        expanded = video != null && video.height > originalHeight && controls?.visibility == View.GONE && exit?.isShown == true &&
                            insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                            !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                    }
                    expanded
                }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor {
                    var landscape = false
                    scenario.onActivity { activity ->
                        val exit = button(activity.window.decorView, activity.getString(R.string.exit_fullscreen))
                        val visible = Rect()
                        landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                            exit?.isShown == true && exit.getGlobalVisibleRect(visible) && visible.height() == exit.height && visible.width() == exit.width
                    }
                    landscape
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor {
                    var restored = false
                    scenario.onActivity { activity ->
                        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        restored = insets != null && (!hadStatusBar || insets.isVisible(WindowInsetsCompat.Type.statusBars())) &&
                            (!hadNavigationBar || insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                    }
                    restored
                }
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    assertEquals(View.VISIBLE, requireNotNull(find(root, VideoControlsScrollView::class.java)).visibility)
                    assertEquals(View.GONE, requireNotNull(button(root, activity.getString(R.string.exit_fullscreen))).visibility)
                    assertTrue(requireNotNull(button(root, activity.getString(R.string.fullscreen))).performClick())
                    assertTrue(requireNotNull(button(root, activity.getString(R.string.exit_fullscreen))).performClick())
                    assertEquals(View.VISIBLE, requireNotNull(find(root, VideoControlsScrollView::class.java)).visibility)
                }
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    @Test fun failedConnectionLeavesFullscreenAndShowsError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(VideoActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                field(activity, "active").setBoolean(activity, true)
                field(activity, "firstFrameShown").setBoolean(activity, true)
                activity.javaClass.getDeclaredMethod("updateControlAvailability").apply { isAccessible = true }.invoke(activity)
                assertTrue(requireNotNull(button(activity.window.decorView, activity.getString(R.string.fullscreen))).performClick())
                activity.javaClass.getDeclaredMethod("stopSession", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, true)
                val root = activity.window.decorView
                assertEquals(View.VISIBLE, requireNotNull(find(root, VideoControlsScrollView::class.java)).visibility)
                assertEquals(View.GONE, requireNotNull(button(root, activity.getString(R.string.exit_fullscreen))).visibility)
                val status = field(activity, "status").get(activity) as TextView
                assertEquals(activity.getString(R.string.screen_disconnected), status.text.toString())
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("Expected layout did not appear", condition())
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
    private fun button(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
        return null
    }
}
