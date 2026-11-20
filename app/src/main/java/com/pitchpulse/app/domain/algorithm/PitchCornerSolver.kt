package com.pitchpulse.app.domain.algorithm

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Solves pitch centre / size / bearing from the 4 corner flags (WGS-84).
 *
 * Corners may be tapped in any order: they are first sorted by angle around their centroid,
 * which yields a proper perimeter order. Opposite sides are averaged to absorb tapping error.
 * The long pair of sides defines the length and the bearing.
 */
object PitchCornerSolver {

    data class Solution(
        val centerLat: Double,
        val centerLng: Double,
        val lengthMeters: Float,
        val widthMeters: Float,
        /** Clockwise from north, 0..180 (a pitch is symmetric, so 180° apart is the same pitch). */
        val bearingDegrees: Float,
    )

    /** @param corners exactly 4 (lat, lng) pairs in WGS-84. */
    fun solve(corners: List<DoubleArray>): Solution {
        require(corners.size == 4) { "需要 4 个角点" }
        val lat0 = corners.sumOf { it[0] } / 4
        val lng0 = corners.sumOf { it[1] } / 4
        val local = corners.map { GeoMath.toLocal(it[0], it[1], lat0, lng0) }
            .sortedBy { atan2(it[1], it[0]) }

        fun vec(from: Int, to: Int) =
            doubleArrayOf(local[to][0] - local[from][0], local[to][1] - local[from][1])
        fun len(v: DoubleArray) = hypot(v[0], v[1])

        val s01 = vec(0, 1); val s32 = vec(3, 2) // one pair of opposite sides (same direction)
        val s12 = vec(1, 2); val s03 = vec(0, 3) // the other pair
        val pairA = (len(s01) + len(s32)) / 2
        val pairB = (len(s12) + len(s03)) / 2

        val (longA, longB, length, width) = if (pairA >= pairB) {
            Quad(s01, s32, pairA, pairB)
        } else {
            Quad(s12, s03, pairB, pairA)
        }
        val axisE = longA[0] + longB[0]
        val axisN = longA[1] + longB[1]
        var bearing = GeoMath.normalizeDegrees(Math.toDegrees(atan2(axisE, axisN)))
        if (bearing >= 180.0) bearing -= 180.0

        // Centre = centroid of the 4 corners (already the local origin).
        return Solution(
            centerLat = lat0,
            centerLng = lng0,
            lengthMeters = length.toFloat(),
            widthMeters = width.toFloat(),
            bearingDegrees = bearing.toFloat(),
        )
    }

    private data class Quad(val a: DoubleArray, val b: DoubleArray, val length: Double, val width: Double)
}
