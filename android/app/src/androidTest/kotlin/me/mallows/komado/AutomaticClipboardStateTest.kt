package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticClipboardStateTest {
    @Test fun tracksOnlyNewAllowedChangesAndDoesNotClearNewerCopies() {
        val state = AutomaticClipboardState(100)
        assertFalse(state.dirty)
        state.changed(100, true) // Text classification can notify without a copy.
        assertFalse(state.dirty)
        state.changed(101, true)
        assertTrue(state.dirty)
        state.changed(102, true)
        state.delivered(101)
        assertTrue(state.dirty)
        state.delivered(102)
        assertFalse(state.dirty)
        state.changed(103, false) // A secret replaced the pending copy.
        assertFalse(state.dirty)
        state.changed(104, true)
        state.changed(null, false)
        assertFalse(state.dirty)
        assertFalse(state.matches(null))
        state.reset(105)
        assertFalse(state.dirty)
        assertTrue(state.matches(105))
    }
}
