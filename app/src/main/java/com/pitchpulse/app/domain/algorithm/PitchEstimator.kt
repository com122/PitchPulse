package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Estimates a pitch rectangle from a track when no saved pitch is nearby:
 * principal component analysis gives the long axis, the 2nd–98th percentile extents give the size.
 */
object PitchEstimator {

    fun centroid(points: List<TrackPoint>): DoubleArray? {
        val valid = points.filter { it.acc <= 15f && it.p != TrackPoint.PHASE_PAUSED }
            .ifEmpty { points }
        if (valid.isEmpty()) return null
        return doubleArrayOf(valid.sumOf { it.lat } / valid.size, valid.sumOf { it.lng } / valid.size)
    }

    fun estimate(points: List<TrackPoint>, id: String, name: String): PitchConfig? {
        val valid = points.filter { it.acc <= 15f && it.p != TrackPoint.PHASE_PAUSED }
        if (valid.size < 30) return null
        val c = centroid(valid) ?: return null
        val local = valid.map { GeoMath.toLocal(it.lat, it.lng, c[0], c[1]) }

        var see = 0.0; var snn = 0.0; var sen = 0.0
        for (p in local) { see += p[0] * p[0]; snn += p[1] * p[1]; sen += p[0] * p[1] }
        // Angle of the principal axis measured counter-clockwise from east.
        val phi = 0.5 * atan2(2 * sen, see - snn)
        val ue = cos(phi); val un = sin(phi)          // long axis unit vector
        val ve = -un; val vn = ue                     // perpendicular

        val along = local.map { it[0] * ue + it[1] * un }.sorted()
        val across = local.map { it[0] * ve + it[1] * vn }.sorted()
        fun pct(list: List<Double>, q: Double) = list[((list.size - 1) * q).toInt()]
        val a0 = pct(along, 0.02); val a1 = pct(along, 0.98)
        val b0 = pct(across, 0.02); val b1 = pct(across, 0.98)

        var length = ((a1 - a0) * 1.05).toFloat()
        var width = ((b1 - b0) * 1.05).toFloat()
        if (width > length) { val t = length; length = width; width = t }
        length = length.coerceIn(25f, 120f)
        width = width.coerceIn(15f, 90f)

        val midAlong = (a0 + a1) / 2
        val midAcross = (b0 + b1) / 2
        val centreE = midAlong * ue + midAcross * ve
        val centreN = midAlong * un + midAcross * vn
        val centre = GeoMath.fromLocal(centreE, centreN, c[0], c[1])

        var bearing = GeoMath.normalizeDegrees(90.0 - Math.toDegrees(phi))
        if (bearing >= 180.0) bearing -= 180.0

        return PitchConfig(
            id = id,
            name = name,
            pitchType = PitchConfig.closestType(length, width),
            centerLat = centre[0],
            centerLng = centre[1],
            lengthMeters = length,
            widthMeters = width,
            bearingDegrees = bearing.toFloat(),
        )
    }
}
