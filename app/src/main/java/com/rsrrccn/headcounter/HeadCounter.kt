package com.rsrrccn.headcounter

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Counts every person once.
 *
 * Each detected person gets a track. A person is only counted after being seen
 * CONFIRM_FRAMES frames in a row (so one-frame false detections never count).
 * A person who disappears for a moment and shows up again in the same spot keeps
 * the same track, so they are NOT counted twice.
 */
class HeadCounter {

    /** head = box drawn on the head (image coordinates). personNumber is null until counted. */
    class Tracked(val head: RectF, val personNumber: Int?, val scanning: Boolean)

    private class Track(var box: RectF, var lastSeenMs: Long) {
        var vx = 0f
        var vy = 0f
        var frames = 0
        var lastFrame = -1L
        var number: Int? = null
    }

    private val tracks = ArrayList<Track>()
    private var frameNo = 0L

    var total = 0
        private set

    fun reset() {
        tracks.clear()
        total = 0
        frameNo = 0
    }

    /** Only draws head boxes, nothing is counted. */
    fun previewOnly(persons: List<RectF>): List<Tracked> =
        persons.map { Tracked(headOf(it), null, false) }

    fun update(persons: List<RectF>, nowMs: Long): List<Tracked> {
        frameNo++

        // Match detections to tracks: closest pairs first, within a distance limit
        class Pair(val t: Track, val d: Int, val dist: Float)
        val pairs = ArrayList<Pair>()
        for (t in tracks) {
            val px = t.box.centerX() + t.vx
            val py = t.box.centerY() + t.vy
            for ((i, d) in persons.withIndex()) {
                val dist = hypot(d.centerX() - px, d.centerY() - py)
                val limit = max(max(d.width(), d.height()), max(t.box.width(), t.box.height())) * 0.6f
                if (dist < limit) pairs.add(Pair(t, i, dist))
            }
        }
        pairs.sortBy { it.dist }

        val usedTracks = HashSet<Track>()
        val trackOf = arrayOfNulls<Track>(persons.size)
        for (p in pairs) {
            if (p.t in usedTracks || trackOf[p.d] != null) continue
            usedTracks.add(p.t)
            trackOf[p.d] = p.t
        }

        val result = ArrayList<Tracked>()
        for ((i, box) in persons.withIndex()) {
            var t = trackOf[i]
            if (t == null) {
                t = Track(RectF(box), nowMs)
                tracks.add(t)
            } else {
                t.vx = (box.centerX() - t.box.centerX()) * 0.5f
                t.vy = (box.centerY() - t.box.centerY()) * 0.5f
                t.box = RectF(box)
            }
            t.frames = if (t.lastFrame == frameNo - 1) t.frames + 1 else 1
            t.lastFrame = frameNo
            t.lastSeenMs = nowMs

            if (t.number == null && t.frames >= CONFIRM_FRAMES) {
                total++
                t.number = total
            }
            result.add(Tracked(headOf(box), t.number, true))
        }

        // Forget old tracks (unconfirmed ones go quickly, counted ones are remembered longer)
        tracks.removeAll { nowMs - it.lastSeenMs > (if (it.number == null) UNCONFIRMED_MS else LOST_MS) }
        return result
    }

    /** Estimates the head box from the top of the person box. */
    private fun headOf(p: RectF): RectF {
        val size = min(p.width() * 0.45f, p.height() * 0.3f)
        val cx = p.centerX()
        return RectF(cx - size / 2f, p.top, cx + size / 2f, p.top + size)
    }

    companion object {
        const val CONFIRM_FRAMES = 3      // steady detection needed before counting
        const val LOST_MS = 8000L         // counted people are remembered this long
        const val UNCONFIRMED_MS = 2500L
    }
}
