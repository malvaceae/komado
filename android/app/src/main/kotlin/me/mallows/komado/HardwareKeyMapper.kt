package me.mallows.komado

import android.view.KeyEvent
import me.mallows.komado.protocol.SpecialKey

internal val SpecialKey.isNavigation: Boolean
    get() = this == SpecialKey.HOME || this == SpecialKey.END || this == SpecialKey.PAGEUP || this == SpecialKey.PAGEDOWN
internal val SpecialKey.isPunctuation: Boolean
    get() = this == SpecialKey.COMMA || this == SpecialKey.PERIOD || this == SpecialKey.SLASH

internal fun supportsSpecialKey(key: SpecialKey, features: Set<String>): Boolean =
    "keyboard.key" in features && (!key.isNavigation || "keyboard.navigation" in features) &&
        (!key.isPunctuation || "keyboard.punctuation" in features)

/** Text without shortcut modifiers remains the IME's responsibility. */
internal object HardwareKeyMapper {
    data class Chord(val key: SpecialKey, val modifiers: Int)

    fun map(event: KeyEvent): Chord? {
        val modifiers = (if (event.isMetaPressed) 1 else 0) or (if (event.isAltPressed) 2 else 0) or
            (if (event.isCtrlPressed) 4 else 0) or (if (event.isShiftPressed) 8 else 0)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> SpecialKey.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> SpecialKey.DELETE
            KeyEvent.KEYCODE_ENTER -> SpecialKey.ENTER
            KeyEvent.KEYCODE_TAB -> SpecialKey.TAB
            KeyEvent.KEYCODE_ESCAPE -> SpecialKey.ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> SpecialKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> SpecialKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> SpecialKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> SpecialKey.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> SpecialKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> SpecialKey.END
            KeyEvent.KEYCODE_PAGE_UP -> SpecialKey.PAGEUP
            KeyEvent.KEYCODE_PAGE_DOWN -> SpecialKey.PAGEDOWN
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH -> {
                if (modifiers and 7 == 0) return null
                when (event.keyCode) {
                    KeyEvent.KEYCODE_COMMA -> SpecialKey.COMMA
                    KeyEvent.KEYCODE_PERIOD -> SpecialKey.PERIOD
                    else -> SpecialKey.SLASH
                }
            }
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> SpecialKey.valueOf("F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}")
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                if (modifiers and 7 == 0) return null
                SpecialKey.valueOf(('A' + event.keyCode - KeyEvent.KEYCODE_A).toString())
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (modifiers and 7 == 0) return null
                SpecialKey.valueOf("DIGIT${event.keyCode - KeyEvent.KEYCODE_0}")
            }
            else -> return null
        }
        return Chord(key, modifiers)
    }
}
