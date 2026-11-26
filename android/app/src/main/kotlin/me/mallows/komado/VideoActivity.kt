package me.mallows.komado

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import me.mallows.komado.network.AuthenticatedHostConnection
import me.mallows.komado.security.DeviceSigningKey
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack
import java.util.UUID
import java.util.concurrent.Executors

class VideoActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var active = true
    private var connection: AuthenticatedHostConnection? = null
    private var media: AndroidMediaSession? = null
    private var renderer: RemoteVideoRenderer? = null
    private var captureRevision: Long? = null
    private var firstFrameShown = false
    private lateinit var status: TextView

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
                            status.setText(R.string.screen_connected)
                        }
                    }
                }
                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
            })
            renderer = video
            root.addView(video.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } catch (_: Exception) { active = false; status.setText(R.string.screen_failed) }
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
                established.requestMedia()
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
                            override fun onCaptureReady(revision: Long) { captureRevision = revision }
                            override fun onVideo(track: VideoTrack?) { renderer?.show(track) }
                            override fun onFailure() { stopSession(failed = true) }
                        })
                        media = session
                        session.acceptOffer(offer)
                    } catch (_: Exception) { stopSession(failed = true) }
                }
            } catch (_: Exception) { showFailure() }
        }
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
        val current = synchronized(lock) {
            active = false
            val old = connection
            connection = null
            old
        }
        runCatching { current?.close() }
        runCatching { media?.close() }
        media = null
        captureRevision = null
        runCatching { renderer?.close() }
        renderer = null
        if (::status.isInitialized) status.setText(
            if (!failed) R.string.screen_closed else if (firstFrameShown) R.string.screen_disconnected else R.string.screen_failed
        )
    }

    private fun hexBytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val HOST_ID = "me.mallows.komado.host_id" }
}
