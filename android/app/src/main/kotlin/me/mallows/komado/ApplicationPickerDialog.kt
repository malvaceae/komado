package me.mallows.komado

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.mallows.komado.protocol.ApplicationInfo

internal class ApplicationPickerDialog(
    private val activity: Activity,
    applications: List<ApplicationInfo>,
    private val onChoose: (ApplicationInfo) -> Unit,
    private val onCancel: () -> Unit,
    private val onIconRequested: (ApplicationInfo) -> Unit,
) {
    private val icons = mutableMapOf<Long, Bitmap>()
    private val requestedIcons = mutableSetOf<Long>()
    private var visible = applications
    private val adapter = object : ArrayAdapter<ApplicationInfo>(activity, android.R.layout.activity_list_item) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: activity.layoutInflater.inflate(android.R.layout.activity_list_item, parent, false)
            val application = getItem(position)!!
            row.findViewById<TextView>(android.R.id.text1).text = application.name
            val icon = row.findViewById<ImageView>(android.R.id.icon)
            icons[application.id]?.let { icon.setImageBitmap(it) } ?: icon.setImageResource(android.R.drawable.sym_def_app_icon)
            if (!icons.containsKey(application.id) && requestedIcons.add(application.id)) onIconRequested(application)
            icon.contentDescription = null
            return row
        }
    }

    val dialog: AlertDialog

    init {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val search = EditText(activity).apply {
            setSingleLine()
            setHint(R.string.application_search)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val empty = TextView(activity).apply { setText(R.string.application_search_empty); visibility = View.GONE }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(TextView(activity).apply {
                setText(R.string.application_picker_title)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                setPadding(0, padding, 0, 0)
            })
            addView(search)
            addView(empty)
        }
        fun update(query: String) {
            val term = query.trim()
            visible = applications.filter { it.name.contains(term, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(visible)
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        update("")
        dialog = AlertDialog.Builder(activity).setCustomTitle(header)
            .setAdapter(adapter) { _, index -> onChoose(visible[index]) }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }.create()
    }

    fun show() {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    fun updateIcon(id: Long, bitmap: Bitmap) {
        icons[id] = bitmap
        adapter.notifyDataSetChanged()
    }
}
