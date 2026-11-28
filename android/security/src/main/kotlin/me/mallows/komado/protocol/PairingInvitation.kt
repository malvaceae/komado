package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The QR payload contains a short-lived secret; never log or persist it. */
data class PairingInvitation(val name: String, val host: String, val port: Int, val fingerprint: String, val token: String)

object PairingInvitationCodec {
    class MessageException : Exception("Invalid pairing invitation")

    fun decode(bytes: ByteArray): PairingInvitation {
        if (bytes.size !in 1..1024) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                fun string(): String {
                    if (reader.peek() != JsonToken.STRING) throw MessageException()
                    return reader.nextString()
                }
                if (string() != "1" || string() != "pairing") throw MessageException()
                val name = string()
                val host = string()
                val portText = string()
                val fingerprint = string()
                val token = string()
                if (!validName(name) || !validHost(host) || portText.isEmpty() || portText.startsWith('0') || portText.any { it !in '0'..'9' } ||
                    !validHex(fingerprint) || !validHex(token) || reader.hasNext()) throw MessageException()
                val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return PairingInvitation(name, host, port, fingerprint, token)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(value: PairingInvitation): ByteArray {
        if (!validName(value.name) || !validHost(value.host) || value.port !in 1..65535 || !validHex(value.fingerprint) || !validHex(value.token)) throw MessageException()
        val output = StringWriter()
        JsonWriter(output).use { it.beginArray().value("1").value("pairing").value(value.name).value(value.host).value(value.port.toString())
            .value(value.fingerprint).value(value.token).endArray() }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (it.size > 1024) throw MessageException() }
    }

    private fun validName(value: String) = value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256 &&
        value.none { it.isISOControl() } && String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == value
    private fun validHost(value: String) = value.length in 1..253 && value.all { it in '0'..'9' || it in 'A'..'Z' ||
        it in 'a'..'z' || it in "-.:_%" }
    private fun validHex(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'A'..'F' }
}
