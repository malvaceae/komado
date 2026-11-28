package me.mallows.komado

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.ApplicationInfo
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.WindowInfo
import me.mallows.komado.security.DeviceSigningKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

@RunWith(AndroidJUnit4::class)
class ApplicationWindowSelectionTest {
    @Test fun opensSingleWindowAndFallsBackToAnApplicationWithoutWindows() {
        checkDirectSelection(listOf(WindowInfo(7, 300, 200, "Editor", "Only", true, 123)), SignalMessage.StartWindow(7))
        checkDirectSelection(emptyList(), SignalMessage.StartApplication(123))
    }

    private fun checkDirectSelection(windows: List<WindowInfo>, expected: SignalMessage) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID()
        val alias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        val key = DeviceSigningKey(alias)
        val server = TestTlsServer()
        val executor = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        try {
            key.getOrCreatePublicKeyDER()
            val task = executor.submit {
                (server.listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 15_000
                    socket.startHandshake()
                    assertEquals(SignalMessage.Authenticate(deviceId), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Challenge(ByteArray(32)))
                    receiveSignal(socket) as SignalMessage.Proof
                    sendSignal(socket, SignalMessage.Accepted(deviceId))
                    assertEquals(SignalMessage.ListApplications(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Applications(0, listOf(ApplicationInfo(123, "Editor"))))
                    ready.countDown()
                    while (true) {
                        when (val request = receiveSignal(socket)) {
                            is SignalMessage.ApplicationIcon -> sendSignal(socket, SignalMessage.ApplicationIconResult(request.id, ByteArray(0)))
                            is SignalMessage.ListWindows -> { sendSignal(socket, SignalMessage.Windows(0, windows)); break }
                            else -> error("Unexpected request: $request")
                        }
                    }
                    if (windows.size == 1) {
                        assertEquals(SignalMessage.ListWindows(0), receiveSignal(socket))
                        sendSignal(socket, SignalMessage.Windows(0, windows))
                    }
                    assertEquals(expected, receiveSignal(socket))
                }
            }
            val fingerprint = server.fingerprint.joinToString("") { "%02X".format(it) }
            assertTrue(HostStore(context).add(Host(hostId, "Test Mac", "127.0.0.1", server.listener.localPort,
                Pairing(deviceId.toString(), fingerprint, alias))))
            val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.HOST_ID, hostId)
                .putExtra(VideoActivity.CHOOSE_APPLICATION, true)
            ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                var selected = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!selected && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        val list = dialog(activity)?.listView
                        if (list != null && list.count == 1) {
                            selected = list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0))
                        }
                    }
                    if (!selected) Thread.sleep(50)
                }
                assertTrue(selected)
                task.get(15, TimeUnit.SECONDS)
            }
        } finally {
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            HostStore(context).load().firstOrNull { it.id == hostId }?.let { HostStore(context).remove(it) }
            key.delete()
        }
    }

    @Test fun selectingAnApplicationShowsOnlyItsWindows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID()
        val alias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        val key = DeviceSigningKey(alias)
        val server = TestTlsServer()
        val executor = Executors.newSingleThreadExecutor()
        val applicationsReady = CountDownLatch(1)
        val windowsReady = CountDownLatch(1)
        val windows = listOf(
            WindowInfo(1, 300, 200, "Editor", "First", true, 123),
            WindowInfo(2, 300, 200, "Editor", "Second", true, 123),
            WindowInfo(3, 300, 200, "Editor", "Other process", true, 456),
        )
        try {
            key.getOrCreatePublicKeyDER()
            val task = executor.submit {
                (server.listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 15_000
                    socket.startHandshake()
                    assertEquals(SignalMessage.Authenticate(deviceId), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Challenge(ByteArray(32)))
                    receiveSignal(socket) as SignalMessage.Proof
                    sendSignal(socket, SignalMessage.Accepted(deviceId))
                    assertEquals(SignalMessage.ListApplications(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Applications(0, listOf(ApplicationInfo(123, "Editor"))))
                    applicationsReady.countDown()
                    while (true) {
                        when (val request = receiveSignal(socket)) {
                            is SignalMessage.ApplicationIcon -> sendSignal(socket, SignalMessage.ApplicationIconResult(request.id, ByteArray(0)))
                            is SignalMessage.ListWindows -> { sendSignal(socket, SignalMessage.Windows(0, windows)); break }
                            else -> error("Unexpected request: $request")
                        }
                    }
                    assertEquals(SignalMessage.WatchWindows, receiveSignal(socket))
                    sendSignal(socket, SignalMessage.WindowsWatching)
                    windowsReady.countDown()
                    assertEquals(SignalMessage.StopWatchingWindows, receiveSignal(socket))
                    sendSignal(socket, SignalMessage.WindowsWatchStopped)
                    assertEquals(SignalMessage.ListWindows(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Windows(0, windows))
                    assertEquals(SignalMessage.StartWindow(2), receiveSignal(socket))
                }
            }
            val fingerprint = server.fingerprint.joinToString("") { "%02X".format(it) }
            val host = Host(hostId, "Test Mac", "127.0.0.1", server.listener.localPort,
                pairing = Pairing(deviceId.toString(), fingerprint, alias))
            assertTrue(HostStore(context).add(host))
            val intent = Intent(context, VideoActivity::class.java).putExtra(VideoActivity.HOST_ID, hostId)
                .putExtra(VideoActivity.CHOOSE_APPLICATION, true)
            ActivityScenario.launch<VideoActivity>(intent).use { scenario ->
                assertTrue(applicationsReady.await(10, TimeUnit.SECONDS))
                var selected = false
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!selected && System.nanoTime() < deadline) {
                    scenario.onActivity { activity ->
                        val dialog = dialog(activity)
                        if (dialog?.isShowing == true && dialog.listView?.count == 1) {
                            val list = dialog.listView
                            selected = list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0))
                        }
                    }
                    if (!selected) Thread.sleep(50)
                }
                assertTrue(selected)
                assertTrue(windowsReady.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    val list = requireNotNull(dialog(activity)).listView
                    assertEquals(2, list.count)
                    assertTrue(list.adapter.getItem(0).toString().contains("First"))
                    assertTrue(list.adapter.getItem(1).toString().contains("Second"))
                    assertTrue(list.performItemClick(list.adapter.getView(1, null, list), 1, list.adapter.getItemId(1)))
                }
                task.get(15, TimeUnit.SECONDS)
            }
        } finally {
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            HostStore(context).load().firstOrNull { it.id == hostId }?.let { HostStore(context).remove(it) }
            key.delete()
        }
    }

    private fun dialog(activity: VideoActivity): android.app.AlertDialog? =
        VideoActivity::class.java.getDeclaredField("targetDialog").apply { isAccessible = true }.get(activity) as? android.app.AlertDialog
}
