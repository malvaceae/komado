package me.mallows.komado

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
class RemoteVideoRendererTest {
    @Test
    fun rendersGeneratedFrameAndReleasesResources() {
        val firstFrame = CountDownLatch(1)
        val factory = MediaFactory.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("test", source)
        var renderer: RemoteVideoRenderer? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    renderer = RemoteVideoRenderer(activity, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() { firstFrame.countDown() }
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                    })
                    activity.setContentView(renderer!!.view)
                    renderer!!.show(track)
                }
                try {
                    source.capturerObserver.onCapturerStarted(true)
                    for (i in 0 until 30) {
                        val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
                        try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                        if (firstFrame.await(100, TimeUnit.MILLISECONDS)) break
                    }
                    assertTrue("No frame was rendered", firstFrame.await(5, TimeUnit.SECONDS))
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
