package me.mallows.komado.protocol

import java.net.Inet6Address
import java.net.InetAddress

/** Restricts locally generated SDP, without binding WebRTC sockets to a route. */
object SdpCandidateFilter {
    class FilterException : Exception()

    /** Use only with the local candidate reported by WebRTC's selected pair callback. */
    fun permitsSelectedLocalCandidate(candidate: String, address: InetAddress): Boolean {
        if (!candidate.startsWith("candidate:") || '\r' in candidate || '\n' in candidate) return false
        return try {
            restrict("v=0\r\na=$candidate\r\n", address)
            true
        } catch (_: FilterException) {
            false
        }
    }

    fun restrict(sdp: String, address: InetAddress): String {
        if (sdp.toByteArray(Charsets.UTF_8).size > 12_288 || !sdp.startsWith("v=0\r\n") || !sdp.endsWith("\r\n") ||
            sdp.any { (it.code < 32 && it != '\t' && it != '\r' && it != '\n') || it.code == 127 }) throw FilterException()
        val family = if (address.address.size == 4) "IP4" else if (address.address.size == 16) "IP6" else throw FilterException()
        val host = address.hostAddress!!.substringBefore('%')
        val result = mutableListOf<String>()
        var kept = 0
        for (line in sdp.split("\r\n")) {
            if ('\r' in line || '\n' in line || line.startsWith("a=remote-candidates:")) throw FilterException()
            when {
                line.startsWith("c=") -> {
                    if (!line.startsWith("c=IN IP4 ") && !line.startsWith("c=IN IP6 ")) throw FilterException()
                    result.add("c=IN $family $host")
                }
                line.startsWith("a=candidate:") -> {
                    val parts = line.split(' ')
                    if (parts.size < 8 || parts.any { it.isEmpty() } || parts[0].length <= "a=candidate:".length ||
                        parts[1] !in setOf("1", "2") || parts[2].lowercase() !in setOf("udp", "tcp") ||
                        parts[3].toLongOrNull()?.let { it in 1..0xffff_ffffL } != true ||
                        parts[5].toIntOrNull()?.let { it in 1..65535 } != true || parts[6] != "typ" ||
                        (parts.size - 8) % 2 != 0) throw FilterException()
                    if (parts[7] != "host") continue
                    if ((8 until parts.size step 2).any { parts[it] == "raddr" || parts[it] == "rport" }) throw FilterException()
                    if (numericAddress(parts[4])?.contentEquals(address.address) == true) {
                        result.add(line)
                        kept++
                    }
                }
                else -> result.add(line)
            }
        }
        if (kept == 0) throw FilterException()
        return result.joinToString("\r\n").also {
            if (it.toByteArray(Charsets.UTF_8).size > 12_288) throw FilterException()
        }
    }

    private fun numericAddress(value: String): ByteArray? {
        if (':' in value) {
            if ('.' in value || '%' in value || value.any { it !in "0123456789abcdefABCDEF:" }) return null
            return try { (InetAddress.getByName(value) as? Inet6Address)?.address } catch (_: Exception) { null }
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } ||
                    (it.length > 1 && it.startsWith('0')) || it.toIntOrNull()?.let { n -> n in 0..255 } != true }) return null
        return parts.map { it.toInt().toByte() }.toByteArray()
    }
}
