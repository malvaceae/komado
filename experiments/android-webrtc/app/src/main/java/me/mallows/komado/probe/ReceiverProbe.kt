package me.mallows.komado.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Test-only receiver. Signaling is exchanged through the app-private directory via adb. */
class ReceiverProbe(
    context: Context,
    private val factory: PeerConnectionFactory,
    private val renderer: SurfaceViewRenderer,
) : PeerConnection.Observer {
    private val files = context.filesDir
    private val handler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val trackAttached = AtomicBoolean(false)
    private val frames = AtomicInteger(0)
    private val messageReceived = AtomicBoolean(false)
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null
    private var videoTrack: VideoTrack? = null
    private var videoSink: VideoSink? = null
    private var answerSet = false
    private var answerWritten = false

    fun start() {
        listOf("offer.json", "answer.json", "result.json").forEach { File(files, it).delete() }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peer = factory.createPeerConnection(config, this) ?: run {
            fail("peer creation failed")
            return
        }
        awaitOffer()
    }

    private fun awaitOffer() {
        if (finished.get()) return
        val offer = File(files, "offer.json")
        if (!offer.exists()) {
            handler.postDelayed({ awaitOffer() }, 200)
            return
        }
        try {
            val sdp = JSONObject(offer.readText()).getString("sdp")
            offer.delete()
            peer?.setRemoteDescription(DescriptionObserver(onSet = {
                peer?.createAnswer(DescriptionObserver(onCreate = { answer ->
                    peer?.setLocalDescription(DescriptionObserver(onSet = {
                        synchronized(this) {
                            answerSet = true
                            writeAnswerIfReady()
                        }
                    }), answer)
                }), MediaConstraints())
            }), SessionDescription(SessionDescription.Type.OFFER, sdp))
        } catch (error: Exception) {
            fail("invalid offer", error)
        }
    }

    private fun writeAnswerIfReady() {
        val connection = peer ?: return
        if (!answerSet || answerWritten || connection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return
        val sdp = connection.localDescription?.description ?: return
        writeJson("answer.json", JSONObject().put("sdp", sdp))
        answerWritten = true
    }

    private fun writeJson(name: String, json: JSONObject) {
        val temp = File(files, "$name.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(files, name))) { "Cannot write probe result" }
    }

    private fun maybeFinish() {
        if (frames.get() >= 10 && messageReceived.get() && finished.compareAndSet(false, true)) {
            val connection = peer ?: return
            handler.post {
                connection.getStats { report ->
                    val inbound = report.statsMap.values.firstOrNull {
                        it.type == "inbound-rtp" && it.members["kind"] == "video"
                    }
                    val codec = (inbound?.members?.get("codecId") as? String)
                        ?.let { report.statsMap[it]?.members?.get("mimeType") as? String }
                        ?: "unknown"
                    writeJson("result.json", JSONObject().put("success", true).put("frames", frames.get()).put("codec", codec))
                    Log.i("KomadoProbe", "Synthetic video and data channel received")
                }
            }
        }
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (finished.compareAndSet(false, true)) {
            writeJson("result.json", JSONObject().put("success", false).put("error", message))
            Log.e("KomadoProbe", message, error)
        }
    }

    private inner class DescriptionObserver(
        private val onCreate: (SessionDescription) -> Unit = {},
        private val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onCreate(description)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String) = fail("SDP creation failed")
        override fun onSetFailure(error: String) = fail("SDP setting failed")
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        synchronized(this) { writeAnswerIfReady() }
    }
    override fun onDataChannel(dataChannel: DataChannel) {
        channel = dataChannel
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                if (!buffer.binary && bytes.contentEquals("komado-probe".toByteArray())) {
                    val sent = dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap("komado-ack".toByteArray()), false))
                    if (sent) { messageReceived.set(true); maybeFinish() } else fail("data response failed")
                }
            }
        })
    }
    override fun onTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver.track() as? VideoTrack ?: return
        if (trackAttached.compareAndSet(false, true)) {
            val sink = VideoSink { frame ->
                renderer.onFrame(frame)
                frames.incrementAndGet()
                maybeFinish()
            }
            videoTrack = track
            videoSink = sink
            track.addSink(sink)
        }
    }
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        if (state == PeerConnection.IceConnectionState.FAILED) fail("ICE connection failed")
    }
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}

    fun close() {
        finished.set(true)
        handler.removeCallbacksAndMessages(null)
        videoSink?.let { videoTrack?.removeSink(it) }
        channel?.unregisterObserver()
        channel?.dispose()
        peer?.close()
        peer?.dispose()
    }
}
