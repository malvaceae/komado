package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object CaptureReadyCodec {
    class MessageException : Exception("Invalid capture message")

    fun decode(data: ByteArray): Long {
        if (data.size !in 1..256) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                if (reader.peek() != JsonToken.STRING || reader.nextString() != "1" ||
                    reader.peek() != JsonToken.STRING || reader.nextString() != "capture.ready" ||
                    reader.peek() != JsonToken.STRING) throw MessageException()
                val raw = reader.nextString()
                if (raw.isEmpty() || raw.length > 16 || raw.startsWith('0') || raw.any { it !in '0'..'9' }) throw MessageException()
                val revision = raw.toLongOrNull()?.takeIf { it in 1..PointerClickCodec.MAX_SEQUENCE } ?: throw MessageException()
                if (reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return revision
            }
        } catch (_: Exception) { throw MessageException() }
    }
}
