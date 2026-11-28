package me.mallows.komado

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ToggleButton
import me.mallows.komado.protocol.SpecialKey

/** Modifiers apply to this bar's keys only; IME text remains independent. */
internal class SpecialKeyBar(context: Context, private val send: (SpecialKey, Int) -> Boolean) : HorizontalScrollView(context) {
    private val row = LinearLayout(context)
    private val controls = mutableListOf<android.view.View>()
    private val modifiers = mutableListOf<Pair<ToggleButton, Int>>()
    private var picker: AlertDialog? = null
    private var available = false

    init {
        isHorizontalScrollBarEnabled = true
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listOf(Triple("⌘", R.string.modifier_command, 1), Triple("⌥", R.string.modifier_option, 2),
            Triple("Ctrl", R.string.modifier_control, 4), Triple("Shift", R.string.modifier_shift, 8)).forEach { (label, description, mask) ->
            val button = ToggleButton(context).apply {
                textOn = label; textOff = label; text = label
                contentDescription = context.getString(description)
            }
            modifiers.add(button to mask)
            addControl(button)
        }
        addControl(Button(context).apply {
            setText(R.string.shortcut_key)
            setOnClickListener { showShortcutKeys() }
        })
        val keys = listOf("Esc" to SpecialKey.ESCAPE, "Tab" to SpecialKey.TAB,
            "Enter" to SpecialKey.ENTER, "Backspace" to SpecialKey.BACKSPACE, "Delete" to SpecialKey.DELETE,
            "←" to SpecialKey.LEFT, "↑" to SpecialKey.UP, "↓" to SpecialKey.DOWN, "→" to SpecialKey.RIGHT) +
            (1..12).map { "F$it" to SpecialKey.valueOf("F$it") }
        keys.forEach { (label, key) ->
            addControl(Button(context).apply {
                text = label
                setOnClickListener { dispatch(key) }
            })
        }
        listOf("Home" to SpecialKey.HOME, "End" to SpecialKey.END,
            "PgUp" to SpecialKey.PAGEUP, "PgDn" to SpecialKey.PAGEDOWN).forEach { (label, key) ->
            val button = Button(context).apply { text = label; setOnClickListener { dispatch(key) } }
            addControl(button)
        }
        setAvailable(false)
    }

    fun setAvailable(value: Boolean) {
        available = value
        controls.forEach { it.isEnabled = value }
        if (!value) {
            modifiers.forEach { it.first.isChecked = false }
            picker?.dismiss()
            picker = null
        }
    }

    private fun addControl(view: android.view.View) {
        controls.add(view)
        row.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dispatch(key: SpecialKey) {
        if (!available) return
        val mask = modifiers.fold(0) { result, (button, value) -> if (button.isChecked) result or value else result }
        send(key, mask)
    }

    internal fun showShortcutKeys(): AlertDialog? {
        if (!available || picker != null) return null
        val names = ('A'..'Z').map { it.toString() to SpecialKey.valueOf(it.toString()) } +
            ('0'..'9').map { it.toString() to SpecialKey.valueOf("DIGIT$it") } +
            listOf("," to SpecialKey.COMMA, "." to SpecialKey.PERIOD, "/" to SpecialKey.SLASH) +
            listOf("Space" to SpecialKey.SPACE, "-" to SpecialKey.MINUS, "=" to SpecialKey.EQUAL,
                "[" to SpecialKey.LEFTBRACKET, "]" to SpecialKey.RIGHTBRACKET, "\\" to SpecialKey.BACKSLASH,
                ";" to SpecialKey.SEMICOLON, "'" to SpecialKey.APOSTROPHE, "`" to SpecialKey.GRAVE)
        val dialog = AlertDialog.Builder(context).setTitle(R.string.shortcut_key).setItems(names.map { it.first }.toTypedArray()) { _, index ->
            dispatch(names[index].second)
        }.setNegativeButton(R.string.cancel, null).setOnDismissListener { picker = null }.show()
        picker = dialog
        return dialog
    }
}
