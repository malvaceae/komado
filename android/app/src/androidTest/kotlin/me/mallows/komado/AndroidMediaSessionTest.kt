package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec
import me.mallows.komado.protocol.KeyboardText
import me.mallows.komado.protocol.KeyboardTextCodec
import me.mallows.komado.protocol.KeyboardTextPostedCodec
import me.mallows.komado.protocol.KeyboardKey
import me.mallows.komado.protocol.KeyboardKeyCodec
import me.mallows.komado.protocol.SpecialKey
import me.mallows.komado.protocol.PointerScroll
import me.mallows.komado.protocol.PointerScrollCodec
import me.mallows.komado.protocol.PointerDrag
import me.mallows.komado.protocol.PointerDragCodec
import me.mallows.komado.protocol.PointerMove
import me.mallows.komado.protocol.PointerMoveCodec
import me.mallows.komado.protocol.ClipboardCodec
import me.mallows.komado.protocol.ClipboardRequest
import me.mallows.komado.protocol.ClipboardResponse
import me.mallows.komado.protocol.ClipboardResult
import me.mallows.komado.protocol.ClipboardOperation
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue

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
                receiver = AndroidMediaSession(instrumentation.targetContext,
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
        exchangeDescriptionsAndGeneratedVideo(confirmText = false)
        exchangeDescriptionsAndGeneratedVideo(confirmText = true)
    }

    @Test fun unconfirmedTextTimesOut() {
        exchangeDescriptionsAndGeneratedVideo(confirmText = true, sendWrongConfirmation = false)
    }

    @Test fun remoteControlChannelClosureReportsUnconfirmedText() {
        exchangeDescriptionsAndGeneratedVideo(confirmText = true, closeControlChannel = true)
    }

    @Test fun localCloseWhileRemoteChannelClosesIsSafe() {
        exchangeDescriptionsAndGeneratedVideo(confirmText = true, closeLocally = true)
    }

    private fun exchangeDescriptionsAndGeneratedVideo(confirmText: Boolean, sendWrongConfirmation: Boolean = true,
                                                       closeControlChannel: Boolean = false, closeLocally: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val egl = EglBase.create()
        val factory = MediaFactory.create(instrumentation.targetContext, egl.eglBaseContext)
        val gathered = CountDownLatch(1)
        val sender = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), SenderObserver(gathered))!!
        val source = factory.createVideoSource(true)
        val track = factory.createVideoTrack("test-screen", source)
        val channel = sender.createDataChannel("control", DataChannel.Init())!!
        val clickReceived = CompletableFuture<PointerClick>()
        val textReceived = CompletableFuture<KeyboardText>()
        val laterTextReceived = CompletableFuture<KeyboardText>()
        val keyReceived = CompletableFuture<KeyboardKey>()
        val navigationReceived = CompletableFuture<KeyboardKey>()
        val punctuationReceived = CompletableFuture<KeyboardKey>()
        val extendedReceived = ConcurrentLinkedQueue<KeyboardKey>()
        val extendedCount = CountDownLatch(2)
        val scrollReceived = CompletableFuture<PointerScroll>()
        val dragsReceived = ConcurrentLinkedQueue<PointerDrag>()
        val dragCount = CountDownLatch(3)
        val moveReceived = CompletableFuture<PointerMove>()
        val clipboardRequests = ConcurrentLinkedQueue<ClipboardRequest>()
        val clipboardGetReceived = CompletableFuture<ClipboardRequest>()
        val clipboardLargeGetReceived = CompletableFuture<ClipboardRequest>()
        val clipboardPrepareReceived = CompletableFuture<ClipboardRequest>()
        val clipboardSetReceived = CompletableFuture<ClipboardRequest>()
        val clipboardGetReply = CompletableFuture<ClipboardResponse>()
        val clipboardPrepareReply = CompletableFuture<ClipboardResponse>()
        val clipboardSetReply = CompletableFuture<ClipboardResponse>()
        val clipboardLargeReply = CompletableFuture<ClipboardResponse>()
        val automaticRequests = LinkedBlockingQueue<ClipboardRequest>()
        val automaticReplies = LinkedBlockingQueue<ClipboardResponse>()
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
                    if (text != null) {
                        if (text.sequence == 21L) laterTextReceived.complete(text) else textReceived.complete(text)
                    }
                    else {
                        val key = runCatching { KeyboardKeyCodec.decode(bytes) }.getOrNull()
                        if (key != null) {
                            if (key.sequence == 17L) navigationReceived.complete(key)
                            else if (key.sequence == 18L) punctuationReceived.complete(key)
                            else if (key.sequence in 19L..20L) { extendedReceived.add(key); extendedCount.countDown() }
                            else keyReceived.complete(key)
                        }
                        else {
                            val scroll = runCatching { PointerScrollCodec.decode(bytes) }.getOrNull()
                            if (scroll != null) scrollReceived.complete(scroll)
                            else {
                                val drag = runCatching { PointerDragCodec.decode(bytes) }.getOrNull()
                                if (drag != null) { dragsReceived.add(drag); dragCount.countDown() }
                                else {
                                    val move = runCatching { PointerMoveCodec.decode(bytes) }.getOrNull()
                                    if (move != null) moveReceived.complete(move) else {
                                        val request = ClipboardCodec.decodeRequest(bytes)
                                        clipboardRequests.add(request)
                                        when (request.operation) {
                                            ClipboardOperation.Get -> if (request.sequence == 12L) clipboardLargeGetReceived.complete(request) else clipboardGetReceived.complete(request)
                                            ClipboardOperation.Prepare -> clipboardPrepareReceived.complete(request)
                                            is ClipboardOperation.Set -> clipboardSetReceived.complete(request)
                                            else -> automaticRequests.add(request)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
        val answer = CompletableFuture<String>()
        val remoteVideo = CompletableFuture<VideoTrack>()
        val frames = CountDownLatch(1)
        val captureReady = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val textUnconfirmed = CountDownLatch(1)
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
            val offerSdp = sender.localDescription.description
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, object : AndroidMediaSession.Listener {
                    override fun onAnswer(sdp: String) { answer.complete(sdp) }
                    override fun onCaptureReady(revision: Long) { if (revision == 1L) captureReady.countDown() }
                    override fun onClipboardResponse(response: ClipboardResponse) {
                        if (response.sequence == 9L) clipboardGetReply.complete(response)
                        if (response.sequence == 10L) clipboardPrepareReply.complete(response)
                        if (response.sequence == 11L) clipboardSetReply.complete(response)
                        if (response.sequence == 12L) clipboardLargeReply.complete(response)
                        if (response.sequence in 13L..16L) automaticReplies.add(response)
                    }
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
                    override fun onTextDeliveryUnconfirmed() { textUnconfirmed.countDown() }
                }, confirmText = confirmText)
                assertTrue(!receiver.sendClick(PointerClick(1, 1, 500_000, 500_000, PointerClick.Button.LEFT)))
                receiver.acceptOffer(offerSdp)
            }
            val answerSdp = answer.get(20, TimeUnit.SECONDS)
            val setRemote = CompletableFuture<Unit>()
            sender.setRemoteDescription(DescriptionObserver(set = setRemote), SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
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
            val drags = listOf(PointerDrag(5, 1, 500_000, 500_000, PointerDrag.Phase.BEGIN),
                PointerDrag(6, 1, 600_000, 500_000, PointerDrag.Phase.MOVE),
                PointerDrag(7, 1, 600_000, 500_000, PointerDrag.Phase.END))
            val move = PointerMove(8, 1, 400_000, 300_000)
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
                drags.forEach { assertTrue(current.sendDrag(it)) }
                assertTrue(!current.sendDrag(drags.last()))
                assertTrue(current.sendMove(move))
                assertTrue(!current.sendMove(move))
            }
            assertTrue(clickReceived.get(5, TimeUnit.SECONDS) == click)
            assertEquals(text.copy(confirm = confirmText), textReceived.get(5, TimeUnit.SECONDS))
            if (confirmText) {
                instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).hasUnconfirmedText()) }
                assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(KeyboardTextPostedCodec.encode(text.sequence)), false)))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var acknowledged = false
                while (!acknowledged && System.nanoTime() < deadline) {
                    instrumentation.runOnMainSync { acknowledged = !requireNotNull(receiver).hasUnconfirmedText() }
                    if (!acknowledged) Thread.sleep(25)
                }
                assertTrue("Text posting confirmation was not accepted", acknowledged)
            }
            assertTrue(keyReceived.get(5, TimeUnit.SECONDS) == key)
            assertTrue(scrollReceived.get(5, TimeUnit.SECONDS) == scroll)
            assertTrue(dragCount.await(5, TimeUnit.SECONDS))
            assertTrue(dragsReceived.toList() == drags)
            assertTrue(moveReceived.get(5, TimeUnit.SECONDS) == move)
            val get = ClipboardRequest(9, 1, ClipboardOperation.Get)
            instrumentation.runOnMainSync {
                assertTrue(requireNotNull(receiver).sendClipboard(get))
                assertTrue(!requireNotNull(receiver).sendClipboard(ClipboardRequest(10, 1, ClipboardOperation.Prepare)))
            }
            assertEquals(get, clipboardGetReceived.get(5, TimeUnit.SECONDS))
            val ignored = ClipboardResponse(99, ClipboardResult.Text("ignored"))
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(ignored)), false)))
            val answerClipboard = ClipboardResponse(9, ClipboardResult.Text("日本語😀"))
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(answerClipboard)), false)))
            assertEquals(answerClipboard, clipboardGetReply.get(5, TimeUnit.SECONDS))
            val prepare = ClipboardRequest(10, 1, ClipboardOperation.Prepare)
            instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendClipboard(prepare)) }
            assertEquals(prepare, clipboardPrepareReceived.get(5, TimeUnit.SECONDS))
            val ready = ClipboardResponse(10, ClipboardResult.Ready)
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(ready)), false)))
            assertEquals(ready, clipboardPrepareReply.get(5, TimeUnit.SECONDS))
            val set = ClipboardRequest(11, 1, ClipboardOperation.Set("sample"))
            instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendClipboard(set)) }
            assertEquals(set, clipboardSetReceived.get(5, TimeUnit.SECONDS))
            val accepted = ClipboardResponse(11, ClipboardResult.Ok)
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(accepted)), false)))
            assertEquals(accepted, clipboardSetReply.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(get, prepare, set), clipboardRequests.toList())
            val largeGet = ClipboardRequest(12, 1, ClipboardOperation.Get)
            instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendClipboard(largeGet)) }
            assertEquals(largeGet, clipboardLargeGetReceived.get(5, TimeUnit.SECONDS))
            val largeReply = ClipboardResponse(12, ClipboardResult.Text("あ".repeat(5_461)))
            assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(largeReply)), false)))
            assertEquals(largeReply, clipboardLargeReply.get(5, TimeUnit.SECONDS))
            val automaticCases = listOf(
                ClipboardOperation.AutoStart to ClipboardResult.Started,
                ClipboardOperation.AutoPoll to ClipboardResult.Unchanged,
                ClipboardOperation.AutoPrepare to ClipboardResult.Ready,
                ClipboardOperation.AutoSet("automatic test") to ClipboardResult.Ok,
            )
            automaticCases.forEachIndexed { index, (operation, result) ->
                val request = ClipboardRequest(13L + index, 1, operation)
                instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendClipboard(request)) }
                assertEquals(request, automaticRequests.poll(5, TimeUnit.SECONDS))
                val response = ClipboardResponse(request.sequence, result)
                assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(ClipboardCodec.encodeResponse(response)), false)))
                assertEquals(response, automaticReplies.poll(5, TimeUnit.SECONDS))
            }
            val home = KeyboardKey(17, 1, SpecialKey.HOME, 8)
            instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendKey(home)) }
            assertEquals(home, navigationReceived.get(5, TimeUnit.SECONDS))
            val comma = KeyboardKey(18, 1, SpecialKey.COMMA, 1)
            instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendKey(comma)) }
            assertEquals(comma, punctuationReceived.get(5, TimeUnit.SECONDS))
            val extended = listOf(KeyboardKey(19, 1, SpecialKey.SPACE, 4), KeyboardKey(20, 1, SpecialKey.MINUS, 1))
            instrumentation.runOnMainSync { extended.forEach { assertTrue(requireNotNull(receiver).sendKey(it)) } }
            assertTrue(extendedCount.await(5, TimeUnit.SECONDS))
            assertEquals(extended, extendedReceived.toList())
            source.capturerObserver.onCapturerStarted(true)
            for (i in 0 until 50) {
                val frame = VideoFrame(JavaI420Buffer.allocate(320, 240), 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                if (frames.await(100, TimeUnit.MILLISECONDS)) break
            }
            assertTrue("No frame received", frames.await(5, TimeUnit.SECONDS))
            if (!closeControlChannel && !closeLocally) {
                val statistics = CompletableFuture<VideoStatistics>()
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(receiver).requestVideoStatistics { statistics.complete(it) })
                }
                val snapshot = statistics.get(10, TimeUnit.SECONDS)
                assertEquals(320, snapshot.width)
                assertEquals(240, snapshot.height)
            }
            source.capturerObserver.onCapturerStopped()
            if (confirmText) {
                val laterText = KeyboardText(21, 1, "sample")
                instrumentation.runOnMainSync { assertTrue(requireNotNull(receiver).sendText(laterText)) }
                assertEquals(laterText.copy(confirm = true), laterTextReceived.get(5, TimeUnit.SECONDS))
                if (closeLocally) {
                    instrumentation.runOnMainSync {
                        channel.close()
                        requireNotNull(receiver).close()
                    }
                    assertTrue("Local close must not report a later failure", !failed.await(500, TimeUnit.MILLISECONDS))
                    assertTrue("Local close must not emit a later confirmation warning", !textUnconfirmed.await(500, TimeUnit.MILLISECONDS))
                    return
                }
                if (closeControlChannel) {
                    channel.close()
                } else if (sendWrongConfirmation) {
                    assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap(KeyboardTextPostedCodec.encode(22)), false)))
                }
                assertTrue("Unconfirmed text did not close the session", failed.await(if (sendWrongConfirmation || closeControlChannel) 5 else 15, TimeUnit.SECONDS))
                assertTrue("Unconfirmed text was not reported", textUnconfirmed.await(5, TimeUnit.SECONDS))
            } else {
                assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap("[\"1\",\"capture.ready\",\"1\"]".toByteArray()), false)))
                assertTrue("Duplicate capture notification was not rejected", failed.await(5, TimeUnit.SECONDS))
            }
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
                receiver = AndroidMediaSession(instrumentation.targetContext,
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
            val offer = sender.localDescription.description
            val fingerprint = Regex("(?m)^a=fingerprint:sha-256 ([0-9A-F]{2})")
            assertTrue("No DTLS fingerprint in generated offer", fingerprint.containsMatchIn(offer))
            val alteredOffer = fingerprint.replace(offer) { match ->
                val firstByte = match.groupValues[1]
                match.value.dropLast(2) + if (firstByte == "00") "01" else "00"
            }
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, object : AndroidMediaSession.Listener {
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
            val answerSdp = answer.get(20, TimeUnit.SECONDS)
            val setRemote = CompletableFuture<Unit>()
            sender.setRemoteDescription(DescriptionObserver(set = setRemote), SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
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
            val offer = sender.localDescription.description
            instrumentation.runOnMainSync {
                receiver = AndroidMediaSession(instrumentation.targetContext, object : AndroidMediaSession.Listener {
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
