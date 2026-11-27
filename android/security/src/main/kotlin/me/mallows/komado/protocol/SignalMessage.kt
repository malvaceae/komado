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

data class DisplayInfo(val id: Long, val width: Int, val height: Int)
data class WindowInfo(val id: Long, val width: Int, val height: Int, val application: String, val title: String)

/** Decoding validates message structure, not the sender or session state. */
sealed interface SignalMessage {
    data class Pair(val name: String, val invitation: ByteArray, val publicKeyDER: ByteArray) : SignalMessage
    data class Authenticate(val deviceID: UUID) : SignalMessage
    data class Challenge(val value: ByteArray) : SignalMessage
    data class Proof(val signatureDER: ByteArray) : SignalMessage
    data class Accepted(val deviceID: UUID) : SignalMessage
    data object Rejected : SignalMessage
    data object StartMedia : SignalMessage
    data object StartControl : SignalMessage
    data class StartDisplay(val id: Long) : SignalMessage
    data object ListDisplays : SignalMessage
    data class Displays(val values: List<DisplayInfo>) : SignalMessage
    data class ListWindows(val offset: Int) : SignalMessage
    data class Windows(val next: Int, val values: List<WindowInfo>) : SignalMessage
    data class StartWindow(val id: Long) : SignalMessage
    data class Offer(val sdp: String) : SignalMessage
    data class Answer(val sdp: String) : SignalMessage
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
                    if (fields.size == 194 || reader.peek() != JsonToken.STRING) throw MessageException()
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
                "start" -> {
                    if (fields.size == 2) SignalMessage.StartMedia
                    else if (fields.size == 3) SignalMessage.StartDisplay(positiveInteger(fields[2], 4_294_967_295L))
                    else throw MessageException()
                }
                "control.start" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.StartControl
                }
                "display.list" -> {
                    if (fields.size != 2) throw MessageException()
                    SignalMessage.ListDisplays
                }
                "display.listed" -> {
                    if (fields.size !in 5..194 || (fields.size - 2) % 3 != 0) throw MessageException()
                    val values = (2 until fields.size step 3).map { i ->
                        DisplayInfo(positiveInteger(fields[i], 4_294_967_295L),
                            positiveInteger(fields[i + 1], 65_535).toInt(), positiveInteger(fields[i + 2], 65_535).toInt())
                    }
                    if (values.map { it.id }.toSet().size != values.size) throw MessageException()
                    SignalMessage.Displays(values)
                }
                "window.list" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.ListWindows(nonnegativeInteger(fields[2], 512).toInt())
                }
                "window.listed" -> {
                    if (fields.size !in 3..163 || (fields.size - 3) % 5 != 0) throw MessageException()
                    val next = nonnegativeInteger(fields[2], 512).toInt()
                    val values = (3 until fields.size step 5).map { i ->
                        if (!validWindowLabel(fields[i + 3]) || !validWindowLabel(fields[i + 4])) throw MessageException()
                        WindowInfo(positiveInteger(fields[i], 4_294_967_295L),
                            positiveInteger(fields[i + 1], 65_535).toInt(), positiveInteger(fields[i + 2], 65_535).toInt(),
                            fields[i + 3], fields[i + 4])
                    }
                    if ((values.isEmpty() && next != 0) || values.map { it.id }.toSet().size != values.size) throw MessageException()
                    SignalMessage.Windows(next, values)
                }
                "window.start" -> {
                    if (fields.size != 3) throw MessageException()
                    SignalMessage.StartWindow(positiveInteger(fields[2], 4_294_967_295L))
                }
                "offer" -> {
                    if (fields.size != 3 || !validSDP(fields[2])) throw MessageException()
                    SignalMessage.Offer(fields[2])
                }
                "answer" -> {
                    if (fields.size != 3 || !validSDP(fields[2])) throw MessageException()
                    SignalMessage.Answer(fields[2])
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
            SignalMessage.StartMedia -> listOf("1", "start")
            SignalMessage.StartControl -> listOf("1", "control.start")
            is SignalMessage.StartDisplay -> listOf("1", "start", message.id.toString())
            SignalMessage.ListDisplays -> listOf("1", "display.list")
            is SignalMessage.Displays -> {
                if (message.values.size !in 1..64) throw MessageException()
                listOf("1", "display.listed") + message.values.flatMap { listOf(it.id.toString(), it.width.toString(), it.height.toString()) }
            }
            is SignalMessage.ListWindows -> listOf("1", "window.list", message.offset.toString())
            is SignalMessage.Windows -> {
                if (message.values.size > 32 || message.values.any { !validWindowLabel(it.application) || !validWindowLabel(it.title) }) throw MessageException()
                listOf("1", "window.listed", message.next.toString()) + message.values.flatMap {
                    listOf(it.id.toString(), it.width.toString(), it.height.toString(), it.application, it.title)
                }
            }
            is SignalMessage.StartWindow -> listOf("1", "window.start", message.id.toString())
            is SignalMessage.Offer -> {
                if (!validSDP(message.sdp)) throw MessageException()
                listOf("1", "offer", message.sdp)
            }
            is SignalMessage.Answer -> {
                if (!validSDP(message.sdp)) throw MessageException()
                listOf("1", "answer", message.sdp)
            }
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

    private fun positiveInteger(value: String, maximum: Long): Long {
        if (value.isEmpty() || value.length > 10 || value.startsWith('0') || value.any { it !in '0'..'9' }) throw MessageException()
        return value.toLongOrNull()?.takeIf { it in 1..maximum } ?: throw MessageException()
    }

    private fun nonnegativeInteger(value: String, maximum: Long): Long =
        if (value == "0") 0 else positiveInteger(value, maximum)

    private fun validWindowLabel(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in 1..96 && String(bytes, Charsets.UTF_8) == value && value.none { it.isISOControl() }
    }

    private fun validSDP(sdp: String): Boolean {
        if (sdp.length > 12_288 || !sdp.startsWith("v=0\r\n")) return false
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        return bytes.size <= 12_288 && String(bytes, Charsets.UTF_8) == sdp &&
            sdp.none { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' }
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
