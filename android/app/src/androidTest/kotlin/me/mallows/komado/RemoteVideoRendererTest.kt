package me.mallows.komado

import androidx.test.core.app.ActivityScenario
import android.graphics.Color
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RemoteVideoRendererTest {
    @Test
    fun rendersGeneratedFrameAndReleasesResources() {
        val firstFrame = CountDownLatch(1)
        val nextFrame = CountDownLatch(1)
        val rendered = AtomicInteger()
        val factory = MediaFactory.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("test", source)
        var renderer: RemoteVideoRenderer? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    renderer = RemoteVideoRenderer(activity, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            if (rendered.getAndIncrement() == 0) firstFrame.countDown() else nextFrame.countDown()
                        }
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                    })
                    activity.setContentView(renderer!!.view)
                    renderer!!.show(track)
                }
                try {
                    source.capturerObserver.onCapturerStarted(true)
                    fun sendFrame(u: Int, v: Int) {
                        val buffer = JavaI420Buffer.allocate(320, 240)
                        for (index in 0 until buffer.dataY.capacity()) buffer.dataY.put(index, 70.toByte())
                        for (index in 0 until buffer.dataU.capacity()) buffer.dataU.put(index, u.toByte())
                        for (index in 0 until buffer.dataV.capacity()) buffer.dataV.put(index, v.toByte())
                        val frame = VideoFrame(buffer, 0, System.nanoTime())
                        try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                    }
                    for (i in 0 until 30) {
                        sendFrame(220, 110)
                        if (firstFrame.await(100, TimeUnit.MILLISECONDS)) break
                    }
                    assertTrue("No frame was rendered", firstFrame.await(5, TimeUnit.SECONDS))
                    scenario.onActivity {
                        renderer!!.reset()
                        renderer!!.show(track)
                    }
                    for (i in 0 until 30) {
                        sendFrame(90, 220)
                        if (nextFrame.await(100, TimeUnit.MILLISECONDS)) break
                    }
                    assertTrue("No frame was rendered after switching", nextFrame.await(5, TimeUnit.SECONDS))
                    var redVisible = false
                    repeat(30) {
                        if (!redVisible) {
                            sendFrame(90, 220)
                            Thread.sleep(100)
                            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                            if (screenshot != null) {
                                val pixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                                redVisible = Color.red(pixel) > 100 && Color.red(pixel) > Color.blue(pixel) + 40
                                screenshot.recycle()
                            }
                        }
                    }
                    assertTrue("New video was not visible after switching", redVisible)
                } finally {
                    source.capturerObserver.onCapturerStopped()
                    scenario.onActivity {
                        renderer!!.show(null)
                        renderer!!.close()
                        renderer!!.close()
                        renderer = null
                    }
                }
            }
        } finally {
            track.dispose()
            source.dispose()
            factory.dispose()
        }
    }
}
