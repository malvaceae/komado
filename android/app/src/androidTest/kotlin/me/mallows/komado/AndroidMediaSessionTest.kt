package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SdpCandidateFilter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidMediaSessionTest {
    @Test fun exchangesDescriptionsAndReceivesGeneratedVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = DatagramSocket().use {
            it.connect(InetAddress.getByName("192.0.2.1"), 9)
            it.localAddress
        }
        val egl = EglBase.create()
        val factory = MediaFactory.create(instrumentation.targetContext, egl.eglBaseContext)
        val gathered = CountDownLatch(1)
        val sender = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), SenderObserver(gathered))!!
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("test-screen", source)
        val channel = sender.createDataChannel("control", DataChannel.Init())!!
        val answer = CompletableFuture<String>()
        val remoteVideo = CompletableFuture<VideoTrack>()
        val frames = CountDownLatch(1)
        val sink = VideoSink { frames.countDown() }
        var attached: VideoTrack? = null
        var receiver: AndroidMediaSession? = null
        try {
            check(sender.addTrack(track, listOf("test")) != null)
            val offerFuture = CompletableFuture<SessionDescription>()
            sender.createOffer(DescriptionObserver(created = offerFuture), MediaConstraints())
            val offer = offerFuture.get(10, TimeUnit.SECONDS)
            val setLocal = CompletableFuture<Unit>()
            sender.setLocalDescription(DescriptionObserver(set = setLocal), offer)
            setLocal.get(10, TimeUnit.SECONDS)
            assertTrue("Sender ICE gathering timed out", gathered.await(10, TimeUnit.SECONDS))
            val restrictedOffer = SdpCandidateFilter.restrict(sender.localDescription.description, address)
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, address, object : AndroidMediaSession.Listener {
                    override fun onAnswer(sdp: String) { answer.complete(sdp) }
                    override fun onVideo(track: VideoTrack?) {
                        attached?.removeSink(sink)
                        attached = track
                        track?.addSink(sink)
                        if (track != null) remoteVideo.complete(track)
                    }
                    override fun onFailure() {
                        answer.completeExceptionally(IllegalStateException("Media session failed"))
                        remoteVideo.completeExceptionally(IllegalStateException("Media session failed"))
                    }
                })
                receiver.acceptOffer(restrictedOffer)
            }
            val restrictedAnswer = SdpCandidateFilter.restrict(answer.get(20, TimeUnit.SECONDS), address)
            val setRemote = CompletableFuture<Unit>()
            sender.setRemoteDescription(DescriptionObserver(set = setRemote), SessionDescription(SessionDescription.Type.ANSWER, restrictedAnswer))
            setRemote.get(10, TimeUnit.SECONDS)
            remoteVideo.get(20, TimeUnit.SECONDS)
            source.capturerObserver.onCapturerStarted(true)
            for (i in 0 until 50) {
                val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                if (frames.await(100, TimeUnit.MILLISECONDS)) break
            }
            assertTrue("No frame received", frames.await(5, TimeUnit.SECONDS))
            source.capturerObserver.onCapturerStopped()
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
            channel.dispose()
            sender.close()
            sender.dispose()
            track.dispose()
            source.dispose()
            factory.dispose()
            egl.release()
        }
    }

    @Test fun malformedOfferFailsAndClosesSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failed = CountDownLatch(1)
        var receiver: AndroidMediaSession? = null
        try {
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, InetAddress.getByName("127.0.0.1"),
                    object : AndroidMediaSession.Listener {
                        override fun onAnswer(sdp: String) = throw AssertionError("Unexpected answer")
                        override fun onVideo(track: VideoTrack?) = throw AssertionError("Unexpected video")
                        override fun onFailure() { failed.countDown() }
                    })
                receiver.acceptOffer("invalid")
            }
            assertTrue("Invalid offer was not rejected", failed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
        }
    }

    private class DescriptionObserver(
        private val created: CompletableFuture<SessionDescription>? = null,
        private val set: CompletableFuture<Unit>? = null,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) { created?.complete(description) }
        override fun onSetSuccess() { set?.complete(Unit) }
        override fun onCreateFailure(error: String) { created?.completeExceptionally(IllegalStateException("Offer failed")) }
        override fun onSetFailure(error: String) { set?.completeExceptionally(IllegalStateException("Description failed")) }
    }

    private class SenderObserver(private val gathered: CountDownLatch) : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }
}
