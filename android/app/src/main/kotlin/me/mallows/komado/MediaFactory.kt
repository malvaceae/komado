package me.mallows.komado

import android.content.Context
import org.webrtc.PeerConnectionFactory

/** Initialize WebRTC only when the app needs a media session. */
internal object MediaFactory {
    fun create(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }
}
