package me.mallows.komado

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import me.mallows.komado.protocol.SpecialKey

/** Keeps composition local and forwards only text that an IME commits. */
internal class CommittedTextConnection(view: View, private val sendKey: (SpecialKey, Int) -> Boolean = { _, _ -> false },
                                       private val send: (String) -> Boolean) : BaseInputConnection(view, true) {
    private val content = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
    private var closed = false
    private val rawText = RawTextKeyHandler(send)

    override fun getEditable(): Editable = content

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > 8192 || !validLocalText(text.toString())) return false
        rawText.reset()
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > 8192) return false
        rawText.reset()
        val value = text.toString()
        if (!validLocalText(value)) return false
        if (value.isNotEmpty() && !send(value)) return false
        if (!super.commitText(text, newCursorPosition)) return false
        clearLocalText()
        return true
    }

    override fun finishComposingText(): Boolean {
        if (closed) return false
        val start = getComposingSpanStart(content)
        val end = getComposingSpanEnd(content)
        if (start >= 0 && end > start && !send(content.subSequence(start, end).toString())) return false
        if (!super.finishComposingText()) return false
        clearLocalText()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (closed || beforeLength < 0 || afterLength < 0) return false
        if (getComposingSpanStart(content) >= 0) return super.deleteSurroundingText(beforeLength, afterLength)
        return when {
            beforeLength == 1 && afterLength == 0 -> sendKey(SpecialKey.BACKSPACE, 0)
            beforeLength == 0 && afterLength == 1 -> sendKey(SpecialKey.DELETE, 0)
            else -> false
        }
    }
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (closed || beforeLength < 0 || afterLength < 0) return false
        if (getComposingSpanStart(content) >= 0) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        return deleteSurroundingText(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (closed) return false
        if (getComposingSpanStart(content) >= 0) return false
        val chord = HardwareKeyMapper.map(event)
        if (chord == null) return when (event.action) {
            KeyEvent.ACTION_DOWN -> rawText.down(event)
            KeyEvent.ACTION_UP -> rawText.up(event)
            else -> false
        }
        rawText.reset()
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> sendKey(chord.key, chord.modifiers)
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    fun hasComposition(): Boolean = getComposingSpanStart(content) >= 0

    override fun closeConnection() {
        closed = true
        rawText.reset()
        clearLocalText()
        super.closeConnection()
    }

    private fun clearLocalText() {
        removeComposingSpans(content)
        content.clear()
        Selection.setSelection(content, 0)
    }

    private fun validLocalText(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return bytes.size <= 8192 && String(bytes, Charsets.UTF_8) == value
    }
}
