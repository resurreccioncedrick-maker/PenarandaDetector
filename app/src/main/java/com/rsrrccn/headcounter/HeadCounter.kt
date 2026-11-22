package com.rsrrccn.headcounter

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max

/**
 * Counts every person once.
 *
 * Each head gets a track. A person is counted after being matched CONFIRM_HITS times.
 * If someone disappears for a moment and shows up again in the same spot, they keep
 * the same track, so they are NOT counted twice.
 */
class HeadCounter {

    /** personNumber is null until the person is counted. */
    class Tracked(val head: RectF, val personNumber: Int?, val scanning: Boolean)

    private class Track(var box: RectF, var lastSeenMs: Long) {
        var vx = 0f
        var vy = 0f
        var hits = 0
        var number: Int? = null
    }

    private val tracks = ArrayList<Track>()

    var total = 0
        private set

    fun reset() {
        tracks.clear()
        total = 0
    }

    /** Only draws head boxes, nothing is counted. */
    fun previewOnly(heads: List<RectF>): List<Tracked> = heads.map { Tracked(it, null, false) }

    fun update(heads: List<RectF>, nowMs: Long): List<Tracked> {
        // Match heads to tracks: closest pairs first, within a distance limit
        class Pair(val t: Track, val d: Int, val dist: Float)
        val pairs = ArrayList<Pair>()
        for (t in tracks) {
            val px = t.box.centerX() + t.vx
            val py = t.box.centerY() + t.vy
            for ((i, d) in heads.withIndex()) {
                val dist = hypot(d.centerX() - px, d.centerY() - py)
                val limit = max(d.width(), t.box.width()) * 1.5f + 6f
                if (dist < limit) pairs.add(Pair(t, i, dist))
            }
        }
        pairs.sortBy { it.dist }

        val usedTracks = HashSet<Track>()
        val trackOf = arrayOfNulls<Track>(heads.size)
        for (p in pairs) {
            if (p.t in usedTracks || trackOf[p.d] != null) continue
            usedTracks.add(p.t)
            trackOf[p.d] = p.t
        }

        val result = ArrayList<Tracked>()
        for ((i, box) in heads.withIndex()) {
            var t = trackOf[i]
            if (t == null) {
                t = Track(RectF(box), nowMs)
                tracks.add(t)
            } else {
                t.vx = (box.centerX() - t.box.centerX()) * 0.5f
                t.vy = (box.centerY() - t.box.centerY()) * 0.5f
                t.box = RectF(box)
            }
            t.hits++
            t.lastSeenMs = nowMs

            if (t.number == null && t.hits >= CONFIRM_HITS) {
                total++
                t.number = total
            }
            result.add(Tracked(box, t.number, true))
        }

        // Forget old tracks (unconfirmed ones go quickly, counted ones are remembered longer)
        tracks.removeAll { nowMs - it.lastSeenMs > (if (it.number == null) UNCONFIRMED_MS else LOST_MS) }
        return result
    }

    companion object {
        const val CONFIRM_HITS = 3        // detections needed before a person is counted
        const val LOST_MS = 8000L         // counted people are remembered this long
        const val UNCONFIRMED_MS = 2500L
    }
}
