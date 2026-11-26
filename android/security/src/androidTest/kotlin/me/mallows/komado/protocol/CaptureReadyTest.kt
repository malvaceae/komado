package me.mallows.komado.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureReadyTest {
    @Test fun sharedMessages() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("capture-ready.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) assertTrue(CaptureReadyCodec.decode(valid.getString(i).toByteArray()) > 0)
        for (i in 0 until invalid.length()) assertThrows(CaptureReadyCodec.MessageException::class.java) {
            CaptureReadyCodec.decode(invalid.getString(i).toByteArray())
        }
    }
}
