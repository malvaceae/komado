package me.mallows.komado

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.security.DeviceSigningKey
import java.util.UUID
import java.util.Date
import java.text.DateFormat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private data class ConnectionCheck(val address: String, val port: Int, val pairing: Pairing, val succeeded: Boolean, val checkedAt: Long)
    private sealed interface HomeDialog {
        data class Actions(val host: Host) : HomeDialog
        data class Edit(val host: Host?) : HomeDialog
        data class Pair(val host: Host) : HomeDialog
        data class Scanned(val invitation: PairingInvitation) : HomeDialog
        data class Delete(val host: Host) : HomeDialog
    }
    private val store by lazy { HostStore(applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private var hosts by mutableStateOf(emptyList<Host>())
    private val connectionChecks = mutableStateMapOf<String, ConnectionCheck>()
    private var readable by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var dialog by mutableStateOf<HomeDialog?>(null)
    private var pendingNetworkAction: (() -> Unit)? = null
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
        enableEdgeToEdge()
        refresh()
        setContent {
            KomadoTheme { HostHome(); HomeDialogs() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
    }

    @Composable
    private fun HostHome() {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.hosts), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = 24.dp))
                Text(stringResource(if (busy) R.string.connecting else R.string.connection_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!readable || hosts.isEmpty()) {
                        item {
                            Text(stringResource(if (readable) R.string.empty_hosts else R.string.storage_error),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(hosts, key = { it.id }) { host ->
                        val destination = if (host.address.contains(':')) "[${host.address}]:${host.port}" else "${host.address}:${host.port}"
                        val last = host.lastConnectedAt?.let {
                            getString(R.string.last_connected, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
                        } ?: getString(R.string.never_connected)
                        val check = connectionChecks[host.id]?.takeIf {
                            it.address == host.address && it.port == host.port && it.pairing == host.pairing
                        }?.let {
                            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.checkedAt))
                            getString(R.string.checked_connection, time, getString(if (it.succeeded) R.string.check_succeeded else R.string.check_failed))
                        }
                        Card(onClick = { hostActions(host) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(host.name, style = MaterialTheme.typography.titleMedium)
                                Text(destination, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(if (host.pairing == null) R.string.not_paired else R.string.paired),
                                    style = MaterialTheme.typography.labelMedium)
                                Text(last, style = MaterialTheme.typography.bodySmall)
                                if (check != null) Text(check, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Button(onClick = {
                    ScannedPairing.clear()
                    scanner.launch(Intent(this@MainActivity, PairingScannerActivity::class.java))
                }, enabled = readable && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.scan_pairing))
                }
                OutlinedButton(onClick = { editHost(null) }, enabled = readable && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.add_host))
                }
            }
        }
    }

    @Composable
    private fun HomeDialogs() {
        when (val current = dialog) {
            is HomeDialog.Actions -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(current.host.name) },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        hostActionLabels(current.host).forEachIndexed { index, label ->
                            TextButton(onClick = { performHostAction(current.host, index) }, modifier = Modifier.fillMaxWidth()) {
                                Text(label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
            is HomeDialog.Edit -> HostEditor(current.host, null)
            is HomeDialog.Scanned -> HostEditor(null, current.invitation)
            is HomeDialog.Pair -> PairEditor(current.host)
            is HomeDialog.Delete -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.delete_host_title)) },
                text = { Text(if (current.host.pairing == null) current.host.name else getString(R.string.delete_paired_message, current.host.name)) },
                confirmButton = { TextButton(onClick = { removeHost(current.host) }) { Text(stringResource(R.string.delete)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
            )
            null -> Unit
        }
    }

    @Composable
    private fun HostEditor(existing: Host?, scanned: PairingInvitation?) {
        var name by remember { mutableStateOf(existing?.name ?: if (scanned != null) "Mac" else "") }
        var address by remember { mutableStateOf(existing?.address ?: scanned?.host.orEmpty()) }
        var port by remember { mutableStateOf((existing?.port ?: scanned?.port ?: 48365).toString()) }
        var nameError by remember { mutableStateOf(false) }
        var addressError by remember { mutableStateOf(false) }
        var portError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (scanned != null) R.string.pair else if (existing == null) R.string.add_host else R.string.edit_host)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (scanned != null) Text(stringResource(R.string.scan_confirm))
                    OutlinedTextField(value = name, onValueChange = { name = it; nameError = false },
                        label = { Text(stringResource(R.string.host_name)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        isError = nameError, supportingText = if (nameError) {{ Text(stringResource(R.string.invalid_name)) }} else null)
                    OutlinedTextField(value = address, onValueChange = { address = it; addressError = false },
                        label = { Text(stringResource(R.string.host_address)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        isError = addressError, supportingText = if (addressError) {{ Text(stringResource(R.string.invalid_address)) }} else null)
                    OutlinedTextField(value = port, onValueChange = { port = it; portError = false },
                        label = { Text(stringResource(R.string.port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = portError, supportingText = if (portError) {{ Text(stringResource(R.string.invalid_port)) }} else null)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hostName = name.trim()
                    val hostAddress = address.trim()
                    val hostPort = port.toIntOrNull()
                    nameError = !HostStore.validName(hostName)
                    addressError = !HostStore.validAddress(hostAddress)
                    portError = hostPort == null || hostPort !in 1..65535
                    if (!nameError && !addressError && !portError) saveHost(existing, scanned, hostName, hostAddress, hostPort!!)
                }) { Text(stringResource(if (scanned != null) R.string.pair else R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun PairEditor(host: Host) {
        var fingerprint by remember { mutableStateOf("") }
        var invitation by remember { mutableStateOf("") }
        var fingerprintError by remember { mutableStateOf(false) }
        var invitationError by remember { mutableStateOf(false) }
        val dismiss = { invitation = ""; dialog = null }
        val privateInput = remember {
            PlatformTextInputInterceptor { request, next ->
                val protected = object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val connection = request.createInputConnection(outAttributes)
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        return connection
                    }
                }
                next.startInputMethod(protected)
            }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.pair)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.pair_instructions))
                    OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it; fingerprintError = false },
                        label = { Text(stringResource(R.string.fingerprint)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), isError = fingerprintError,
                        supportingText = if (fingerprintError) {{ Text(stringResource(R.string.invalid_fingerprint)) }} else null)
                    InterceptPlatformTextInput(interceptor = privateInput) {
                        val view = LocalView.current.rootView
                        DisposableEffect(view) {
                            val previous = view.importantForAutofill
                            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            onDispose { view.importantForAutofill = previous }
                        }
                        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                            OutlinedTextField(value = invitation, onValueChange = { invitation = it; invitationError = false },
                                label = { Text(stringResource(R.string.invitation)) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                isError = invitationError,
                                supportingText = if (invitationError) {{ Text(stringResource(R.string.invalid_invitation)) }} else null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val pin = normalizeHex(fingerprint)
                    val token = normalizeHex(invitation)
                    fingerprintError = pin == null
                    invitationError = token == null
                    if (pin != null && token != null) { dismiss(); startPairing(host, pin, token) }
                }) { Text(stringResource(R.string.pair)) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun hostActions(host: Host) { dialog = HomeDialog.Actions(host) }

    private fun hostActionLabels(host: Host): List<String> {
        val actions = if (host.pairing == null) arrayOf(getString(R.string.pair), getString(R.string.edit_host), getString(R.string.delete))
        else arrayOf(getString(R.string.view_screen), getString(R.string.control_screen), getString(R.string.select_screen),
            getString(R.string.control_display), getString(R.string.select_application), getString(R.string.control_application),
            getString(R.string.select_window), getString(R.string.control_window), getString(R.string.check_connection), getString(R.string.edit_host), getString(R.string.delete))
        return actions.toList()
    }

    private fun performHostAction(host: Host, index: Int) {
        dialog = null
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
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_APPLICATION, true)) }
                5 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_APPLICATION, true)
                    .putExtra(VideoActivity.CONTROL_MODE, true)) }
                6 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_WINDOW, true)) }
                7 -> withLocalNetworkPermission { startActivity(Intent(this, VideoActivity::class.java)
                    .putExtra(VideoActivity.HOST_ID, host.id).putExtra(VideoActivity.CHOOSE_WINDOW, true)
                    .putExtra(VideoActivity.CONTROL_MODE, true)) }
                8 -> withLocalNetworkPermission { checkConnection(host) }
                9 -> editHost(host)
                10 -> deleteHost(host)
        }
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

    private fun editHost(existing: Host?) { dialog = HomeDialog.Edit(existing) }
    private fun deleteHost(host: Host) { dialog = HomeDialog.Delete(host) }
    private fun pairHost(host: Host) { dialog = HomeDialog.Pair(host) }
    private fun confirmScannedPairing(invitation: PairingInvitation) { dialog = HomeDialog.Scanned(invitation) }

    private fun saveHost(existing: Host?, scanned: PairingInvitation?, name: String, address: String, port: Int) {
        val host = Host(existing?.id ?: UUID.randomUUID().toString(), name, address, port,
            existing?.pairing, existing?.lastConnectedAt)
        val saved = runCatching { store.save(existing, host) }.getOrDefault(false)
        if (saved) {
            dialog = null
            if (scanned != null) startPairing(host, scanned.fingerprint, scanned.token)
        } else toast(R.string.save_error)
        refresh()
    }

    private fun removeHost(host: Host) {
        val removed = runCatching { store.remove(host) }.getOrDefault(false)
        if (removed) {
            host.pairing?.let {
                if (runCatching { DeviceSigningKey(it.keyAlias).delete() }.isFailure) toast(R.string.key_delete_error)
            }
        } else toast(R.string.save_error)
        dialog = null
        refresh()
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

    private fun normalizeHex(value: String): String? {
        val normalized = value.filterNot { it == ':' || it.isWhitespace() }.uppercase(java.util.Locale.ROOT)
        return normalized.takeIf { HostStore.validHex(it) }
    }

    private fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

}
