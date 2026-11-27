package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Confirms event posting, not delivery to or processing by the target app. */
object KeyboardTextPostedCodec {
    class MessageException : Exception("Invalid text posting confirmation")

    fun decode(data: ByteArray): Long {
        if (data.size !in 1..256) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                if (reader.peek() != JsonToken.STRING || reader.nextString() != "1" ||
                    reader.peek() != JsonToken.STRING || reader.nextString() != "keyboard.text.posted" ||
                    reader.peek() != JsonToken.STRING) throw MessageException()
                val raw = reader.nextString()
                if (raw.isEmpty() || raw.length > 16 || raw.startsWith('0') || raw.any { it !in '0'..'9' }) throw MessageException()
                val sequence = raw.toLongOrNull()?.takeIf { it in 1..PointerClickCodec.MAX_SEQUENCE } ?: throw MessageException()
                if (reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return sequence
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(sequence: Long): ByteArray {
        if (sequence !in 1..PointerClickCodec.MAX_SEQUENCE) throw MessageException()
        return "[\"1\",\"keyboard.text.posted\",\"$sequence\"]".toByteArray(Charsets.UTF_8)
    }
}
