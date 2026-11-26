package me.mallows.komado.protocol

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Decoding validates message structure, not the sender or session state. */
sealed interface SignalMessage {
    data class Pair(val name: String, val invitation: ByteArray, val publicKeyDER: ByteArray) : SignalMessage
    data class Authenticate(val deviceID: UUID) : SignalMessage
    data class Challenge(val value: ByteArray) : SignalMessage
    data class Proof(val signatureDER: ByteArray) : SignalMessage
    data class Accepted(val deviceID: UUID) : SignalMessage
    data object Rejected : SignalMessage
}

object SignalMessageCodec {
    class MessageException : Exception("Invalid signaling message")

    fun decode(payload: ByteArray): SignalMessage {
        if (payload.size !in 1..SignalFrame.MAXIMUM_PAYLOAD) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
            val fields = mutableListOf<String>()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                while (reader.hasNext()) {
                    if (fields.size == 5 || reader.peek() != JsonToken.STRING) throw MessageException()
                    fields.add(reader.nextString())
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
            }
            if (fields.size < 2 || fields[0] != "1") throw MessageException()
            return when (fields[1]) {
                "pair" -> {
                    if (fields.size != 5 || !validName(fields[2])) throw MessageException()
                    SignalMessage.Pair(fields[2], decodeBase64(fields[3], 32..32), decodeBase64(fields[4], 1..128))
                }
                "auth" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Authenticate(parseUUID(fields[2]))
                }
                "challenge" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Challenge(decodeBase64(fields[2], 32..32))
                }
                "proof" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Proof(decodeBase64(fields[2], 1..72))
                }
                "accepted" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.Accepted(parseUUID(fields[2]))
                }
                "rejected" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.Rejected
                }
                else -> throw MessageException()
            }
        } catch (_: MessageException) {
            throw MessageException()
        } catch (_: Exception) {
            throw MessageException()
        }
    }

    fun encode(message: SignalMessage): ByteArray {
        fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
        val fields = when (message) {
            is SignalMessage.Pair -> {
                if (!validName(message.name) || message.invitation.size != 32 || message.publicKeyDER.size !in 1..128) throw MessageException()
                listOf("1", "pair", message.name, b64(message.invitation), b64(message.publicKeyDER))
            }
            is SignalMessage.Authenticate -> listOf("1", "auth", message.deviceID.toString())
            is SignalMessage.Challenge -> {
                if (message.value.size != 32) throw MessageException()
                listOf("1", "challenge", b64(message.value))
            }
            is SignalMessage.Proof -> {
                if (message.signatureDER.size !in 1..72) throw MessageException()
                listOf("1", "proof", b64(message.signatureDER))
            }
            is SignalMessage.Accepted -> listOf("1", "accepted", message.deviceID.toString())
            SignalMessage.Rejected -> listOf("1", "rejected")
        }
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray()
            fields.forEach { writer.value(it) }
            writer.endArray()
        }
        val bytes = output.toString().toByteArray(Charsets.UTF_8)
        decode(bytes)
        return bytes
    }

    private fun validName(name: String): Boolean {
        if (name.length > 256) return false
        val bytes = name.toByteArray(Charsets.UTF_8)
        return name.trim(' ').isNotEmpty() && bytes.size <= 256 &&
            String(bytes, Charsets.UTF_8) == name && name.none { it.isISOControl() }
    }

    private fun parseUUID(value: String): UUID {
        val uuid = UUID.fromString(value)
        if (uuid.toString() != value) throw MessageException()
        return uuid
    }

    private fun decodeBase64(value: String, size: IntRange): ByteArray {
        if (value.length > ((size.last + 2) / 3) * 4) throw MessageException()
        val decoded = Base64.decode(value, Base64.NO_WRAP)
        if (decoded.size !in size || Base64.encodeToString(decoded, Base64.NO_WRAP) != value) throw MessageException()
        return decoded
    }
}
