package me.mallows.komado

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.mallows.komado.protocol.SignalMessage
import me.mallows.komado.protocol.PairingInvitation
import me.mallows.komado.security.DeviceSigningKey
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.util.UUID
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

class MainActivityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val hostName = "Test Mac ${UUID.randomUUID()}"

    @Before fun grantLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                compose.activity.packageName, Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    @After fun cleanup() {
        val hosts = HostStore(compose.activity)
        hosts.load().firstOrNull { it.name == hostName }?.let {
            it.pairing?.let { pairing -> DeviceSigningKey(pairing.keyAlias).delete() }
            hosts.remove(it)
        }
    }

    @Test fun registersEditsAndRemovesAHost() {
        val activity = compose.activity
        compose.onNodeWithText(activity.getString(R.string.add_host)).performClick()
        compose.onNodeWithText(activity.getString(R.string.save)).performClick()
        compose.onNodeWithText(activity.getString(R.string.invalid_name)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.invalid_address)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.host_name)).performTextInput(hostName)
        compose.onNodeWithText(activity.getString(R.string.host_address)).performTextInput("192.0.2.1")
        compose.onNodeWithText(activity.getString(R.string.save)).performClick()
        compose.onNodeWithText(hostName).assertIsDisplayed().performClick()
        compose.onNodeWithText(activity.getString(R.string.edit_host)).performClick()
        compose.onNodeWithText(activity.getString(R.string.save)).performClick()
        compose.onNodeWithText(hostName).performClick()
        compose.onNodeWithText(activity.getString(R.string.delete)).performClick()
        compose.onNodeWithText(activity.getString(R.string.delete)).performClick()
        compose.onNodeWithText(hostName).assertDoesNotExist()
    }

    @Test fun primaryActionsAvoidSystemBars() {
        val activity = compose.activity
        val qr = compose.onNodeWithText(activity.getString(R.string.scan_pairing)).assertIsDisplayed().fetchSemanticsNode()
        val add = compose.onNodeWithText(activity.getString(R.string.add_host)).assertIsDisplayed().fetchSemanticsNode()
        if (Build.VERSION.SDK_INT >= 35) {
            compose.runOnIdle {
                val root = activity.window.decorView
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue(qr.boundsInWindow.bottom <= root.height - insets.bottom)
                assertTrue(add.boundsInWindow.bottom <= root.height - insets.bottom)
            }
        }
    }

    @Test fun primaryActionsRemainVisibleInLandscape() {
        val activity = compose.activity
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(activity.getString(R.string.scan_pairing)).assertIsDisplayed()
            compose.onNodeWithText(activity.getString(R.string.add_host)).assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun pairingCodeIsNotOfferedToAutofillOrImeLearning() {
        val activity = compose.activity
        openManualPairing()
        compose.onNodeWithText(activity.getString(R.string.invitation)).performClick()
        compose.onNodeWithText(activity.getString(R.string.invitation)).performTextInput("A1B2C3")
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
            val protected = views.firstOrNull { it.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
            assertTrue("The pairing dialog must not expose its fields to autofill", protected != null)
            val editor = EditorInfo()
            val inputView = protected?.let { descendants(it).firstOrNull { view -> view.javaClass.name.endsWith("AndroidComposeView") } }
            val connection = inputView?.onCreateInputConnection(editor)
            assertTrue("The pairing code must have an input connection", connection != null)
            assertEquals(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
            connection?.closeConnection()
        }
    }

    @Test fun pairingCodeIsNotRestoredAfterActivityRecreation() {
        val activity = compose.activity
        openManualPairing()
        compose.onNodeWithText(activity.getString(R.string.invitation)).performTextInput("A1B2C3")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(hostName).performClick()
        compose.onNodeWithText(activity.getString(R.string.pair)).performClick()
        compose.onNodeWithText("A1B2C3").assertDoesNotExist()
    }

    @Test fun pairsFromMaterialDialogAndStoresDevice() {
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
                compose.onNodeWithText(activity.getString(R.string.add_host)).performClick()
                compose.onNodeWithText(activity.getString(R.string.host_name)).performTextInput(hostName)
                compose.onNodeWithText(activity.getString(R.string.host_address)).performTextInput("127.0.0.1")
                compose.onNodeWithText(activity.getString(R.string.port)).performTextReplacement(server.listener.localPort.toString())
                compose.onNodeWithText(activity.getString(R.string.save)).performClick()
                compose.onNodeWithText(hostName).performClick()
                compose.onNodeWithText(activity.getString(R.string.pair)).performClick()
                compose.onNodeWithText(activity.getString(R.string.fingerprint)).performTextInput(server.fingerprint.hex())
                compose.onNodeWithText(activity.getString(R.string.invitation)).performTextInput(token.hex())
                compose.onNode(hasText(activity.getString(R.string.pair)) and hasClickAction()).performClick()
                task.get(20, TimeUnit.SECONDS)
                compose.waitUntil(20_000) {
                    HostStore(activity).load().firstOrNull { it.name == hostName }?.pairing?.deviceID == deviceID.toString()
                }
                compose.onNodeWithText(activity.getString(R.string.paired)).assertIsDisplayed()
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun confirmsScannedPairingWithMaterialDialog() {
        val activity = compose.activity
        val token = ByteArray(32) { (it + 1).toByte() }
        val deviceID = UUID.randomUUID()
        val executor = Executors.newSingleThreadExecutor()
        TestTlsServer().use { server ->
            val task = executor.submit {
                (server.listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    val request = receiveSignal(socket) as SignalMessage.Pair
                    assertArrayEquals(token, request.invitation)
                    sendSignal(socket, SignalMessage.Challenge(ByteArray(32)))
                    receiveSignal(socket) as SignalMessage.Proof
                    sendSignal(socket, SignalMessage.Accepted(deviceID))
                }
            }
            try {
                val invitation = PairingInvitation("127.0.0.1", server.listener.localPort, server.fingerprint.hex(), token.hex())
                compose.runOnUiThread {
                    activity.javaClass.getDeclaredMethod("confirmScannedPairing", PairingInvitation::class.java)
                        .apply { isAccessible = true }.invoke(activity, invitation)
                }
                compose.onNodeWithText(activity.getString(R.string.scan_confirm)).assertIsDisplayed()
                compose.onNodeWithText(activity.getString(R.string.host_name)).performTextReplacement(hostName)
                compose.onNode(hasText(activity.getString(R.string.pair)) and hasClickAction()).performClick()
                task.get(20, TimeUnit.SECONDS)
                compose.waitUntil(20_000) {
                    HostStore(activity).load().firstOrNull { it.name == hostName }?.pairing?.deviceID == deviceID.toString()
                }
                compose.onNodeWithText(activity.getString(R.string.paired)).assertIsDisplayed()
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    private fun openManualPairing() {
        val activity = compose.activity
        compose.onNodeWithText(activity.getString(R.string.add_host)).performClick()
        compose.onNodeWithText(activity.getString(R.string.host_name)).performTextInput(hostName)
        compose.onNodeWithText(activity.getString(R.string.host_address)).performTextInput("192.0.2.1")
        compose.onNodeWithText(activity.getString(R.string.save)).performClick()
        compose.onNodeWithText(hostName).performClick()
        compose.onNodeWithText(activity.getString(R.string.pair)).performClick()
    }

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
