package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardTextTest {
    @Test fun sharedMessagesAndLimits() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("keyboard-text.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) {
            val message = KeyboardTextCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(message, KeyboardTextCodec.decode(KeyboardTextCodec.encode(message)))
        }
        for (i in 0 until invalid.length()) assertThrows(KeyboardTextCodec.MessageException::class.java) {
            KeyboardTextCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
        }
        KeyboardTextCodec.encode(KeyboardText(1, 1, "あ".repeat(170)))
        assertThrows(KeyboardTextCodec.MessageException::class.java) { KeyboardTextCodec.encode(KeyboardText(1, 1, "あ".repeat(171))) }
        assertThrows(KeyboardTextCodec.MessageException::class.java) { KeyboardTextCodec.decode(ByteArray(2049)) }
    }
}
