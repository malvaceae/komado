package me.mallows.komado

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.security.DeviceSigningKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

class MainActivityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val createdHosts = mutableListOf<String>()

    @Before fun grantLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                compose.activity.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    @After fun cleanup() {
        val hosts = HostStore(compose.activity)
        hosts.load().filter { it.id in createdHosts }.forEach {
            it.pairing?.let { pairing -> DeviceSigningKey(pairing.keyAlias).delete() }
            hosts.remove(it)
        }
    }

    @Test fun addMacAvoidsSystemBars() {
        val activity = compose.activity
        val add = compose.onNodeWithText(activity.getString(R.string.add_host)).assertIsDisplayed().fetchSemanticsNode()
        if (Build.VERSION.SDK_INT >= 35) {
            compose.runOnIdle {
                val root = activity.window.decorView
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue(add.boundsInWindow.bottom <= root.height - insets.bottom)
            }
        }
    }

    @Test fun addMacRemainsVisibleInLandscape() {
        val activity = compose.activity
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(activity.getString(R.string.add_host)).assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun addsOnlyAfterAuthenticatedQrPairing() {
        val activity = compose.activity
        val token = ByteArray(32) { (it + 1).toByte() }
        val challenge = ByteArray(32) { (it + 3).toByte() }
        val deviceID = UUID.randomUUID()
        val executor = Executors.newSingleThreadExecutor()
        TestTlsServer().use { server ->
            val task = executor.submit {
                (server.listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    val request = receiveSignal(socket) as SignalMessage.Pair
                    assertArrayEquals(token, request.invitation)
                    sendSignal(socket, SignalMessage.Challenge(challenge))
                    val proof = receiveSignal(socket) as SignalMessage.Proof
                    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(request.publicKeyDER))
                    val valid = Signature.getInstance("SHA256withECDSA").apply {
                        initVerify(key)
                        update("komado.device-auth.v1\u0000".toByteArray())
                        update(challenge)
                    }.verify(proof.signatureDER)
                    assertTrue("The paired device must sign the challenge", valid)
                    sendSignal(socket, SignalMessage.Accepted(deviceID))
                }
            }
            try {
                val invitation = PairingInvitation("127.0.0.1", server.listener.localPort, server.fingerprint.hex(), token.hex())
                compose.runOnUiThread {
                    activity.javaClass.getDeclaredMethod("confirmScannedPairing", PairingInvitation::class.java)
                        .apply { isAccessible = true }.invoke(activity, invitation)
                }
                task.get(20, TimeUnit.SECONDS)
                compose.waitUntil(20_000) {
                    HostStore(activity).load().any { it.pairing?.deviceID == deviceID.toString() }
                }
                val saved = HostStore(activity).load().single { it.pairing?.deviceID == deviceID.toString() }
                createdHosts.add(saved.id)
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun failedQrPairingDoesNotLeaveUnpairedHost() {
        val activity = compose.activity
        val before = HostStore(activity).load().map { it.id }.toSet()
        val invitation = PairingInvitation("127.0.0.1", 1, "A".repeat(64), "B".repeat(64))
        compose.runOnUiThread {
            activity.javaClass.getDeclaredMethod("confirmScannedPairing", PairingInvitation::class.java)
                .apply { isAccessible = true }.invoke(activity, invitation)
        }
        compose.waitUntil(20_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(activity.getString(R.string.connecting)))
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(HostStore(activity).load().all { it.id in before })
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }
}
