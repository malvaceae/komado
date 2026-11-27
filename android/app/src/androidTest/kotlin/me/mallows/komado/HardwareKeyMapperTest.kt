package me.mallows.komado

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SpecialKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareKeyMapperTest {
    @Test fun mapsShortcutsButLeavesNormalTextToIme() {
        fun event(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
        assertNull(HardwareKeyMapper.map(event(KeyEvent.KEYCODE_A)))
        assertNull(HardwareKeyMapper.map(event(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.C, 5), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_C, KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.DIGIT9, 10), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_9, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.F12, 4), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_F12, KeyEvent.META_CTRL_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.TAB, 8), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)))
    }

    @Test fun physicalKeysAreSentOnceAndCompositionStaysLocal() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val sent = mutableListOf<Pair<SpecialKey, Int>>()
            val view = RemoteKeyboardView(instrument.targetContext, sendKey = { key, mask -> sent.add(key to mask); true }, sendText = { true })
            val input = view.onCreateInputConnection(EditorInfo())!!
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)
            val up = KeyEvent(0, 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, 0)
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, down))
            assertTrue(view.onKeyUp(KeyEvent.KEYCODE_C, up))
            assertEquals(listOf(SpecialKey.C to 4), sent)
            assertFalse(view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertTrue(input.setComposingText("変換中", 1))
            assertFalse(view.onKeyDown(KeyEvent.KEYCODE_C, down))
            assertFalse(input.sendKeyEvent(down))
            assertEquals(1, sent.size)
            view.close()
            assertFalse(input.sendKeyEvent(down))
        }
    }
}
