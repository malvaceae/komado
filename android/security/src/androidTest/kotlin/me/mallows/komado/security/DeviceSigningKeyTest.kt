package me.mallows.komado.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceSigningKeyTest {
    private val alias = "me.mallows.komado.test.${UUID.randomUUID()}"
    private val signingKey = DeviceSigningKey(alias)
    private val domain = "komado.device-auth.v1\u0000".toByteArray(Charsets.UTF_8)

    @After fun cleanup() { signingKey.delete() }

    @Test fun generatedKeyPersistsAndRejectsChangedChallenge() {
        val publicKey = signingKey.getOrCreatePublicKeyDER()
        assertArrayEquals(publicKey, DeviceSigningKey(alias).getOrCreatePublicKeyDER())
        val challenge = ByteArray(32) { it.toByte() }
        val signature = signingKey.signChallenge(challenge)
        assertTrue(verify(publicKey, challenge, signature))
        challenge[0] = 99
        assertFalse(verify(publicKey, challenge, signature))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNotNull(store.getKey(alias, null))
        assertTrue(store.getKey(alias, null).encoded == null)
        signingKey.delete()
        try {
            signingKey.signChallenge(ByteArray(32))
            fail("Deleted key must not sign")
        } catch (_: KeyStoreException) { }
    }

    @Test fun rejectsInvalidChallengeAndAlias() {
        signingKey.getOrCreatePublicKeyDER()
        try {
            signingKey.signChallenge(ByteArray(31))
            fail("Invalid challenge length must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            DeviceSigningKey("unrelated-key")
            fail("Invalid alias must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            DeviceSigningKey("me.mallows.komado.")
            fail("Empty alias suffix must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun verifiesSharedFixtureAndRejectsTampering() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixture = JSONObject(assets.open("device-auth-valid.json").bufferedReader().use { it.readText() })
        fun decode(name: String) = Base64.decode(fixture.getString(name), Base64.DEFAULT)
        val publicKey = decode("publicKeyDer")
        val challenge = decode("challenge")
        val signature = decode("signatureDer")
        assertTrue(verify(publicKey, challenge, signature))
        challenge[0] = (challenge[0].toInt() xor 1).toByte()
        assertFalse(verify(publicKey, challenge, signature))
    }

    private fun verify(publicKey: ByteArray, challenge: ByteArray, signature: ByteArray): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(domain)
            update(challenge)
            verify(signature)
        }
    }
}
