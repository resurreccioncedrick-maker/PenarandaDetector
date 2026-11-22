package com.rsrrccn.headcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var btnScan: Button
    private lateinit var btnShow: Button
    private lateinit var btnReset: Button

    private var detector: ObjectDetector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val counter = HeadCounter()
    private var scanning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        btnScan = findViewById(R.id.btnScan)
        btnShow = findViewById(R.id.btnShow)
        btnReset = findViewById(R.id.btnReset)
        previewView.keepScreenOn = true

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.35f)
            .setMaxResults(40)
            .setCategoryAllowlist(listOf("person"))
            .build()
        detector = ObjectDetector.createFromOptions(this, options)

        btnScan.setOnClickListener { if (scanning) stopScan() else startScan() }
        btnShow.setOnClickListener { showCount() }
        btnReset.setOnClickListener { resetCount() }

        checkCameraPermission()
    }

    // ---------- Camera permission ----------

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                AlertDialog.Builder(this)
                    .setTitle("Camera permission")
                    .setMessage("The camera is needed to see and count the people.")
                    .setPositiveButton("Allow") { _, _ ->
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showPermissionDenied() {
        statusText.text = "Camera permission is required"
        AlertDialog.Builder(this)
            .setTitle("Camera permission needed")
            .setMessage("Without the camera the app cannot count people. You can allow it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                )
            }
            .setNeutralButton("Try again") { _, _ -> checkCameraPermission() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- Camera + detection ----------

    private fun startCamera() {
        val controller = LifecycleCameraController(this)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        controller.setImageAnalysisAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
        controller.bindToLifecycle(this)
        previewView.controller = controller
    }

    /** Runs on a background thread for every camera frame. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val det = detector ?: return
            var bmp: Bitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val result = det.detect(BitmapImageBuilder(bmp).build())
            val persons = result.detections().map { RectF(it.boundingBox()) }
            val w = bmp.width
            val h = bmp.height
            val now = SystemClock.elapsedRealtime()
            runOnUiThread { onPersons(persons, w, h, now) }
        } finally {
            proxy.close()
        }
    }

    private fun onPersons(persons: List<RectF>, w: Int, h: Int, nowMs: Long) {
        if (scanning) {
            overlay.setHeads(counter.update(persons, nowMs), w, h)
            statusText.text = "Scanning...  in view: ${persons.size}   counted: ${counter.total}"
        } else {
            overlay.setHeads(counter.previewOnly(persons), w, h)
            statusText.text = "Ready  -  heads in view: ${persons.size}"
        }
    }

    // ---------- Buttons ----------

    private fun startScan() {
        scanning = true
        btnScan.text = "Stop scan"
    }

    private fun stopScan() {
        scanning = false
        btnScan.text = "Start scan"
    }

    private fun showCount() {
        stopScan()
        val n = counter.total
        AlertDialog.Builder(this)
            .setTitle("Peñaranda Branch Attendance")
            .setMessage("Total people counted: $n")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun resetCount() {
        AlertDialog.Builder(this)
            .setTitle("Reset count?")
            .setMessage("This clears the total and starts over.")
            .setPositiveButton("Reset") { _, _ ->
                stopScan()
                counter.reset()
                overlay.clear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        detector?.close()
    }
}
