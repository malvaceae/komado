package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PointerMove(val sequence: Long, val captureRevision: Long, val x: Int, val y: Int)

object PointerMoveCodec {
    class MessageException : Exception("Invalid pointer move")
    fun decode(data: ByteArray): PointerMove {
        if (data.size !in 1..1024) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false; reader.beginArray()
                fun string(): String { if (reader.peek() != JsonToken.STRING) throw MessageException(); return reader.nextString() }
                fun integer(max: Long): Long {
                    val value = string()
                    if (value.isEmpty() || value.length > 16 || value.any { it !in '0'..'9' } ||
                        (value.length > 1 && value.startsWith('0'))) throw MessageException()
                    return value.toLongOrNull()?.takeIf { it <= max } ?: throw MessageException()
                }
                if (string() != "1" || string() != "pointer.move") throw MessageException()
                val sequence = integer(PointerClickCodec.MAX_SEQUENCE); val revision = integer(PointerClickCodec.MAX_SEQUENCE)
                val x = integer(1_000_000).toInt(); val y = integer(1_000_000).toInt()
                if (sequence == 0L || revision == 0L || reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return PointerMove(sequence, revision, x, y)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(move: PointerMove): ByteArray {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("pointer.move").value(move.sequence.toString()).value(move.captureRevision.toString())
                .value(move.x.toString()).value(move.y.toString()).endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (decode(it) != move) throw MessageException() }
    }
}
