package me.mallows.komado

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PairingScannerActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val delivered = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private var provider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.camera_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { setText(R.string.scan_pairing_instruction); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.addView(status)
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { setText(R.string.cancel); setOnClickListener { finish() } })
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
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
            } catch (_: Exception) { status.setText(R.string.camera_unavailable) }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
