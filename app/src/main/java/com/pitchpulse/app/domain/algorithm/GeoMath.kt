package com.pitchpulse.app.domain.algorithm

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local tangent-plane (east/north, meters) helpers on the WGS-84 ellipsoid.
 * Over pitch-sized extents (< 200 m) the error of this projection is well under 1 cm.
 */
object GeoMath {
    private const val WGS84_A = 6378137.0
    private const val WGS84_E2 = 6.69437999014e-3
    private const val EARTH_MEAN_RADIUS = 6371008.8

    /** Meridional and prime-vertical radii of curvature at [latDeg]. */
    private fun radii(latDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val s = sin(phi)
        val w = 1 - WGS84_E2 * s * s
        val m = WGS84_A * (1 - WGS84_E2) / (w * sqrt(w))
        val n = WGS84_A / sqrt(w)
        return m to n
    }

    /** Returns (east, north) meters of (lat, lng) relative to the origin (lat0, lng0). */
    fun toLocal(lat: Double, lng: Double, lat0: Double, lng0: Double): DoubleArray {
        val (m, n) = radii(lat0)
        val north = Math.toRadians(lat - lat0) * m
        val east = Math.toRadians(lng - lng0) * n * cos(Math.toRadians(lat0))
        return doubleArrayOf(east, north)
    }

    /** Inverse of [toLocal]: returns (lat, lng). */
    fun fromLocal(east: Double, north: Double, lat0: Double, lng0: Double): DoubleArray {
        val (m, n) = radii(lat0)
        val lat = lat0 + Math.toDegrees(north / m)
        val lng = lng0 + Math.toDegrees(east / (n * cos(Math.toRadians(lat0))))
        return doubleArrayOf(lat, lng)
    }

    /** Great-circle distance in meters (haversine). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_MEAN_RADIUS * asin(min(1.0, sqrt(a)))
    }

    fun normalizeDegrees(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
