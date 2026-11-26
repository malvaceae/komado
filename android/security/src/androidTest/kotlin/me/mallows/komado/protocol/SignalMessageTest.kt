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
}
