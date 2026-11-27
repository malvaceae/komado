package me.mallows.komado

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.protocol.DisplayInfo
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.protocol.KeyboardKey
import me.mallows.komado.protocol.SpecialKey
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerScroll
import me.mallows.komado.protocol.PointerDrag
import me.mallows.komado.protocol.PointerMove
import me.mallows.komado.protocol.ClipboardRequest
import me.mallows.komado.protocol.ClipboardResponse
import me.mallows.komado.protocol.ClipboardResult
import me.mallows.komado.protocol.ClipboardOperation
import me.mallows.komado.security.DeviceSigningKey
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class VideoActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var active = true
    private var connection: AuthenticatedHostConnection? = null
    private var media: AndroidMediaSession? = null
    private var renderer: RemoteVideoRenderer? = null
    private var targetDialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private var statisticsDialog: AlertDialog? = null
    private var viewport: ZoomableVideoView? = null
    private val zoomButtons = mutableListOf<Button>()
    private var captureRevision: Long? = null
    private val controlSequence = ControlSequence()
    private val tapMapper = TapClickMapper(controlSequence)
    private val textMapper = CommittedTextMapper(controlSequence)
    private var controlMode = false
    @Volatile private var supportedFeatures: Set<String> = emptySet()
    private var keyboard: RemoteKeyboardView? = null
    private var keyboardButton: Button? = null
    private var trackpadButton: Button? = null
    private var trackpadSettingsButton: Button? = null
    private val trackpadSettings by lazy { TrackpadSettings(applicationContext) }
    private var trackpadEnabled = false
    private var clipboardSendButton: Button? = null
    private var clipboardReceiveButton: Button? = null
    private var statisticsButton: Button? = null
    private enum class ClipboardAction { GET, PREPARE_SEND, SEND, AUTO_START, AUTO_POLL, AUTO_PREPARE, AUTO_SEND }
    private var clipboardAction: ClipboardAction? = null
    private var clipboardChangeGuard: ClipboardChangeGuard? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var automaticSwitch: CheckBox? = null
    private var automaticWanted = false
    private var automaticStarted = false
    private var automaticEpoch = 0L
    private var automaticRequestEpoch = 0L
    private var automaticRequestTimestamp: Long? = null
    private var automaticState: AutomaticClipboardState? = null
    private var automaticListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val automaticTick = Runnable { automaticStep() }
    private var specialKeys: SpecialKeyBar? = null
    private var firstFrameShown = false
    private var lastVideoTapSent = false
    private lateinit var status: TextView
    private val firstFrameDeadline = FirstFrameDeadline(mainHandler) { if (active) stopSession(failed = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controlMode = intent.getBooleanExtra(CONTROL_MODE, false)
        val host = runCatching { HostStore(applicationContext).load().firstOrNull { it.id == intent.getStringExtra(HOST_ID) } }.getOrNull()
        val pairing = host?.pairing
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = host?.name.orEmpty(); textSize = 20f; setPadding(dp(16), dp(12), dp(16), 0) })
        status = TextView(this).apply { setText(R.string.screen_authenticating); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        root.addView(status)
        if (controlMode && intent.getBooleanExtra(CHOOSE_WINDOW, false)) {
            root.addView(TextView(this).apply {
                setText(R.string.window_control_note)
                setPadding(dp(16), 0, dp(16), dp(8))
            })
        }
        try {
            val video = RemoteVideoRenderer(this, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    runOnUiThread {
                        if (active) {
                            firstFrameShown = true
                            firstFrameDeadline.finish()
                            status.setText(R.string.screen_connected)
                            updateControlAvailability()
                        }
                    }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    runOnUiThread {
                        if (active) {
                            viewport?.setFrameSize(width, height, rotation)
                            val valid = width > 0 && height > 0 && rotation in listOf(0, 90, 180, 270)
                            zoomButtons.forEach { it.isEnabled = valid }
                            if (trackpadEnabled && viewport?.supportsTrackpad() != true) {
                                viewport?.setTrackpadMode(false)
                                trackpadEnabled = false
                                trackpadButton?.setText(R.string.enable_trackpad)
                                configureControlFeatures()
                            }
                            updateControlAvailability()
                        }
                    }
                }
            })
            renderer = video
            viewport = ZoomableVideoView(this, video.view)
            val sensitivity = trackpadSettings.load()
            viewport?.setTrackpadSensitivity(sensitivity.cursor, sensitivity.scroll)
            if (controlMode) viewport?.contentDescription = getString(R.string.video_control_initial_description)
            viewport?.onVideoTap = { x, y ->
                lastVideoTapSent = sendPointerClick(x, y, PointerClick.Button.LEFT)
            }
            viewport?.onVideoLongPress = { x, y -> sendPointerClick(x, y, PointerClick.Button.RIGHT) }
            viewport?.onMouseClick = { x, y, button -> sendPointerClick(x, y, button) }
            viewport?.onVideoDoubleTap = { x, y ->
                sendPointerClick(x, y, PointerClick.Button.LEFT,
                    count = if (lastVideoTapSent && "pointer.double" in supportedFeatures) 2 else 1)
                lastVideoTapSent = false
            }
            viewport?.onVideoScroll = { x, y, dx, dy ->
                if (active && controlMode && "pointer.scroll" in supportedFeatures && firstFrameShown && dx.isFinite() && dy.isFinite()) {
                    val horizontal = (dx / resources.displayMetrics.density).roundToInt().coerceIn(-120, 120)
                    val vertical = (dy / resources.displayMetrics.density).roundToInt().coerceIn(-120, 120)
                    if (horizontal != 0 || vertical != 0) {
                        val location = viewport?.mapTap(x, y, captureRevision, tapMapper)
                        if (location != null) {
                            val scroll = PointerScroll(location.sequence, location.captureRevision, location.x, location.y, horizontal, vertical)
                            if (media?.sendScroll(scroll) != true) stopSession(failed = true)
                        }
                    }
                }
            }
            root.addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } catch (_: Exception) { active = false; status.setText(R.string.screen_failed) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val zoomControls = LinearLayout(this)
        listOf(R.string.zoom_out to { viewport?.zoomBy(1.0 / 1.5) },
            R.string.zoom_in to { viewport?.zoomBy(1.5) }, R.string.zoom_reset to { viewport?.resetZoom() }).forEach { (label, action) ->
            val button = Button(this).apply { setText(label); isEnabled = false; setOnClickListener { action() } }
            zoomButtons.add(button)
            zoomControls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        controls.addView(zoomControls)
        if (controlMode) {
            trackpadButton = Button(this).apply {
                setText(R.string.enable_trackpad)
                isEnabled = false
                setOnClickListener {
                    trackpadEnabled = !trackpadEnabled
                    viewport?.setTrackpadMode(trackpadEnabled)
                    setText(if (trackpadEnabled) R.string.enable_direct_touch else R.string.enable_trackpad)
                    configureControlFeatures()
                    updateControlAvailability()
                }
            }
            trackpadSettingsButton = Button(this).apply {
                setText(R.string.trackpad_sensitivity)
                isEnabled = false
                setOnClickListener { showTrackpadSettings() }
            }
            val trackpadControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            trackpadControls.addView(trackpadButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trackpadControls.addView(trackpadSettingsButton)
            controls.addView(trackpadControls)
            clipboardSendButton = Button(this).apply {
                setText(R.string.clipboard_send); isEnabled = false; setOnClickListener { requestClipboard(get = false) }
            }
            clipboardReceiveButton = Button(this).apply {
                setText(R.string.clipboard_receive); isEnabled = false; setOnClickListener { requestClipboard(get = true) }
            }
            val clipboardControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            clipboardControls.addView(clipboardSendButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            clipboardControls.addView(clipboardReceiveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(clipboardControls)
            automaticSwitch = CheckBox(this).apply {
                setText(R.string.clipboard_automatic)
                isEnabled = false
                setOnCheckedChangeListener { _, checked ->
                    automaticWanted = checked
                    if (checked) beginAutomaticClipboard() else suspendAutomaticClipboard()
                }
            }
            controls.addView(automaticSwitch)
            controls.addView(TextView(this).apply { setText(R.string.special_keys_hint); setPadding(dp(8), 0, dp(8), 0) })
            specialKeys = SpecialKeyBar(this) { key, modifiers ->
                val revision = captureRevision
                val sequence = if (active && canSendKey(key) && firstFrameShown && revision != null) controlSequence.next() else null
                val sent = sequence != null && revision != null && media?.sendKey(KeyboardKey(sequence, revision, key, modifiers)) == true
                if (!sent && active) stopSession(failed = true)
                sent
            }
            controls.addView(specialKeys)
            keyboard = RemoteKeyboardView(this, sendKey = { key, modifiers ->
                val revision = captureRevision
                val sequence = if (active && canSendKey(key) && firstFrameShown && revision != null) controlSequence.next() else null
                if (sequence != null && revision != null) media?.sendKey(KeyboardKey(sequence, revision, key, modifiers)) == true else false
            }, sendText = { text ->
                val message = if (active && "keyboard.text" in supportedFeatures && firstFrameShown) textMapper.message(text, captureRevision) else null
                message != null && media?.sendText(message) == true
            })
            controls.addView(keyboard, LinearLayout.LayoutParams(1, 1))
            keyboardButton = Button(this).apply {
                setText(R.string.show_keyboard)
                isEnabled = false
                setOnClickListener { keyboard?.showKeyboard() }
            }
            controls.addView(keyboardButton)
        }
        statisticsButton = Button(this).apply {
            setText(R.string.statistics_title)
            isEnabled = false
            setOnClickListener { showVideoStatistics() }
        }
        controls.addView(statisticsButton)
        controls.addView(Button(this).apply { setText(R.string.close_screen); setOnClickListener { finish() } })
        root.addView(VideoControlsScrollView(this).apply { addView(controls) })
        setContentView(root)
        if (host == null || pairing == null || !active) {
            stopSession(failed = true)
            return
        }
        executor.execute {
            try {
                val established = AuthenticatedHostConnection.reconnect(host.address, host.port, hexBytes(pairing.fingerprint),
                    UUID.fromString(pairing.deviceID), DeviceSigningKey(pairing.keyAlias))
                val keep = synchronized(lock) {
                    if (!active) false else { connection = established; true }
                }
                if (!keep) { established.close(); return@execute }
                runCatching { HostStore(applicationContext).recordConnection(host, System.currentTimeMillis()) }
                if (controlMode) {
                    val features = try { established.listFeatures() } catch (_: Exception) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.features_failed) } }
                        return@execute
                    }
                    if ("pointer.click" !in features ||
                        (intent.getBooleanExtra(CHOOSE_WINDOW, false) && "window.control" !in features) ||
                        (intent.getBooleanExtra(CHOOSE_DISPLAY, false) && "display.control" !in features)) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.features_unsupported) } }
                        return@execute
                    }
                    supportedFeatures = features
                }
                if (intent.getBooleanExtra(CHOOSE_WINDOW, false)) {
                    showProgress(R.string.window_list_waiting)
                    val windows = try { established.listWindows() } catch (_: Exception) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.window_selection_failed) } }
                        return@execute
                    }
                    runOnUiThread {
                        if (active) {
                            if (windows.isEmpty()) { stopSession(failed = true); status.setText(R.string.no_windows) }
                            else { status.setText(R.string.window_choosing); chooseWindow(established, windows) }
                        }
                    }
                } else if (intent.getBooleanExtra(CHOOSE_DISPLAY, false)) {
                    showProgress(R.string.screen_list_waiting)
                    val displays = try { established.listDisplays() } catch (_: Exception) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.screen_selection_failed) } }
                        return@execute
                    }
                    runOnUiThread { if (active) { status.setText(R.string.screen_choosing); chooseDisplay(established, displays) } }
                } else startMedia(established, null)
            } catch (_: Exception) { showFailure() }
        }
    }

    private fun chooseDisplay(established: AuthenticatedHostConnection, displays: List<DisplayInfo>) {
        val labels = displays.mapIndexed { index, display ->
            getString(R.string.screen_option, index + 1, display.width, display.height)
        }.toTypedArray()
        targetDialog = AlertDialog.Builder(this).setTitle(R.string.select_screen).setItems(labels) { _, index ->
            targetDialog = null
            if (active) executor.execute { startMedia(established, displays[index].id) }
        }.setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun chooseWindow(established: AuthenticatedHostConnection, windows: List<WindowInfo>) {
        targetDialog = WindowPickerDialog.show(this, windows, onChoose = { window ->
            targetDialog = null
            if (active) executor.execute { startMedia(established, null, window.id) }
        }, onCancel = { finish() })
    }

    private fun startMedia(established: AuthenticatedHostConnection, displayId: Long?, windowId: Long? = null) {
        showProgress(R.string.screen_waiting)
        try {
            established.requestMedia(displayId, windowId, control = controlMode)
            val offer = established.receiveOffer()
            runOnUiThread {
                if (!active) return@runOnUiThread
                try {
                    if (controlMode) configureControlFeatures()
                    val session = AndroidMediaSession(applicationContext, established.localAddress, object : AndroidMediaSession.Listener {
                        override fun onAnswer(sdp: String) {
                            if (!active) return
                            executor.execute {
                                try {
                                    established.sendAnswer(sdp)
                                    established.awaitClosure()
                                    showFailure()
                                } catch (_: Exception) { showFailure() }
                            }
                        }
                        override fun onCaptureReady(revision: Long) {
                            captureRevision = revision
                            firstFrameDeadline.start()
                            updateControlAvailability()
                        }
                        override fun onVideo(track: VideoTrack?) { renderer?.show(track) }
                        override fun onFailure() { stopSession(failed = true) }
                        override fun onClipboardResponse(response: ClipboardResponse) { handleClipboardResponse(response) }
                    })
                    media = session
                    session.acceptOffer(offer)
                } catch (_: Exception) { stopSession(failed = true) }
            }
        } catch (_: Exception) { showFailure() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!automaticWanted) return
        if (hasFocus) beginAutomaticClipboard() else suspendAutomaticClipboard()
    }

    override fun onStop() {
        stopSession(failed = false)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun showFailure() {
        runOnUiThread { if (active) stopSession(failed = true) }
    }

    private fun showProgress(message: Int) {
        runOnUiThread { if (active) status.setText(message) }
    }

    private fun updateControlAvailability() {
        val available = active && firstFrameShown && captureRevision != null
        keyboardButton?.isEnabled = available && "keyboard.text" in supportedFeatures
        trackpadButton?.isEnabled = available && "pointer.move" in supportedFeatures && viewport?.supportsTrackpad() == true
        trackpadSettingsButton?.isEnabled = trackpadButton?.isEnabled == true && trackpadEnabled
        val clipboardAvailable = available && controlMode && "clipboard.manual" in supportedFeatures && clipboardAction == null
        clipboardSendButton?.isEnabled = clipboardAvailable
        clipboardReceiveButton?.isEnabled = clipboardAvailable
        statisticsButton?.isEnabled = active && firstFrameShown
        automaticSwitch?.isEnabled = available && controlMode && "clipboard.auto" in supportedFeatures
        specialKeys?.setAvailable(available && "keyboard.key" in supportedFeatures,
            "keyboard.navigation" in supportedFeatures, "keyboard.punctuation" in supportedFeatures,
            "keyboard.shortcut-keys" in supportedFeatures)
    }

    private fun canSendKey(key: SpecialKey): Boolean = supportsSpecialKey(key, supportedFeatures)

    private fun requestClipboard(get: Boolean) {
        if (!active || !hasWindowFocus() || clipboardAction != null || "clipboard.manual" !in supportedFeatures) return
        val revision = captureRevision ?: return
        val sequence = controlSequence.next() ?: run { showClipboardMessage(R.string.clipboard_unavailable); return }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val guard = ClipboardChangeGuard(runCatching { manager.primaryClipDescription?.timestamp }.getOrNull())
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            guard.onNotification(runCatching { manager.primaryClipDescription?.timestamp }.getOrNull())
        }
        if (runCatching { manager.addPrimaryClipChangedListener(listener) }.isFailure) {
            showClipboardMessage(R.string.clipboard_unavailable); return
        }
        clipboardChangeGuard = guard
        clipboardListener = listener
        guard.afterRegistration(runCatching { manager.primaryClipDescription?.timestamp }.getOrNull())
        val operation = if (get) ClipboardOperation.Get else ClipboardOperation.Prepare
        if (media?.sendClipboard(ClipboardRequest(sequence, revision, operation)) != true) {
            removeClipboardListener(); showClipboardMessage(R.string.clipboard_unavailable); return
        }
        clipboardAction = if (get) ClipboardAction.GET else ClipboardAction.PREPARE_SEND
        updateControlAvailability()
    }

    private fun handleClipboardResponse(response: ClipboardResponse) {
        val action = clipboardAction ?: return
        clipboardAction = null
        if (action in setOf(ClipboardAction.AUTO_START, ClipboardAction.AUTO_POLL, ClipboardAction.AUTO_PREPARE, ClipboardAction.AUTO_SEND)) {
            handleAutomaticClipboardResponse(action, response.result)
            updateControlAvailability()
            return
        }
        scheduleAutomaticClipboard()
        val changed = clipboardChangeGuard?.changed != false
        if (action == ClipboardAction.PREPARE_SEND && response.result == ClipboardResult.Ready) {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = if (active && hasWindowFocus() && !changed) ClipboardContent.text(runCatching { manager.primaryClip }.getOrNull()) else null
            removeClipboardListener()
            val revision = captureRevision
            val sequence = if (text != null && revision != null) controlSequence.next() else null
            if (text != null && revision != null && sequence != null &&
                media?.sendClipboard(ClipboardRequest(sequence, revision, ClipboardOperation.Set(text))) == true) {
                clipboardAction = ClipboardAction.SEND
                updateControlAvailability()
                return
            }
            updateControlAvailability()
            showClipboardMessage(if (changed) R.string.clipboard_changed else R.string.clipboard_unavailable)
            return
        }
        removeClipboardListener()
        updateControlAvailability()
        val message = when (val result = response.result) {
            ClipboardResult.Ok -> if (action == ClipboardAction.SEND) R.string.clipboard_sent else R.string.clipboard_unavailable
            ClipboardResult.Ready, ClipboardResult.Started, ClipboardResult.Unchanged -> R.string.clipboard_unavailable
            ClipboardResult.Busy -> R.string.clipboard_busy
            ClipboardResult.Unavailable -> R.string.clipboard_unavailable
            is ClipboardResult.Text -> {
                val clip = if (action == ClipboardAction.GET && active && hasWindowFocus() && !changed)
                    ClipboardContent.remote(result.value, getString(R.string.clipboard_label)) else null
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (changed && action == ClipboardAction.GET) R.string.clipboard_changed
                else if (clip != null && runCatching { manager.setPrimaryClip(clip) }.isSuccess) R.string.clipboard_received
                else R.string.clipboard_unavailable
            }
        }
        showClipboardMessage(message)
    }

    private fun beginAutomaticClipboard() {
        suspendAutomaticClipboard()
        if (!automaticWanted || !active || !hasWindowFocus()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val state = AutomaticClipboardState(runCatching { manager.primaryClipDescription?.timestamp }.getOrNull())
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val description = runCatching { manager.primaryClipDescription }.getOrNull()
            state.changed(description?.timestamp, ClipboardContent.automaticAllowed(description))
            scheduleAutomaticClipboard()
        }
        if (runCatching { manager.addPrimaryClipChangedListener(listener) }.isFailure) {
            disableAutomaticClipboard()
            return
        }
        automaticState = state
        automaticListener = listener
        state.reset(runCatching { manager.primaryClipDescription?.timestamp }.getOrNull())
        scheduleAutomaticClipboard(0)
    }

    private fun suspendAutomaticClipboard() {
        automaticEpoch++
        automaticStarted = false
        automaticState = null
        mainHandler.removeCallbacks(automaticTick)
        automaticListener?.let { listener ->
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            runCatching { manager.removePrimaryClipChangedListener(listener) }
        }
        automaticListener = null
    }

    private fun disableAutomaticClipboard() {
        automaticWanted = false
        suspendAutomaticClipboard()
        automaticSwitch?.isChecked = false
        showClipboardMessage(R.string.clipboard_automatic_unavailable)
    }

    private fun scheduleAutomaticClipboard(delay: Long = 1000) {
        mainHandler.removeCallbacks(automaticTick)
        if (automaticWanted && active && hasWindowFocus() && automaticState != null) mainHandler.postDelayed(automaticTick, delay)
    }

    private fun automaticStep() {
        if (!automaticWanted || !active || !hasWindowFocus() || automaticState == null) return
        if (clipboardAction != null || !firstFrameShown || captureRevision == null) { scheduleAutomaticClipboard(); return }
        val state = automaticState ?: return
        val action = if (!automaticStarted) ClipboardAction.AUTO_START
            else if (state.dirty) ClipboardAction.AUTO_PREPARE else ClipboardAction.AUTO_POLL
        val operation = when (action) {
            ClipboardAction.AUTO_START -> ClipboardOperation.AutoStart
            ClipboardAction.AUTO_PREPARE -> ClipboardOperation.AutoPrepare
            else -> ClipboardOperation.AutoPoll
        }
        automaticRequestTimestamp = state.timestamp
        automaticRequestEpoch = automaticEpoch
        if (!sendAutomaticClipboard(action, operation)) disableAutomaticClipboard()
    }

    private fun sendAutomaticClipboard(action: ClipboardAction, operation: ClipboardOperation): Boolean {
        val revision = captureRevision ?: return false
        val sequence = controlSequence.next() ?: return false
        if (media?.sendClipboard(ClipboardRequest(sequence, revision, operation)) != true) return false
        clipboardAction = action
        updateControlAvailability()
        return true
    }

    private fun handleAutomaticClipboardResponse(action: ClipboardAction, result: ClipboardResult) {
        if (!automaticWanted || !active || !hasWindowFocus() || automaticRequestEpoch != automaticEpoch) {
            scheduleAutomaticClipboard(0)
            return
        }
        val state = automaticState ?: return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val descriptionResult = runCatching { manager.primaryClipDescription }
        if (descriptionResult.isFailure) { disableAutomaticClipboard(); return }
        val description = descriptionResult.getOrNull()
        val currentTimestamp = description?.timestamp
        when {
            result == ClipboardResult.Unavailable -> { disableAutomaticClipboard(); return }
            result == ClipboardResult.Busy -> Unit
            action == ClipboardAction.AUTO_START && result == ClipboardResult.Started -> automaticStarted = true
            action == ClipboardAction.AUTO_PREPARE && result == ClipboardResult.Ready -> {
                if (state.dirty && state.matches(automaticRequestTimestamp) && state.matches(currentTimestamp) &&
                    ClipboardContent.automaticAllowed(description)) {
                    val clip = runCatching { manager.primaryClip }.getOrNull()
                    val text = if (ClipboardContent.automaticAllowed(clip?.description) &&
                        clip?.description?.timestamp == automaticRequestTimestamp) ClipboardContent.text(clip) else null
                    if (text != null && sendAutomaticClipboard(ClipboardAction.AUTO_SEND, ClipboardOperation.AutoSet(text))) return
                }
                state.delivered(automaticRequestTimestamp)
            }
            action == ClipboardAction.AUTO_SEND && result == ClipboardResult.Ok -> state.delivered(automaticRequestTimestamp)
            action == ClipboardAction.AUTO_POLL && result is ClipboardResult.Text -> {
                val unchanged = if (description == null) state.timestamp == null && automaticRequestTimestamp == null
                    else state.matches(currentTimestamp) && state.matches(automaticRequestTimestamp)
                if (!state.dirty && unchanged) {
                    val clip = ClipboardContent.remote(result.value, getString(R.string.clipboard_label))
                    if (clip != null && runCatching { manager.setPrimaryClip(clip) }.isSuccess) {
                        val updated = runCatching { manager.primaryClipDescription }.getOrNull()
                        state.reset(updated?.timestamp)
                    }
                }
            }
            action == ClipboardAction.AUTO_POLL && result == ClipboardResult.Unchanged -> Unit
            else -> { disableAutomaticClipboard(); return }
        }
        scheduleAutomaticClipboard()
    }

    private fun removeClipboardListener() {
        val listener = clipboardListener ?: return
        clipboardListener = null
        clipboardChangeGuard = null
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { manager.removePrimaryClipChangedListener(listener) }
    }

    private fun showClipboardMessage(message: Int) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    private fun showVideoStatistics() {
        if (!active || statisticsDialog != null) return
        val dialog = AlertDialog.Builder(this).setTitle(R.string.statistics_title)
            .setMessage(R.string.statistics_loading).setPositiveButton(R.string.close_screen, null).create()
        statisticsDialog = dialog
        val timeout = Runnable {
            if (statisticsDialog === dialog && dialog.isShowing) dialog.setMessage(getString(R.string.statistics_unavailable))
        }
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(timeout)
            if (statisticsDialog === dialog) statisticsDialog = null
        }
        dialog.show()
        val requested = media?.requestVideoStatistics { snapshot ->
            if (!active || statisticsDialog !== dialog || !dialog.isShowing) return@requestVideoStatistics
            mainHandler.removeCallbacks(timeout)
            val unknown = getString(R.string.statistics_unknown)
            val size = if (snapshot.width != null && snapshot.height != null)
                getString(R.string.statistics_size_value, snapshot.width, snapshot.height) else unknown
            val fps = snapshot.framesPerSecond?.let { getString(R.string.statistics_fps_value, it) } ?: unknown
            val rtt = snapshot.roundTripSeconds?.let { getString(R.string.statistics_rtt_value, it * 1000) } ?: unknown
            dialog.setMessage(getString(R.string.statistics_message, size, fps, rtt))
        } == true
        if (requested) mainHandler.postDelayed(timeout, 5_000)
        else dialog.setMessage(getString(R.string.statistics_unavailable))
    }

    private fun showTrackpadSettings() {
        val current = trackpadSettings.load()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun slider(label: Int, value: Int): SeekBar {
            val caption = TextView(this)
            content.addView(caption)
            return SeekBar(this).apply {
                max = 200; min = 50; progress = value
                caption.text = getString(label, value)
                contentDescription = caption.text
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        caption.text = getString(label, progress)
                        seekBar.contentDescription = caption.text
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
                content.addView(this)
            }
        }
        val cursor = slider(R.string.trackpad_cursor_speed, current.cursor)
        val scroll = slider(R.string.trackpad_scroll_speed, current.scroll)
        settingsDialog = AlertDialog.Builder(this).setTitle(R.string.trackpad_sensitivity).setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val values = TrackpadSettings.Values(cursor.progress, scroll.progress)
                trackpadSettings.save(values)
                viewport?.setTrackpadSensitivity(values.cursor, values.scroll)
            }.setOnDismissListener { settingsDialog = null }.show()
    }

    private fun configureControlFeatures() {
        val descriptions = mutableListOf(getString(if (trackpadEnabled) R.string.control_trackpad else R.string.control_tap))
        if ("pointer.double" in supportedFeatures) descriptions.add(getString(R.string.control_double))
        descriptions.add(getString(R.string.control_long_press))
        if ("pointer.drag" in supportedFeatures) descriptions.add(getString(R.string.control_drag))
        if ("pointer.scroll" in supportedFeatures) descriptions.add(getString(R.string.control_scroll))
        if ("pointer.move" in supportedFeatures) descriptions.add(getString(R.string.control_hover))
        descriptions.add(getString(R.string.control_zoom))
        viewport?.contentDescription = descriptions.joinToString("。", postfix = "。")
        viewport?.onVideoHover = if ("pointer.move" !in supportedFeatures) null else { x, y ->
            if (active && firstFrameShown) {
                val location = viewport?.mapTap(x, y, captureRevision, tapMapper)
                if (location != null) {
                    val move = PointerMove(location.sequence, location.captureRevision, location.x, location.y)
                    if (media?.sendMove(move) != true) stopSession(failed = true)
                }
            }
        }
        viewport?.onVideoDrag = if ("pointer.drag" !in supportedFeatures) null else { phase, x, y ->
            if (!active || !firstFrameShown) false else {
                val location = viewport?.mapTap(x, y, captureRevision, tapMapper)
                if (location == null) false else {
                    val drag = PointerDrag(location.sequence, location.captureRevision, location.x, location.y, phase)
                    val sent = media?.sendDrag(drag) == true
                    if (!sent) stopSession(failed = true)
                    sent
                }
            }
        }
    }

    private fun sendPointerClick(x: Float, y: Float, button: PointerClick.Button, count: Int = 1): Boolean {
        if (!active || !controlMode || !firstFrameShown) return false
        val click = viewport?.mapTap(x, y, captureRevision, tapMapper, button, count) ?: return false
        val sent = media?.sendClick(click) == true
        if (!sent) stopSession(failed = true)
        return sent
    }

    private fun stopSession(failed: Boolean) {
        firstFrameDeadline.finish()
        val current = synchronized(lock) {
            active = false
            val old = connection
            connection = null
            old
        }
        runCatching { current?.close() }
        targetDialog?.dismiss()
        targetDialog = null
        settingsDialog?.dismiss()
        settingsDialog = null
        statisticsDialog?.dismiss()
        statisticsDialog = null
        runCatching { media?.close() }
        media = null
        captureRevision = null
        supportedFeatures = emptySet()
        viewport?.setTrackpadMode(false)
        trackpadEnabled = false
        viewport?.onVideoHover = null
        trackpadButton?.isEnabled = false
        trackpadSettingsButton?.isEnabled = false
        automaticWanted = false
        suspendAutomaticClipboard()
        automaticSwitch?.isChecked = false
        automaticSwitch?.isEnabled = false
        clipboardAction = null
        removeClipboardListener()
        clipboardSendButton?.isEnabled = false
        clipboardReceiveButton?.isEnabled = false
        statisticsButton?.isEnabled = false
        keyboardButton?.isEnabled = false
        specialKeys?.setAvailable(false)
        keyboard?.close()
        keyboard = null
        zoomButtons.forEach { it.isEnabled = false }
        runCatching { renderer?.close() }
        renderer = null
        if (::status.isInitialized) status.setText(
            if (!failed) R.string.screen_closed else if (firstFrameShown) R.string.screen_disconnected else R.string.screen_failed
        )
    }

    private fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HOST_ID = "me.mallows.komado.host_id"
        const val CHOOSE_DISPLAY = "me.mallows.komado.choose_display"
        const val CHOOSE_WINDOW = "me.mallows.komado.choose_window"
        const val CONTROL_MODE = "me.mallows.komado.control_mode"
    }
}
