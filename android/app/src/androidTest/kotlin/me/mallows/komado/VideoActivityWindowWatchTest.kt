package me.mallows.komado

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class VideoActivityWindowWatchTest {
    @Test fun updatesTheOpenChooserAndSelectsTheUpdatedWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostID = UUID.randomUUID().toString()
        val deviceID = UUID.randomUUID()
        val deviceAlias = "me.mallows.komado.pair.${UUID.randomUUID()}"
        val server = TestTlsServer()
        val deviceKey = DeviceSigningKey(deviceAlias)
        val executor = Executors.newSingleThreadExecutor()
        val allowChange = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            deviceKey.getOrCreatePublicKeyDER()
            val listener = server.listener
            val original = WindowInfo(1, 300, 200, "Editor", "Notes", true, 123)
            val updated = WindowInfo(1, 640, 480, "Editor", "Project", true, 123)
            val task = executor.submit {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    assertEquals(SignalMessage.Authenticate(deviceID), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Challenge(ByteArray(32)))
                    receiveSignal(socket) as SignalMessage.Proof
                    sendSignal(socket, SignalMessage.Accepted(deviceID))
                    assertEquals(SignalMessage.ListWindows(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Windows(0, listOf(original)))
                    assertEquals(SignalMessage.WatchWindows, receiveSignal(socket))
                    sendSignal(socket, SignalMessage.WindowsWatching)
                    started.countDown()
                    assertTrue(allowChange.await(10, TimeUnit.SECONDS))
                    sendSignal(socket, SignalMessage.WindowsChanged)
                    assertEquals(SignalMessage.ListWindows(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Windows(0, listOf(updated)))
                    assertEquals(SignalMessage.StopWatchingWindows, receiveSignal(socket))
                    sendSignal(socket, SignalMessage.WindowsWatchStopped)
                    assertEquals(SignalMessage.ListWindows(0), receiveSignal(socket))
                    sendSignal(socket, SignalMessage.Windows(0, listOf(updated)))
                    assertEquals(SignalMessage.StartWindow(1), receiveSignal(socket))
                }
            }
            val fingerprint = server.fingerprint.joinToString("") { "%02X".format(it) }
            val host = Host(hostID, "Test Mac", "127.0.0.1", listener.localPort)
            val hosts = HostStore(context)
            assertTrue(hosts.add(host.copy(pairing = Pairing(deviceID.toString(), fingerprint, deviceAlias))))
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
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            HostStore(context).load().firstOrNull { it.id == hostID }?.let { HostStore(context).remove(it) }
            deviceKey.delete()
        }
    }

    private fun dialog(activity: VideoActivity): android.app.AlertDialog =
        VideoActivity::class.java.getDeclaredField("targetDialog").apply { isAccessible = true }.get(activity) as android.app.AlertDialog

    private fun <T : View> find(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), type)?.let { return it }
        return null
    }
}
