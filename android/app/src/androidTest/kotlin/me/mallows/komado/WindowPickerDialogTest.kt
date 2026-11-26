package me.mallows.komado

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowPickerDialogTest {
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
