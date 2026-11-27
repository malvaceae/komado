package me.mallows.komado

import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.KeyboardTextCodec
import me.mallows.komado.protocol.PointerClickCodec
import java.text.BreakIterator
import java.util.Locale

/** Shared by all ordered input types within one media session. */
internal class ControlSequence(initial: Long = 0) {
    private var last = initial
    init { require(initial in 0..PointerClickCodec.MAX_SEQUENCE) }

    @Synchronized fun reserve(count: Int): LongRange? {
        if (count <= 0 || count.toLong() > PointerClickCodec.MAX_SEQUENCE - last) return null
        val start = last + 1
        last += count
        return start..last
    }

    fun next(): Long? = reserve(1)?.first
}

internal class CommittedTextMapper(private val sequence: ControlSequence) {
    fun send(text: String, captureRevision: Long?, sendMessage: (KeyboardText) -> Boolean, onFailure: () -> Unit): Boolean {
        val batch = messages(text, captureRevision) ?: return false
        for (message in batch) {
            val delivered = try { sendMessage(message) } catch (_: RuntimeException) { false }
            if (!delivered) {
                onFailure()
                return false
            }
        }
        return true
    }

    fun messages(text: String, captureRevision: Long?): List<KeyboardText>? {
        val revision = captureRevision ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 8192 || String(bytes, Charsets.UTF_8) != text) return null
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val chunks = mutableListOf<String>()
        var start = boundaries.first()
        var end = start
        var size = 0
        while (true) {
            val next = boundaries.next()
            if (next == BreakIterator.DONE) break
            val length = text.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (length > KeyboardTextCodec.MAX_TEXT_BYTES) return null
            if (size + length > KeyboardTextCodec.MAX_TEXT_BYTES) {
                chunks.add(text.substring(start, end))
                start = end
                size = 0
            }
            size += length
            end = next
        }
        if (end > start) chunks.add(text.substring(start, end))
        if (chunks.isEmpty() || chunks.any { runCatching { KeyboardTextCodec.encode(KeyboardText(1, revision, it)) }.isFailure }) return null
        val numbers = sequence.reserve(chunks.size) ?: return null
        return chunks.mapIndexed { index, chunk -> KeyboardText(numbers.first + index, revision, chunk) }
    }
}
