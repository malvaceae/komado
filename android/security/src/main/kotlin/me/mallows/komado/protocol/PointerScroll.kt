package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PointerScroll(val sequence: Long, val captureRevision: Long, val x: Int, val y: Int,
                         val horizontal: Int, val vertical: Int)

object PointerScrollCodec {
    class MessageException : Exception("Invalid scroll message")

    fun decode(data: ByteArray): PointerScroll {
        if (data.size !in 1..1024) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                fun string(): String { if (reader.peek() != JsonToken.STRING) throw MessageException(); return reader.nextString() }
                fun unsigned(max: Long): Long {
                    val value = string()
                    if (value.isEmpty() || value.length > 16 || value.any { it !in '0'..'9' } ||
                        (value.length > 1 && value.startsWith('0'))) throw MessageException()
                    return value.toLongOrNull()?.takeIf { it <= max } ?: throw MessageException()
                }
                fun delta(): Int {
                    val value = string()
                    val number = value.toIntOrNull() ?: throw MessageException()
                    if (number !in -120..120 || number.toString() != value) throw MessageException()
                    return number
                }
                if (string() != "1" || string() != "pointer.scroll") throw MessageException()
                val sequence = unsigned(PointerClickCodec.MAX_SEQUENCE)
                val revision = unsigned(PointerClickCodec.MAX_SEQUENCE)
                val x = unsigned(1_000_000).toInt(); val y = unsigned(1_000_000).toInt()
                val horizontal = delta(); val vertical = delta()
                if (sequence == 0L || revision == 0L || (horizontal == 0 && vertical == 0) || reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return PointerScroll(sequence, revision, x, y, horizontal, vertical)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(scroll: PointerScroll): ByteArray {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("pointer.scroll").value(scroll.sequence.toString()).value(scroll.captureRevision.toString())
                .value(scroll.x.toString()).value(scroll.y.toString()).value(scroll.horizontal.toString()).value(scroll.vertical.toString()).endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (decode(it) != scroll) throw MessageException() }
    }
}
