package me.mallows.komado

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoActivityKeyFailureTest {
    @Test fun keyboardSendFailureEndsSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.CONTROL_MODE, true)
        ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val keyboard = requireNotNull(find(activity.window.decorView, RemoteKeyboardView::class.java))
                field(keyboard, "closed").setBoolean(keyboard, false)
                readyWithoutChannel(activity)
                assertFalse(keyboard.onKeyDown(KeyEvent.KEYCODE_MOVE_HOME,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME)))
                assertFalse(field(activity, "active").getBoolean(activity))
                assertTrue(hasText(activity.window.decorView, activity.getString(R.string.screen_disconnected)))
            }
        }
    }

    @Test fun specialKeyBarSendFailureEndsSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.CONTROL_MODE, true)
        ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val bar = requireNotNull(find(activity.window.decorView, SpecialKeyBar::class.java))
                readyWithoutChannel(activity)
                bar.setAvailable(true)
                val escape = requireNotNull(findButton(bar, "Esc"))
                assertTrue(escape.performClick())
                assertFalse(field(activity, "active").getBoolean(activity))
                assertTrue(hasText(activity.window.decorView, activity.getString(R.string.screen_disconnected)))
            }
        }
    }

    private fun readyWithoutChannel(activity: VideoActivity) {
        field(activity, "active").setBoolean(activity, true)
        field(activity, "firstFrameShown").setBoolean(activity, true)
        field(activity, "captureRevision").set(activity, 1L)
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
    private fun findButton(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findButton(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun hasText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) if (hasText(view.getChildAt(i), text)) return true
        return false
    }
}
