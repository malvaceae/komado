package me.mallows.komado

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowPickerDialogTest {
    @Test fun searchAndCancelRemainVisibleWithManyWindows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var dialog: AlertDialog
            lateinit var search: EditText
            var selected: Long? = null
            scenario.onActivity { activity ->
                val windows = (1L..513L).map { WindowInfo(it, 300, 200, "App", "Window $it", true) }
                dialog = WindowPickerDialog.show(activity, windows, { selected = it.id }, {}, null, {}).dialog
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                search = find(dialog.window!!.decorView, EditText::class.java)!!
                val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                val searchBounds = Rect()
                val cancelBounds = Rect()
                assertTrue("Search must be visible", search.isShown && search.getGlobalVisibleRect(searchBounds))
                assertEquals(search.height, searchBounds.height())
                assertTrue("Cancel must be visible", cancel.isShown && cancel.getGlobalVisibleRect(cancelBounds))
                assertEquals(cancel.height, cancelBounds.height())
                search.requestFocus()
                (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var keyboardConnected = false
            while (!keyboardConnected && System.nanoTime() < deadline) {
                scenario.onActivity {
                    keyboardConnected = (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isActive(search)
                }
                if (!keyboardConnected) Thread.sleep(50)
            }
            assertTrue("Search must connect to the keyboard", keyboardConnected)
            scenario.onActivity {
                search.setText("Window 513")
                val list = dialog.listView
                assertEquals(1, list.count)
                assertTrue(list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0)))
                assertEquals(513L, selected)
            }
        }
    }

    @Test fun filteringSelectsTheMatchingWindowAndHandlesNoResults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val windows = listOf(
                    WindowInfo(1, 300, 200, "Editor", "Notes", true),
                    WindowInfo(2, 300, 200, "Browser", "Project", false),
                    WindowInfo(3, 640, 480, "Editor", "Project", true)
                )
                var selected: Long? = null
                val dialog = WindowPickerDialog.show(activity, windows, { selected = it.id }, {}, null, {}).dialog
                val root = dialog.window!!.decorView
                val search = find(root, EditText::class.java)!!
                assertEquals(3, dialog.listView.count)
                assertEquals("Browser — Project（300 × 200 pt） （画面に表示されていません）", dialog.listView.adapter.getItem(1))
                assertEquals("Editor — Project（640 × 480 pt）", dialog.listView.adapter.getItem(2))
                search.setText("editor")
                assertEquals(2, dialog.listView.count)
                search.setText("PROJECT")
                assertEquals(2, dialog.listView.count)
                search.setText("missing")
                assertEquals(0, dialog.listView.count)
                val empty = find(root, TextView::class.java) { it.text == activity.getString(R.string.window_search_empty) }
                assertNotNull(empty)
                assertEquals(View.VISIBLE, empty!!.visibility)
                search.setText(" project ")
                assertEquals(2, dialog.listView.count)
                val list = dialog.listView
                assertTrue(list.performItemClick(list.adapter.getView(1, null, list), 1, list.adapter.getItemId(1)))
                assertEquals(3L, selected)
            }
        }
    }

    @Test fun longPressRequestsActionForFilteredWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var requested: Long? = null
                val windows = listOf(WindowInfo(1, 300, 200, "Editor", "Notes", true), WindowInfo(2, 640, 480, "Editor", "Project", false))
                val dialog = WindowPickerDialog.show(activity, windows, {}, {}, { requested = it.id }, {}).dialog
                find(dialog.window!!.decorView, EditText::class.java)!!.setText("Project")
                val list = dialog.listView
                assertTrue(list.onItemLongClickListener.onItemLongClick(list, list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0)))
                assertEquals(2L, requested)
                dialog.dismiss()
            }
        }
    }

    @Test fun emptyListCanBeRefreshed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var refreshed = false
            scenario.onActivity { activity ->
                val dialog = WindowPickerDialog.show(activity, emptyList(), {}, {}, null, { refreshed = true }).dialog
                assertEquals(0, dialog.listView.count)
                val empty = find(dialog.window!!.decorView, TextView::class.java) { it.text == activity.getString(R.string.window_search_empty) }
                assertEquals(View.VISIBLE, empty?.visibility)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(refreshed)
        }
    }

    @Test fun refreshPreservesSearchAndUpdatesTheVisibleList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var refreshed = false
                val picker = WindowPickerDialog.show(activity,
                    listOf(WindowInfo(1, 300, 200, "Editor", "Notes", true)), {}, {}, null, { refreshed = true })
                val dialog = picker.dialog
                val search = find(dialog.window!!.decorView, EditText::class.java)!!
                search.setText("project")
                assertEquals(0, dialog.listView.count)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick())
                assertTrue(refreshed)
                assertTrue(dialog.isShowing)
                picker.setLoading(true)
                assertTrue(!dialog.listView.isEnabled)
                assertTrue(!dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
                picker.update(listOf(WindowInfo(2, 640, 480, "Editor", "Project", true),
                    WindowInfo(3, 300, 200, "Editor", "Notes", true)))
                picker.setLoading(false)
                assertEquals("project", search.text.toString())
                assertEquals(1, dialog.listView.count)
                assertEquals("Editor — Project（640 × 480 pt）", dialog.listView.adapter.getItem(0))
                dialog.dismiss()
            }
        }
    }

    private fun <T : View> find(view: View, type: Class<T>, predicate: (T) -> Boolean = { true }): T? {
        if (type.isInstance(view) && predicate(type.cast(view)!!)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type, predicate)?.let { return it }
        return null
    }
}
