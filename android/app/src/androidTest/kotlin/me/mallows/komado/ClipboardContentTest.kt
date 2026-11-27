package me.mallows.komado

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardContentTest {
    @Test fun acceptsOnlyBoundedPlainTextAndMarksRemoteContent() {
        val clip = ClipData.newPlainText("test", "日本語😀\r\n")
        assertEquals("日本語😀\r\n", ClipboardContent.text(clip))
        assertNull(ClipboardContent.text(null))
        assertNull(ClipboardContent.text(ClipData.newRawUri("test", Uri.parse("content://example/item"))))
        assertNull(ClipboardContent.text(ClipData.newPlainText("test", "a".repeat(16_385))))
        clip.addItem(ClipData.Item("second"))
        assertNull(ClipboardContent.text(clip))
        val remote = requireNotNull(ClipboardContent.remote("test", "remote"))
        assertTrue(remote.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
        assertTrue(remote.description.extras?.getBoolean("android.content.extra.IS_REMOTE_DEVICE") == true)
        assertTrue(remote.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertTrue(!ClipboardContent.automaticAllowed(remote.description))
        val plain = ClipData.newPlainText("test", "plain")
        assertTrue(ClipboardContent.automaticAllowed(plain.description))
        plain.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        assertTrue(!ClipboardContent.automaticAllowed(plain.description))
        assertTrue(!ClipboardContent.automaticAllowed(null))
        assertNull(ClipboardContent.remote("bad\u0000text", "remote"))
    }
}
