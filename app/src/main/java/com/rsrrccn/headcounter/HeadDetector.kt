package com.rsrrccn.headcounter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds whole PEOPLE in a crowd (model trained on the CrowdHuman dataset, so it handles
 * people standing behind each other) and also gives the head position of each person.
 */
class HeadDetector(context: Context) : AutoCloseable {

    /** One detected person: body box, head box and how sure the model is. */
    class Det(val body: RectF, val head: RectF, val score: Float)

    private val env = OrtEnvironment.getEnvironment()
    private val modelBytes = context.assets.open("crowd_model.onnx").use { it.readBytes() }
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private var session: OrtSession? = null
    private var inputName = "images"
    private var sessW = 0
    private var sessH = 0

    // buffers are reused for every frame (no memory churn)
    private var pixels = IntArray(0)
    private var floats = FloatArray(0)
    private var buffer: FloatBuffer? = null

    /** Bigger = finds smaller people but is slower. */
    @Volatile
    var longSide = 960

    /** Which engine ended up faster on this phone (shown for information). */
    var engine = "CPU"
        private set

    /** Picture size (multiple of 32) that the model should get for a picture of w x h. */
    fun sizeFor(w: Int, h: Int): IntArray {
        val scale = longSide.toFloat() / max(w, h)
        return intArrayOf(
            max(32, (w * scale / 32f).roundToInt() * 32),
            max(32, (h * scale / 32f).roundToInt() * 32)
        )
    }

    /** Returns detected people in the coordinates of [bmp]. */
    fun detect(bmp: Bitmap): List<Det> {
        val exact = bmp.width % 32 == 0 && bmp.height % 32 == 0
        val size = if (exact) intArrayOf(bmp.width, bmp.height) else sizeFor(bmp.width, bmp.height)
        val nw = size[0]
        val nh = size[1]
        val input = if (exact) bmp else Bitmap.createScaledBitmap(bmp, nw, nh, true)

        val sess = ensureSession(nw, nh)
        fill(input, nw, nh)
        if (input !== bmp) input.recycle()

        val sx = bmp.width / nw.toFloat()
        val sy = bmp.height / nh.toFloat()
        val hits = ArrayList<Det>()
        OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { tensor ->
            sess.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val n = (out.info as TensorInfo).shape[2].toInt()     // output is [1, 5, n]
                val fb = out.floatBuffer                              // rows: cx, cy, w, h, score
                for (i in 0 until n) {
                    val score = fb.get(4 * n + i)
                    if (score < SCORE_MIN) continue
                    val w = fb.get(2 * n + i) * sx
                    val h = fb.get(3 * n + i) * sy
                    if (w < 6f || h < 12f) continue
                    val left = fb.get(i) * sx - w / 2f
                    val top = fb.get(n + i) * sy - h / 2f
                    hits.add(Det(RectF(left, top, left + w, top + h), headOf(left, top, w, h), score))
                }
            }
        }
        return removeDuplicates(hits)
    }

    private fun fill(bmp: Bitmap, nw: Int, nh: Int) {
        val plane = nw * nh
        if (pixels.size != plane) {
            pixels = IntArray(plane)
            floats = FloatArray(3 * plane)
            buffer = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        bmp.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        for (i in 0 until plane) {
            val p = pixels[i]
            floats[i] = ((p shr 16) and 255) * INV255
            floats[plane + i] = ((p shr 8) and 255) * INV255
            floats[2 * plane + i] = (p and 255) * INV255
        }
        val b = buffer!!
        b.clear()
        b.put(floats)
        b.rewind()
    }

    // ---------- Speed: pick the faster engine for this phone ----------

    private fun ensureSession(nw: Int, nh: Int): OrtSession {
        val current = session
        if (current != null && sessW == nw && sessH == nh) return current
        current?.close()
        session = null

        var best: OrtSession? = null
        var bestMs = Long.MAX_VALUE
        var bestName = "CPU"
        for (xnn in listOf(false, true)) {
            val s = try { build(nw, nh, xnn) } catch (e: Throwable) { null } ?: continue
            val ms = try { benchmark(s, nw, nh) } catch (e: Throwable) { s.close(); continue }
            if (ms < bestMs) {
                best?.close()
                best = s
                bestMs = ms
                bestName = if (xnn) "XNNPACK" else "CPU"
            } else {
                s.close()
            }
        }
        val chosen = best ?: build(nw, nh, false)
        inputName = chosen.inputNames.iterator().next()
        engine = bestName
        sessW = nw
        sessH = nh
        session = chosen
        return chosen
    }

    private fun build(nw: Int, nh: Int, xnnpack: Boolean): OrtSession {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // fixed picture size lets the engine prepare everything in advance
        o.setSymbolicDimensionValue("batch", 1)
        o.setSymbolicDimensionValue("height", nh.toLong())
        o.setSymbolicDimensionValue("width", nw.toLong())
        if (xnnpack) {
            o.setIntraOpNumThreads(1)
            o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        } else {
            o.setIntraOpNumThreads(threads)
        }
        return env.createSession(modelBytes, o)
    }

    private fun benchmark(s: OrtSession, nw: Int, nh: Int): Long {
        val buf = ByteBuffer.allocateDirect(3 * nw * nh * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val name = s.inputNames.iterator().next()
        var total = 0L
        for (i in 0 until 3) {
            OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
                val start = SystemClock.elapsedRealtime()
                s.run(mapOf(name to t)).close()
                if (i > 0) total += SystemClock.elapsedRealtime() - start   // first run is warm-up
            }
        }
        return total
    }

    // ---------- Cleaning up the detections ----------

    /** The boxes cover the whole body, so the head is a square at the top-middle. */
    private fun headOf(left: Float, top: Float, w: Float, h: Float): RectF {
        val size = min(0.4f * w, 0.15f * h)
        val cx = left + w / 2f
        return RectF(cx - size / 2f, top, cx + size / 2f, top + size)
    }

    /** Standard clean-up: when two boxes sit on the same person, keep the surest one. */
    private fun removeDuplicates(hits: List<Det>): List<Det> {
        val kept = ArrayList<Det>()
        for (d in hits.sortedByDescending { it.score }) {
            if (kept.none { iou(it.body, d.body) > 0.5f || iou(it.head, d.head) > 0.3f }) kept.add(d)
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    override fun close() {
        session?.close()
    }

    companion object {
        const val SCORE_MIN = 0.30f
        private const val INV255 = 1f / 255f
    }
}
