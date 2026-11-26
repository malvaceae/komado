package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HostStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "komado-test-${UUID.randomUUID()}"
    private val store = HostStore(context, preferenceName)

    @After fun cleanup() { context.deleteSharedPreferences(preferenceName) }

    @Test fun savesPairingAndRejectsStaleUpdates() {
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local")
        assertTrue(store.save(null, host))
        val paired = Pairing(UUID.randomUUID().toString(), "AB".repeat(32),
            "me.mallows.komado.pair.${UUID.randomUUID()}")
        assertTrue(store.pair(host, paired))
        assertFalse(store.pair(host, paired))
        assertFalse(store.save(host, host.copy(name = "stale")))
        val saved = store.load().single()
        assertEquals(paired, saved.pairing)
        assertTrue(store.save(saved, saved.copy(address = "100.64.0.1")))
        val edited = store.load().single()
        assertEquals(paired, edited.pairing)
        assertFalse(store.remove(saved))
        assertTrue(store.remove(edited))
        assertTrue(store.load().isEmpty())
    }

    @Test fun rejectsCorruptedPairingMetadataWithoutOverwritingIt() {
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local")
        assertTrue(store.save(null, host))
        val preferences = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
        val broken = """[{"id":"${host.id}","name":"Mac","address":"mac.local","pairing":{"deviceID":"bad","fingerprint":"AB","keyAlias":"other"}}]"""
        assertTrue(preferences.edit().putString("entries", broken).commit())
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(broken, preferences.getString("entries", null))
    }
}
