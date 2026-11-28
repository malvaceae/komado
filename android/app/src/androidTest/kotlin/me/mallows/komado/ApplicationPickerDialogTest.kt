package me.mallows.komado

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationPickerDialogTest {
    @Test fun showsIconFiltersNamesAndSelectsApplication() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val applications = listOf(ApplicationInfo(1, "Editor"), ApplicationInfo(2, "Browser"))
                var selected: Long? = null
                val picker = ApplicationPickerDialog(activity, applications, { selected = it.id }, {}, {}, {})
                picker.show()
                val icon = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                picker.updateIcon(2, icon)
                val search = requireNotNull(find(picker.dialog.window!!.decorView, EditText::class.java))
                search.setText("browser")
                assertEquals(1, picker.dialog.listView.count)
                val row = picker.dialog.listView.adapter.getView(0, null, picker.dialog.listView)
                val image = row.findViewById<ImageView>(android.R.id.icon)
                assertTrue((image.drawable as BitmapDrawable).bitmap === icon)
                picker.dialog.listView.performItemClick(row, 0, picker.dialog.listView.adapter.getItemId(0))
                assertEquals(2L, selected)
            }
        }
    }

    @Test fun emptyListCanBeRefreshed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var refreshed = false
            scenario.onActivity { activity ->
                val picker = ApplicationPickerDialog(activity, emptyList(), {}, {}, {}, { refreshed = true })
                picker.show()
                assertEquals(0, picker.dialog.listView.count)
                assertTrue(picker.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(refreshed)
        }
    }

    @Test fun launchButtonOpensTheLaunchPicker() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var launched = false
            scenario.onActivity { activity ->
                val picker = ApplicationPickerDialog(activity, emptyList(), {}, {}, {}, {}, onLaunch = { launched = true })
                picker.show()
                assertTrue(picker.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(launched)
        }
    }

    private fun <T : View> find(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), type)?.let { return it }
        return null
    }
}
