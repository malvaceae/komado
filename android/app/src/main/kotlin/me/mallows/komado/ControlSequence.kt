package me.mallows.komado

import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.KeyboardTextCodec
import me.mallows.komado.protocol.PointerClickCodec

/** Shared by all ordered input types within one media session. */
internal class ControlSequence(initial: Long = 0) {
    private var last = initial
    init { require(initial in 0..PointerClickCodec.MAX_SEQUENCE) }

    @Synchronized fun next(): Long? {
        if (last == PointerClickCodec.MAX_SEQUENCE) return null
        return ++last
    }
}

internal class CommittedTextMapper(private val sequence: ControlSequence) {
    fun message(text: String, captureRevision: Long?): KeyboardText? {
        val revision = captureRevision ?: return null
        // Validate before consuming a sequence. This also checks UTF-8 size.
        if (runCatching { KeyboardTextCodec.encode(KeyboardText(1, revision, text)) }.isFailure) return null
        return KeyboardText(sequence.next() ?: return null, revision, text)
    }
}
