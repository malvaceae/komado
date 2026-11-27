package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class PointerDrag(val sequence: Long, val captureRevision: Long, val x: Int, val y: Int, val phase: Phase) {
    enum class Phase { BEGIN, MOVE, END }
}

object PointerDragCodec {
    class MessageException : Exception("Invalid drag message")
    fun decode(data: ByteArray): PointerDrag {
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
                if (string() != "1" || string() != "pointer.drag") throw MessageException()
                val sequence = integer(PointerClickCodec.MAX_SEQUENCE); val revision = integer(PointerClickCodec.MAX_SEQUENCE)
                val x = integer(1_000_000).toInt(); val y = integer(1_000_000).toInt()
                val phase = when (string()) { "begin" -> PointerDrag.Phase.BEGIN; "move" -> PointerDrag.Phase.MOVE; "end" -> PointerDrag.Phase.END; else -> throw MessageException() }
                if (sequence == 0L || revision == 0L || reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return PointerDrag(sequence, revision, x, y, phase)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(drag: PointerDrag): ByteArray {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("pointer.drag").value(drag.sequence.toString()).value(drag.captureRevision.toString())
                .value(drag.x.toString()).value(drag.y.toString()).value(drag.phase.name.lowercase()).endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (decode(it) != drag) throw MessageException() }
    }
}
