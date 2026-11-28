package me.mallows.komado

import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SignalFrame
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.SignalMessageCodec
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.security.DeviceSigningKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

@RunWith(AndroidJUnit4::class)
class VideoActivityWindowWatchTest {
    @Test fun updatesTheOpenChooserAndSelectsTheUpdatedWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostID = UUID.randomUUID().toString()
        val deviceID = UUID.randomUUID()
        val deviceAlias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        val serverAlias = "me.mallows.komado.test.server.${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val deviceKey = DeviceSigningKey(deviceAlias)
        val executor = Executors.newSingleThreadExecutor()
        val allowChange = CountDownLatch(1)
        val started = CountDownLatch(1)
        var server: SSLServerSocket? = null
        try {
            deviceKey.getOrCreatePublicKeyDER()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(serverAlias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=komado test")).build())
            generator.generateKeyPair()
            val certificate = store.getCertificate(serverAlias) as X509Certificate
            val privateKey = store.getKey(serverAlias, null) as PrivateKey
            val keyManager = object : X509KeyManager {
                override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
                override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
                override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(serverAlias)
                override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = serverAlias
                override fun getCertificateChain(alias: String?) = if (alias == serverAlias) arrayOf(certificate) else null
                override fun getPrivateKey(alias: String?) = if (alias == serverAlias) privateKey else null
            }
            val tls = SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(keyManager), null, null) }
            server = tls.serverSocketFactory.createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server.soTimeout = 10_000
            val listener = server
            val original = WindowInfo(1, 300, 200, "Editor", "Notes", true)
            val updated = WindowInfo(1, 640, 480, "Editor", "Project", true)
            val task = executor.submit {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    assertEquals(SignalMessage.Authenticate(deviceID), receive(socket))
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(deviceID))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, listOf(original)))
                    assertEquals(SignalMessage.WatchWindows, receive(socket))
                    send(socket, SignalMessage.WindowsWatching)
                    started.countDown()
                    assertTrue(allowChange.await(10, TimeUnit.SECONDS))
                    send(socket, SignalMessage.WindowsChanged)
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, listOf(updated)))
                    assertEquals(SignalMessage.StopWatchingWindows, receive(socket))
                    send(socket, SignalMessage.WindowsWatchStopped)
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, listOf(updated)))
                    assertEquals(SignalMessage.StartWindow(1), receive(socket))
                }
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
            val host = Host(hostID, "Test Mac", "127.0.0.1", listener.localPort)
            val hosts = HostStore(context)
            assertTrue(hosts.save(null, host))
            assertTrue(hosts.pair(host, Pairing(deviceID.toString(), fingerprint, deviceAlias)))
            val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.HOST_ID, hostID)
                .putExtra(VideoActivity.CHOOSE_WINDOW, true)
            ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
                assertTrue(started.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val dialog = dialog(activity)
                    val search = find(dialog.window!!.decorView, EditText::class.java)!!
                    search.setText("project")
                    assertEquals(0, dialog.listView.count)
                }
                allowChange.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var updatedVisible = false
                while (!updatedVisible && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        val dialog = dialog(activity)
                        updatedVisible = dialog.listView.count == 1 &&
                            dialog.listView.adapter.getItem(0).toString().contains("Project")
                        if (updatedVisible) {
                            val list = dialog.listView
                            assertTrue(list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0)))
                        }
                    }
                    if (!updatedVisible) Thread.sleep(50)
                }
                assertTrue("Updated window must appear without losing the search", updatedVisible)
                task.get(10, TimeUnit.SECONDS)
            }
        } finally {
            allowChange.countDown()
            server?.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            HostStore(context).load().firstOrNull { it.id == hostID }?.let { HostStore(context).remove(it) }
            deviceKey.delete()
            store.deleteEntry(serverAlias)
        }
    }

    private fun dialog(activity: VideoActivity): android.app.AlertDialog =
        VideoActivity::class.java.getDeclaredField("targetDialog").apply { isAccessible = true }.get(activity) as android.app.AlertDialog

    private fun receive(socket: SSLSocket): SignalMessage {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..SignalFrame.MAXIMUM_PAYLOAD)
        return SignalMessageCodec.decode(ByteArray(length).also { input.readFully(it) })
    }

    private fun send(socket: SSLSocket, message: SignalMessage) {
        socket.outputStream.write(SignalFrame.encode(SignalMessageCodec.encode(message)))
        socket.outputStream.flush()
    }

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
}
