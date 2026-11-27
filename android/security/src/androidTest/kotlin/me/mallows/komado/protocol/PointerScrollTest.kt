package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointerScrollTest {
    @Test fun sharedMessages() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("pointer-scroll.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        for (i in 0 until valid.length()) {
            val scroll = PointerScrollCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(scroll, PointerScrollCodec.decode(PointerScrollCodec.encode(scroll)))
        }
        for (i in 0 until invalid.length()) assertThrows(PointerScrollCodec.MessageException::class.java) {
            PointerScrollCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
        }
        assertThrows(PointerScrollCodec.MessageException::class.java) { PointerScrollCodec.decode(ByteArray(1025)) }
    }
}
