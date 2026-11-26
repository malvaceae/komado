package me.mallows.komado

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection

/** Keeps composition local and forwards only text that an IME commits. */
internal class CommittedTextConnection(view: View, private val send: (String) -> Boolean) : BaseInputConnection(view, true) {
    private val content = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
    private var closed = false

    override fun getEditable(): Editable = content

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > 8192 || !validLocalText(text.toString())) return false
        return super.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (closed || text == null || text.length > 8192) return false
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

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = false
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false

    override fun closeConnection() {
        closed = true
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
