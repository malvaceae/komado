package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.PointerClickCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlSequenceTest {
    @Test fun sharesOrderBetweenClicksAndCommittedText() {
        val sequence = ControlSequence()
        val tap = TapClickMapper(sequence)
        val text = CommittedTextMapper(sequence)
        assertNull(text.message("", 1))
        assertNull(text.message("日本語", null))
        assertEquals(1L, tap.click(5.0, 5.0, 10, 10, 10, 10, 0, 1)?.sequence)
        assertEquals(KeyboardText(2, 1, "日本語"), text.message("日本語", 1))
        assertEquals(3L, tap.click(5.0, 5.0, 10, 10, 10, 10, 0, 1)?.sequence)
    }

    @Test fun refusesSequenceOverflow() {
        val sequence = ControlSequence(PointerClickCodec.MAX_SEQUENCE - 1)
        assertEquals(PointerClickCodec.MAX_SEQUENCE, sequence.next())
        assertNull(sequence.next())
    }
}
