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
            scenario.onActivity { activity ->
                val windows = (1L..150L).map { WindowInfo(it, 300, 200, "App", "Window $it") }
                dialog = WindowPickerDialog.show(activity, windows, {}, {})
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
            scenario.onActivity { dialog.dismiss() }
        }
    }

    @Test fun filteringSelectsTheMatchingWindowAndHandlesNoResults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val windows = listOf(
                    WindowInfo(1, 300, 200, "Editor", "Notes"),
                    WindowInfo(2, 300, 200, "Browser", "Project"),
                    WindowInfo(3, 300, 200, "Editor", "Project")
                )
                var selected: Long? = null
                val dialog = WindowPickerDialog.show(activity, windows, { selected = it.id }, {})
                val root = dialog.window!!.decorView
                val search = find(root, EditText::class.java)!!
                assertEquals(3, dialog.listView.count)
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

    private fun <T : View> find(view: View, type: Class<T>, predicate: (T) -> Boolean = { true }): T? {
        if (type.isInstance(view) && predicate(type.cast(view)!!)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type, predicate)?.let { return it }
        return null
    }
}
