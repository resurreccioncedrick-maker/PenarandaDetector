package com.rsrrccn.headcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.view.View
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var btnScan: Button
    private lateinit var btnShow: Button
    private lateinit var btnReset: Button

    // Several detectors run at the same time (one per worker thread) so the zoomed tiles are scanned in parallel
    private val workerCount = 4
    private val detectors = ArrayList<ObjectDetector>()
    private val freeDetectors = LinkedBlockingQueue<ObjectDetector>()
    private val workers = Executors.newFixedThreadPool(workerCount)
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

        // Keep the top text and bottom buttons clear of the status bar / navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val topPad = statusText.paddingTop
        val bottomPad = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusText.updatePadding(top = topPad + bars.top)
            bottomBar.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.25f)
            .setMaxResults(50)
            .setCategoryAllowlist(listOf("person"))
            .build()
        repeat(workerCount) {
            val d = ObjectDetector.createFromOptions(this, options)
            detectors.add(d)
            freeDetectors.add(d)
        }

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
            var bmp: Bitmap = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val persons = detectPeople(bmp)
            val w = bmp.width
            val h = bmp.height
            val now = SystemClock.elapsedRealtime()
            val ms = now - started
            runOnUiThread { onPersons(persons, w, h, now, ms) }
        } finally {
            proxy.close()
        }
    }

    // ---------- Detection (whole picture + 9 zoomed-in tiles, so small far-away heads are found) ----------

    private class Hit(val box: RectF, val score: Float)

    private fun detectPeople(bmp: Bitmap): List<RectF> {
        val w = bmp.width
        val h = bmp.height
        val cols = 3
        val rows = 3
        val overlap = 0.3f
        val tw = (w / (1f + (cols - 1) * (1f - overlap))).toInt()
        val th = (h / (1f + (rows - 1) * (1f - overlap))).toInt()
        val stepX = (tw * (1f - overlap)).toInt()
        val stepY = (th * (1f - overlap)).toInt()

        // Each job = one area to scan: x, y, width, height
        val areas = ArrayList<IntArray>()
        areas.add(intArrayOf(0, 0, w, h))
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = if (c == cols - 1) w - tw else c * stepX
                val y = if (r == rows - 1) h - th else r * stepY
                areas.add(intArrayOf(x, y, tw, th))
            }
        }

        val jobs = areas.map { a ->
            Callable {
                val det = freeDetectors.take()
                try {
                    detectIn(det, bmp, a[0], a[1], a[2], a[3], w, h)
                } finally {
                    freeDetectors.put(det)
                }
            }
        }
        val hits = ArrayList<Hit>()
        for (f in workers.invokeAll(jobs)) hits.addAll(f.get())
        return removeDuplicates(hits)
    }

    private fun detectIn(
        det: ObjectDetector, bmp: Bitmap,
        x: Int, y: Int, tw: Int, th: Int, fullW: Int, fullH: Int
    ): List<Hit> {
        val crop = if (tw == bmp.width && th == bmp.height) bmp else Bitmap.createBitmap(bmp, x, y, tw, th)
        val out = ArrayList<Hit>()
        val m = 4f
        for (d in det.detect(BitmapImageBuilder(crop).build()).detections()) {
            val b = d.boundingBox()
            // Skip people cut off by the edge of a tile (the neighbouring tile sees them whole)
            if (x > 0 && b.left < m) continue
            if (y > 0 && b.top < m) continue
            if (x + tw < fullW && b.right > tw - m) continue
            if (y + th < fullH && b.bottom > th - m) continue
            if (b.width() < 8f) continue
            out.add(
                Hit(RectF(b.left + x, b.top + y, b.right + x, b.bottom + y), d.categories()[0].score())
            )
        }
        return out
    }

    private fun removeDuplicates(hits: List<Hit>): List<RectF> {
        val kept = ArrayList<Hit>()
        for (hit in hits.sortedByDescending { it.score }) {
            if (kept.none { sameBox(it.box, hit.box) }) kept.add(hit)
        }
        return kept.map { it.box }
    }

    private fun sameBox(a: RectF, b: RectF): Boolean {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return false
        val inter = iw * ih
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        return inter / (areaA + areaB - inter) > 0.45f || inter / min(areaA, areaB) > 0.8f
    }

    private fun onPersons(persons: List<RectF>, w: Int, h: Int, nowMs: Long, ms: Long) {
        if (scanning) {
            overlay.setHeads(counter.update(persons, nowMs), w, h)
            statusText.text = "Scanning...  in view: ${persons.size}   counted: ${counter.total}   (${ms} ms)"
        } else {
            overlay.setHeads(counter.previewOnly(persons), w, h)
            statusText.text = "Ready  -  heads in view: ${persons.size}   (${ms} ms)"
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
        workers.shutdown()
        detectors.forEach { it.close() }
    }
}
