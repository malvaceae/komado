package me.mallows.komado

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.mallows.komado.protocol.SignalFrame
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.SignalMessageCodec
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

internal class TestTlsServer : Closeable {
    private val alias = "me.mallows.komado.test.server.${UUID.randomUUID()}"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val listener: SSLServerSocket
    val fingerprint: ByteArray

    init {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=komado test")).build())
        generator.generateKeyPair()
        val certificate = store.getCertificate(alias) as X509Certificate
        val privateKey = store.getKey(alias, null) as PrivateKey
        val manager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun getCertificateChain(candidate: String?) = if (candidate == alias) arrayOf(certificate) else null
            override fun getPrivateKey(candidate: String?) = if (candidate == alias) privateKey else null
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(manager), null, null) }
        listener = context.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        listener.soTimeout = 10_000
        fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    }

    override fun close() {
        listener.close()
        store.deleteEntry(alias)
    }
}

internal fun receiveSignal(socket: SSLSocket): SignalMessage {
    val input = DataInputStream(socket.inputStream)
    val length = input.readInt()
    require(length in 1..SignalFrame.MAXIMUM_PAYLOAD)
    return SignalMessageCodec.decode(ByteArray(length).also { input.readFully(it) })
}

internal fun sendSignal(socket: SSLSocket, message: SignalMessage) {
    socket.outputStream.write(SignalFrame.encode(SignalMessageCodec.encode(message)))
    socket.outputStream.flush()
}
