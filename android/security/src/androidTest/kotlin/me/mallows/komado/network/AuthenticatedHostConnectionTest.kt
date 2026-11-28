package me.mallows.komado.network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.mallows.komado.protocol.SignalFrame
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.SignalMessageCodec
import me.mallows.komado.protocol.ApplicationInfo
import me.mallows.komado.protocol.WindowSize
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.protocol.WindowAction
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
import java.net.SocketTimeoutException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun listsAndLaunchesOnlyAfterACompleteSnapshot() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val values = (1..33).map { ApplicationInfo(it.toLong(), "Application $it") }
            val icon = byteArrayOf(1, 2, 3)
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    repeat(2) { attempt ->
                        assertEquals(SignalMessage.ListLaunchableApplications(0), receive(socket))
                        send(socket, SignalMessage.LaunchableApplications(32, values.take(32)))
                        assertEquals(SignalMessage.ListLaunchableApplications(32), receive(socket))
                        send(socket, SignalMessage.LaunchableApplications(0, values.drop(32)))
                        assertEquals(SignalMessage.LaunchableApplicationIcon(33), receive(socket))
                        send(socket, SignalMessage.LaunchableApplicationIconResult(33, icon))
                        assertEquals(SignalMessage.LaunchApplication(33), receive(socket))
                        send(socket, SignalMessage.LaunchApplicationResult(33, attempt == 1))
                    }
                    assertEquals(SignalMessage.ListApplications(0), receive(socket))
                    send(socket, SignalMessage.Applications(0, listOf(ApplicationInfo(44, "Running app"))))
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint, "Android test", ByteArray(32), key).use { connection ->
                    assertThrows(IOException::class.java) { connection.launchApplication(33) }
                    assertEquals(values, connection.listLaunchableApplications())
                    assertTrue(icon.contentEquals(connection.launchableApplicationIcon(33)))
                    assertFalse(connection.launchApplication(33))
                    assertThrows(IOException::class.java) { connection.launchApplication(33) }
                    assertEquals(values, connection.listLaunchableApplications())
                    assertTrue(icon.contentEquals(connection.launchableApplicationIcon(33)))
                    assertTrue(connection.launchApplication(33))
                    assertEquals(listOf(ApplicationInfo(44, "Running app")), connection.listApplications())
                }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun listsMoreThan512WindowsAndSelectsTheLast() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val windows = (1..513).map { WindowInfo(it.toLong(), 300, 200, "App", "Window $it", it % 2 == 0, 123) }
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    for (offset in windows.indices step 32) {
                        assertEquals(SignalMessage.ListWindows(offset), receive(socket))
                        val end = minOf(offset + 32, windows.size)
                        send(socket, SignalMessage.Windows(if (end < windows.size) end else 0, windows.subList(offset, end)))
                    }
                    assertEquals(SignalMessage.StartWindow(513), receive(socket))
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint, "Android test", ByteArray(32), key).use { connection ->
                    assertEquals(windows, connection.listWindows())
                    connection.requestMedia(windowId = 513)
                }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun watchesWindowChangesAndStopsDuringARefresh() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val first = listOf(WindowInfo(1, 300, 200, "App", "First", true, 123))
            val updated = listOf(WindowInfo(1, 640, 480, "App", "Updated", true, 123))
            val allowChange = CountDownLatch(1)
            val secondRequest = CountDownLatch(1)
            val allowSecondResponse = CountDownLatch(1)
            val update = CountDownLatch(1)
            val received = AtomicReference<List<WindowInfo>>()
            val failure = java.util.concurrent.atomic.AtomicBoolean(false)
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, first))
                    assertEquals(SignalMessage.WatchWindows, receive(socket))
                    send(socket, SignalMessage.WindowsWatching)
                    assertTrue(allowChange.await(10, TimeUnit.SECONDS))
                    send(socket, SignalMessage.WindowsChanged)
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.WindowsChanged)
                    send(socket, SignalMessage.Windows(0, updated))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    secondRequest.countDown()
                    assertTrue(allowSecondResponse.await(10, TimeUnit.SECONDS))
                    send(socket, SignalMessage.Windows(0, updated))
                    assertEquals(SignalMessage.StopWatchingWindows, receive(socket))
                    send(socket, SignalMessage.WindowsChanged)
                    send(socket, SignalMessage.WindowsWatchStopped)
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, updated))
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint, "Android test", ByteArray(32), key).use { connection ->
                    assertEquals(first, connection.listWindows())
                    val watch = connection.watchWindows({ received.set(it); update.countDown() }, { failure.set(true) })
                    allowChange.countDown()
                    assertTrue(update.await(10, TimeUnit.SECONDS))
                    assertEquals(updated, received.get())
                    assertTrue(secondRequest.await(10, TimeUnit.SECONDS))
                    watch.stop()
                    allowSecondResponse.countDown()
                    assertEquals(updated, connection.listWindows())
                    assertFalse(failure.get())
                }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                allowChange.countDown()
                allowSecondResponse.countDown()
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun stoppedWatchDoesNotInterruptAFollowingMediaRequest() {
        withServer { server, fingerprint ->
            val serverExecutor = Executors.newSingleThreadExecutor()
            val clientExecutor = Executors.newSingleThreadExecutor()
            val requestStarted = CountDownLatch(1)
            val allowResponse = CountDownLatch(1)
            val watchFailed = java.util.concurrent.atomic.AtomicBoolean(false)
            val windows = listOf(WindowInfo(1, 300, 200, "App", "Window", true, 123))
            val task = serverExecutor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, windows))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    requestStarted.countDown()
                    assertTrue(allowResponse.await(10, TimeUnit.SECONDS))
                    send(socket, SignalMessage.Windows(0, windows))
                    assertEquals(SignalMessage.StartWindow(1), receive(socket))
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint, "Android test", ByteArray(32), key).use { connection ->
                    assertEquals(windows, connection.listWindows())
                    val list = clientExecutor.submit<List<WindowInfo>> { connection.listWindows() }
                    assertTrue(requestStarted.await(10, TimeUnit.SECONDS))
                    val watch = connection.watchWindows({}, { watchFailed.set(true) })
                    watch.stop()
                    allowResponse.countDown()
                    assertEquals(windows, list.get(10, TimeUnit.SECONDS))
                    connection.requestMedia(windowId = 1)
                    assertFalse(watchFailed.get())
                }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                allowResponse.countDown()
                server.close()
                serverExecutor.shutdownNow()
                clientExecutor.shutdownNow()
                serverExecutor.awaitTermination(10, TimeUnit.SECONDS)
                clientExecutor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun refreshesWindowsAfterAnAction() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val windows = listOf(WindowInfo(1, 300, 200, "App", "First", true, 123), WindowInfo(2, 300, 200, "App", "Second", false, 123))
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, windows.take(1)))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, windows))
                    assertEquals(SignalMessage.WindowActionRequest(1, WindowAction.CLOSE), receive(socket))
                    send(socket, SignalMessage.WindowActionResult(1, true))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, windows.drop(1)))
                    assertEquals(SignalMessage.WindowActionRequest(2, WindowAction.MINIMIZE), receive(socket))
                    send(socket, SignalMessage.WindowActionResult(2, false))
                    assertEquals(SignalMessage.ListWindows(0), receive(socket))
                    send(socket, SignalMessage.Windows(0, windows.drop(1)))
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint, "Android test", ByteArray(32), key).use { connection ->
                    assertThrows(IOException::class.java) { connection.performWindowAction(1, WindowAction.CLOSE) }
                    assertEquals(windows.take(1), connection.listWindows())
                    assertEquals(windows, connection.listWindows())
                    assertTrue(connection.performWindowAction(1, WindowAction.CLOSE))
                    assertEquals(windows.drop(1), connection.listWindows())
                    assertFalse(connection.performWindowAction(2, WindowAction.MINIMIZE))
                    assertEquals(windows.drop(1), connection.listWindows())
                }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
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
                        if (index == 1) {
                            assertEquals(SignalMessage.ResumeCheck, receive(socket))
                            send(socket, SignalMessage.ResumeAllowed)
                        }
                        assertEquals(SignalMessage.StartMedia, receive(socket))
                        if (index == 0) {
                            send(socket, SignalMessage.Offer("v=0\r\ns=offer\r\n"))
                            val expected = "v=0\r\ns=answer\r\nc=IN IP4 192.0.2.5\r\n" +
                                "a=candidate:1 1 udp 1 127.0.0.1 54321 typ host\r\n" +
                                "a=candidate:2 1 udp 1 192.0.2.5 54322 typ host\r\n"
                            assertEquals(expected, (receive(socket) as SignalMessage.Answer).sdp)
                            assertEquals(SignalMessage.StopMedia, receive(socket))
                            send(socket, SignalMessage.MediaStopped)
                            assertEquals(SignalMessage.ListApplications(0), receive(socket))
                            send(socket, SignalMessage.Applications(0, listOf(ApplicationInfo(123, "Test application"))))
                            assertEquals(SignalMessage.StartApplication(123), receive(socket))
                            send(socket, SignalMessage.Offer("v=0\r\ns=second offer\r\n"))
                            assertEquals("v=0\r\ns=second answer\r\n", (receive(socket) as SignalMessage.Answer).sdp)
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
                paired.requestMediaStop()
                assertThrows(IOException::class.java) { paired.requestMedia() }
                assertTrue(paired.awaitClosure())
                assertEquals(listOf(ApplicationInfo(123, "Test application")), paired.listApplications())
                paired.requestMedia(applicationId = 123)
                assertEquals("v=0\r\ns=second offer\r\n", paired.receiveOffer())
                paired.sendAnswer("v=0\r\ns=second answer\r\n")
                assertFalse(paired.awaitClosure())
                paired.close()
                val reconnected = AuthenticatedHostConnection.reconnect("127.0.0.1", server.localPort, fingerprint, id, key)
                assertEquals(id, reconnected.deviceID)
                assertTrue(reconnected.canResume())
                assertThrows(IOException::class.java) { reconnected.canResume() }
                reconnected.requestMedia()
                reconnected.receiveOffer()
                assertThrows(SignalMessageCodec.MessageException::class.java) {
                    reconnected.sendAnswer("invalid SDP")
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

    @Test fun mediaStopRestoresTimeoutForNextRequest() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(UUID.randomUUID()))
                    assertEquals(SignalMessage.StartMedia, receive(socket))
                    send(socket, SignalMessage.Offer("v=0\r\ns=offer\r\n"))
                    receive(socket) as SignalMessage.Answer
                    assertEquals(SignalMessage.StopMedia, receive(socket))
                    send(socket, SignalMessage.MediaStopped)
                    assertEquals(SignalMessage.ListDisplays, receive(socket))
                    socket.soTimeout = 35_000
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            try {
                val connection = AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", ByteArray(32), key)
                connection.requestMedia()
                connection.receiveOffer()
                connection.sendAnswer("v=0\r\ns=answer\r\n")
                connection.requestMediaStop()
                assertTrue(connection.awaitClosure())
                assertThrows(SocketTimeoutException::class.java) { connection.listDisplays() }
                task.get(10, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun listsApplicationsLoadsIconAndStartsSelectedApp() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val id = UUID.randomUUID()
            val applications = (1..33).map { ApplicationInfo(it.toLong(), "Application $it") }
            val refreshed = applications + ApplicationInfo(34, "New application")
            val icon = byteArrayOf(1, 2, 3, 4)
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(id))
                    assertEquals(SignalMessage.ListApplications(0), receive(socket))
                    send(socket, SignalMessage.Applications(32, applications.take(32)))
                    assertEquals(SignalMessage.ListApplications(32), receive(socket))
                    send(socket, SignalMessage.Applications(0, applications.drop(32)))
                    assertEquals(SignalMessage.ApplicationIcon(33), receive(socket))
                    send(socket, SignalMessage.ApplicationIconResult(33, icon))
                    assertEquals(SignalMessage.ListApplications(0), receive(socket))
                    send(socket, SignalMessage.Applications(32, refreshed.take(32)))
                    assertEquals(SignalMessage.ListApplications(32), receive(socket))
                    send(socket, SignalMessage.Applications(0, refreshed.drop(32)))
                    assertEquals(SignalMessage.ApplicationIcon(34), receive(socket))
                    send(socket, SignalMessage.ApplicationIconResult(34, icon))
                    assertEquals(SignalMessage.StartApplicationControl(34), receive(socket))
                }
            }
            try {
                val connection = AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", ByteArray(32), key)
                assertEquals(applications, connection.listApplications())
                assertTrue(icon.contentEquals(connection.applicationIcon(33)))
                assertEquals(refreshed, connection.listApplications())
                assertTrue(icon.contentEquals(connection.applicationIcon(34)))
                connection.requestMedia(control = true, applicationId = 34)
                connection.close()
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun hostCanDenyResumption() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val id = UUID.randomUUID()
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(id))
                    assertEquals(SignalMessage.ResumeCheck, receive(socket))
                    send(socket, SignalMessage.ResumeDenied)
                    assertThrows(java.io.EOFException::class.java) { receive(socket) }
                }
            }
            try {
                AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", ByteArray(32), key).use { assertFalse(it.canResume()) }
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun windowControlRequestUsesSelectedWindow() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val id = UUID.randomUUID()
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(id))
                    assertEquals(SignalMessage.StartWindowControl(123, WindowSize(1080, 1920)), receive(socket))
                }
            }
            try {
                val connection = AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", ByteArray(32), key)
                connection.requestMedia(windowId = 123, control = true, windowSize = WindowSize(1080, 1920))
                connection.close()
                task.get(20, TimeUnit.SECONDS)
            } finally {
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun displayControlRequestUsesSelectedDisplay() {
        withServer { server, fingerprint ->
            val executor = Executors.newSingleThreadExecutor()
            val id = UUID.randomUUID()
            val task = executor.submit {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    receive(socket) as SignalMessage.Pair
                    send(socket, SignalMessage.Challenge(ByteArray(32)))
                    receive(socket) as SignalMessage.Proof
                    send(socket, SignalMessage.Accepted(id))
                    assertEquals(SignalMessage.StartDisplayControl(123), receive(socket))
                }
            }
            try {
                val connection = AuthenticatedHostConnection.pair("127.0.0.1", server.localPort, fingerprint,
                    "Android test", ByteArray(32), key)
                connection.requestMedia(displayId = 123, control = true)
                connection.close()
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
