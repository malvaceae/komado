package me.mallows.komado

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import me.mallows.komado.protocol.SpecialKey

/** A transient IME target; composed text stays local until committed. */
internal class RemoteKeyboardView(context: Context, private val sendKey: (SpecialKey) -> Boolean,
                                  private val sendText: (String) -> Boolean) : View(context) {
    private var current: CommittedTextConnection? = null
    private var closed = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }

    override fun onCheckIsTextEditor() = !closed

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (closed) return null
        current?.closeConnection()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return CommittedTextConnection(this, sendKey, sendText).also { current = it }
    }

    fun showKeyboard() {
        if (closed || !requestFocus()) return
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        if (closed) return
        closed = true
        current?.closeConnection()
        current = null
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }
}
