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
class KeyboardKeyTest {
    @Test fun sharedMessagesAndLimits() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("keyboard-key.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) {
            val message = KeyboardKeyCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(message, KeyboardKeyCodec.decode(KeyboardKeyCodec.encode(message)))
        }
        for (i in 0 until invalid.length()) assertThrows(KeyboardKeyCodec.MessageException::class.java) {
            KeyboardKeyCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
        }
        for (key in SpecialKey.entries) {
            val message = KeyboardKey(1, 2, key, 15)
            assertEquals(message, KeyboardKeyCodec.decode(KeyboardKeyCodec.encode(message)))
        }
        assertThrows(KeyboardKeyCodec.MessageException::class.java) { KeyboardKeyCodec.encode(KeyboardKey(1, 1, SpecialKey.TAB, 16)) }
        assertThrows(KeyboardKeyCodec.MessageException::class.java) { KeyboardKeyCodec.decode(ByteArray(1025)) }
    }
}
