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

    @Test fun recordsAuthenticatedConnectionWithoutResurrectingOrOverwritingEdits() {
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local")
        val pairing = Pairing(UUID.randomUUID().toString(), "AB".repeat(32),
            "me.mallows.komado.pair.${UUID.randomUUID()}")
        assertTrue(store.save(null, host))
        assertFalse(store.recordConnection(host, 1))
        assertTrue(store.pair(host, pairing))
        val paired = store.load().single()
        assertFalse(store.recordConnection(paired, 0))
        assertTrue(store.recordConnection(paired, 1_700_000_000_000))
        val connected = store.load().single()
        assertEquals(1_700_000_000_000L, connected.lastConnectedAt)
        assertTrue(store.save(connected, connected.copy(name = "Renamed")))
        assertTrue(store.recordConnection(paired, 1_700_000_000_001))
        val renamed = store.load().single()
        assertEquals("Renamed", renamed.name)
        assertEquals(1_700_000_000_001L, renamed.lastConnectedAt)
        assertTrue(store.save(renamed, renamed.copy(address = "other.local")))
        assertFalse(store.recordConnection(paired, 1_700_000_000_002))
        val edited = store.load().single()
        assertEquals(1_700_000_000_001L, edited.lastConnectedAt)
        assertTrue(store.remove(edited))
        assertFalse(store.recordConnection(edited, 1_700_000_000_003))
        assertTrue(store.load().isEmpty())
    }

    @Test fun rejectsMalformedConnectionHistoryWithoutOverwritingIt() {
        val preferences = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
        val id = UUID.randomUUID()
        val deviceID = UUID.randomUUID()
        val alias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        for (value in listOf("\"yesterday\"", "0", "1.5", "253402300800000")) {
            val broken = """[{"id":"$id","name":"Mac","address":"mac.local","pairing":{"deviceID":"$deviceID","fingerprint":"${"AB".repeat(32)}","keyAlias":"$alias"},"lastConnectedAt":$value}]"""
            assertTrue(preferences.edit().putString("entries", broken).commit())
            assertThrows(Exception::class.java) { store.load() }
            assertEquals(broken, preferences.getString("entries", null))
        }
        val unpaired = """[{"id":"$id","name":"Mac","address":"mac.local","lastConnectedAt":1}]"""
        assertTrue(preferences.edit().putString("entries", unpaired).commit())
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(unpaired, preferences.getString("entries", null))
    }
}
