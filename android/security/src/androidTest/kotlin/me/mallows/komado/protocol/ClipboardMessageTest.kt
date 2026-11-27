package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardMessageTest {
    @Test fun sharedRequestsAndResponses() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("clipboard-messages.json").bufferedReader().use { it.readText() })
        val requests = fixture.getJSONObject("requests")
        val responses = fixture.getJSONObject("responses")
        val validRequests = requests.getJSONArray("valid")
        for (i in 0 until validRequests.length()) {
            val value = ClipboardCodec.decodeRequest(validRequests.getString(i).toByteArray())
            assertEquals(value, ClipboardCodec.decodeRequest(ClipboardCodec.encodeRequest(value)))
        }
        val invalidRequests = requests.getJSONArray("invalid")
        for (i in 0 until invalidRequests.length()) assertThrows(ClipboardCodec.MessageException::class.java) {
            ClipboardCodec.decodeRequest(invalidRequests.getString(i).toByteArray())
        }
        val validResponses = responses.getJSONArray("valid")
        for (i in 0 until validResponses.length()) {
            val value = ClipboardCodec.decodeResponse(validResponses.getString(i).toByteArray())
            assertEquals(value, ClipboardCodec.decodeResponse(ClipboardCodec.encodeResponse(value)))
        }
        val invalidResponses = responses.getJSONArray("invalid")
        for (i in 0 until invalidResponses.length()) assertThrows(ClipboardCodec.MessageException::class.java) {
            ClipboardCodec.decodeResponse(invalidResponses.getString(i).toByteArray())
        }
        ClipboardCodec.encodeRequest(ClipboardRequest(1, 1, ClipboardOperation.Set("a".repeat(16_384))))
        assertThrows(ClipboardCodec.MessageException::class.java) { ClipboardCodec.encodeRequest(ClipboardRequest(1, 1, ClipboardOperation.Set("a".repeat(16_385)))) }
        assertThrows(ClipboardCodec.MessageException::class.java) { ClipboardCodec.encodeResponse(ClipboardResponse(1, ClipboardResult.Text("a".repeat(16_385)))) }
        assertThrows(ClipboardCodec.MessageException::class.java) { ClipboardCodec.decodeResponse(ByteArray(65_537)) }
    }
}
