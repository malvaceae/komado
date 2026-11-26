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
class PointerClickTest {
    @Test fun sharedMessages() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("pointer-click.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) {
            val click = PointerClickCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(click, PointerClickCodec.decode(PointerClickCodec.encode(click)))
        }
        for (i in 0 until invalid.length()) {
            assertThrows(PointerClickCodec.MessageException::class.java) {
                PointerClickCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
            }
        }
        assertThrows(PointerClickCodec.MessageException::class.java) { PointerClickCodec.decode(ByteArray(1025)) }
        assertThrows(PointerClickCodec.MessageException::class.java) {
            PointerClickCodec.encode(PointerClick(1, 1, -1, 0, PointerClick.Button.LEFT))
        }
    }
}
