package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class KeyboardText(val sequence: Long, val captureRevision: Long, val text: String)

object KeyboardTextCodec {
    const val MAX_TEXT_BYTES = 512
    class MessageException : Exception("Invalid keyboard message")
    fun decode(data: ByteArray): KeyboardText {
        if (data.size !in 1..2048) throw MessageException()
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                fun string(): String {
                    if (reader.peek() != JsonToken.STRING) throw MessageException()
                    return reader.nextString()
                }
                if (string() != "1" || string() != "keyboard.text") throw MessageException()
                val sequence = positiveInteger(string())
                val revision = positiveInteger(string())
                val value = string()
                if (!validText(value) || reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return KeyboardText(sequence, revision, value)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(message: KeyboardText): ByteArray {
        if (message.sequence !in 1..PointerClickCodec.MAX_SEQUENCE || message.captureRevision !in 1..PointerClickCodec.MAX_SEQUENCE ||
            !validText(message.text)) throw MessageException()
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("keyboard.text").value(message.sequence.toString())
                .value(message.captureRevision.toString()).value(message.text).endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (it.size > 2048) throw MessageException() }
    }

    private fun validText(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size in 1..MAX_TEXT_BYTES && String(bytes, Charsets.UTF_8) == value &&
            value.none { it.isISOControl() && it != '\t' && it != '\n' }
    }

    private fun positiveInteger(value: String): Long {
        if (value.isEmpty() || value.length > 16 || value.startsWith('0') || value.any { it !in '0'..'9' }) throw MessageException()
        return value.toLongOrNull()?.takeIf { it in 1..PointerClickCodec.MAX_SEQUENCE } ?: throw MessageException()
    }
}
