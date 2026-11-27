package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Coordinates are millionths of the current captured image. */
data class PointerClick(val sequence: Long, val captureRevision: Long, val x: Int, val y: Int, val button: Button, val count: Int = 1) {
    enum class Button(val wireValue: String) { LEFT("left"), RIGHT("right"), MIDDLE("middle") }
}

object PointerClickCodec {
    const val MAX_SEQUENCE = 9_007_199_254_740_991L
    class MessageException : Exception("Invalid pointer message")

    fun decode(data: ByteArray): PointerClick {
        if (data.size !in 1..1024) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                if (reader.peek() != JsonToken.STRING || reader.nextString() != "1" ||
                    reader.peek() != JsonToken.STRING || reader.nextString() != "pointer.click") throw MessageException()
                fun integer(max: Long): Long {
                    if (reader.peek() != JsonToken.STRING) throw MessageException()
                    val value = reader.nextString()
                    if (value.isEmpty() || value.length > 16 || value.any { it !in '0'..'9' } ||
                        (value.length > 1 && value.startsWith('0'))) throw MessageException()
                    return value.toLongOrNull()?.takeIf { it <= max } ?: throw MessageException()
                }
                val sequence = integer(MAX_SEQUENCE)
                val revision = integer(MAX_SEQUENCE)
                val x = integer(1_000_000).toInt()
                val y = integer(1_000_000).toInt()
                if (sequence == 0L || revision == 0L || reader.peek() != JsonToken.STRING) throw MessageException()
                val rawButton = reader.nextString()
                val button = PointerClick.Button.entries.firstOrNull { it.wireValue == rawButton } ?: throw MessageException()
                val count = if (reader.hasNext()) {
                    if (reader.peek() != JsonToken.STRING || reader.nextString() != "2" || button != PointerClick.Button.LEFT) throw MessageException()
                    2
                } else 1
                if (reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return PointerClick(sequence, revision, x, y, button, count)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(click: PointerClick): ByteArray {
        if (click.sequence !in 1..MAX_SEQUENCE || click.captureRevision !in 1..MAX_SEQUENCE ||
            click.x !in 0..1_000_000 || click.y !in 0..1_000_000 ||
            !(click.count == 1 || (click.count == 2 && click.button == PointerClick.Button.LEFT))) throw MessageException()
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("pointer.click").value(click.sequence.toString()).value(click.captureRevision.toString())
                .value(click.x.toString()).value(click.y.toString()).value(click.button.wireValue)
            if (click.count == 2) writer.value("2")
            writer.endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8)
    }
}
