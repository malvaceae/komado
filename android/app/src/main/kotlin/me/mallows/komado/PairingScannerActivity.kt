package me.mallows.komado

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PairingScannerActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: PreviewView
    private var status by mutableIntStateOf(R.string.scan_pairing_instruction)
    private var canRequestCamera by mutableStateOf(false)
    private var provider: ProcessCameraProvider? = null
    private var cameraStarting = false
    private var cameraPreview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            canRequestCamera = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            status = R.string.camera_permission_denied
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        setContent {
            KomadoTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
                        val message = if (status == R.string.camera_permission_denied && !canRequestCamera) {
                            R.string.camera_permission_settings
                        } else status
                        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                        if (status == R.string.scan_pairing_instruction) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                                Box(Modifier.size(220.dp).border(2.dp, Color.White, RoundedCornerShape(16.dp)))
                            }
                        } else {
                            if (status == R.string.camera_permission_denied) {
                                Button(onClick = {
                                    if (canRequestCamera) {
                                        cameraPermission.launch(Manifest.permission.CAMERA)
                                    } else {
                                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", packageName, null)))
                                    }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(if (canRequestCamera) R.string.request_camera else R.string.open_app_settings))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun startCamera() {
        if (cameraStarting || provider != null || delivered.get()) return
        cameraStarting = true
        status = R.string.scan_pairing_instruction
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (isFinishing || isDestroyed) return@addListener
            try {
                val current = future.get()
                val selector = when {
                    current.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    current.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> throw IllegalStateException("Camera unavailable")
                }
                val camera = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val frames = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                frames.setAnalyzer(worker) { image ->
                    try {
                        if (!delivered.get() && image.planes.isNotEmpty()) {
                            val plane = image.planes[0]
                            val invitation = PairingQrDecoder.decode(image.width, image.height, plane.rowStride, plane.pixelStride, plane.buffer)
                            if (invitation != null && delivered.compareAndSet(false, true)) {
                                main.post {
                                    if (!isFinishing && !isDestroyed) {
                                        ScannedPairing.put(invitation)
                                        setResult(RESULT_OK)
                                        finish()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // A frame can become unavailable while the camera is stopping.
                    } finally { image.close() }
                }
                current.bindToLifecycle(this, selector, camera, frames)
                provider = current
                cameraPreview = camera
                analysis = frames
            } catch (_: Exception) { status = R.string.camera_unavailable }
        }, { command -> main.post(command) })
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(cameraPreview, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        worker.shutdown()
        if (!delivered.get()) ScannedPairing.clear()
        super.onDestroy()
    }
}
