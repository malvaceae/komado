package me.mallows.komado.protocol

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class SpecialKey {
    BACKSPACE, DELETE, ENTER, TAB, ESCAPE, LEFT, RIGHT, UP, DOWN,
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT0, DIGIT1, DIGIT2, DIGIT3, DIGIT4, DIGIT5, DIGIT6, DIGIT7, DIGIT8, DIGIT9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12
}

/** Modifier bits: Command 1, Option 2, Control 4, Shift 8. */
data class KeyboardKey(val sequence: Long, val captureRevision: Long, val key: SpecialKey, val modifiers: Int)

object KeyboardKeyCodec {
    class MessageException : Exception("Invalid special key message")

    fun decode(data: ByteArray): KeyboardKey {
        if (data.size !in 1..1024) throw MessageException()
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
                if (string() != "1" || string() != "keyboard.key") throw MessageException()
                val sequence = integer(string(), PointerClickCodec.MAX_SEQUENCE)
                val revision = integer(string(), PointerClickCodec.MAX_SEQUENCE)
                val rawKey = string()
                val key = SpecialKey.entries.firstOrNull { it.name.lowercase() == rawKey } ?: throw MessageException()
                val modifiers = integer(string(), 15).toInt()
                if (sequence == 0L || revision == 0L || reader.hasNext()) throw MessageException()
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw MessageException()
                return KeyboardKey(sequence, revision, key, modifiers)
            }
        } catch (_: Exception) { throw MessageException() }
    }

    fun encode(message: KeyboardKey): ByteArray {
        if (message.sequence !in 1..PointerClickCodec.MAX_SEQUENCE || message.captureRevision !in 1..PointerClickCodec.MAX_SEQUENCE ||
            message.modifiers !in 0..15) throw MessageException()
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginArray().value("1").value("keyboard.key").value(message.sequence.toString())
                .value(message.captureRevision.toString()).value(message.key.name.lowercase()).value(message.modifiers.toString()).endArray()
        }
        return output.toString().toByteArray(Charsets.UTF_8).also { if (it.size > 1024) throw MessageException() }
    }

    private fun integer(value: String, maximum: Long): Long {
        if (value.isEmpty() || value.length > 16 || (value.length > 1 && value.startsWith('0')) ||
            value.any { it !in '0'..'9' }) throw MessageException()
        return value.toLongOrNull()?.takeIf { it in 0..maximum } ?: throw MessageException()
    }
}
