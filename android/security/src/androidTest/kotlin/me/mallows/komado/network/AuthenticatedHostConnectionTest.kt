package me.mallows.komado.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.SignalFrame
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.SignalMessageCodec
import me.mallows.komado.protocol.SdpCandidateFilter
import me.mallows.komado.security.DeviceSigningKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

@RunWith(AndroidJUnit4::class)
class AuthenticatedHostConnectionTest {
    private val serverAlias = "me.mallows.komado.test.server.${UUID.randomUUID()}"
    private val deviceAlias = "me.mallows.komado.test.device.${UUID.randomUUID()}"
    private val key = DeviceSigningKey(deviceAlias)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanup() {
        key.delete()
        store.deleteEntry(serverAlias)
    }

    @Test fun pairAndReconnectOverTls() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val id = UUID.randomUUID()
            val token = ByteArray(32) { it.toByte() }
            val task = executor.submit {
                var registeredKey: ByteArray? = null
                repeat(2) { index ->
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 10_000
                        socket.startHandshake()
                        val request = receive(socket)
                        if (index == 0) {
                            val pair = request as SignalMessage.Pair
                            assertEquals("Android test", pair.name)
                            assertTrue(pair.invitation.contentEquals(token))
                            registeredKey = pair.publicKeyDER
                        } else {
                            assertEquals(id, (request as SignalMessage.Authenticate).deviceID)
                        }
                        val challenge = ByteArray(32) { (it + index).toByte() }
                        send(socket, SignalMessage.Challenge(challenge))
                        val proof = receive(socket) as SignalMessage.Proof
                        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(registeredKey!!))
                        val verified = Signature.getInstance("SHA256withECDSA").run {
                            initVerify(publicKey)
                            update("komado.device-auth.v1\u0000".toByteArray(Charsets.UTF_8))
                            update(challenge)
                            verify(proof.signatureDER)
                        }
                        assertTrue(verified)
                        send(socket, SignalMessage.Accepted(id))
                        assertEquals(SignalMessage.StartMedia, receive(socket))
                        if (index == 0) {
                            send(socket, SignalMessage.Offer("v=0\r\ns=offer\r\n"))
                            val expected = "v=0\r\ns=answer\r\nc=IN IP4 127.0.0.1\r\n" +
                                "a=candidate:1 1 udp 1 127.0.0.1 54321 typ host\r\n"
                            assertEquals(expected, (receive(socket) as SignalMessage.Answer).sdp)
                        } else {
                            send(socket, SignalMessage.Offer("v=0\r\ns=offer\r\n"))
                            assertThrows(java.io.EOFException::class.java) { receive(socket) }
                        }
                    }
                }
            }
            try {
                val paired = AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", token, key)
                assertEquals(id, paired.deviceID)
                assertThrows(IOException::class.java) { paired.sendAnswer("v=0\r\ns=answer\r\n") }
                assertThrows(IOException::class.java) { paired.receiveOffer() }
                paired.requestMedia()
                assertThrows(IOException::class.java) { paired.requestMedia() }
                assertEquals("v=0\r\ns=offer\r\n", paired.receiveOffer())
                paired.sendAnswer("v=0\r\ns=answer\r\nc=IN IP4 192.0.2.5\r\n" +
                    "a=candidate:1 1 udp 1 127.0.0.1 54321 typ host\r\n" +
                    "a=candidate:2 1 udp 1 192.0.2.5 54322 typ host\r\n")
                assertThrows(IOException::class.java) { paired.sendAnswer("v=0\r\ns=answer\r\n") }
                paired.awaitClosure()
                paired.close()
                val reconnected = AuthenticatedHostConnection.reconnect("127.0.0.1", server.localPort, fingerprint, id, key)
                assertEquals(id, reconnected.deviceID)
                reconnected.requestMedia()
                reconnected.receiveOffer()
                assertThrows(SdpCandidateFilter.FilterException::class.java) {
                    reconnected.sendAnswer("v=0\r\na=candidate:2 1 udp 1 192.0.2.5 54322 typ host\r\n")
                }
                assertThrows(IOException::class.java) { reconnected.receiveOffer() }
                reconnected.close()
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun mismatchedPinIsRejectedBeforeDeviceKeyCreation() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit {
                try { (server.accept() as SSLSocket).use { it.soTimeout = 10_000; it.startHandshake() } }
                catch (_: SSLHandshakeException) { }
            }
            val wrong = fingerprint.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            try {
                assertThrows(SSLHandshakeException::class.java) {
                    AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, wrong, "Android test", ByteArray(32), key)
                }
                assertFalse(store.containsAlias(deviceAlias))
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun withServer(action: (SSLServerSocket, ByteArray) -> Unit) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(serverAlias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=komado test"))
            .build())
        generator.generateKeyPair()
        val cert = store.getCertificate(serverAlias) as X509Certificate
        val privateKey = store.getKey(serverAlias, null) as PrivateKey
        val manager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(serverAlias)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = serverAlias
            override fun getCertificateChain(alias: String?) = if (alias == serverAlias) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?) = if (alias == serverAlias) privateKey else null
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, null) }
        val server = context.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.soTimeout = 10_000
        try { action(server, MessageDigest.getInstance("SHA-256").digest(cert.encoded)) }
        finally { server.close() }
    }

    private fun receive(socket: SSLSocket): SignalMessage {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..SignalFrame.MAXIMUM_PAYLOAD)
        val payload = ByteArray(length)
        input.readFully(payload)
        return SignalMessageCodec.decode(payload)
    }

    private fun send(socket: SSLSocket, message: SignalMessage) {
        socket.outputStream.write(SignalFrame.encode(SignalMessageCodec.encode(message)))
        socket.outputStream.flush()
    }
}
