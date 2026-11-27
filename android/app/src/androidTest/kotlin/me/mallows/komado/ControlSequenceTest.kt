package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.PointerClickCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlSequenceTest {
    @Test fun sharesOrderBetweenClicksAndCommittedText() {
        val sequence = ControlSequence()
        val tap = TapClickMapper(sequence)
        val text = CommittedTextMapper(sequence)
        assertNull(text.messages("", 1))
        assertNull(text.messages("日本語", null))
        assertEquals(1L, tap.click(5.0, 5.0, 10, 10, 10, 10, 0, 1)?.sequence)
        assertEquals(listOf(KeyboardText(2, 1, "日本語")), text.messages("日本語", 1))
        assertEquals(3L, tap.click(5.0, 5.0, 10, 10, 10, 10, 0, 1)?.sequence)
    }

    @Test fun splitsLongTextAtCharacterBoundariesAndKeepsSequenceOrder() {
        val sequence = ControlSequence()
        val mapper = CommittedTextMapper(sequence)
        val text = "あ".repeat(170) + "👩‍👩‍👧‍👦" + "e\u0301".repeat(200) + "日本語"
        val messages = requireNotNull(mapper.messages(text, 7))
        assertEquals(text, messages.joinToString("") { it.text })
        assertEquals((1L..messages.size.toLong()).toList(), messages.map { it.sequence })
        assertEquals(listOf(7L), messages.map { it.captureRevision }.distinct())
        assertEquals(1, messages.count { it.text.contains("👩‍👩‍👧‍👦") })
        assertTrue(messages.all { it.text.toByteArray(Charsets.UTF_8).size in 1..512 })
        assertEquals(messages.size.toLong() + 1, sequence.next())
    }

    @Test fun stopsSendingAndReportsDeliveryFailure() {
        val mapper = CommittedTextMapper(ControlSequence())
        val attempted = mutableListOf<KeyboardText>()
        var failures = 0
        assertFalse(mapper.send("a".repeat(1400), 1, { attempted.add(it); attempted.size != 2 }, { failures++ }))
        assertEquals(2, attempted.size)
        assertEquals(listOf(1L, 2L), attempted.map { it.sequence })
        assertEquals(1, failures)
        assertFalse(mapper.send("bad\u0001", 1, { attempted.add(it); true }, { failures++ }))
        assertEquals(2, attempted.size)
        assertEquals(1, failures)
        assertFalse(mapper.send("ok", 1, { throw IllegalStateException("send failed") }, { failures++ }))
        assertEquals(2, failures)
    }

    @Test fun rejectsWholeBatchBeforeUsingSequenceNumbers() {
        val sequence = ControlSequence()
        val mapper = CommittedTextMapper(sequence)
        assertNull(mapper.messages("a".repeat(512) + "\u0001", 1))
        assertNull(mapper.messages("a".repeat(8193), 1))
        assertNull(mapper.messages("a" + "\u0301".repeat(300), 1))
        assertNull(mapper.messages("\ud800", 1))
        assertEquals(1L, sequence.next())

        val nearEnd = ControlSequence(PointerClickCodec.MAX_SEQUENCE - 1)
        assertNull(CommittedTextMapper(nearEnd).messages("a".repeat(513), 1))
        assertEquals(PointerClickCodec.MAX_SEQUENCE, nearEnd.next())
    }

    @Test fun acceptsMaximumLengthText() {
        val sequence = ControlSequence()
        val messages = requireNotNull(CommittedTextMapper(sequence).messages("x".repeat(8192), 1))
        assertEquals(16, messages.size)
        assertEquals("x".repeat(8192), messages.joinToString("") { it.text })
        assertEquals(17L, sequence.next())
    }

    @Test fun refusesSequenceOverflow() {
        val sequence = ControlSequence(PointerClickCodec.MAX_SEQUENCE - 1)
        assertEquals(PointerClickCodec.MAX_SEQUENCE, sequence.next())
        assertNull(sequence.next())
    }
}
