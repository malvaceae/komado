package me.mallows.komado

import android.Manifest
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class PairingScannerComposeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun cameraPreviewAndCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        ActivityScenario.launch(PairingScannerActivity::class.java).use { scenario ->
            compose.onNodeWithText(context.getString(R.string.scan_pairing_instruction)).assertIsDisplayed()
            compose.waitUntil(20_000) {
                var streaming = false
                scenario.onActivity { activity ->
                    val preview = findPreview(activity.window.decorView)
                    streaming = preview?.previewStreamState?.value == PreviewView.StreamState.STREAMING
                }
                streaming
            }
            compose.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findPreview(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
