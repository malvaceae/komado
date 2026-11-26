package me.mallows.komado

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FirstFrameDeadlineTest {
    @Test fun missingFrameTimesOut() {
        val fired = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            FirstFrameDeadline(Handler(Looper.getMainLooper()), 100) { fired.countDown() }.start()
        }
        assertTrue(fired.await(2, TimeUnit.SECONDS))
    }

    @Test fun frameOrCloseCancelsDeadlineEvenBeforeStart() {
        val fired = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val first = FirstFrameDeadline(Handler(Looper.getMainLooper()), 100) { fired.countDown() }
            first.start()
            first.finish()
            val second = FirstFrameDeadline(Handler(Looper.getMainLooper()), 100) { fired.countDown() }
            second.finish()
            second.start()
        }
        assertFalse(fired.await(400, TimeUnit.MILLISECONDS))
    }
}
