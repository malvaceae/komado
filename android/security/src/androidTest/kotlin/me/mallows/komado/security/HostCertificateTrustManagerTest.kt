package me.mallows.komado.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

@RunWith(AndroidJUnit4::class)
class HostCertificateTrustManagerTest {
    private val alias = "me.mallows.komado.test.tls.${UUID.randomUUID()}"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun cleanup() { store.deleteEntry(alias) }

    @Test fun tlsAcceptsPinnedCertificateAndRejectsDifferentFingerprint() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=komado test"))
            .build())
        generator.generateKeyPair()
        val cert = store.getCertificate(alias) as X509Certificate
        val key = store.getKey(alias, null) as PrivateKey
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val trust = HostCertificateTrustManager(fingerprint)
        val original = fingerprint.copyOf()
        fingerprint[0] = (fingerprint[0].toInt() xor 1).toByte()

        assertEquals(7, exchange(cert, key, trust))
        assertThrows(SSLHandshakeException::class.java) {
            exchange(cert, key, HostCertificateTrustManager(fingerprint))
        }
        assertArrayEquals(original, MessageDigest.getInstance("SHA-256").digest(cert.encoded))
    }

    @Test fun rejectsInvalidPinLength() {
        assertThrows(IllegalArgumentException::class.java) { HostCertificateTrustManager(ByteArray(31)) }
    }

    private fun exchange(cert: X509Certificate, key: PrivateKey, trust: HostCertificateTrustManager): Int {
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) = alias
            override fun getCertificateChain(requestedAlias: String?) = if (requestedAlias == alias) arrayOf(cert) else null
            override fun getPrivateKey(requestedAlias: String?) = if (requestedAlias == alias) key else null
        }
        val serverContext = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager), null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), null) }
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = serverContext.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket
        server.soTimeout = 10_000
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit<Int> {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                socket.startHandshake()
                socket.outputStream.write(7)
                socket.outputStream.flush()
                7
            }
        }
        try {
            val value = try {
                (clientContext.socketFactory.createSocket(loopback, server.localPort) as SSLSocket).use { client ->
                    client.soTimeout = 10_000
                    client.startHandshake()
                    client.inputStream.read()
                }
            } catch (error: Exception) {
                if (task.isDone) {
                    try { task.get() } catch (serverError: Exception) { error.addSuppressed(serverError) }
                }
                throw error
            }
            assertEquals(7, task.get(10, TimeUnit.SECONDS))
            return value
        } finally {
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
