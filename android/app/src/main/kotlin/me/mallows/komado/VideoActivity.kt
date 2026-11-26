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
import me.mallows.komado.security.DeviceSigningKey
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executors

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
    private var firstFrameShown = false
    private lateinit var status: TextView
    private val firstFrameDeadline = FirstFrameDeadline(mainHandler) { if (active) stopSession(failed = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = runCatching { HostStore(applicationContext).load().firstOrNull { it.id == intent.getStringExtra(HOST_ID) } }.getOrNull()
        val pairing = host?.pairing
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = host?.name.orEmpty(); textSize = 20f; setPadding(dp(16), dp(12), dp(16), 0) })
        status = TextView(this).apply { setText(R.string.screen_waiting); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        root.addView(status)
        try {
            val video = RemoteVideoRenderer(this, object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    runOnUiThread {
                        if (active) {
                            firstFrameShown = true
                            firstFrameDeadline.finish()
                            status.setText(R.string.screen_connected)
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
                if (intent.getBooleanExtra(CHOOSE_WINDOW, false)) {
                    val windows = try { established.listWindows() } catch (_: Exception) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.window_selection_failed) } }
                        return@execute
                    }
                    runOnUiThread {
                        if (active) {
                            if (windows.isEmpty()) { stopSession(failed = true); status.setText(R.string.no_windows) }
                            else chooseWindow(established, windows)
                        }
                    }
                } else if (intent.getBooleanExtra(CHOOSE_DISPLAY, false)) {
                    val displays = try { established.listDisplays() } catch (_: Exception) {
                        runOnUiThread { if (active) { stopSession(failed = true); status.setText(R.string.screen_selection_failed) } }
                        return@execute
                    }
                    runOnUiThread { if (active) chooseDisplay(established, displays) }
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
        val labels = windows.map { getString(R.string.window_option, it.application, it.title) }.toTypedArray()
        targetDialog = AlertDialog.Builder(this).setTitle(R.string.select_window).setItems(labels) { _, index ->
            targetDialog = null
            if (active) executor.execute { startMedia(established, null, windows[index].id) }
        }.setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun startMedia(established: AuthenticatedHostConnection, displayId: Long?, windowId: Long? = null) {
        try {
            established.requestMedia(displayId, windowId)
            val offer = established.receiveOffer()
            runOnUiThread {
                if (!active) return@runOnUiThread
                try {
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
    }
}
