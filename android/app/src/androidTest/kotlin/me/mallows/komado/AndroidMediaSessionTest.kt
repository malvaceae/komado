package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SdpCandidateFilter
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec
import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.KeyboardTextCodec
import me.mallows.komado.protocol.KeyboardKey
import me.mallows.komado.protocol.KeyboardKeyCodec
import me.mallows.komado.protocol.SpecialKey
import me.mallows.komado.protocol.PointerScroll
import me.mallows.komado.protocol.PointerScrollCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidMediaSessionTest {
    @Test fun rejectsUnexpectedAudioBeforeAnswering() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val factory = MediaFactory.create(instrumentation.targetContext)
        val sender = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), SenderObserver(CountDownLatch(1)))!!
        val failed = CountDownLatch(1)
        val answer = CompletableFuture<String>()
        var receiver: AndroidMediaSession? = null
        try {
            assertTrue(sender.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) != null)
            val offer = CompletableFuture<SessionDescription>()
            sender.createOffer(DescriptionObserver(created = offer), MediaConstraints())
            val sdp = offer.get(10, TimeUnit.SECONDS).description
            assertTrue(sdp.contains("m=audio "))
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, InetAddress.getByName("127.0.0.1"),
                    object : AndroidMediaSession.Listener {
                        override fun onAnswer(sdp: String) { answer.complete(sdp) }
                        override fun onCaptureReady(revision: Long) = Unit
                        override fun onVideo(track: VideoTrack?) = Unit
                        override fun onFailure() { failed.countDown() }
                    })
                receiver!!.acceptOffer(sdp)
            }
            assertTrue("Unexpected audio did not close the session", failed.await(10, TimeUnit.SECONDS))
            assertTrue("Unexpected audio must not receive an answer", !answer.isDone)
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
            sender.close()
            sender.dispose()
            factory.dispose()
        }
    }

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
        val clickReceived = CompletableFuture<PointerClick>()
        val textReceived = CompletableFuture<KeyboardText>()
        val keyReceived = CompletableFuture<KeyboardKey>()
        val scrollReceived = CompletableFuture<PointerScroll>()
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val click = runCatching { PointerClickCodec.decode(bytes) }.getOrNull()
                if (click != null) clickReceived.complete(click)
                else {
                    val text = runCatching { KeyboardTextCodec.decode(bytes) }.getOrNull()
                    if (text != null) textReceived.complete(text)
                    else {
                        val key = runCatching { KeyboardKeyCodec.decode(bytes) }.getOrNull()
                        if (key != null) keyReceived.complete(key)
                        else runCatching { PointerScrollCodec.decode(bytes) }.onSuccess { scrollReceived.complete(it) }
                            .onFailure { scrollReceived.completeExceptionally(it) }
                    }
                }
            }
        })
        val answer = CompletableFuture<String>()
        val remoteVideo = CompletableFuture<VideoTrack>()
        val frames = CountDownLatch(1)
        val captureReady = CountDownLatch(1)
        val failed = CountDownLatch(1)
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
                    override fun onCaptureReady(revision: Long) { if (revision == 1L) captureReady.countDown() }
                    override fun onVideo(track: VideoTrack?) {
                        attached?.removeSink(sink)
                        attached = track
                        track?.addSink(sink)
                        if (track != null) remoteVideo.complete(track)
                    }
                    override fun onFailure() {
                        failed.countDown()
                        answer.completeExceptionally(IllegalStateException("Media session failed"))
                        remoteVideo.completeExceptionally(IllegalStateException("Media session failed"))
                    }
                })
                assertTrue(!receiver.sendClick(PointerClick(1, 1, 500_000, 500_000, PointerClick.Button.LEFT)))
                receiver.acceptOffer(restrictedOffer)
            }
            val restrictedAnswer = SdpCandidateFilter.restrict(answer.get(20, TimeUnit.SECONDS), address)
            val setRemote = CompletableFuture<Unit>()
            sender.setRemoteDescription(DescriptionObserver(set = setRemote), SessionDescription(SessionDescription.Type.ANSWER, restrictedAnswer))
            setRemote.get(10, TimeUnit.SECONDS)
            remoteVideo.get(20, TimeUnit.SECONDS)
            val channelDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (channel.state() != DataChannel.State.OPEN && System.nanoTime() < channelDeadline) Thread.sleep(50)
            assertTrue("Control channel did not open", channel.state() == DataChannel.State.OPEN)
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap("[\"1\",\"capture.ready\",\"1\"]".toByteArray()), false)))
            assertTrue("Capture revision was not received", captureReady.await(5, TimeUnit.SECONDS))
            val click = PointerClick(1, 1, 500_000, 500_000, PointerClick.Button.LEFT)
            val text = KeyboardText(2, 1, "日本語")
            val key = KeyboardKey(3, 1, SpecialKey.ESCAPE, 1)
            val scroll = PointerScroll(4, 1, 500_000, 500_000, 3, -12)
            instrumentation.runOnMainSync {
                val current = requireNotNull(receiver)
                assertTrue(!current.sendClick(click.copy(captureRevision = 2)))
                assertTrue(current.sendClick(click))
                assertTrue(!current.sendClick(click))
                assertTrue(!current.sendText(text.copy(captureRevision = 2)))
                assertTrue(current.sendText(text))
                assertTrue(!current.sendText(text))
                assertTrue(!current.sendKey(key.copy(captureRevision = 2)))
                assertTrue(current.sendKey(key))
                assertTrue(!current.sendKey(key))
                assertTrue(current.sendScroll(scroll))
                assertTrue(!current.sendScroll(scroll))
            }
            assertTrue(clickReceived.get(5, TimeUnit.SECONDS) == click)
            assertTrue(textReceived.get(5, TimeUnit.SECONDS) == text)
            assertTrue(keyReceived.get(5, TimeUnit.SECONDS) == key)
            assertTrue(scrollReceived.get(5, TimeUnit.SECONDS) == scroll)
            source.capturerObserver.onCapturerStarted(true)
            for (i in 0 until 50) {
                val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                if (frames.await(100, TimeUnit.MILLISECONDS)) break
            }
            assertTrue("No frame received", frames.await(5, TimeUnit.SECONDS))
            source.capturerObserver.onCapturerStopped()
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap("[\"1\",\"capture.ready\",\"1\"]".toByteArray()), false)))
            assertTrue("Duplicate capture notification was not rejected", failed.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
            channel.unregisterObserver()
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
                        override fun onCaptureReady(revision: Long) = throw AssertionError("Unexpected capture")
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

    @Test fun alteredDtlsFingerprintCannotOpenControlChannel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = DatagramSocket().use {
            it.connect(InetAddress.getByName("192.0.2.1"), 9)
            it.localAddress
        }
        val egl = EglBase.create()
        val factory = MediaFactory.create(instrumentation.targetContext, egl.eglBaseContext)
        val gathered = CountDownLatch(1)
        val connectionFailed = CountDownLatch(1)
        val sender = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()),
            SenderObserver(gathered, connectionFailed))!!
        val channel = sender.createDataChannel("control", DataChannel.Init())!!
        val answer = CompletableFuture<String>()
        val receiverFailed = CountDownLatch(1)
        var receiver: AndroidMediaSession? = null
        try {
            val offerFuture = CompletableFuture<SessionDescription>()
            sender.createOffer(DescriptionObserver(created = offerFuture), MediaConstraints())
            val setLocal = CompletableFuture<Unit>()
            sender.setLocalDescription(DescriptionObserver(set = setLocal), offerFuture.get(10, TimeUnit.SECONDS))
            setLocal.get(10, TimeUnit.SECONDS)
            assertTrue(gathered.await(10, TimeUnit.SECONDS))
            val offer = SdpCandidateFilter.restrict(sender.localDescription.description, address)
            val fingerprint = Regex("(?m)^a=fingerprint:sha-256 ([0-9A-F]{2})")
            assertTrue("No DTLS fingerprint in generated offer", fingerprint.containsMatchIn(offer))
            val alteredOffer = fingerprint.replace(offer) { match ->
                val firstByte = match.groupValues[1]
                match.value.dropLast(2) + if (firstByte == "00") "01" else "00"
            }
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, address, object : AndroidMediaSession.Listener {
                    override fun onAnswer(sdp: String) { answer.complete(sdp) }
                    override fun onCaptureReady(revision: Long) = Unit
                    override fun onVideo(track: VideoTrack?) = Unit
                    override fun onFailure() {
                        receiverFailed.countDown()
                        answer.completeExceptionally(IllegalStateException("Media session failed"))
                    }
                })
                receiver.acceptOffer(alteredOffer)
            }
            val restrictedAnswer = SdpCandidateFilter.restrict(answer.get(20, TimeUnit.SECONDS), address)
            val setRemote = CompletableFuture<Unit>()
            sender.setRemoteDescription(DescriptionObserver(set = setRemote), SessionDescription(SessionDescription.Type.ANSWER, restrictedAnswer))
            setRemote.get(10, TimeUnit.SECONDS)
            assertTrue("DTLS mismatch did not fail connection", connectionFailed.await(20, TimeUnit.SECONDS))
            assertTrue("Control channel opened with altered fingerprint", channel.state() != DataChannel.State.OPEN)
            assertTrue("Receiver did not report failed connection", receiverFailed.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
            channel.dispose()
            sender.close()
            sender.dispose()
            factory.dispose()
            egl.release()
        }
    }

    @Test fun unansweredMediaConnectionTimesOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = DatagramSocket().use {
            it.connect(InetAddress.getByName("192.0.2.1"), 9)
            it.localAddress
        }
        val egl = EglBase.create()
        val factory = MediaFactory.create(instrumentation.targetContext, egl.eglBaseContext)
        val gathered = CountDownLatch(1)
        val sender = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), SenderObserver(gathered))!!
        val channel = sender.createDataChannel("control", DataChannel.Init())!!
        val answer = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var receiver: AndroidMediaSession? = null
        try {
            val offerFuture = CompletableFuture<SessionDescription>()
            sender.createOffer(DescriptionObserver(created = offerFuture), MediaConstraints())
            val setLocal = CompletableFuture<Unit>()
            sender.setLocalDescription(DescriptionObserver(set = setLocal), offerFuture.get(10, TimeUnit.SECONDS))
            setLocal.get(10, TimeUnit.SECONDS)
            assertTrue(gathered.await(10, TimeUnit.SECONDS))
            val offer = SdpCandidateFilter.restrict(sender.localDescription.description, address)
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, address, object : AndroidMediaSession.Listener {
                    override fun onAnswer(sdp: String) { answer.countDown() }
                    override fun onCaptureReady(revision: Long) = Unit
                    override fun onVideo(track: VideoTrack?) = Unit
                    override fun onFailure() { failed.countDown() }
                })
                receiver.acceptOffer(offer)
            }
            assertTrue("Answer was not generated", answer.await(10, TimeUnit.SECONDS))
            assertTrue("Stalled media connection was not closed", failed.await(25, TimeUnit.SECONDS))
            assertTrue(channel.state() != DataChannel.State.OPEN)
        } finally {
            instrumentation.runOnMainSync { receiver?.close() }
            channel.dispose()
            sender.close()
            sender.dispose()
            factory.dispose()
            egl.release()
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

    private class SenderObserver(private val gathered: CountDownLatch, private val failed: CountDownLatch? = null) : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (state == PeerConnection.PeerConnectionState.FAILED) failed?.countDown()
        }
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
