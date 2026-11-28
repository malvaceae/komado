package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class SignalMessageTest {
    @Test fun sharedValidAndInvalidMessages() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("signal-messages.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) {
            val input = valid.getString(i)
            val message = SignalMessageCodec.decode(input.toByteArray(Charsets.UTF_8))
            val encoded = SignalMessageCodec.encode(message).toString(Charsets.UTF_8)
            assertEquals(JSONArray(input).toString(), JSONArray(encoded).toString())
        }
        for (i in 0 until invalid.length()) {
            assertThrows("Invalid fixture $i", SignalMessageCodec.MessageException::class.java) {
                SignalMessageCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test fun rejectsInvalidValuesAtEncodingAndMalformedBytes() {
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Challenge(ByteArray(31))) }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Proof(byteArrayOf())) }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.decode(byteArrayOf(-1)) }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.decode(ByteArray(16_385) { 32 }) }
    }

    @Test fun displayListBoundariesAndDuplicateIds() {
        val values = (1..64).map { DisplayInfo(it.toLong(), 1920, 1080, "\"".repeat(96)) }
        val message = SignalMessage.Displays(values)
        assertEquals(message, SignalMessageCodec.decode(SignalMessageCodec.encode(message)))
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Displays(emptyList())) }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Displays(values + DisplayInfo(65, 1, 1, "画面")))
        }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Displays(listOf(values[0], values[0])))
        }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Displays(listOf(DisplayInfo(1, 65_536, 1, "画面"))))
        }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Displays(listOf(DisplayInfo(1, 1, 1, ""))))
        }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Displays(listOf(DisplayInfo(1, 1, 1, "あ".repeat(33)))))
        }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.StartDisplay(0)) }
    }

    @Test fun windowPageBoundariesAndLabels() {
        val windows = (1..32).map { WindowInfo(it.toLong(), 800, 600, "ア".repeat(32), "ウインドウ", it % 2 == 0, 123) }
        val page = SignalMessage.Windows(32, windows)
        assertEquals(page, SignalMessageCodec.decode(SignalMessageCodec.encode(page)))
        assertEquals(SignalMessage.Windows(0, emptyList()), SignalMessageCodec.decode(SignalMessageCodec.encode(SignalMessage.Windows(0, emptyList()))))
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Windows(1, emptyList())) }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Windows(0, windows + windows[0])) }
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.Windows(0, listOf(windows[0], windows[0]))) }
        assertThrows(SignalMessageCodec.MessageException::class.java) {
            SignalMessageCodec.encode(SignalMessage.Windows(0, listOf(WindowInfo(1, 1, 1, "a", "あ".repeat(33), true, 123))))
        }
        assertEquals(SignalMessage.ListWindows(65_535), SignalMessageCodec.decode(SignalMessageCodec.encode(SignalMessage.ListWindows(65_535))))
        assertThrows(SignalMessageCodec.MessageException::class.java) { SignalMessageCodec.encode(SignalMessage.ListWindows(65_536)) }
    }
}
