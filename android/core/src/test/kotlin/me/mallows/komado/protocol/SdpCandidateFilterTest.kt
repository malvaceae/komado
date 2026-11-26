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

    @Test fun selectedLocalCandidateMustMatchAddress() {
        val address = InetAddress.getByName("192.168.1.5")
        val candidate = "candidate:1 1 udp 1 192.168.1.5 54321 typ host generation 0"
        assertTrue(SdpCandidateFilter.permitsSelectedLocalCandidate(candidate, address))
        assertTrue(!SdpCandidateFilter.permitsSelectedLocalCandidate(candidate.replace("192.168.1.5", "10.0.0.1"), address))
        assertTrue(!SdpCandidateFilter.permitsSelectedLocalCandidate(candidate + "\r\na=candidate:2", address))
        val reflexive = "candidate:3 1 udp 1 203.0.113.1 1234 typ prflx raddr 192.168.1.5 rport 54321"
        assertTrue(SdpCandidateFilter.permitsSelectedLocalCandidate(reflexive, address))
        assertTrue(!SdpCandidateFilter.permitsSelectedLocalCandidate(reflexive.replace("192.168.1.5", "10.0.0.1"), address))
        assertTrue(!SdpCandidateFilter.permitsSelectedLocalCandidate(reflexive + " raddr 192.168.1.5", address))
        assertTrue(!SdpCandidateFilter.permitsSelectedLocalCandidate(reflexive.replace("rport 54321", "rport 0"), address))
    }
}
