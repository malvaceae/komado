package me.mallows.komado.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer

/** Standalone probe: does not establish a network connection or accept remote input. */
class MainActivity : Activity() {
    private var eglBase: EglBase? = null
    private var renderer: SurfaceViewRenderer? = null
    private var factory: PeerConnectionFactory? = null
    private var receiverProbe: ReceiverProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { setText(R.string.initializing) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
        }
        setContentView(container)

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create().also { eglBase = it }
            val view = SurfaceViewRenderer(this).also { renderer = it }
            container.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, 1f))
            view.init(egl.eglBaseContext, null)
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            if (intent.getBooleanExtra("exchangeProbe", false)) {
                receiverProbe = ReceiverProbe(this, requireNotNull(factory), view).also { it.start() }
            }
            status.setText(R.string.initialized)
            Log.i("KomadoProbe", "WebRTC and renderer initialized")
        } catch (error: Exception) {
            status.setText(R.string.initialization_failed)
            Log.e("KomadoProbe", "WebRTC initialization failed", error)
        } catch (error: LinkageError) {
            status.setText(R.string.library_load_failed)
            Log.e("KomadoProbe", "WebRTC library load failed", error)
        }
    }

    override fun onDestroy() {
        receiverProbe?.close()
        renderer?.release()
        factory?.dispose()
        eglBase?.release()
        super.onDestroy()
    }
}
