package me.mallows.komado

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VideoActivityRotationTest {
    @Test fun rotationKeepsActivityAndDoesNotStopIt() {
        var original: Activity? = null
        val stops = AtomicInteger()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) { if (activity === original) stops.incrementAndGet() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        ActivityScenario.launch(VideoActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    original = activity
                    activity.application.registerActivityLifecycleCallbacks(callbacks)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var landscape = false
                while (!landscape && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        assertSame(original, activity)
                        landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    }
                    if (!landscape) Thread.sleep(50)
                }
                assertTrue("Landscape configuration was not applied", landscape)
                assertEquals(0, stops.get())
            } finally {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    activity.application.unregisterActivityLifecycleCallbacks(callbacks)
                }
            }
        }
    }
}
