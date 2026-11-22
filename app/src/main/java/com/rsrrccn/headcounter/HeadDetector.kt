package com.rsrrccn.headcounter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds people in a crowd (model trained on the CrowdHuman dataset, so it handles
 * people standing behind each other) and returns a box on each HEAD.
 */
class HeadDetector(context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open("crowd_model.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        session = env.createSession(bytes, options)
    }

    private class Hit(val box: RectF, val score: Float)

    /** Returns head boxes in the coordinates of [bmp]. */
    fun detect(bmp: Bitmap): List<RectF> {
        // The model accepts any size that is a multiple of 32
        val scale = LONG_SIDE.toFloat() / max(bmp.width, bmp.height)
        val nw = max(32, (bmp.width * scale / 32f).roundToInt() * 32)
        val nh = max(32, (bmp.height * scale / 32f).roundToInt() * 32)
        val small = Bitmap.createScaledBitmap(bmp, nw, nh, true)

        val pixels = IntArray(nw * nh)
        small.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        val plane = nw * nh
        val buf = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plane) {
            val p = pixels[i]
            buf.put(i, ((p shr 16) and 255) / 255f)
            buf.put(plane + i, ((p shr 8) and 255) / 255f)
            buf.put(2 * plane + i, (p and 255) / 255f)
        }
        if (small !== bmp) small.recycle()

        val hits = ArrayList<Hit>()
        val inputName = session.inputNames.iterator().next()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result.get(0).value as Array<Array<FloatArray>>   // [1][5][anchors]
                val rows = out[0]                                             // cx, cy, w, h, score
                val sx = bmp.width / nw.toFloat()
                val sy = bmp.height / nh.toFloat()
                for (i in rows[4].indices) {
                    val score = rows[4][i]
                    if (score < SCORE_MIN) continue
                    val w = rows[2][i] * sx
                    val h = rows[3][i] * sy
                    if (w < 6f || h < 12f) continue
                    val left = rows[0][i] * sx - w / 2f
                    val top = rows[1][i] * sy - h / 2f
                    hits.add(Hit(headOf(left, top, w, h), score))
                }
            }
        }
        return removeOverlaps(hits)
    }

    /** The boxes cover the whole body, so the head is a square at the top-middle. */
    private fun headOf(left: Float, top: Float, w: Float, h: Float): RectF {
        val size = min(0.4f * w, 0.15f * h)
        val cx = left + w / 2f
        return RectF(cx - size / 2f, top, cx + size / 2f, top + size)
    }

    /** Keeps the best box when two head boxes sit on top of each other. */
    private fun removeOverlaps(hits: List<Hit>): List<RectF> {
        val kept = ArrayList<Hit>()
        for (hit in hits.sortedByDescending { it.score }) {
            if (kept.none { iou(it.box, hit.box) > 0.3f }) kept.add(hit)
        }
        return kept.map { it.box }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val LONG_SIDE = 1024     // bigger = finds smaller heads but is slower
        const val SCORE_MIN = 0.30f
    }
}
