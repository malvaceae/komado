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
    private val navigationControls = mutableListOf<android.view.View>()
    private val modifiers = mutableListOf<Pair<ToggleButton, Int>>()
    private var picker: AlertDialog? = null
    private var available = false
    private var navigationAvailable = false

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
            navigationControls.add(button)
            addControl(button)
        }
        setAvailable(false)
    }

    fun setAvailable(value: Boolean, navigation: Boolean = false) {
        available = value
        navigationAvailable = value && navigation
        controls.forEach { it.isEnabled = value }
        navigationControls.forEach { it.isEnabled = navigationAvailable }
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
        if (!available || (key.isNavigation && !navigationAvailable)) return
        val mask = modifiers.fold(0) { result, (button, value) -> if (button.isChecked) result or value else result }
        send(key, mask)
    }

    internal fun showShortcutKeys(): AlertDialog? {
        if (!available || picker != null) return null
        val names = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.shortcut_key).setItems(names.toTypedArray()) { _, index ->
            val name = names[index]
            dispatch(SpecialKey.valueOf(if (name[0].isDigit()) "DIGIT$name" else name))
        }.setNegativeButton(R.string.cancel, null).setOnDismissListener { picker = null }.show()
        picker = dialog
        return dialog
    }
}
