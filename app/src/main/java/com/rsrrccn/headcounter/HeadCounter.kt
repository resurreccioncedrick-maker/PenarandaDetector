package com.rsrrccn.headcounter

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Counts every person once, even if the camera moves away and comes back.
 *
 * - Every counted person is remembered for the whole scan (never forgotten).
 * - People are remembered by where they are in the ROOM, not where they are on the screen.
 *   The app follows how the camera moves (by comparing the picture with the one before)
 *   and corrects the small errors by lining the heads up with the people it already counted.
 * - A person is counted only after being in view AND standing still for STILL_MS (3 seconds).
 *   Someone who walks around, or who leaves the view for more than a moment, starts over.
 *
 * Works best for people who stay in place (seated or standing).
 */
class HeadCounter {

    /** personNumber is null until the person is counted. */
    class Tracked(
        val body: RectF, val head: RectF, val personNumber: Int?, val scanning: Boolean,
        val progress: Float = 0f      // 0..1 = how much of the 3 seconds has passed
    )

    /** A small grey copy of the camera picture, used to follow the camera movement. */
    class Gray(val px: IntArray, val w: Int, val h: Int, val imgW: Int, val imgH: Int)

    private class Person(var x: Float, var y: Float, var size: Float, var lastSeenMs: Long) {
        var number: Int? = null
        var anchorX = x               // where the person was when they last started standing still
        var anchorY = y
        var stillSinceMs = lastSeenMs
    }

    private val people = ArrayList<Person>()
    private var prevGray: Gray? = null
    private var offX = 0f      // room position = screen position + off
    private var offY = 0f

    var total = 0
        private set

    fun reset() {
        people.clear()
        prevGray = null
        offX = 0f
        offY = 0f
        total = 0
    }

    /** Call when a scan starts or continues after a pause (the camera may have moved). */
    fun beginScan() {
        prevGray = null
    }

    /** Only draws head boxes, nothing is counted. */
    fun previewOnly(dets: List<HeadDetector.Det>): List<Tracked> = dets.map { Tracked(it.body, it.head, null, false) }

    fun update(dets: List<HeadDetector.Det>, gray: Gray, nowMs: Long): List<Tracked> {
        val heads = dets.map { it.head }   // each person is followed by the position of the head
        // 1. Where is the camera now? (follow the picture movement)
        var tx = offX
        var ty = offY
        val prev = prevGray
        if (prev != null) {
            val s = estimateShift(prev, gray)
            val k = gray.imgW / gray.w.toFloat()
            tx += s[0] * k
            ty += s[1] * k
        }
        // 2. Fix small errors by lining heads up with the people already counted
        val radius = if (prev == null) Float.MAX_VALUE else 0.12f * min(gray.imgW, gray.imgH)
        val fixed = alignWithKnownPeople(heads, tx, ty, radius)
        if (fixed != null) {
            tx = fixed[0]
            ty = fixed[1]
        }
        offX = tx
        offY = ty
        prevGray = gray

        // 3. Match heads to people (closest first)
        class Match(val p: Person, val d: Int, val dist: Float)
        val matches = ArrayList<Match>()
        for ((i, d) in heads.withIndex()) {
            val wx = d.centerX() + tx
            val wy = d.centerY() + ty
            for (p in people) {
                val dist = hypot(wx - p.x, wy - p.y)
                val limit = max(d.width(), p.size) * 1.2f + 4f
                if (dist < limit) matches.add(Match(p, i, dist))
            }
        }
        matches.sortBy { it.dist }

        val usedPeople = HashSet<Person>()
        val personOf = arrayOfNulls<Person>(heads.size)
        for (m in matches) {
            if (m.p in usedPeople || personOf[m.d] != null) continue
            usedPeople.add(m.p)
            personOf[m.d] = m.p
        }

        val result = ArrayList<Tracked>()
        for ((i, box) in heads.withIndex()) {
            val wx = box.centerX() + tx
            val wy = box.centerY() + ty
            var p = personOf[i]
            if (p == null) {
                p = Person(wx, wy, box.width(), nowMs)
                people.add(p)
            } else {
                p.x = 0.3f * p.x + 0.7f * wx
                p.y = 0.3f * p.y + 0.7f * wy
                p.size = 0.5f * p.size + 0.5f * box.width()
                // moved more than about one head width = walking, so the 3 seconds start again
                if (hypot(wx - p.anchorX, wy - p.anchorY) > max(p.size, 12f)) {
                    p.anchorX = wx
                    p.anchorY = wy
                    p.stillSinceMs = nowMs
                }
            }
            p.lastSeenMs = nowMs

            val stillMs = nowMs - p.stillSinceMs
            if (p.number == null && stillMs >= STILL_MS) {
                total++
                p.number = total
            }
            val progress = if (p.number != null) 1f else (stillMs / STILL_MS.toFloat()).coerceIn(0f, 1f)
            result.add(Tracked(dets[i].body, box, p.number, true, progress))
        }

        // Someone not seen for a moment starts over; people already counted are always kept
        people.removeAll { it.number == null && nowMs - it.lastSeenMs > UNCONFIRMED_MS }
        return result
    }

    // ---------- Lining up with people already counted ----------

    /** Every (head, known person) pair votes for a camera position; the most voted one wins. */
    private fun alignWithKnownPeople(heads: List<RectF>, px: Float, py: Float, radius: Float): FloatArray? {
        val known = people.filter { it.number != null }
        if (known.size < 3 || heads.size < 3) return null

        val sizes = heads.map { it.width() }.sorted()
        val cell = max(15f, sizes[sizes.size / 2] * 1.2f)
        // each vote cell holds: count, sum of x, sum of y
        val votes = HashMap<Long, FloatArray>()
        for (d in heads) {
            for (p in known) {
                val vx = p.x - d.centerX()
                val vy = p.y - d.centerY()
                if (abs(vx - px) > radius || abs(vy - py) > radius) continue
                val v = votes.getOrPut(key(floor(vx / cell).toInt(), floor(vy / cell).toInt())) { FloatArray(3) }
                v[0] += 1f
                v[1] += vx
                v[2] += vy
            }
        }
        if (votes.isEmpty()) return null

        fun around(cx: Int, cy: Int): FloatArray {
            val sum = FloatArray(3)
            for (a in -1..1) for (b in -1..1) {
                val v = votes[key(cx + a, cy + b)] ?: continue
                sum[0] += v[0]; sum[1] += v[1]; sum[2] += v[2]
            }
            return sum
        }

        var best: FloatArray? = null
        for (k in votes.keys) {
            val cx = (k shr 32).toInt()
            val cy = k.toInt()
            val s = around(cx, cy)
            if (best == null || s[0] > best[0]) best = s
        }
        val here = around(floor(px / cell).toInt(), floor(py / cell).toInt())[0]
        val b = best ?: return null
        val needed = max(4f, 0.35f * min(heads.size, known.size))
        if (b[0] < needed || b[0] < here + 2f) return null
        return floatArrayOf(b[1] / b[0], b[2] / b[0])
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    // ---------- Following the camera movement ----------

    /** How far the picture moved since the last frame, in grey-picture pixels (can be fractions). */
    private fun estimateShift(a: Gray, b: Gray): FloatArray {
        // Rough search on a tiny picture, then finer and finer
        val a4 = shrink(shrink(a.px, a.w, a.h), a.w / 2, a.h / 2)
        val b4 = shrink(shrink(b.px, b.w, b.h), b.w / 2, b.h / 2)
        val w4 = a.w / 4
        val h4 = a.h / 4
        val c = search(a4, b4, w4, h4, 0, 0, 8)
        val a2 = shrink(a.px, a.w, a.h)
        val b2 = shrink(b.px, b.w, b.h)
        val m = search(a2, b2, a.w / 2, a.h / 2, c[0] * 2, c[1] * 2, 2)
        val f = search(a.px, b.px, a.w, a.h, m[0] * 2, m[1] * 2, 2)
        // Sub-pixel: fit a curve through the best spot and its neighbours
        val sx = subPixel(a.px, b.px, a.w, a.h, f[0], f[1], true)
        val sy = subPixel(a.px, b.px, a.w, a.h, f[0], f[1], false)
        return floatArrayOf(f[0] + sx, f[1] + sy)
    }

    private fun shrink(src: IntArray, w: Int, h: Int): IntArray {
        val nw = w / 2
        val nh = h / 2
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = (2 * y) * w + 2 * x
            out[y * nw + x] = (src[i] + src[i + 1] + src[i + w] + src[i + w + 1]) / 4
        }
        return out
    }

    /** Average difference between the current picture and the previous one moved by (sx, sy). */
    private fun cost(prev: IntArray, cur: IntArray, w: Int, h: Int, sx: Int, sy: Int): Float {
        val x0 = max(0, -sx)
        val x1 = min(w, w - sx)
        val y0 = max(0, -sy)
        val y1 = min(h, h - sy)
        if (x1 - x0 < w * 0.6f || y1 - y0 < h * 0.6f) return Float.MAX_VALUE   // too little overlap
        var sum = 0L
        for (y in y0 until y1) {
            val rowC = y * w
            val rowP = (y + sy) * w + sx
            for (x in x0 until x1) sum += abs(cur[rowC + x] - prev[rowP + x])
        }
        return sum.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    private fun search(prev: IntArray, cur: IntArray, w: Int, h: Int, cx: Int, cy: Int, range: Int): IntArray {
        var bestX = cx
        var bestY = cy
        var best = Float.MAX_VALUE
        for (sy in cy - range..cy + range) for (sx in cx - range..cx + range) {
            // tiny penalty so a plain wall does not make the estimate jump around
            val c = cost(prev, cur, w, h, sx, sy) + 0.05f * (abs(sx) + abs(sy))
            if (c < best) {
                best = c
                bestX = sx
                bestY = sy
            }
        }
        return intArrayOf(bestX, bestY)
    }

    private fun subPixel(prev: IntArray, cur: IntArray, w: Int, h: Int, sx: Int, sy: Int, horizontal: Boolean): Float {
        val dx = if (horizontal) 1 else 0
        val dy = if (horizontal) 0 else 1
        val l = cost(prev, cur, w, h, sx - dx, sy - dy)
        val c = cost(prev, cur, w, h, sx, sy)
        val r = cost(prev, cur, w, h, sx + dx, sy + dy)
        if (l == Float.MAX_VALUE || r == Float.MAX_VALUE || c == Float.MAX_VALUE) return 0f
        val denom = l - 2f * c + r
        if (denom <= 1e-6f) return 0f
        return (0.5f * (l - r) / denom).coerceIn(-0.5f, 0.5f)
    }

    companion object {
        const val STILL_MS = 3000L        // must be in view and standing still this long to be counted
        const val UNCONFIRMED_MS = 1000L  // a gap longer than this means "not in view"

        /** Makes the small grey picture used to follow the camera movement. */
        fun makeGray(bmp: Bitmap): Gray {
            val gw = 144
            val gh = ((gw * bmp.height / bmp.width.toFloat()).roundToInt() / 4) * 4
            val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
            val px = IntArray(gw * gh)
            small.getPixels(px, 0, gw, 0, 0, gw, gh)
            for (i in px.indices) {
                val p = px[i]
                px[i] = (((p shr 16) and 255) * 30 + ((p shr 8) and 255) * 59 + (p and 255) * 11) / 100
            }
            if (small !== bmp) small.recycle()
            return Gray(px, gw, gh, bmp.width, bmp.height)
        }
    }
}
