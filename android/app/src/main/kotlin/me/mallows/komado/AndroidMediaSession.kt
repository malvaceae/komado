package me.mallows.komado

import android.content.Context
import android.os.Handler
import android.os.Looper
import me.mallows.komado.protocol.SdpCandidateFilter
import me.mallows.komado.protocol.CaptureReadyCodec
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec
import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.KeyboardTextCodec
import me.mallows.komado.protocol.KeyboardTextPostedCodec
import me.mallows.komado.protocol.KeyboardKey
import me.mallows.komado.protocol.KeyboardKeyCodec
import me.mallows.komado.protocol.ClipboardCodec
import me.mallows.komado.protocol.ClipboardRequest
import me.mallows.komado.protocol.ClipboardResponse
import me.mallows.komado.protocol.ClipboardResult
import me.mallows.komado.protocol.ClipboardOperation
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.AudioTrack
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
import java.util.ArrayDeque

/** One receiving peer; callbacks and lifetime management use the main thread. */
internal class AndroidMediaSession(
    context: Context,
    private val signalingAddress: InetAddress,
    private val listener: Listener,
    private val confirmText: Boolean = false,
) : PeerConnection.Observer, AutoCloseable {
    interface Listener {
        fun onAnswer(sdp: String)
        fun onCaptureReady(revision: Long)
        /** Detach synchronously when the track is null, before the peer is disposed. */
        fun onVideo(track: VideoTrack?)
        fun onFailure()
        fun onTextDeliveryUnconfirmed() = Unit
        fun onClipboardResponse(response: ClipboardResponse) = Unit
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
    private var pendingMove: me.mallows.komado.protocol.PointerMove? = null
    private val retryMove = Runnable { flushMove() }
    private enum class ClipboardExpected { GET, PREPARE, SET, AUTO_START, AUTO_POLL, AUTO_PREPARE, AUTO_SET }
    private data class PendingClipboard(val sequence: Long, val expected: ClipboardExpected)
    private var pendingClipboard: PendingClipboard? = null
    private val clipboardTimeout = Runnable {
        val pending = pendingClipboard
        pendingClipboard = null
        if (pending != null) listener.onClipboardResponse(ClipboardResponse(pending.sequence, ClipboardResult.Unavailable))
    }
    private val pendingText = ArrayDeque<Long>()
    private val textTimeout = Runnable { fail() }

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
        try {
            peer.setAudioPlayout(false)
            peer.setAudioRecording(false)
        } catch (error: Throwable) {
            peer.close()
            peer.dispose()
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
        // Gathering is complete, but the remote peer might never finish DTLS or
        // announce a capture. Bound that phase independently as well.
        handler.postDelayed(timeout, 20_000)
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

    fun requestVideoStatistics(callback: (VideoStatistics) -> Unit): Boolean {
        checkMainThread()
        if (state != State.ANSWER_READY || !selectedRouteAllowed) return false
        return try {
            peer.getStats { report ->
                val snapshot = VideoStatistics.from(report.statsMap)
                postIfOpen { callback(snapshot) }
            }
            true
        } catch (_: RuntimeException) { false }
    }

    /** The UI must separately authorize input and map it to the current capture. */
    fun sendClick(click: PointerClick): Boolean {
        return sendInput(click.sequence, click.captureRevision) { PointerClickCodec.encode(click) }
    }

    fun sendText(text: KeyboardText): Boolean {
        checkMainThread()
        if (confirmText && pendingText.size >= 64) return false
        val outgoing = text.copy(confirm = confirmText)
        if (!sendInput(outgoing.sequence, outgoing.captureRevision) { KeyboardTextCodec.encode(outgoing) }) return false
        if (confirmText) {
            pendingText.addLast(outgoing.sequence)
            if (pendingText.size == 1) handler.postDelayed(textTimeout, 10_000)
        }
        return true
    }

    fun hasUnconfirmedText(): Boolean {
        checkMainThread()
        return pendingText.isNotEmpty()
    }

    private fun receiveTextPosted(data: ByteArray): Boolean {
        val sequence = try { KeyboardTextPostedCodec.decode(data) } catch (_: Exception) { return false }
        if (!confirmText || pendingText.peekFirst() != sequence) { fail(); return true }
        pendingText.removeFirst()
        handler.removeCallbacks(textTimeout)
        if (pendingText.isNotEmpty()) handler.postDelayed(textTimeout, 10_000)
        return true
    }

    fun sendKey(key: KeyboardKey): Boolean {
        return sendInput(key.sequence, key.captureRevision) { KeyboardKeyCodec.encode(key) }
    }

    fun sendScroll(scroll: me.mallows.komado.protocol.PointerScroll): Boolean {
        return sendInput(scroll.sequence, scroll.captureRevision) { me.mallows.komado.protocol.PointerScrollCodec.encode(scroll) }
    }

    fun sendDrag(drag: me.mallows.komado.protocol.PointerDrag): Boolean {
        return sendInput(drag.sequence, drag.captureRevision) { me.mallows.komado.protocol.PointerDragCodec.encode(drag) }
    }

    fun sendClipboard(request: ClipboardRequest): Boolean {
        checkMainThread()
        if (pendingClipboard != null) return false
        if (!sendInput(request.sequence, request.captureRevision) { ClipboardCodec.encodeRequest(request) }) return false
        val expected = when (request.operation) {
            ClipboardOperation.Get -> ClipboardExpected.GET
            ClipboardOperation.Prepare -> ClipboardExpected.PREPARE
            is ClipboardOperation.Set -> ClipboardExpected.SET
            ClipboardOperation.AutoStart -> ClipboardExpected.AUTO_START
            ClipboardOperation.AutoPoll -> ClipboardExpected.AUTO_POLL
            ClipboardOperation.AutoPrepare -> ClipboardExpected.AUTO_PREPARE
            is ClipboardOperation.AutoSet -> ClipboardExpected.AUTO_SET
        }
        pendingClipboard = PendingClipboard(request.sequence, expected)
        handler.postDelayed(clipboardTimeout, 10_000)
        return true
    }

    private fun receiveClipboard(data: ByteArray) {
        val response = try { ClipboardCodec.decodeResponse(data) } catch (_: Exception) { fail(); return }
        val pending = pendingClipboard ?: return
        if (response.sequence != pending.sequence) return
        val valid = when (response.result) {
            ClipboardResult.Unavailable, ClipboardResult.Busy -> true
            ClipboardResult.Ok -> pending.expected == ClipboardExpected.SET || pending.expected == ClipboardExpected.AUTO_SET
            ClipboardResult.Ready -> pending.expected == ClipboardExpected.PREPARE || pending.expected == ClipboardExpected.AUTO_PREPARE
            ClipboardResult.Started -> pending.expected == ClipboardExpected.AUTO_START
            ClipboardResult.Unchanged -> pending.expected == ClipboardExpected.AUTO_POLL
            is ClipboardResult.Text -> pending.expected == ClipboardExpected.GET || pending.expected == ClipboardExpected.AUTO_POLL
        }
        if (!valid) { fail(); return }
        pendingClipboard = null
        handler.removeCallbacks(clipboardTimeout)
        listener.onClipboardResponse(response)
    }

    /** Coalesce hover updates while the reliable channel is congested. */
    fun sendMove(move: me.mallows.komado.protocol.PointerMove): Boolean {
        checkMainThread()
        if (state != State.ANSWER_READY || captureRevision != move.captureRevision || move.sequence <= lastSentSequence ||
            (pendingMove != null && move.sequence <= pendingMove!!.sequence) || !selectedRouteAllowed) return false
        val current = channel ?: return false
        if (current.state() != DataChannel.State.OPEN || runCatching { me.mallows.komado.protocol.PointerMoveCodec.encode(move) }.isFailure) return false
        if (current.bufferedAmount() >= 65_536) {
            if (pendingMove == null || move.sequence > pendingMove!!.sequence) pendingMove = move
            handler.removeCallbacks(retryMove)
            handler.postDelayed(retryMove, 50)
            return true
        }
        pendingMove = null
        handler.removeCallbacks(retryMove)
        return sendInput(move.sequence, move.captureRevision) { me.mallows.komado.protocol.PointerMoveCodec.encode(move) }
    }

    private fun flushMove() {
        val move = pendingMove ?: return
        if (move.sequence <= lastSentSequence) { pendingMove = null; return }
        val current = channel
        if (state != State.ANSWER_READY || captureRevision != move.captureRevision || !selectedRouteAllowed ||
            current == null || current.state() != DataChannel.State.OPEN) { pendingMove = null; fail(); return }
        if (current.bufferedAmount() >= 65_536) { handler.postDelayed(retryMove, 50); return }
        pendingMove = null
        if (!sendInput(move.sequence, move.captureRevision) { me.mallows.komado.protocol.PointerMoveCodec.encode(move) }) fail()
    }

    private fun sendInput(sequence: Long, revision: Long, encode: () -> ByteArray): Boolean {
        checkMainThread()
        if (state != State.ANSWER_READY || captureRevision != revision || sequence <= lastSentSequence || !selectedRouteAllowed) return false
        val current = channel ?: return false
        if (current.state() != DataChannel.State.OPEN) return false
        val data = try { encode() } catch (_: Exception) { return false }
        return current.send(DataChannel.Buffer(ByteBuffer.wrap(data), false)).also { if (it) lastSentSequence = sequence }
    }

    private fun fail() {
        if (state == State.CLOSED) return
        val textUnconfirmed = pendingText.isNotEmpty()
        close()
        listener.onFailure()
        if (textUnconfirmed) listener.onTextDeliveryUnconfirmed()
    }

    override fun close() {
        checkMainThread()
        if (state == State.CLOSED) return
        state = State.CLOSED
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(retryMove)
        handler.removeCallbacks(clipboardTimeout)
        handler.removeCallbacks(textTimeout)
        pendingMove = null
        pendingClipboard = null
        pendingText.clear()
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
        val received = transceiver.receiver.track()
        if (received is AudioTrack) {
            runCatching { received.setEnabled(false) }
            postIfOpen { fail() }
            return
        }
        val track = received as? VideoTrack ?: return
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
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
        if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.DISCONNECTED) postIfOpen { fail() }
    }
    override fun onDataChannel(dataChannel: DataChannel) {
        handler.post {
            if (state == State.CLOSED) dataChannel.dispose()
            else if (channel != null || dataChannel.label() != "control") {
                dataChannel.dispose()
                fail()
            } else {
                channel = dataChannel
                fun checkChannelState() {
                    postIfOpen {
                        if (channel !== dataChannel) return@postIfOpen
                        val currentState = dataChannel.state()
                        if (currentState == DataChannel.State.CLOSING || currentState == DataChannel.State.CLOSED) fail()
                    }
                }
                dataChannel.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() = checkChannelState()
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary || buffer.data.remaining() !in 1..ClipboardCodec.MAX_MESSAGE_BYTES) {
                            postIfOpen { fail() }
                            return
                        }
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        postIfOpen {
                            if (captureRevision == null) {
                                val revision = try { CaptureReadyCodec.decode(data) } catch (_: Exception) { fail(); return@postIfOpen }
                                captureRevision = revision
                                handler.removeCallbacks(timeout)
                                listener.onCaptureReady(revision)
                            } else if (!receiveTextPosted(data)) receiveClipboard(data)
                        }
                    }
                })
                checkChannelState()
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
