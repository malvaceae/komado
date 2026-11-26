package me.mallows.komado.protocol

import java.io.File
import java.net.InetAddress
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SdpCandidateFilterTest {
    @Test fun sharedCandidates() {
        val rows = File(System.getProperty("komado.fixtures"), "sdp-candidates.txt")
            .readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val (name, address, encoded, expected) = row.split(' ')
            val sdp = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            val host = InetAddress.getByName(address)
            if (expected == "ERROR") {
                assertFailsWith<SdpCandidateFilter.FilterException>(name) { SdpCandidateFilter.restrict(sdp, host) }
            } else {
                assertEquals(String(Base64.getDecoder().decode(expected), Charsets.UTF_8), SdpCandidateFilter.restrict(sdp, host), name)
            }
        }
    }

    @Test fun ipv6MatchesInBinaryForm() {
        val sdp = "v=0\r\nc=IN IP6 2001:db8::2\r\na=candidate:1 1 udp 1 2001:db8:0:0:0:0:0:1 4000 typ host\r\n"
        val result = SdpCandidateFilter.restrict(sdp, InetAddress.getByName("2001:db8::1"))
        assertTrue(result.contains("a=candidate:1"))
        val address = result.lineSequence().first { it.startsWith("c=IN IP6 ") }.substringAfterLast(' ')
        assertEquals(InetAddress.getByName("2001:db8::1"), InetAddress.getByName(address))
    }
}
