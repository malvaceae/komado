package me.mallows.komado

import android.view.View
import android.os.Build
import android.view.inputmethod.TextAttribute
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommittedTextConnectionTest {
    @Test fun sendsCommittedTextOnlyOnce() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val sent = mutableListOf<String>()
            val connection = CommittedTextConnection(View(instrument.targetContext)) { sent.add(it); true }
            assertTrue(connection.setComposingText("に", 1))
            assertTrue(connection.setComposingText("日本", 1))
            assertTrue(sent.isEmpty())
            assertTrue(connection.commitText("日本", 1))
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("日本"), sent)
            assertTrue(connection.setComposingText("語", 1))
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("日本", "語"), sent)
            if (Build.VERSION.SDK_INT >= 33) {
                val attributes = TextAttribute.Builder().build()
                assertTrue(connection.setComposingText("あ", 1, attributes))
                assertTrue(connection.commitText("絵文字😀", 1, attributes))
                assertEquals(listOf("日本", "語", "絵文字😀"), sent)
            }
            assertEquals(0, connection.editable.length)
            connection.closeConnection()
        }
    }

    @Test fun doesNotCommitAfterCloseOrWhenDeliveryFails() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val sent = mutableListOf<String>()
            var available = false
            val connection = CommittedTextConnection(View(instrument.targetContext)) {
                if (available) { sent.add(it); true } else false
            }
            assertTrue(connection.setComposingText("変換中", 1))
            assertFalse(connection.setComposingText("\ud800", 1))
            assertFalse(connection.setComposingText("a".repeat(8193), 1))
            assertFalse(connection.commitText("確定", 1))
            assertEquals("変換中", connection.editable.toString())
            assertFalse(connection.finishComposingText())
            available = true
            connection.closeConnection()
            assertTrue(sent.isEmpty())
            assertFalse(connection.commitText("送信しない", 1))
            assertFalse(connection.deleteSurroundingText(1, 0))
        }
    }
}
