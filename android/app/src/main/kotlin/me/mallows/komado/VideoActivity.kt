package me.mallows.komado

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.View
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.protocol.DisplayInfo
import me.mallows.komado.protocol.ApplicationInfo
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.protocol.WindowSize
import me.mallows.komado.protocol.WindowAction
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
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.roundToInt

class VideoActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val switchExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var active = true
    private var connection: AuthenticatedHostConnection? = null
    private var media: AndroidMediaSession? = null
    private var renderer: RemoteVideoRenderer? = null
    private var targetDialog: AlertDialog? = null
    private var windowActionDialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private var statisticsDialog: AlertDialog? = null
    private var viewport: ZoomableVideoView? = null
    private val zoomButtons = mutableListOf<Button>()
    private var captureRevision: Long? = null
    private val controlSequence = ControlSequence()
    private val tapMapper = TapClickMapper(controlSequence)
    private val textMapper = CommittedTextMapper(controlSequence)
    private var controlMode = false
    private var keyboard: RemoteKeyboardView? = null
    private var keyboardButton: Button? = null
    private var trackpadButton: Button? = null
    private var trackpadSettingsButton: Button? = null
    private val trackpadSettings by lazy { TrackpadSettings(applicationContext) }
    private var trackpadEnabled = false
    private var clipboardSendButton: Button? = null
    private var clipboardReceiveButton: Button? = null
    private var statisticsButton: Button? = null
    private var switchButton: Button? = null
    private var fullscreenButton: Button? = null
    private var exitFullscreenButton: Button? = null
    private var fullscreen = false
    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setFullscreen(false) }
    }
    private lateinit var header: View
    private var windowNote: View? = null
    private var showingWindowNote = false
    private var applicationNote: View? = null
    private var showingApplicationNote = false
    private var windowResizeSwitch: CheckBox? = null
    private var updatingResizeSwitch = false
    private var currentWindowId: Long? = null
    private var reloadWindowId: Long? = null
    private lateinit var controlsScroll: VideoControlsScrollView
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
    private enum class Target { MAIN, DISPLAY, APPLICATION, WINDOW }
    private sealed interface Selection {
        data object Main : Selection
        data class Display(val info: DisplayInfo) : Selection
        data class Application(val info: ApplicationInfo) : Selection
        data class Window(val info: WindowInfo) : Selection
    }
    @Volatile private var selected: Selection? = null
    private var sessionHost: Host? = null
    @Volatile private var reconnecting = false
    private var reconnectedSize: WindowSize? = null
    private val retryConnection = Runnable { reconnect() }
    private var nextTarget: Target? = null
    @Volatile private var switching = false
    @Volatile private var rendererGeneration = 0
    private var textDeliveryUnconfirmed = false
    private var lastVideoTapSent = false
    private lateinit var status: TextView
    private var firstFrameDeadline = FirstFrameDeadline(mainHandler) { firstFrameTimedOut() }
    private val resizeForLayout = object : Runnable {
        override fun run() {
            if (!active || !showingWindowNote || windowResizeSwitch?.isChecked != true) return
            if (switching || !firstFrameShown || clipboardAction != null || media?.hasUnconfirmedText() != false) {
                mainHandler.postDelayed(this, 500)
                return
            }
            currentWindowId?.let { beginTargetSwitch(Target.WINDOW, it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, fullscreenBack)
        controlMode = intent.getBooleanExtra(CONTROL_MODE, false)
        val host = runCatching { HostStore(applicationContext).load().firstOrNull { it.id == intent.getStringExtra(HOST_ID) } }.getOrNull()
        val pairing = host?.pairing
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = TextView(this).apply { text = host?.name.orEmpty(); textSize = 20f; setPadding(dp(16), dp(12), dp(16), 0) }
        root.addView(header)
        status = TextView(this).apply { setText(R.string.screen_authenticating); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        root.addView(status)
        showingWindowNote = controlMode && intent.getBooleanExtra(CHOOSE_WINDOW, false)
        showingApplicationNote = intent.getBooleanExtra(CHOOSE_APPLICATION, false)
        if (controlMode) {
            windowNote = TextView(this).apply {
                setText(R.string.window_control_note)
                setPadding(dp(16), 0, dp(16), dp(8))
                visibility = if (showingWindowNote) View.VISIBLE else View.GONE
            }
            root.addView(windowNote)
        }
        applicationNote = TextView(this).apply {
            setText(R.string.application_capture_note)
            setPadding(dp(16), 0, dp(16), dp(8))
            visibility = if (showingApplicationNote) View.VISIBLE else View.GONE
        }
        root.addView(applicationNote)
        try {
            val video = RemoteVideoRenderer(this, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    if (switching) return
                    val generation = rendererGeneration
                    runOnUiThread {
                        if (active && !switching && generation == rendererGeneration) {
                            firstFrameShown = true
                            reconnecting = false
                            mainHandler.removeCallbacks(retryConnection)
                            firstFrameDeadline.finish()
                            status.setText(R.string.screen_connected)
                            updateControlAvailability()
                        }
                    }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    if (switching) return
                    val generation = rendererGeneration
                    runOnUiThread {
                        if (active && !switching && generation == rendererGeneration) {
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
                    count = if (lastVideoTapSent) 2 else 1)
                lastVideoTapSent = false
            }
            viewport?.onVideoScroll = { x, y, dx, dy ->
                if (active && controlMode && firstFrameShown && dx.isFinite() && dy.isFinite()) {
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
            val videoFrame = FrameLayout(this)
            videoFrame.addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            exitFullscreenButton = Button(this).apply {
                setText(R.string.exit_fullscreen)
                visibility = View.GONE
                setOnClickListener { setFullscreen(false) }
            }
            val exit = exitFullscreenButton!!
            videoFrame.addView(exit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
            ViewCompat.setOnApplyWindowInsetsListener(exit) { view, insets ->
                val safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
                val params = view.layoutParams as FrameLayout.LayoutParams
                val margin = dp(8)
                val endInset = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) safe.left else safe.right
                if (params.topMargin != safe.top + margin || params.marginEnd != endInset + margin) {
                    params.topMargin = safe.top + margin
                    params.marginEnd = endInset + margin
                    view.layoutParams = params
                }
                insets
            }
            root.addView(videoFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } catch (_: Exception) { active = false; status.setText(R.string.screen_failed) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (controlMode) {
            windowResizeSwitch = CheckBox(this).apply {
                setText(R.string.resize_window_automatically)
                visibility = if (showingWindowNote) View.VISIBLE else View.GONE
                isEnabled = false
                setOnCheckedChangeListener { button, checked ->
                    if (!updatingResizeSwitch && active && firstFrameShown && !switching) {
                        if (clipboardAction != null || media?.hasUnconfirmedText() != false) {
                            updatingResizeSwitch = true
                            button.isChecked = !checked
                            updatingResizeSwitch = false
                            Toast.makeText(this@VideoActivity, R.string.switch_waiting_input, Toast.LENGTH_SHORT).show()
                        } else currentWindowId?.let { beginTargetSwitch(Target.WINDOW, it) }
                    }
                }
            }
            controls.addView(windowResizeSwitch)
        }
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
            specialKeys = SpecialKeyBar(this, ::sendRemoteKey)
            controls.addView(specialKeys)
            keyboard = RemoteKeyboardView(this, sendKey = ::sendRemoteKey, sendText = { text ->
                if (!active || !firstFrameShown) false else
                    textMapper.send(text, captureRevision, { media?.sendText(it) == true },
                        { if (active) { textDeliveryUnconfirmed = true; stopSession(failed = true) } })
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
        switchButton = Button(this).apply {
            setText(R.string.change_target)
            isEnabled = false
            setOnClickListener { showTargetChoices() }
        }
        controls.addView(switchButton)
        fullscreenButton = Button(this).apply {
            setText(R.string.fullscreen)
            isEnabled = false
            setOnClickListener { setFullscreen(true) }
        }
        controls.addView(fullscreenButton)
        controls.addView(Button(this).apply { setText(R.string.close_screen); setOnClickListener { finish() } })
        controlsScroll = VideoControlsScrollView(this).apply { addView(controls) }
        root.addView(controlsScroll)
        setContentView(root)
        if (host == null || pairing == null || !active) {
            stopSession(failed = true)
            return
        }
        sessionHost = host
        executor.execute {
            try {
                val established = AuthenticatedHostConnection.reconnect(host.address, host.port, hexBytes(pairing.fingerprint),
                    UUID.fromString(pairing.deviceID), DeviceSigningKey(pairing.keyAlias))
                val keep = synchronized(lock) {
                    if (!active) false else { connection = established; true }
                }
                if (!keep) { established.close(); return@execute }
                runCatching { HostStore(applicationContext).recordConnection(host, System.currentTimeMillis()) }
                val target = when {
                    intent.getBooleanExtra(CHOOSE_WINDOW, false) -> Target.WINDOW
                    intent.getBooleanExtra(CHOOSE_APPLICATION, false) -> Target.APPLICATION
                    intent.getBooleanExtra(CHOOSE_DISPLAY, false) -> Target.DISPLAY
                    else -> Target.MAIN
                }
                prepareTarget(established, target)
            } catch (_: Exception) { showFailure() }
        }
    }

    private fun prepareTarget(established: AuthenticatedHostConnection, target: Target,
                              reload: Long? = null, size: WindowSize? = null) {
        when (target) {
            Target.MAIN -> {
                selected = Selection.Main
                startMedia(established, null)
            }
            Target.DISPLAY -> {
                showProgress(R.string.screen_list_waiting)
                val displays = try { established.listDisplays() } catch (_: Exception) {
                    runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.screen_selection_failed) } }
                    return
                }
                runOnUiThread { if (active) { status.setText(R.string.screen_choosing); chooseDisplay(established, displays) } }
            }
            Target.APPLICATION -> {
                showProgress(R.string.application_list_waiting)
                val applications = try { established.listApplications() } catch (_: Exception) {
                    runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.application_selection_failed) } }
                    return
                }
                runOnUiThread {
                    if (active) {
                        if (applications.isEmpty()) { stopSession(failed = true); status.setText(R.string.no_applications) }
                        else { status.setText(R.string.application_choosing); chooseApplication(established, applications) }
                    }
                }
            }
            Target.WINDOW -> {
                showProgress(R.string.window_list_waiting)
                val windows = try { established.listWindows() } catch (_: Exception) {
                    runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.window_selection_failed) } }
                    return
                }
                if (reload != null) {
                    val window = windows.firstOrNull { it.id == reload } ?: run { showFailure(); return }
                    selected = Selection.Window(window)
                    startMedia(established, null, reload, size)
                    return
                }
                runOnUiThread {
                    if (active) {
                        status.setText(R.string.window_choosing)
                        chooseWindow(established, windows)
                    }
                }
            }
        }
    }

    private fun showTargetChoices() {
        if (!active || switching) return
        val targets = arrayOf(getString(if (controlMode) R.string.control_screen else R.string.view_screen),
            getString(R.string.select_screen), getString(R.string.application_picker_title), getString(R.string.select_window))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.change_target).setItems(targets) { _, index ->
            beginTargetSwitch(Target.entries[index])
        }.setNegativeButton(R.string.cancel, null).create()
        targetDialog = dialog
        dialog.setOnDismissListener { if (targetDialog === dialog) targetDialog = null }
        dialog.show()
    }

    private fun beginTargetSwitch(target: Target, reload: Long? = null) {
        if (!active || switching) return
        val current = synchronized(lock) { connection } ?: return
        if (clipboardAction != null || media?.hasUnconfirmedText() != false) {
            if (reload != null) mainHandler.postDelayed(resizeForLayout, 500)
            else Toast.makeText(this, R.string.switch_waiting_input, Toast.LENGTH_SHORT).show()
            return
        }
        mainHandler.removeCallbacks(resizeForLayout)
        nextTarget = target
        reloadWindowId = reload
        switching = true
        rendererGeneration++
        firstFrameShown = false
        captureRevision = null
        firstFrameDeadline.finish()
        keyboard?.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        suspendAutomaticClipboard()
        updateControlAvailability()
        status.setText(R.string.switching_target)
        switchExecutor.execute { try { current.requestMediaStop() } catch (_: Exception) { showFailure() } }
    }

    private fun desiredWindowSize(): WindowSize? {
        if (windowResizeSwitch?.isChecked != true) return null
        val view = viewport ?: return null
        if (view.width !in 1..65_535 || view.height !in 1..65_535) return null
        return WindowSize(view.width, view.height)
    }

    private fun finishTargetSwitch(established: AuthenticatedHostConnection) {
        if (!active || !switching || synchronized(lock) { connection !== established }) return
        val target = nextTarget ?: run { stopSession(failed = true); return }
        nextTarget = null
        val reload = reloadWindowId
        reloadWindowId = null
        media?.close()
        media = null
        try { renderer?.reset() } catch (_: Exception) { stopSession(failed = true); return }
        switching = false
        showingWindowNote = controlMode && target == Target.WINDOW
        showingApplicationNote = target == Target.APPLICATION
        windowNote?.visibility = if (showingWindowNote) View.VISIBLE else View.GONE
        applicationNote?.visibility = if (showingApplicationNote) View.VISIBLE else View.GONE
        windowResizeSwitch?.visibility = if (showingWindowNote) View.VISIBLE else View.GONE
        if (reload == null) currentWindowId = null
        firstFrameDeadline = FirstFrameDeadline(mainHandler) { firstFrameTimedOut() }
        viewport?.setTrackpadMode(false)
        trackpadEnabled = false
        trackpadButton?.setText(R.string.enable_trackpad)
        zoomButtons.forEach { it.isEnabled = false }
        if (automaticWanted && hasWindowFocus()) beginAutomaticClipboard()
        val size = if (reload != null) desiredWindowSize() else null
        executor.execute { prepareTarget(established, target, reload, size) }
    }

    private fun chooseDisplay(established: AuthenticatedHostConnection, displays: List<DisplayInfo>) {
        val labels = displays.mapIndexed { index, display ->
            getString(R.string.screen_option, index + 1, display.name, display.width, display.height)
        }.toTypedArray()
        targetDialog = AlertDialog.Builder(this).setTitle(R.string.select_screen).setItems(labels) { _, index ->
            targetDialog = null
            selected = Selection.Display(displays[index])
            if (active) executor.execute { startMedia(established, displays[index].id) }
        }.setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun chooseWindow(established: AuthenticatedHostConnection, windows: List<WindowInfo>) {
        targetDialog = WindowPickerDialog.show(this, windows, onChoose = { window ->
            targetDialog = null
            selected = Selection.Window(window)
            currentWindowId = window.id
            val size = desiredWindowSize()
            if (active) executor.execute { startMedia(established, null, window.id, size) }
        }, onCancel = { finish() }, onActions = if (!controlMode) null else { window ->
            windowActionDialog = AlertDialog.Builder(this).setTitle(R.string.window_action_title)
                .setItems(arrayOf(getString(R.string.window_minimize), getString(R.string.window_restore), getString(R.string.window_close))) { _, index ->
                    performWindowAction(established, window, WindowAction.entries[index])
                }.setNegativeButton(R.string.cancel, null).create().also { dialog ->
                    dialog.setOnDismissListener { if (windowActionDialog === dialog) windowActionDialog = null }
                    dialog.show()
                }
        }, onRefresh = {
            targetDialog = null
            if (active) executor.execute { prepareTarget(established, Target.WINDOW) }
        })
    }

    private fun performWindowAction(established: AuthenticatedHostConnection, window: WindowInfo, action: WindowAction) {
        val dialog = targetDialog
        dialog?.listView?.isEnabled = false
        executor.execute {
            val result = runCatching { established.performWindowAction(window.id, action) }
            runOnUiThread {
                if (active && targetDialog === dialog) {
                    dialog?.listView?.isEnabled = true
                    if (result.isFailure) {
                        stopSession(failed = true)
                        status.setText(R.string.window_selection_failed)
                    } else {
                        Toast.makeText(this, if (result.getOrDefault(false)) R.string.window_action_sent else R.string.window_action_failed, Toast.LENGTH_SHORT).show()
                        targetDialog = null
                        dialog?.dismiss()
                        executor.execute { prepareTarget(established, Target.WINDOW) }
                    }
                }
            }
        }
    }

    private fun chooseApplication(established: AuthenticatedHostConnection, applications: List<ApplicationInfo>) {
        val cancelled = AtomicBoolean(false)
        lateinit var picker: ApplicationPickerDialog
        picker = ApplicationPickerDialog(this, applications, onChoose = { application ->
            cancelled.set(true)
            targetDialog = null
            selected = Selection.Application(application)
            if (active) executor.execute { startMedia(established, null, applicationId = application.id) }
        }, onCancel = { cancelled.set(true); finish() }, onIconRequested = { application ->
            if (active && !cancelled.get()) executor.execute {
                if (active && !cancelled.get()) {
                    val bytes = try { established.applicationIcon(application.id) } catch (_: Exception) {
                        if (!cancelled.get()) showFailure()
                        return@execute
                    }
                    if (bytes.isEmpty()) return@execute
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth !in 1..64 || options.outHeight !in 1..64) return@execute
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                    runOnUiThread { if (active && !cancelled.get() && targetDialog === picker.dialog) picker.updateIcon(application.id, bitmap) }
                }
            }
        })
        targetDialog = picker.dialog
        picker.dialog.setOnDismissListener {
            cancelled.set(true)
            if (targetDialog === picker.dialog) targetDialog = null
        }
        picker.show()
    }

    private fun startMedia(established: AuthenticatedHostConnection, displayId: Long?, windowId: Long? = null,
                           size: WindowSize? = null, applicationId: Long? = null) {
        if (!isCurrent(established)) return
        if (!reconnecting) showProgress(R.string.screen_waiting)
        try {
            established.requestMedia(displayId, windowId, control = controlMode, windowSize = size, applicationId = applicationId)
            val offer = established.receiveOffer()
            runOnUiThread {
                if (!isCurrent(established)) return@runOnUiThread
                try {
                    if (controlMode) configureControlFeatures()
                    var owner: AndroidMediaSession? = null
                    val session = AndroidMediaSession(applicationContext, object : AndroidMediaSession.Listener {
                        override fun onAnswer(sdp: String) {
                            if (!isCurrent(established)) return
                            executor.execute {
                                try {
                                    if (!isCurrent(established)) return@execute
                                    established.sendAnswer(sdp)
                                    if (established.awaitClosure()) runOnUiThread { finishTargetSwitch(established) }
                                    else connectionLost(established)
                                } catch (error: Exception) { signalingFailed(established, error) }
                            }
                        }
                        override fun onCaptureReady(revision: Long) {
                            if (switching || !isCurrent(established)) return
                            captureRevision = revision
                            firstFrameDeadline.start()
                            updateControlAvailability()
                        }
                        override fun onVideo(track: VideoTrack?) {
                            if ((isCurrent(established) || (track == null && media === owner)) && (!switching || track == null)) renderer?.show(track)
                        }
                        override fun onFailure() { if (isCurrent(established) && !switching) stopSession(failed = true) }
                        override fun onTransportFailure() { if (!switching) connectionLost(established) }
                        override fun onTextDeliveryUnconfirmed() {
                            if (!isCurrent(established)) return
                            textDeliveryUnconfirmed = true
                            status.setText(R.string.text_delivery_unconfirmed)
                        }
                        override fun onClipboardResponse(response: ClipboardResponse) { if (isCurrent(established)) handleClipboardResponse(response) }
                    }, requireNotNull(renderer).eglContext)
                    owner = session
                    media = session
                    session.acceptOffer(offer)
                } catch (_: Exception) { stopSession(failed = true) }
            }
        } catch (error: Exception) { signalingFailed(established, error) }
    }

    private fun isCurrent(established: AuthenticatedHostConnection): Boolean =
        synchronized(lock) { active && connection === established }

    private fun signalingFailed(established: AuthenticatedHostConnection, error: Exception) {
        if (error is EOFException || error is SocketException || error is SocketTimeoutException ||
            (error is SSLException && error !is SSLHandshakeException)) connectionLost(established)
        else runOnUiThread { if (isCurrent(established)) stopSession(failed = true) }
    }

    private fun firstFrameTimedOut() {
        val current = synchronized(lock) { connection }
        if (reconnecting && current != null) connectionLost(current)
        else if (active) stopSession(failed = true)
    }

    private fun connectionLost(established: AuthenticatedHostConnection) {
        runOnUiThread {
            if (!isCurrent(established)) return@runOnUiThread
            if (switching || selected == null || (!firstFrameShown && !reconnecting) || media?.hasUnconfirmedText() == true) {
                stopSession(failed = true)
                return@runOnUiThread
            }
            val retryDelay = if (reconnecting) 8_000L else 1_000L
            if (!reconnecting) reconnectedSize = if (selected is Selection.Window) desiredWindowSize() else null
            reconnecting = true
            setFullscreen(false)
            rendererGeneration++
            firstFrameShown = false
            lastVideoTapSent = false
            captureRevision = null
            firstFrameDeadline.finish()
            keyboard?.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
            viewport?.setTrackpadMode(false)
            trackpadEnabled = false
            trackpadButton?.setText(R.string.enable_trackpad)
            automaticWanted = false
            suspendAutomaticClipboard()
            automaticSwitch?.isChecked = false
            clipboardAction = null
            removeClipboardListener()
            mainHandler.removeCallbacks(resizeForLayout)
            runCatching { media?.close() }
            media = null
            synchronized(lock) { if (connection === established) connection = null }
            runCatching { established.close() }
            try { renderer?.reset() } catch (_: Exception) { stopSession(failed = true); return@runOnUiThread }
            firstFrameDeadline = FirstFrameDeadline(mainHandler) { firstFrameTimedOut() }
            updateControlAvailability()
            status.setText(if (controlMode) R.string.screen_reconnecting_control else R.string.screen_reconnecting)
            mainHandler.removeCallbacks(retryConnection)
            mainHandler.postDelayed(retryConnection, retryDelay)
        }
    }

    private fun reconnect() {
        val host = sessionHost ?: return stopSession(failed = true)
        val pairing = host.pairing ?: return stopSession(failed = true)
        val target = selected ?: return stopSession(failed = true)
        val size = reconnectedSize
        if (!active || !reconnecting) return
        executor.execute {
            try {
                val established = AuthenticatedHostConnection.reconnect(host.address, host.port, hexBytes(pairing.fingerprint),
                    UUID.fromString(pairing.deviceID), DeviceSigningKey(pairing.keyAlias))
                val keep = synchronized(lock) {
                    if (!active || !reconnecting || connection != null) false else { connection = established; true }
                }
                if (!keep) { established.close(); return@execute }
                if (!established.canResume()) {
                    runOnUiThread { if (isCurrent(established)) { stopSession(failed = false); status.setText(R.string.reconnect_stopped) } }
                    return@execute
                }
                val available = when (target) {
                    Selection.Main -> true
                    is Selection.Display -> established.listDisplays().any { it.id == target.info.id }
                    is Selection.Application -> established.listApplications().any { it.id == target.info.id && it.name == target.info.name }
                    is Selection.Window -> established.listWindows().any { it.id == target.info.id && it.application == target.info.application }
                }
                if (!available) {
                    runOnUiThread { if (isCurrent(established)) { stopSession(failed = true); status.setText(R.string.reconnect_target_unavailable) } }
                    return@execute
                }
                when (target) {
                    Selection.Main -> startMedia(established, null)
                    is Selection.Display -> startMedia(established, target.info.id)
                    is Selection.Application -> startMedia(established, null, applicationId = target.info.id)
                    is Selection.Window -> startMedia(established, null, windowId = target.info.id, size = size)
                }
            } catch (error: Exception) {
                val current = synchronized(lock) { connection }
                if (current != null) signalingFailed(current, error)
                else runOnUiThread {
                    if (active && reconnecting) {
                        if (error is SSLHandshakeException || error is SecurityException) stopSession(failed = true)
                        else {
                            status.setText(if (controlMode) R.string.screen_reconnecting_control else R.string.screen_reconnecting)
                            mainHandler.postDelayed(retryConnection, 8_000)
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!automaticWanted) return
        if (hasFocus) beginAutomaticClipboard() else suspendAutomaticClipboard()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleWindowResize()
    }

    private fun scheduleWindowResize() {
        mainHandler.removeCallbacks(resizeForLayout)
        if (showingWindowNote && windowResizeSwitch?.isChecked == true) mainHandler.postDelayed(resizeForLayout, 500)
    }

    override fun onStop() {
        stopSession(failed = false)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        switchExecutor.shutdownNow()
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
        keyboardButton?.isEnabled = available
        trackpadButton?.isEnabled = available && viewport?.supportsTrackpad() == true
        trackpadSettingsButton?.isEnabled = trackpadButton?.isEnabled == true && trackpadEnabled
        val clipboardAvailable = available && controlMode && clipboardAction == null
        clipboardSendButton?.isEnabled = clipboardAvailable
        clipboardReceiveButton?.isEnabled = clipboardAvailable
        statisticsButton?.isEnabled = active && firstFrameShown
        switchButton?.isEnabled = available && !switching
        windowResizeSwitch?.isEnabled = available && showingWindowNote && !switching
        fullscreenButton?.isEnabled = active && firstFrameShown
        automaticSwitch?.isEnabled = available && controlMode
        specialKeys?.setAvailable(available)
    }

    private fun setFullscreen(value: Boolean) {
        if (value && (!active || !firstFrameShown)) return
        if (fullscreen == value) return
        fullscreen = value
        fullscreenBack.isEnabled = value
        header.visibility = if (value) View.GONE else View.VISIBLE
        status.visibility = if (value) View.GONE else View.VISIBLE
        windowNote?.visibility = if (!value && showingWindowNote) View.VISIBLE else View.GONE
        applicationNote?.visibility = if (!value && showingApplicationNote) View.VISIBLE else View.GONE
        controlsScroll.visibility = if (value) View.GONE else View.VISIBLE
        exitFullscreenButton?.visibility = if (value) View.VISIBLE else View.GONE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (value) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        scheduleWindowResize()
    }

    private fun sendRemoteKey(key: SpecialKey, modifiers: Int): Boolean {
        val revision = captureRevision ?: return false
        if (!active || !firstFrameShown) return false
        val sequence = controlSequence.next()
        val sent = sequence != null && media?.sendKey(KeyboardKey(sequence, revision, key, modifiers)) == true
        if (!sent && active) stopSession(failed = true)
        return sent
    }

    private fun requestClipboard(get: Boolean) {
        if (!active || !hasWindowFocus() || clipboardAction != null) return
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
        descriptions.add(getString(R.string.control_double))
        descriptions.add(getString(R.string.control_long_press))
        descriptions.add(getString(R.string.control_drag))
        descriptions.add(getString(R.string.control_scroll))
        descriptions.add(getString(R.string.control_hover))
        descriptions.add(getString(R.string.control_zoom))
        viewport?.contentDescription = descriptions.joinToString("。", postfix = "。")
        viewport?.onVideoHover = { x, y ->
            if (active && firstFrameShown) {
                val location = viewport?.mapTap(x, y, captureRevision, tapMapper)
                if (location != null) {
                    val move = PointerMove(location.sequence, location.captureRevision, location.x, location.y)
                    if (media?.sendMove(move) != true) stopSession(failed = true)
                }
            }
        }
        viewport?.onVideoDrag = { phase, x, y ->
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
        setFullscreen(false)
        reconnecting = false
        mainHandler.removeCallbacks(retryConnection)
        textDeliveryUnconfirmed = textDeliveryUnconfirmed || media?.hasUnconfirmedText() == true
        firstFrameDeadline.finish()
        val current = synchronized(lock) {
            active = false
            val old = connection
            connection = null
            old
        }
        switching = false
        nextTarget = null
        reloadWindowId = null
        currentWindowId = null
        mainHandler.removeCallbacks(resizeForLayout)
        runCatching { current?.close() }
        targetDialog?.dismiss()
        targetDialog = null
        windowActionDialog?.dismiss()
        windowActionDialog = null
        settingsDialog?.dismiss()
        settingsDialog = null
        statisticsDialog?.dismiss()
        statisticsDialog = null
        runCatching { media?.close() }
        media = null
        captureRevision = null
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
        switchButton?.isEnabled = false
        windowResizeSwitch?.isEnabled = false
        keyboardButton?.isEnabled = false
        specialKeys?.setAvailable(false)
        keyboard?.close()
        keyboard = null
        zoomButtons.forEach { it.isEnabled = false }
        runCatching { renderer?.close() }
        renderer = null
        if (::status.isInitialized) status.setText(
            if (textDeliveryUnconfirmed) R.string.text_delivery_unconfirmed else if (!failed) R.string.screen_closed
            else if (firstFrameShown) R.string.screen_disconnected else R.string.screen_failed
        )
    }

    private fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HOST_ID = "me.mallows.komado.host_id"
        const val CHOOSE_DISPLAY = "me.mallows.komado.choose_display"
        const val CHOOSE_WINDOW = "me.mallows.komado.choose_window"
        const val CHOOSE_APPLICATION = "me.mallows.komado.choose_application"
        const val CONTROL_MODE = "me.mallows.komado.control_mode"
    }
}
