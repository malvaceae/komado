package me.mallows.komado

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ToggleButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SpecialKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpecialKeyBarTest {
    @Test fun shortcutPickerSendsTheSelectedKeyAndClosesOnDisconnect() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bar: SpecialKeyBar
            lateinit var control: ToggleButton
            val sent = mutableListOf<Pair<SpecialKey, Int>>()
            scenario.onActivity { activity ->
                bar = SpecialKeyBar(activity) { key, mask -> sent.add(key to mask); true }
                bar.setAvailable(true)
                val row = bar.getChildAt(0) as LinearLayout
                control = (0 until row.childCount).map { row.getChildAt(it) }
                    .filterIsInstance<ToggleButton>().first { it.textOff == "Ctrl" }
                control.performClick()
                val dialog = bar.showShortcutKeys()!!
                val list = dialog.listView
                assertEquals(48, list.adapter.count)
                assertTrue(list.performItemClick(list.adapter.getView(2, null, list), 2, list.adapter.getItemId(2)))
                assertEquals(listOf(SpecialKey.C to 4), sent)
            }
            scenario.onActivity {
                val second = bar.showShortcutKeys()!!
                bar.setAvailable(false)
                assertFalse(second.isShowing)
                assertFalse(control.isChecked)
            }
        }
    }

    @Test fun modifiersApplyToKeysAndResetOnDisconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val sent = mutableListOf<Pair<SpecialKey, Int>>()
            val bar = SpecialKeyBar(instrumentation.targetContext) { key, mask -> sent.add(key to mask); true }
            val row = bar.getChildAt(0) as LinearLayout
            val controls = (0 until row.childCount).map { row.getChildAt(it) }
            val command = controls.filterIsInstance<ToggleButton>().first { it.textOff == "⌘" }
            val control = controls.filterIsInstance<ToggleButton>().first { it.textOff == "Ctrl" }
            val escape = controls.filterIsInstance<Button>().first { it.text == "Esc" }
            val enter = controls.filterIsInstance<Button>().first { it.text == "Enter" }
            val backspace = controls.filterIsInstance<Button>().first { it.text == "Backspace" }
            val delete = controls.filterIsInstance<Button>().first { it.text == "Delete" }
            val f12 = controls.filterIsInstance<Button>().first { it.text == "F12" }
            val home = controls.filterIsInstance<Button>().first { it.text == "Home" }
            assertFalse(escape.isEnabled)
            assertFalse(home.isEnabled)
            bar.setAvailable(true)
            assertTrue(home.isEnabled)
            command.performClick()
            control.performClick()
            escape.performClick()
            enter.performClick()
            backspace.performClick()
            delete.performClick()
            f12.performClick()
            home.performClick()
            assertEquals(listOf(SpecialKey.ESCAPE to 5, SpecialKey.ENTER to 5, SpecialKey.BACKSPACE to 5,
                SpecialKey.DELETE to 5, SpecialKey.F12 to 5, SpecialKey.HOME to 5), sent)
            bar.setAvailable(false)
            assertFalse(command.isChecked)
            assertFalse(control.isChecked)
            assertFalse(escape.isEnabled)
            bar.setAvailable(true)
            assertTrue(escape.isEnabled)
            escape.performClick()
            assertEquals(SpecialKey.ESCAPE to 0, sent.last())
        }
    }
}
