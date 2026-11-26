package me.mallows.komado

import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ZoomRenderingTest {
    @Test fun scaledSurfaceIsClippedAndChangesDisplayedContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factory = MediaFactory.create(instrumentation.targetContext)
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("synthetic", source)
        val firstFrame = CountDownLatch(1)
        var renderer: RemoteVideoRenderer? = null
        var viewport: ZoomableVideoView? = null
        var capturerStarted = false
        val origin = IntArray(2)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    renderer = RemoteVideoRenderer(activity, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() { firstFrame.countDown() }
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                    })
                    viewport = ZoomableVideoView(activity, renderer!!.view)
                    val root = FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
                    root.addView(viewport, FrameLayout.LayoutParams(600, 600).apply { leftMargin = 100; topMargin = 100 })
                    activity.setContentView(root)
                    renderer!!.show(track)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val view = requireNotNull(viewport)
                    assertTrue(view.setFrameSize(320, 320, 0))
                    view.getLocationOnScreen(origin)
                }
                source.capturerObserver.onCapturerStarted(true)
                capturerStarted = true
                for (i in 0 until 30) {
                    val buffer = JavaI420Buffer.allocate(320, 320)
                    for (y in 0 until 320) for (x in 0 until 320) buffer.dataY.put(y * buffer.strideY + x, if (x in 100..144) 220.toByte() else 32.toByte())
                    for (y in 0 until 160) for (x in 0 until 160) {
                        buffer.dataU.put(y * buffer.strideU + x, 128.toByte())
                        buffer.dataV.put(y * buffer.strideV + x, 128.toByte())
                    }
                    val frame = VideoFrame(buffer, 0, System.nanoTime())
                    try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                    if (firstFrame.await(100, TimeUnit.MILLISECONDS)) break
                }
                assertTrue("No synthetic frame rendered", firstFrame.await(5, TimeUnit.SECONDS))
                // Inspect only the generated test view; never save the screenshot.
                fun sample(x: Int, y: Int): Int {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Screenshot unavailable")
                    return try { bitmap.getPixel(x, y) } finally { bitmap.recycle() }
                }
                instrumentation.waitForIdleSync()
                var before = sample(origin[0] + 150, origin[1] + 300)
                for (i in 0 until 20) {
                    if (Color.red(before) < 80) break
                    Thread.sleep(50)
                    before = sample(origin[0] + 150, origin[1] + 300)
                }
                scenario.onActivity { viewport!!.zoomBy(2.0) }
                instrumentation.waitForIdleSync()
                var after = before
                for (i in 0 until 20) {
                    after = sample(origin[0] + 150, origin[1] + 300)
                    if (Color.red(after) > 180) break
                    Thread.sleep(50)
                }
                val outside = sample(origin[0] - 40, origin[1] + 300)
                assertTrue("Synthetic dark stripe was not rendered (red=${Color.red(before)})", Color.red(before) < 80)
                assertTrue("Zoom did not expose the bright stripe", Color.red(after) > 180)
                assertTrue("Video escaped the viewport", Color.red(outside) > 180 && Color.green(outside) < 50)
                scenario.onActivity { renderer!!.close(); renderer = null }
            }
        } finally {
            if (capturerStarted) source.capturerObserver.onCapturerStopped()
            renderer?.let { instrumentation.runOnMainSync { it.close() } }
            track.dispose()
            source.dispose()
            factory.dispose()
        }
    }
}
