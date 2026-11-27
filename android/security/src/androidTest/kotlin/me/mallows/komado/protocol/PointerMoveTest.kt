package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PointerMoveTest {
    @Test fun sharedMessages() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("pointer-move.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid"); val invalid = fixture.getJSONArray("invalid")
        for (i in 0 until valid.length()) {
            val move = PointerMoveCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(move, PointerMoveCodec.decode(PointerMoveCodec.encode(move)))
        }
        for (i in 0 until invalid.length()) assertThrows(PointerMoveCodec.MessageException::class.java) {
            PointerMoveCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
        }
        assertThrows(PointerMoveCodec.MessageException::class.java) { PointerMoveCodec.decode(ByteArray(1025)) }
    }
}
