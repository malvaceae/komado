package me.mallows.komado

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.protocol.DisplayInfo
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.protocol.KeyboardKey
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerScroll
import me.mallows.komado.protocol.PointerDrag
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
                        }
                    }
                }
            })
            renderer = video
            viewport = ZoomableVideoView(this, video.view)
            if (controlMode) viewport?.contentDescription = getString(R.string.video_control_initial_description)
            viewport?.onVideoTap = { x, y ->
                lastVideoTapSent = sendPointerClick(x, y, PointerClick.Button.LEFT)
            }
            viewport?.onVideoLongPress = { x, y -> sendPointerClick(x, y, PointerClick.Button.RIGHT) }
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
        val zoomControls = LinearLayout(this)
        listOf(R.string.zoom_out to { viewport?.zoomBy(1.0 / 1.5) },
            R.string.zoom_in to { viewport?.zoomBy(1.5) }, R.string.zoom_reset to { viewport?.resetZoom() }).forEach { (label, action) ->
            val button = Button(this).apply { setText(label); isEnabled = false; setOnClickListener { action() } }
            zoomButtons.add(button)
            zoomControls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(zoomControls)
        if (controlMode) {
            root.addView(TextView(this).apply { setText(R.string.special_keys_hint); setPadding(dp(8), 0, dp(8), 0) })
            specialKeys = SpecialKeyBar(this) { key, modifiers ->
                val revision = captureRevision
                val sequence = if (active && firstFrameShown && revision != null) controlSequence.next() else null
                val sent = sequence != null && revision != null && media?.sendKey(KeyboardKey(sequence, revision, key, modifiers)) == true
                if (!sent && active) stopSession(failed = true)
                sent
            }
            root.addView(specialKeys)
            keyboard = RemoteKeyboardView(this, sendKey = { key, modifiers ->
                val revision = captureRevision
                val sequence = if (active && "keyboard.key" in supportedFeatures && firstFrameShown && revision != null) controlSequence.next() else null
                if (sequence != null && revision != null) media?.sendKey(KeyboardKey(sequence, revision, key, modifiers)) == true else false
            }, sendText = { text ->
                val message = if (active && "keyboard.text" in supportedFeatures && firstFrameShown) textMapper.message(text, captureRevision) else null
                message != null && media?.sendText(message) == true
            })
            root.addView(keyboard, LinearLayout.LayoutParams(1, 1))
            keyboardButton = Button(this).apply {
                setText(R.string.show_keyboard)
                isEnabled = false
                setOnClickListener { keyboard?.showKeyboard() }
            }
            root.addView(keyboardButton)
        }
        root.addView(Button(this).apply { setText(R.string.close_screen); setOnClickListener { finish() } })
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
                    if ("pointer.click" !in features) {
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
                    })
                    media = session
                    session.acceptOffer(offer)
                } catch (_: Exception) { stopSession(failed = true) }
            }
        } catch (_: Exception) { showFailure() }
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
        specialKeys?.setAvailable(available && "keyboard.key" in supportedFeatures)
    }

    private fun configureControlFeatures() {
        val descriptions = mutableListOf(getString(R.string.control_tap))
        if ("pointer.double" in supportedFeatures) descriptions.add(getString(R.string.control_double))
        descriptions.add(getString(R.string.control_long_press))
        if ("pointer.drag" in supportedFeatures) descriptions.add(getString(R.string.control_drag))
        if ("pointer.scroll" in supportedFeatures) descriptions.add(getString(R.string.control_scroll))
        descriptions.add(getString(R.string.control_zoom))
        viewport?.contentDescription = descriptions.joinToString("。", postfix = "。")
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
        runCatching { media?.close() }
        media = null
        captureRevision = null
        supportedFeatures = emptySet()
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
