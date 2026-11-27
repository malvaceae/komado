package me.mallows.komado

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.security.DeviceSigningKey
import java.util.UUID
import java.util.Date
import java.text.DateFormat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private data class ConnectionCheck(val address: String, val port: Int, val pairing: Pairing, val succeeded: Boolean, val checkedAt: Long)
    private val store by lazy { HostStore(applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private var hosts = emptyList<Host>()
    private val connectionChecks = mutableMapOf<String, ConnectionCheck>()
    private var readable = true
    private var busy = false
    private var pendingNetworkAction: (() -> Unit)? = null
    private lateinit var list: LinearLayout
    private lateinit var addButton: Button
    private lateinit var scanButton: Button
    private lateinit var status: TextView
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val invitation = ScannedPairing.take()
        if (result.resultCode == Activity.RESULT_OK) {
            if (invitation == null) toast(R.string.scan_expired)
            else withLocalNetworkPermission { confirmScannedPairing(invitation) }
        }
    }
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        if (!granted) toast(R.string.local_network_denied)
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        root.addView(TextView(this).apply { setText(R.string.hosts); textSize = 26f })
        status = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addButton = Button(this).apply {
            setText(R.string.add_host)
            setOnClickListener { editHost(null) }
        }
        root.addView(addButton)
        scanButton = Button(this).apply {
            setText(R.string.scan_pairing)
            setOnClickListener {
                ScannedPairing.clear()
                scanner.launch(Intent(this@MainActivity, PairingScannerActivity::class.java))
            }
        }
        root.addView(scanButton)
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    override fun onDestroy() {
        pendingNetworkAction = null
        executor.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        try {
            hosts = store.load()
            readable = true
        } catch (_: Exception) {
            hosts = emptyList()
            readable = false
        }
        connectionChecks.keys.retainAll(hosts.map { it.id }.toSet())
        status.setText(if (busy) R.string.connecting else R.string.connection_unavailable)
        addButton.isEnabled = readable && !busy
        scanButton.isEnabled = readable && !busy
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
                val destination = if (host.address.contains(':')) "[${host.address}]:${host.port}" else "${host.address}:${host.port}"
                val details = getString(R.string.host_description, host.name, destination,
                    getString(if (host.pairing == null) R.string.not_paired else R.string.paired))
                val last = host.lastConnectedAt?.let {
                    getString(R.string.last_connected, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
                } ?: getString(R.string.never_connected)
                val check = connectionChecks[host.id]?.takeIf {
                    it.address == host.address && it.port == host.port && it.pairing == host.pairing
                }?.let {
                    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.checkedAt))
                    getString(R.string.checked_connection, time, getString(if (it.succeeded) R.string.check_succeeded else R.string.check_failed))
                }
                text = listOfNotNull(details, last, check).joinToString("\n")
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                isEnabled = !busy
                setOnClickListener { hostActions(host) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hostActions(host: Host) {
        val actions = if (host.pairing == null) arrayOf(getString(R.string.pair), getString(R.string.edit_host), getString(R.string.delete))
        else arrayOf(getString(R.string.view_screen), getString(R.string.control_screen), getString(R.string.select_screen),
            getString(R.string.control_display), getString(R.string.select_window), getString(R.string.control_window), getString(R.string.check_connection), getString(R.string.edit_host), getString(R.string.delete))
        AlertDialog.Builder(this).setTitle(host.name).setItems(actions) { _, index ->
            if (host.pairing == null) when (index) {
                0 -> withLocalNetworkPermission { pairHost(host) }
                1 -> editHost(host)
                2 -> deleteHost(host)
            } else when (index) {
                0 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java).putExtra(VideoActivity.HOST_ID, host.id)) }
                1 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CONTROL_MODE, true)) }
                2 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_DISPLAY, true)) }
                3 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_DISPLAY, true)
                    .putExtra(VideoActivity.CONTROL_MODE, true)) }
                4 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_WINDOW, true)) }
                5 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_WINDOW, true)
                    .putExtra(VideoActivity.CONTROL_MODE, true)) }
                6 -> withLocalNetworkPermission { checkConnection(host) }
                7 -> editHost(host)
                8 -> deleteHost(host)
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun withLocalNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingNetworkAction = action
        localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun editHost(existing: Host?) {
        val fields = fields()
        val name = field(R.string.host_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, existing?.name)
        val address = field(R.string.host_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, existing?.address)
        val port = field(R.string.port, InputType.TYPE_CLASS_NUMBER, (existing?.port ?: 48365).toString())
        fields.addView(name)
        fields.addView(address)
        fields.addView(port)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_host else R.string.edit_host)
            .setView(fields).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hostName = name.text.toString().trim()
                val hostAddress = address.text.toString().trim()
                val hostPort = port.text.toString().toIntOrNull()
                when {
                    !HostStore.validName(hostName) -> name.error = getString(R.string.invalid_name)
                    !HostStore.validAddress(hostAddress) -> address.error = getString(R.string.invalid_address)
                    hostPort == null || hostPort !in 1..65535 -> port.error = getString(R.string.invalid_port)
                    else -> {
                        val host = Host(existing?.id ?: UUID.randomUUID().toString(), hostName, hostAddress, hostPort,
                            existing?.pairing, existing?.lastConnectedAt)
                        val saved = runCatching { store.save(existing, host) }.getOrDefault(false)
                        if (saved) dialog.dismiss() else toast(R.string.save_error)
                        refresh()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun deleteHost(host: Host) {
        AlertDialog.Builder(this).setTitle(R.string.delete_host_title)
            .setMessage(if (host.pairing == null) host.name else getString(R.string.delete_paired_message, host.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = runCatching { store.remove(host) }.getOrDefault(false)
                if (removed) {
                    host.pairing?.let {
                        if (runCatching { DeviceSigningKey(it.keyAlias).delete() }.isFailure) toast(R.string.key_delete_error)
                    }
                } else toast(R.string.save_error)
                refresh()
            }.show()
    }

    private fun pairHost(host: Host) {
        val fields = fields()
        fields.addView(TextView(this).apply { setText(R.string.pair_instructions); setPadding(0, 0, 0, dp(12)) })
        val fingerprint = field(R.string.fingerprint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val invitation = field(R.string.invitation, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        fields.addView(fingerprint)
        fields.addView(invitation)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.pair).setView(fields)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.pair, null).create()
        dialog.setOnDismissListener { invitation.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pinText = normalizeHex(fingerprint.text.toString())
                val tokenText = normalizeHex(invitation.text.toString())
                when {
                    pinText == null -> fingerprint.error = getString(R.string.invalid_fingerprint)
                    tokenText == null -> invitation.error = getString(R.string.invalid_invitation)
                    else -> {
                        dialog.dismiss()
                        startPairing(host, pinText, tokenText)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmScannedPairing(invitation: PairingInvitation) {
        val fields = fields()
        fields.addView(TextView(this).apply { setText(R.string.scan_confirm); setPadding(0, 0, 0, dp(12)) })
        val name = field(R.string.host_name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "Mac")
        val address = field(R.string.host_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, invitation.host)
        val port = field(R.string.port, InputType.TYPE_CLASS_NUMBER, invitation.port.toString())
        fields.addView(name)
        fields.addView(address)
        fields.addView(port)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.pair).setView(fields)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.pair, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hostName = name.text.toString().trim()
                val hostAddress = address.text.toString().trim()
                val hostPort = port.text.toString().toIntOrNull()
                when {
                    !HostStore.validName(hostName) -> name.error = getString(R.string.invalid_name)
                    !HostStore.validAddress(hostAddress) -> address.error = getString(R.string.invalid_address)
                    hostPort == null || hostPort !in 1..65535 -> port.error = getString(R.string.invalid_port)
                    else -> {
                        val host = Host(UUID.randomUUID().toString(), hostName, hostAddress, hostPort)
                        if (runCatching { store.save(null, host) }.getOrDefault(false)) {
                            dialog.dismiss()
                            startPairing(host, invitation.fingerprint, invitation.token)
                        } else toast(R.string.save_error)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun startPairing(host: Host, pinText: String, tokenText: String) {
        if (busy) return
        busy = true
        refresh()
        val repository = HostStore(applicationContext)
        executor.execute {
            val alias = "me.mallows.komado.pair.${UUID.randomUUID()}"
            val key = DeviceSigningKey(alias)
            val token = hexBytes(tokenText)
            var saved = false
            try {
                val model = android.os.Build.MODEL
                val deviceName = if (HostStore.validName(model)) model else "Android"
                val connection = AuthenticatedHostConnection.pair(host.address, host.port, hexBytes(pinText),
                    deviceName, token, key)
                val deviceID = connection.deviceID.toString()
                connection.close()
                val pairing = Pairing(deviceID, pinText, alias)
                saved = repository.pair(host, pairing)
                if (saved) runCatching { repository.recordConnection(host.copy(pairing = pairing), System.currentTimeMillis()) }
            } catch (_: Exception) {
                // Do not log addresses, invitation codes, or authentication details.
            } finally {
                token.fill(0)
                if (!saved) runCatching { key.delete() }
            }
            finishOperation(if (saved) R.string.pair_success else R.string.pair_failed)
        }
    }

    private fun checkConnection(host: Host) {
        val pairing = host.pairing ?: return
        if (busy) return
        busy = true
        refresh()
        executor.execute {
            val success = runCatching {
                AuthenticatedHostConnection.reconnect(host.address, host.port, hexBytes(pairing.fingerprint),
                    UUID.fromString(pairing.deviceID), DeviceSigningKey(pairing.keyAlias)).use { }
                runCatching { HostStore(applicationContext).recordConnection(host, System.currentTimeMillis()) }
            }.isSuccess
            finishOperation(if (success) R.string.connection_success else R.string.connection_failed, host, success)
        }
    }

    private fun finishOperation(message: Int, checkedHost: Host? = null, succeeded: Boolean = false) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                checkedHost?.pairing?.let {
                    connectionChecks[checkedHost.id] = ConnectionCheck(checkedHost.address, checkedHost.port, it, succeeded, System.currentTimeMillis())
                }
                busy = false
                refresh()
                toast(message)
            }
        }
    }

    private fun fields() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), 0, dp(24), 0)
    }

    private fun field(hint: Int, input: Int, value: String? = null) = EditText(this).apply {
        setHint(hint)
        setSingleLine()
        inputType = input
        setText(value.orEmpty())
    }

    private fun normalizeHex(value: String): String? {
        val normalized = value.filterNot { it == ':' || it.isWhitespace() }.uppercase(java.util.Locale.ROOT)
        return normalized.takeIf { HostStore.validHex(it) }
    }

    private fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

}
