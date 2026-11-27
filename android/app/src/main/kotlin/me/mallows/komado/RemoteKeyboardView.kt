package me.mallows.komado

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import me.mallows.komado.protocol.SpecialKey

/** A transient IME target; composed text stays local until committed. */
internal class RemoteKeyboardView(context: Context, private val sendKey: (SpecialKey, Int) -> Boolean,
                                  private val sendText: (String) -> Boolean) : View(context) {
    private var current: CommittedTextConnection? = null
    private var closed = false
    private val handledKeys = mutableSetOf<Int>()
    private val rawText = RawTextKeyHandler(sendText)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }

    override fun onCheckIsTextEditor() = !closed

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { handledKeys.clear(); rawText.reset() }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (closed) return null
        rawText.reset()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (closed || current?.hasComposition() == true) { rawText.reset(); return super.onKeyDown(keyCode, event) }
        val chord = HardwareKeyMapper.map(event)
        if (chord == null) return rawText.down(event) || super.onKeyDown(keyCode, event)
        rawText.reset()
        if (!sendKey(chord.key, chord.modifiers)) return false
        handledKeys.add(keyCode)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handledKeys.remove(keyCode)) return true
        if (rawText.up(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    fun close() {
        if (closed) return
        closed = true
        handledKeys.clear()
        rawText.reset()
        current?.closeConnection()
        current = null
        val input = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }
}
