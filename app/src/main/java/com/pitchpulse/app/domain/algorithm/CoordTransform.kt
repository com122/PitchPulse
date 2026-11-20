package com.pitchpulse.app.domain.algorithm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 <-> GCJ-02 ("Mars coordinates") conversion.
 *
 * Only used when the user picks points on the AMap (高德) satellite layer, whose imagery is
 * offset into GCJ-02. Everything persisted by the app is WGS-84.
 *
 * wgs84ToGcj02 is the standard published forward transform. gcj02ToWgs84 inverts it with a
 * fixed-point iteration, which converges to < 1e-9 degrees (sub-millimetre) in a few steps,
 * instead of the common one-step approximation that is off by 1-2 m.
 */
object CoordTransform {
    private const val A = 6378245.0                 // Krasovsky 1940 semi-major axis
    private const val EE = 0.00669342162296594323   // Krasovsky 1940 eccentricity squared

    data class LatLng(val lat: Double, val lng: Double)

    fun isOutOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        val (dLat, dLng) = delta(lat, lng)
        return LatLng(lat + dLat, lng + dLng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        var wLat = lat
        var wLng = lng
        repeat(MAX_ITERATIONS) {
            val g = wgs84ToGcj02(wLat, wLng)
            val errLat = g.lat - lat
            val errLng = g.lng - lng
            wLat -= errLat
            wLng -= errLng
            if (abs(errLat) < EPSILON && abs(errLng) < EPSILON) return LatLng(wLat, wLng)
        }
        return LatLng(wLat, wLng)
    }

    private const val MAX_ITERATIONS = 30
    private const val EPSILON = 1e-10

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
