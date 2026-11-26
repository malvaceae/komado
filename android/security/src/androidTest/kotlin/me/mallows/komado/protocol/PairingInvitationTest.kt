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
class PairingInvitationTest {
    @Test fun sharedMessagesAndLimits() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("pairing-invitations.json").bufferedReader().use { it.readText() })
        val valid = fixture.getJSONArray("valid")
        val invalid = fixture.getJSONArray("invalid")
        assertTrue(valid.length() > 0 && invalid.length() > 0)
        for (i in 0 until valid.length()) {
            val invitation = PairingInvitationCodec.decode(valid.getString(i).toByteArray(Charsets.UTF_8))
            assertEquals(invitation, PairingInvitationCodec.decode(PairingInvitationCodec.encode(invitation)))
        }
        for (i in 0 until invalid.length()) assertThrows(PairingInvitationCodec.MessageException::class.java) {
            PairingInvitationCodec.decode(invalid.getString(i).toByteArray(Charsets.UTF_8))
        }
        assertThrows(PairingInvitationCodec.MessageException::class.java) { PairingInvitationCodec.decode(ByteArray(1025)) }
    }
}
