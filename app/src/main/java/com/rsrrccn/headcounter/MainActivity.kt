package com.rsrrccn.headcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var countLive: TextView
    private lateinit var scanProgress: LinearProgressIndicator
    private lateinit var btnScan: MaterialButton
    private lateinit var btnShow: MaterialButton
    private lateinit var btnReset: MaterialButton

    @Volatile
    private var detector: HeadDetector? = null
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
        countLive = findViewById(R.id.countLive)
        scanProgress = findViewById(R.id.scanProgress)
        btnScan = findViewById(R.id.btnScan)
        btnShow = findViewById(R.id.btnShow)
        btnReset = findViewById(R.id.btnReset)
        previewView.keepScreenOn = true

        // Keep the top card and bottom buttons clear of the status bar / navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val topPanel = findViewById<View>(R.id.topPanel)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val topPad = topPanel.paddingTop
        val bottomPad = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topPanel.updatePadding(top = topPad + bars.top)
            bottomBar.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }

        btnScan.setOnClickListener { if (scanning) stopScan() else startScan() }
        btnShow.setOnClickListener { showCount() }
        btnReset.setOnClickListener { resetCount() }
        statusText.text = "Loading..."

        checkCameraPermission()
    }

    // ---------- Camera permission ----------

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startCamera()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1440, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        controller.imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        controller.setImageAnalysisAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
        controller.bindToLifecycle(this)
        previewView.controller = controller
    }

    /** Runs on a background thread for every camera frame. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val started = SystemClock.elapsedRealtime()
            val det = detector ?: HeadDetector(this).also { detector = it }

            var bmp: Bitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val heads = det.detect(bmp)
            val w = bmp.width
            val h = bmp.height
            val now = SystemClock.elapsedRealtime()
            val ms = now - started
            runOnUiThread { onHeads(heads, w, h, now, ms) }
        } finally {
            proxy.close()
        }
    }

    private fun onHeads(heads: List<RectF>, w: Int, h: Int, nowMs: Long, ms: Long) {
        if (scanning) {
            overlay.setHeads(counter.update(heads, nowMs), w, h)
            countLive.text = counter.total.toString()
            statusText.text = "Scanning  ·  ${heads.size} in view  ·  $ms ms"
        } else {
            overlay.setHeads(counter.previewOnly(heads), w, h)
            statusText.text = "Ready  ·  ${heads.size} heads in view  ·  $ms ms"
        }
    }

    // ---------- Buttons ----------

    private fun startScan() {
        scanning = true
        scanProgress.visibility = View.VISIBLE
        btnScan.text = "Stop scan"
        btnScan.setIconResource(R.drawable.ic_stop)
        btnScan.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
    }

    private fun stopScan() {
        scanning = false
        scanProgress.visibility = View.GONE
        btnScan.text = "Start scan"
        btnScan.setIconResource(R.drawable.ic_play)
        btnScan.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22D3A6"))
    }

    private fun showCount() {
        stopScan()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_result, null)
        view.findViewById<TextView>(R.id.resultNumber).text = counter.total.toString()
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun resetCount() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset count?")
            .setMessage("This clears the total and starts over.")
            .setPositiveButton("Reset") { _, _ ->
                stopScan()
                counter.reset()
                countLive.text = "0"
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
