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
    @Test fun mapsShortcutsSeparatelyFromText() {
        fun event(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
        assertNull(HardwareKeyMapper.map(event(KeyEvent.KEYCODE_A)))
        assertNull(HardwareKeyMapper.map(event(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.C, 5), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_C, KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.DIGIT9, 10), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_9, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.F12, 4), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_F12, KeyEvent.META_CTRL_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.TAB, 8), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.HOME, 0), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_MOVE_HOME)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.END, 4), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.PAGEUP, 0), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_PAGE_UP)))
        assertEquals(HardwareKeyMapper.Chord(SpecialKey.PAGEDOWN, 0), HardwareKeyMapper.map(event(KeyEvent.KEYCODE_PAGE_DOWN)))
        assertFalse(supportsSpecialKey(SpecialKey.HOME, setOf("keyboard.key")))
        assertTrue(supportsSpecialKey(SpecialKey.HOME, setOf("keyboard.key", "keyboard.navigation")))
        assertFalse(supportsSpecialKey(SpecialKey.HOME, setOf("keyboard.navigation")))
        assertTrue(supportsSpecialKey(SpecialKey.TAB, setOf("keyboard.key")))
    }

    @Test fun physicalKeysAreSentOnceAndCompositionStaysLocal() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val sent = mutableListOf<Pair<SpecialKey, Int>>()
            val text = mutableListOf<String>()
            val view = RemoteKeyboardView(instrument.targetContext, sendKey = { key, mask -> sent.add(key to mask); true }, sendText = { text.add(it); true })
            val input = view.onCreateInputConnection(EditorInfo())!!
            val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)
            val up = KeyEvent(0, 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, 0)
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_C, down))
            assertTrue(view.onKeyUp(KeyEvent.KEYCODE_C, up))
            assertEquals(listOf(SpecialKey.C to 4), sent)
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertTrue(view.onKeyUp(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
            assertEquals(listOf("a"), text)
            assertTrue(input.setComposingText("変換中", 1))
            assertFalse(view.onKeyDown(KeyEvent.KEYCODE_C, down))
            assertFalse(view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertFalse(input.sendKeyEvent(down))
            assertEquals(listOf("a"), text)
            assertEquals(1, sent.size)
            view.close()
            assertFalse(input.sendKeyEvent(down))
        }
    }
}
