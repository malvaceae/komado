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

    @Test fun addsOnlyPairedHostsAndRejectsDuplicates() {
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local")
        val paired = Pairing(UUID.randomUUID().toString(), "AB".repeat(32),
            "me.mallows.komado.pair.${UUID.randomUUID()}")
        assertFalse(store.add(host))
        assertTrue(store.add(host.copy(pairing = paired)))
        assertFalse(store.add(host.copy(pairing = paired)))
        val saved = store.load().single()
        assertEquals(paired, saved.pairing)
        assertTrue(store.remove(saved))
        assertTrue(store.load().isEmpty())
    }

    @Test fun rejectsCorruptedPairingMetadataWithoutOverwritingIt() {
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local")
        val preferences = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
        val broken = """[{"id":"${host.id}","name":"Mac","address":"mac.local","port":48365,"pairing":{"deviceID":"bad","fingerprint":"AB","keyAlias":"other"}}]"""
        assertTrue(preferences.edit().putString("entries", broken).commit())
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(broken, preferences.getString("entries", null))
    }

    @Test fun rejectsMissingPortWithoutOverwritingData() {
        val preferences = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
        val broken = """[{"id":"${UUID.randomUUID()}","name":"Mac","address":"mac.local"}]"""
        assertTrue(preferences.edit().putString("entries", broken).commit())
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(broken, preferences.getString("entries", null))
    }

    @Test fun recordsAuthenticatedConnectionWithoutResurrectingRemovedHosts() {
        val pairing = Pairing(UUID.randomUUID().toString(), "AB".repeat(32),
            "me.mallows.komado.pair.${UUID.randomUUID()}")
        val host = Host(UUID.randomUUID().toString(), "Mac", "mac.local", pairing = pairing)
        assertTrue(store.add(host))
        assertFalse(store.recordConnection(host, 0))
        assertTrue(store.recordConnection(host, 1_700_000_000_000))
        val connected = store.load().single()
        assertEquals(1_700_000_000_000L, connected.lastConnectedAt)
        assertTrue(store.remove(connected))
        assertFalse(store.recordConnection(host, 1_700_000_000_001))
        assertTrue(store.load().isEmpty())
    }

    @Test fun rejectsMalformedConnectionHistoryWithoutOverwritingIt() {
        val preferences = context.getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
        val id = UUID.randomUUID()
        val deviceID = UUID.randomUUID()
        val alias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        for (value in listOf("\"yesterday\"", "0", "1.5", "253402300800000")) {
            val broken = """[{"id":"$id","name":"Mac","address":"mac.local","port":48365,"pairing":{"deviceID":"$deviceID","fingerprint":"${"AB".repeat(32)}","keyAlias":"$alias"},"lastConnectedAt":$value}]"""
            assertTrue(preferences.edit().putString("entries", broken).commit())
            assertThrows(Exception::class.java) { store.load() }
            assertEquals(broken, preferences.getString("entries", null))
        }
        val unpaired = """[{"id":"$id","name":"Mac","address":"mac.local","port":48365,"lastConnectedAt":1}]"""
        assertTrue(preferences.edit().putString("entries", unpaired).commit())
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(unpaired, preferences.getString("entries", null))
    }
}
