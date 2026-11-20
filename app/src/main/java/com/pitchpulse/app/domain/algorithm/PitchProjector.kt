package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.cos
import kotlin.math.sin

/** A point in normalised pitch space: nx along the length (goal to goal), ny across the width. */
data class ProjectedPoint(
    val nx: Float,
    val ny: Float,
    val phase: Int,
    val speedMs: Float,
    /** Index into the clean list this point came from. */
    val cleanIndex: Int,
    /** True if the polyline must not connect this point to the previous one. */
    val breakBefore: Boolean,
    /** Time weight in seconds (sample interval), used by the heatmap. */
    val weight: Float,
)

/**
 * Projects WGS-84 positions into normalised pitch coordinates.
 *
 * 1. Local east/north meters relative to the pitch centre.
 * 2. Rotate by the bearing so the pitch's long axis lines up with the y' axis:
 *    x' =  e·cosθ − n·sinθ  (across, pointing to the right of the bearing)
 *    y' =  e·sinθ + n·cosθ  (along the bearing)
 *    i.e. the standard 2-D rotation matrix applied with angle θ, which undoes ("回正") the
 *    clockwise bearing −θ of the pitch.
 * 3. nx = 0.5 + y'/length, ny = 0.5 + x'/width, so the pitch maps to [0,1] x [0,1].
 *
 * Drawn with nx to the right and ny downwards this is a proper rotation of the map (no mirror).
 */
class PitchProjector(private val pitch: PitchConfig) {

    private val theta = Math.toRadians(pitch.bearingDegrees.toDouble())
    private val cosT = cos(theta)
    private val sinT = sin(theta)

    fun normalize(lat: Double, lng: Double): FloatArray {
        val en = GeoMath.toLocal(lat, lng, pitch.centerLat, pitch.centerLng)
        val e = en[0]
        val n = en[1]
        val xPrime = e * cosT - n * sinT
        val yPrime = e * sinT + n * cosT
        val nx = 0.5 + yPrime / pitch.lengthMeters
        val ny = 0.5 + xPrime / pitch.widthMeters
        return floatArrayOf(nx.toFloat(), ny.toFloat())
    }

    /** Inverse of [normalize]; returns (lat, lng). */
    fun denormalize(nx: Double, ny: Double): DoubleArray {
        val yPrime = (nx - 0.5) * pitch.lengthMeters
        val xPrime = (ny - 0.5) * pitch.widthMeters
        // Inverse rotation (transpose).
        val e = xPrime * cosT + yPrime * sinT
        val n = -xPrime * sinT + yPrime * cosT
        return GeoMath.fromLocal(e, n, pitch.centerLat, pitch.centerLng)
    }

    /** Pitch corners in WGS-84, ordered (0,0), (1,0), (1,1), (0,1). */
    fun cornersLatLng(): List<DoubleArray> = listOf(
        denormalize(0.0, 0.0), denormalize(1.0, 0.0), denormalize(1.0, 1.0), denormalize(0.0, 1.0),
    )

    /**
     * Projects clean points, discarding those outside [-0.05, 1.05] on either axis
     * (bench, water breaks) so they don't create false hot spots along the touchline.
     * With [swapSecondHalf] the second half is mirrored through the centre: (1 − nx, 1 − ny),
     * so both halves are shown attacking the same direction.
     */
    fun project(clean: List<CleanPoint>, swapSecondHalf: Boolean): List<ProjectedPoint> {
        val out = ArrayList<ProjectedPoint>(clean.size)
        var previousKept = -2
        for (i in clean.indices) {
            val c = clean[i]
            val xy = normalize(c.point.lat, c.point.lng)
            var nx = xy[0]
            var ny = xy[1]
            if (nx < MIN || nx > MAX || ny < MIN || ny > MAX) continue
            if (swapSecondHalf && c.point.p == TrackPoint.PHASE_SECOND_HALF) {
                nx = 1f - nx
                ny = 1f - ny
            }
            val weight = if (c.segmentStart || c.dtMs <= 0) 1f else (c.dtMs / 1000f).coerceIn(0.2f, 10f)
            out += ProjectedPoint(
                nx = nx,
                ny = ny,
                phase = c.point.p,
                speedMs = c.speedMs,
                cleanIndex = i,
                breakBefore = c.segmentStart || previousKept != i - 1,
                weight = weight,
            )
            previousKept = i
        }
        return out
    }

    companion object {
        const val MIN = -0.05f
        const val MAX = 1.05f
    }
}
