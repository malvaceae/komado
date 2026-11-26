package me.mallows.komado

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class Host(val id: String, val name: String, val address: String)

/** Local address book only. An entry is not an authenticated or paired host. */
class MainActivity : Activity() {
    private val preferences by lazy { getSharedPreferences("hosts", MODE_PRIVATE) }
    private val hosts = mutableListOf<Host>()
    private var readable = true
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadHosts()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        val title = TextView(this).apply { setText(R.string.hosts); textSize = 26f }
        root.addView(title)
        root.addView(TextView(this).apply {
            setText(R.string.connection_unavailable)
            setPadding(0, dp(12), 0, dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(Button(this).apply {
            setText(R.string.add_host)
            isEnabled = readable
            setOnClickListener { editHost(null) }
        })
        setContentView(root)
        refresh()
    }

    private fun loadHosts() {
        try {
            val stored = preferences.getString("entries", null) ?: return
            val array = JSONArray(stored)
            val ids = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val host = Host(item.getString("id"), item.getString("name"), item.getString("address"))
                require(UUID.fromString(host.id).toString() == host.id && ids.add(host.id))
                require(validName(host.name) && validAddress(host.address))
                hosts.add(host)
            }
        } catch (_: Exception) {
            hosts.clear()
            readable = false
        }
    }

    private fun saveHosts(updated: List<Host>): Boolean {
        val array = JSONArray()
        updated.forEach { host -> array.put(JSONObject().put("id", host.id).put("name", host.name).put("address", host.address)) }
        if (!preferences.edit().putString("entries", array.toString()).commit()) {
            Toast.makeText(this, R.string.save_error, Toast.LENGTH_LONG).show()
            return false
        }
        hosts.clear()
        hosts.addAll(updated)
        refresh()
        return true
    }

    private fun refresh() {
        list.removeAllViews()
        if (!readable || hosts.isEmpty()) {
            list.addView(TextView(this).apply {
                setText(if (readable) R.string.empty_hosts else R.string.storage_error)
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        hosts.forEach { host ->
            list.addView(Button(this).apply {
                text = getString(R.string.host_description, host.name, host.address, getString(R.string.not_paired))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                setOnClickListener { editHost(host) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun editHost(existing: Host?) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val name = EditText(this).apply {
            setHint(R.string.host_name)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.name.orEmpty())
        }
        val address = EditText(this).apply {
            setHint(R.string.host_address)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.address.orEmpty())
        }
        fields.addView(name)
        fields.addView(address)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_host else R.string.edit_host)
            .setView(fields)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .apply { if (existing != null) setNeutralButton(R.string.delete, null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hostName = name.text.toString().trim()
                val hostAddress = address.text.toString().trim()
                when {
                    !validName(hostName) -> name.setError(getString(R.string.invalid_name))
                    !validAddress(hostAddress) -> address.setError(getString(R.string.invalid_address))
                    else -> {
                        val host = Host(existing?.id ?: UUID.randomUUID().toString(), hostName, hostAddress)
                        val updated = if (existing == null) hosts + host else hosts.map { if (it.id == host.id) host else it }
                        if (saveHosts(updated)) dialog.dismiss()
                    }
                }
            }
            if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_host_title)
                    .setMessage(existing.name)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (saveHosts(hosts.filterNot { it.id == existing.id })) dialog.dismiss()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun validName(value: String) = value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }
    private fun validAddress(value: String) = value.isNotBlank() && value.length <= 253 && value.none { it.isWhitespace() || it.isISOControl() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
