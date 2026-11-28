package me.mallows.komado

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import me.mallows.komado.protocol.WindowInfo

internal object WindowPickerDialog {
    fun show(activity: Activity, windows: List<WindowInfo>, onChoose: (WindowInfo) -> Unit, onCancel: () -> Unit,
             onActions: ((WindowInfo) -> Unit)?, onRefresh: () -> Unit): AlertDialog {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val search = EditText(activity).apply {
            setSingleLine()
            setHint(R.string.window_search)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val empty = TextView(activity).apply {
            setText(R.string.window_search_empty)
            visibility = View.GONE
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(TextView(activity).apply {
                setText(R.string.select_window)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                setPadding(0, padding, 0, 0)
            })
            addView(search)
            if (onActions != null) addView(TextView(activity).apply { setText(R.string.window_action_hint) })
            addView(empty)
        }
        var visible = windows
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        fun update(query: String) {
            val term = query.trim()
            visible = windows.filter { it.application.contains(term, ignoreCase = true) || it.title.contains(term, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(visible.map {
                val label = activity.getString(R.string.window_option, it.application, it.title, it.width, it.height)
                if (it.onScreen) label else "$label ${activity.getString(R.string.window_offscreen)}"
            })
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        update("")
        return AlertDialog.Builder(activity).setCustomTitle(header)
            .setAdapter(adapter) { _, index -> onChoose(visible[index]) }
            .setNeutralButton(R.string.refresh) { _, _ -> onRefresh() }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create().also { dialog ->
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                dialog.show()
                if (onActions != null) dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                    onActions(visible[position])
                    true
                }
                // AlertDialog does not detect an editor inside its custom title.
                dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
    }
}
