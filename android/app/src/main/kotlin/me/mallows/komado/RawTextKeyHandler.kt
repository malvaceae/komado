package me.mallows.komado

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** Handles printable key events that an IME passes through instead of committing text. */
internal class RawTextKeyHandler(private val send: (String) -> Boolean) {
    private var accent = 0
    private val handled = mutableSetOf<Int>()

    fun down(event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
        val value = event.unicodeChar
        if (value == 0) return false
        if (value and KeyCharacterMap.COMBINING_ACCENT != 0) {
            accent = value and KeyCharacterMap.COMBINING_ACCENT_MASK
            handled.add(event.keyCode)
            return true
        }
        if (!Character.isValidCodePoint(value) || Character.isISOControl(value) || value in 0xD800..0xDFFF) return false
        val combined = if (accent == 0) 0 else KeyCharacterMap.getDeadChar(accent, value)
        val text = when {
            accent == 0 -> String(Character.toChars(value))
            combined != 0 -> String(Character.toChars(combined))
            else -> String(Character.toChars(accent)) + String(Character.toChars(value))
        }
        if (!send(text)) return false
        accent = 0
        handled.add(event.keyCode)
        return true
    }

    fun up(event: KeyEvent): Boolean = handled.remove(event.keyCode)
    fun reset() { accent = 0; handled.clear() }
}
