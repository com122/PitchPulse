package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.PitchConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metric frames around a pitch.
 *
 * - ENU: local east/north meters with the pitch centre as origin (GeoMath tangent plane).
 * - Pitch frame (u, v): u along the length, positive towards the bearing (normalised x = 1 end);
 *   v across the width, positive to the right of the bearing (normalised y = 1 side).
 *
 * Uses exactly the rotation of [PitchProjector]: nx = 0.5 + u / length, ny = 0.5 + v / width.
 */
class PitchFrame(val pitch: PitchConfig) {
    private val theta = Math.toRadians(pitch.bearingDegrees.toDouble())
    private val c = cos(theta)
    private val s = sin(theta)

    val length: Double = pitch.lengthMeters.toDouble()
    val width: Double = pitch.widthMeters.toDouble()
    val halfLength: Double = length / 2
    val halfWidth: Double = width / 2

    fun enu(lat: Double, lng: Double): DoubleArray = GeoMath.toLocal(lat, lng, pitch.centerLat, pitch.centerLng)

    fun latLng(e: Double, n: Double): DoubleArray = GeoMath.fromLocal(e, n, pitch.centerLat, pitch.centerLng)

    fun toUv(e: Double, n: Double): DoubleArray = doubleArrayOf(e * s + n * c, e * c - n * s)

    fun toEn(u: Double, v: Double): DoubleArray = doubleArrayOf(v * c + u * s, -v * s + u * c)

    fun normalizedToUv(nx: Double, ny: Double): DoubleArray = doubleArrayOf((nx - 0.5) * length, (ny - 0.5) * width)

    fun normalizedToEn(nx: Double, ny: Double): DoubleArray {
        val uv = normalizedToUv(nx, ny)
        return toEn(uv[0], uv[1])
    }

    /** Compass azimuth (0..360, clockwise from north) of a direction given in the pitch frame. */
    fun azimuthOf(du: Double, dv: Double): Double {
        val en = toEn(du, dv)
        return GeoMath.normalizeDegrees(Math.toDegrees(atan2(en[0], en[1])))
    }

    companion object {
        private val WINDS = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

        /** 8-wind Chinese compass label, e.g. "西北". */
        fun compassLabel(azimuth: Double): String {
            val idx = (((GeoMath.normalizeDegrees(azimuth) + 22.5) / 45.0).toInt()) % 8
            return WINDS[idx]
        }
    }
}
