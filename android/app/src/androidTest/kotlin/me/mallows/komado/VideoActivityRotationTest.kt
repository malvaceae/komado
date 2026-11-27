package me.mallows.komado

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VideoActivityRotationTest {
    @Test fun controlScreenKeepsVideoAreaInLandscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.CONTROL_MODE, true)
        fun findVideo(view: View): ZoomableVideoView? {
            if (view is ZoomableVideoView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findVideo(view.getChildAt(i))?.let { return it }
            return null
        }
        fun findControls(view: View): VideoControlsScrollView? {
            if (view is VideoControlsScrollView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findControls(view.getChildAt(i))?.let { return it }
            return null
        }
        fun findClose(view: View, label: String): Button? {
            if (view is Button && view.text == label) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findClose(view.getChildAt(i), label)?.let { return it }
            return null
        }
        ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
            try {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var height = -1
                var minimumHeight = 0
                var scrollable = false
                while (height < 0 && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        val video = findVideo(activity.window.decorView)
                        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                            video != null && video.isLaidOut && !video.isLayoutRequested) {
                            height = video.height
                            minimumHeight = (48 * activity.resources.displayMetrics.density).toInt()
                            scrollable = findControls(activity.window.decorView)?.canScrollVertically(1) == true
                        }
                    }
                    if (height < 0) Thread.sleep(50)
                }
                assertTrue("Landscape video area must remain usable, height=$height", height >= minimumHeight && height > 0)
                assertTrue("Controls should scroll when they exceed the available height", scrollable)
                scenario.onActivity { activity ->
                    val controls = requireNotNull(findControls(activity.window.decorView))
                    controls.isSmoothScrollingEnabled = false
                    controls.fullScroll(View.FOCUS_DOWN)
                    val close = requireNotNull(findClose(controls, activity.getString(R.string.close_screen)))
                    val visible = Rect()
                    assertTrue("The close button must remain reachable", close.getGlobalVisibleRect(visible) && visible.height() == close.height)
                }
            } finally {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

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
