package me.mallows.komado.network

import me.mallows.komado.protocol.SignalFrame
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.SignalMessageCodec
import me.mallows.komado.protocol.SdpCandidateFilter
import me.mallows.komado.protocol.DisplayInfo
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.security.DeviceSigningKey
import me.mallows.komado.security.HostCertificateTrustManager
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/** A host-authenticated TLS connection whose device handshake has succeeded. */
class AuthenticatedHostConnection private constructor(
    private val socket: SSLSocket,
    val deviceID: UUID,
) : Closeable {
    val localAddress: InetAddress get() = socket.localAddress
    private enum class MediaState { IDLE, EXPECTING_OFFER, OFFER_RECEIVED, ANSWER_SENT }
    private val mediaLock = Any()
    private var mediaState = MediaState.IDLE
    private var displaysRequested = false
    private var windowsRequested = false
    @Volatile private var closed = false

    /** Request one media session after device authentication. */
    fun requestMedia(displayId: Long? = null, windowId: Long? = null, control: Boolean = false) = synchronized(mediaLock) {
        if (closed || mediaState != MediaState.IDLE) throw IOException("Unexpected signaling state")
        try {
            if ((displayId != null && windowId != null) || (control && (displayId != null || windowId != null))) {
                throw IOException("Conflicting capture targets")
            }
            val request = when {
                control -> SignalMessage.StartControl
                displayId != null -> SignalMessage.StartDisplay(displayId)
                windowId != null -> SignalMessage.StartWindow(windowId)
                else -> SignalMessage.StartMedia
            }
            send(socket, request)
            mediaState = MediaState.EXPECTING_OFFER
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    /** Fetches one bounded snapshot in pages; the total wait is limited. */
    fun listWindows(): List<WindowInfo> = synchronized(mediaLock) {
        if (closed || mediaState != MediaState.IDLE || windowsRequested) throw IOException("Unexpected signaling state")
        windowsRequested = true
        val timeout = timeouts.schedule({ runCatching { close() } }, HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            val windows = mutableListOf<WindowInfo>()
            val ids = mutableSetOf<Long>()
            var offset = 0
            do {
                send(socket, SignalMessage.ListWindows(offset))
                val page = receive(socket) as? SignalMessage.Windows ?: throw IOException("Expected window list")
                if (page.values.any { !ids.add(it.id) }) throw IOException("Duplicate window")
                windows.addAll(page.values)
                if (windows.size > 512) throw IOException("Window list too large")
                if (page.next != 0 && (page.values.size != 32 || page.next != windows.size || page.next >= 512)) {
                    throw IOException("Invalid window pagination")
                }
                offset = page.next
            } while (offset != 0)
            windows
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        } finally { timeout.cancel(false) }
    }

    /** Queries the authenticated host once before starting media. */
    fun listDisplays(): List<DisplayInfo> = synchronized(mediaLock) {
        if (closed || mediaState != MediaState.IDLE || displaysRequested) throw IOException("Unexpected signaling state")
        try {
            displaysRequested = true
            send(socket, SignalMessage.ListDisplays)
            (receive(socket) as? SignalMessage.Displays)?.values ?: throw IOException("Expected display list")
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    /** Waits for the authenticated host's SDP offer. Call from a background thread. */
    fun receiveOffer(): String = synchronized(mediaLock) {
        if (closed || mediaState != MediaState.EXPECTING_OFFER) throw IOException("Unexpected signaling state")
        try {
            val offer = receive(socket) as? SignalMessage.Offer ?: throw IOException("Expected SDP offer")
            mediaState = MediaState.OFFER_RECEIVED
            offer.sdp
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    /** Sends one SDP answer to the authenticated host. */
    fun sendAnswer(sdp: String) = synchronized(mediaLock) {
        if (closed || mediaState != MediaState.OFFER_RECEIVED) throw IOException("Unexpected signaling state")
        try {
            val restricted = SdpCandidateFilter.restrict(sdp, socket.localAddress)
            send(socket, SignalMessage.Answer(restricted))
            mediaState = MediaState.ANSWER_SENT
        } catch (error: Exception) {
            runCatching { close() }
            throw error
        }
    }

    /** Blocks until the host closes the signaling connection. */
    fun awaitClosure() {
        synchronized(mediaLock) {
            if (closed || mediaState != MediaState.ANSWER_SENT) throw IOException("Unexpected signaling state")
            socket.soTimeout = 0
        }
        if (socket.inputStream.read() != -1) throw IOException("Unexpected signaling data")
    }

    override fun close() {
        closed = true
        socket.close()
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000
        private const val HANDSHAKE_TIMEOUT_SECONDS = 30L
        private val timeouts = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "komado-connection-timeouts").apply { isDaemon = true }
        }

        /**
         * Call from a background thread. The fingerprint must be confirmed out
         * of band. Use a fresh key alias for a new pairing; the caller owns its
         * cleanup if pairing fails and its persistence after pairing succeeds.
         */
        fun pair(host: String, port: Int, fingerprint: ByteArray, name: String, invitation: ByteArray,
                 signingKey: DeviceSigningKey): AuthenticatedHostConnection {
            require(invitation.size == 32) { "Invalid invitation" }
            return connect(host, port, fingerprint) { socket ->
                val publicKey = signingKey.getOrCreatePublicKeyDER()
                send(socket, SignalMessage.Pair(name, invitation, publicKey))
                completeHandshake(socket, signingKey)
            }
        }

        /** Call from a background thread with the device ID saved after pairing. */
        fun reconnect(host: String, port: Int, fingerprint: ByteArray, deviceID: UUID,
                      signingKey: DeviceSigningKey): AuthenticatedHostConnection = connect(host, port, fingerprint) { socket ->
            send(socket, SignalMessage.Authenticate(deviceID))
            val acceptedID = completeHandshake(socket, signingKey)
            if (acceptedID != deviceID) throw IOException("Host returned an unexpected device ID")
            acceptedID
        }

        private inline fun connect(host: String, port: Int, fingerprint: ByteArray,
                                   handshake: (SSLSocket) -> UUID): AuthenticatedHostConnection {
            require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() })
            require(port in 1..65535)
            // Construct the trust manager before any network or key operation.
            val trust = HostCertificateTrustManager(fingerprint)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
            val transport = Socket()
            val timeout = timeouts.schedule({ runCatching { transport.close() } }, HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            try {
                transport.connect(InetSocketAddress(host, port), TIMEOUT_MILLIS)
                transport.soTimeout = TIMEOUT_MILLIS
                val socket = context.socketFactory.createSocket(transport, host, port, true) as SSLSocket
                try {
                    socket.soTimeout = TIMEOUT_MILLIS
                    val protocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
                    if (protocols.isEmpty()) throw IOException("TLS 1.2 or later is unavailable")
                    socket.enabledProtocols = protocols.toTypedArray()
                    // No invitation, public key, or signature is sent before this succeeds.
                    socket.startHandshake()
                    val deviceID = handshake(socket)
                    if (transport.isClosed) throw IOException("Authentication timed out")
                    socket.soTimeout = 30_000
                    return AuthenticatedHostConnection(socket, deviceID)
                } catch (error: Exception) {
                    runCatching { socket.close() }
                    throw error
                }
            } catch (error: Exception) {
                runCatching { transport.close() }
                throw error
            } finally {
                timeout.cancel(false)
            }
        }

        private fun completeHandshake(socket: SSLSocket, signingKey: DeviceSigningKey): UUID {
            val challenge = receive(socket) as? SignalMessage.Challenge ?: throw IOException("Expected authentication challenge")
            send(socket, SignalMessage.Proof(signingKey.signChallenge(challenge.value)))
            val accepted = receive(socket) as? SignalMessage.Accepted ?: throw IOException("Authentication rejected")
            return accepted.deviceID
        }

        private fun send(socket: SSLSocket, message: SignalMessage) {
            val frame = SignalFrame.encode(SignalMessageCodec.encode(message))
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }

        private fun receive(socket: SSLSocket): SignalMessage {
            val header = readExact(socket.inputStream, 4)
            val length = header.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
            if (length !in 1..SignalFrame.MAXIMUM_PAYLOAD.toLong()) throw IOException("Invalid signaling frame")
            val payload = readExact(socket.inputStream, length.toInt())
            return SignalMessageCodec.decode(payload)
        }

        private fun readExact(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < bytes.size) {
                val received = input.read(bytes, offset, bytes.size - offset)
                if (received <= 0) throw EOFException("Signaling connection closed")
                offset += received
            }
            return bytes
        }
    }
}
