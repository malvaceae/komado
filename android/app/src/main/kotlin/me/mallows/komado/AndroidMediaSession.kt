package me.mallows.komado

import android.content.Context
import android.os.Handler
import android.os.Looper
import me.mallows.komado.protocol.SdpCandidateFilter
import me.mallows.komado.protocol.CaptureReadyCodec
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.net.InetAddress
import java.nio.ByteBuffer

/** One receiving peer; callbacks and lifetime management use the main thread. */
internal class AndroidMediaSession(
    context: Context,
    private val signalingAddress: InetAddress,
    private val listener: Listener,
) : PeerConnection.Observer, AutoCloseable {
    interface Listener {
        fun onAnswer(sdp: String)
        fun onCaptureReady(revision: Long)
        /** Detach synchronously when the track is null, before the peer is disposed. */
        fun onVideo(track: VideoTrack?)
        fun onFailure()
    }

    private enum class State { IDLE, NEGOTIATING, ANSWER_READY, CLOSED }
    private val handler = Handler(Looper.getMainLooper())
    private val egl: EglBase
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var state = State.IDLE
    private var localSet = false
    private var selectedRouteAllowed = false
    private var remoteTrack: VideoTrack? = null
    private var trackShown = false
    private var captureRevision: Long? = null
    private var lastSentSequence = 0L
    private var channel: DataChannel? = null
    private val timeout = Runnable { fail() }

    init {
        checkMainThread()
        egl = EglBase.create()
        factory = try { MediaFactory.create(context, egl.eglBaseContext) } catch (error: Throwable) {
            egl.release()
            throw error
        }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = try { factory.createPeerConnection(config, this) ?: throw IllegalStateException("Could not create media session") }
        catch (error: Throwable) {
            factory.dispose()
            egl.release()
            throw error
        }
    }

    fun acceptOffer(sdp: String) {
        checkMainThread()
        check(state == State.IDLE)
        state = State.NEGOTIATING
        handler.postDelayed(timeout, 20_000)
        try {
            peer.setRemoteDescription(observer(onSet = {
                peer.createAnswer(observer(onCreate = { answer ->
                    peer.setLocalDescription(observer(onSet = {
                        localSet = true
                        answerIfReady()
                    }), answer)
                }), MediaConstraints())
            }), SessionDescription(SessionDescription.Type.OFFER, sdp))
        } catch (_: RuntimeException) { fail() }
    }

    private fun answerIfReady() {
        if (state != State.NEGOTIATING || !localSet || peer.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return
        val answer = peer.localDescription?.description ?: return fail()
        state = State.ANSWER_READY
        handler.removeCallbacks(timeout)
        listener.onAnswer(answer)
    }

    private fun observer(onCreate: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = postIfOpen { onCreate(description) }
        override fun onSetSuccess() = postIfOpen { onSet() }
        override fun onCreateFailure(error: String) = postIfOpen { fail() }
        override fun onSetFailure(error: String) = postIfOpen { fail() }
    }

    private fun postIfOpen(action: () -> Unit) {
        handler.post {
            if (state != State.CLOSED) {
                try { action() } catch (_: RuntimeException) { fail() }
            }
        }
    }

    private fun showTrackIfReady() {
        val track = remoteTrack ?: return
        if (selectedRouteAllowed && !trackShown) {
            trackShown = true
            listener.onVideo(track)
        }
    }

    /** The UI must separately authorize input and map it to the current capture. */
    fun sendClick(click: PointerClick): Boolean {
        checkMainThread()
        if (state != State.ANSWER_READY || captureRevision != click.captureRevision ||
            click.sequence <= lastSentSequence || !selectedRouteAllowed) return false
        val current = channel ?: return false
        if (current.state() != DataChannel.State.OPEN) return false
        val data = try { PointerClickCodec.encode(click) } catch (_: Exception) { return false }
        return current.send(DataChannel.Buffer(ByteBuffer.wrap(data), false)).also { if (it) lastSentSequence = click.sequence }
    }

    private fun fail() {
        if (state == State.CLOSED) return
        close()
        listener.onFailure()
    }

    override fun close() {
        checkMainThread()
        if (state == State.CLOSED) return
        state = State.CLOSED
        handler.removeCallbacks(timeout)
        try {
            if (trackShown) listener.onVideo(null)
        } finally {
            trackShown = false
            remoteTrack = null
            channel?.unregisterObserver()
            channel?.dispose()
            channel = null
            peer.close()
            peer.dispose()
            factory.dispose()
            egl.release()
        }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
        val allowed = SdpCandidateFilter.permitsSelectedLocalCandidate(event.local.sdp, signalingAddress)
        postIfOpen {
            if (!allowed) fail() else {
                selectedRouteAllowed = true
                showTrackIfReady()
            }
        }
    }
    override fun onTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track() as? VideoTrack ?: return
        postIfOpen {
            if (remoteTrack == null) {
                remoteTrack = track
                showTrackIfReady()
            } else if (remoteTrack !== track) fail()
        }
    }
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = postIfOpen { answerIfReady() }
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) postIfOpen { fail() }
    }
    override fun onDataChannel(dataChannel: DataChannel) {
        handler.post {
            if (state == State.CLOSED) dataChannel.dispose()
            else if (channel != null || dataChannel.label() != "control") {
                dataChannel.dispose()
                fail()
            } else {
                channel = dataChannel
                dataChannel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() = Unit
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary || buffer.data.remaining() !in 1..256) {
                            postIfOpen { fail() }
                            return
                        }
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        postIfOpen {
                            if (captureRevision != null) fail() else {
                                val revision = try { CaptureReadyCodec.decode(data) } catch (_: Exception) { fail(); return@postIfOpen }
                                captureRevision = revision
                                listener.onCaptureReady(revision)
                            }
                        }
                    }
                })
            }
        }
    }
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    override fun onRemoveTrack(receiver: RtpReceiver) = postIfOpen { fail() }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Media session must be managed on the main thread" }
    }
}
