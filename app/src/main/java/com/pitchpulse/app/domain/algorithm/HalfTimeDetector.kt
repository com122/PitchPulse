package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.TrackPoint

/**
 * Assigns phases to tracks that don't carry them (e.g. GPX files).
 *
 * 1. If there is a recording gap >= 3 min in the middle half of the session, that gap is half-time.
 * 2. Otherwise the longest run of >= 5 consecutive minutes averaging < 0.45 m/s (on 10 s position
 *    centroids, to suppress GPS jitter) that starts in the
 *    middle half is treated as half-time (points inside become p = 0).
 * 3. Otherwise everything is first half.
 */
object HalfTimeDetector {
    private const val MIN_GAP_MS = 3 * 60_000L
    private const val BUCKET_MS = 60_000L
    private const val MIN_REST_BUCKETS = 5
    private const val SUB_BUCKET_MS = 10_000L
    /** m/s of smoothed movement below which a minute counts as resting. */
    private const val REST_SPEED = 0.45

    fun assignPhases(points: List<TrackPoint>): List<TrackPoint> {
        val sorted = points.sortedBy { it.t }
        if (sorted.size < 2) return sorted.map { it.copy(p = TrackPoint.PHASE_FIRST_HALF) }
        val t0 = sorted.first().t
        val total = sorted.last().t - t0
        if (total < 20 * 60_000L) return sorted.map { it.copy(p = TrackPoint.PHASE_FIRST_HALF) }
        val lo = t0 + total / 4
        val hi = t0 + total * 3 / 4

        // 1. Largest gap.
        var bestGap = 0L
        var gapIndex = -1
        for (i in 1 until sorted.size) {
            val gap = sorted[i].t - sorted[i - 1].t
            if (sorted[i - 1].t in lo..hi && gap > bestGap) { bestGap = gap; gapIndex = i }
        }
        if (gapIndex > 0 && bestGap >= MIN_GAP_MS) {
            return sorted.mapIndexed { i, p ->
                p.copy(p = if (i < gapIndex) TrackPoint.PHASE_FIRST_HALF else TrackPoint.PHASE_SECOND_HALF)
            }
        }

        // 2. Longest slow stretch. Positions are first averaged into 10 s centroids so that GPS
        //    jitter of a standing player doesn't look like movement; the path through the
        //    centroids gives the movement per minute.
        val buckets = ((total / BUCKET_MS) + 1).toInt()
        val subPerBucket = (BUCKET_MS / SUB_BUCKET_MS).toInt()
        val subCount = buckets * subPerBucket
        val sumLat = DoubleArray(subCount)
        val sumLng = DoubleArray(subCount)
        val cnt = IntArray(subCount)
        for (p in sorted) {
            if (p.acc > 15f) continue
            val k = ((p.t - t0) / SUB_BUCKET_MS).toInt().coerceIn(0, subCount - 1)
            sumLat[k] += p.lat; sumLng[k] += p.lng; cnt[k]++
        }
        val dist = DoubleArray(buckets)
        val dur = LongArray(buckets)
        var prev = -1
        for (k in 0 until subCount) {
            if (cnt[k] == 0) continue
            if (prev >= 0 && k - prev <= 3) {
                val d = GeoMath.distanceMeters(
                    sumLat[prev] / cnt[prev], sumLng[prev] / cnt[prev], sumLat[k] / cnt[k], sumLng[k] / cnt[k],
                )
                val b = k / subPerBucket
                dist[b] += d
                dur[b] += (k - prev) * SUB_BUCKET_MS
            }
            prev = k
        }
        var bestStart = -1; var bestLen = 0
        var runStart = -1
        for (k in 0..buckets) {
            val slow = k < buckets && dur[k] > 0 && dist[k] / (dur[k] / 1000.0) < REST_SPEED
            if (slow) {
                if (runStart < 0) runStart = k
            } else if (runStart >= 0) {
                val len = k - runStart
                val startT = t0 + runStart * BUCKET_MS
                if (len > bestLen && startT in lo..hi) { bestLen = len; bestStart = runStart }
                runStart = -1
            }
        }
        if (bestStart >= 0 && bestLen >= MIN_REST_BUCKETS) {
            val restStart = t0 + bestStart * BUCKET_MS
            val restEnd = restStart + bestLen * BUCKET_MS
            return sorted.map { p ->
                val phase = when {
                    p.t < restStart -> TrackPoint.PHASE_FIRST_HALF
                    p.t < restEnd -> TrackPoint.PHASE_PAUSED
                    else -> TrackPoint.PHASE_SECOND_HALF
                }
                p.copy(p = phase)
            }
        }
        return sorted.map { it.copy(p = TrackPoint.PHASE_FIRST_HALF) }
    }
}
