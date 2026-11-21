package com.pitchpulse.app.domain.algorithm

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Small numeric helpers shared by the accuracy-enhancement layers. */
object SignalFilters {
    /** ωτ at which the forward–backward first-order filter has amplitude 1/√2. */
    val HALF_POWER_WT: Double = sqrt(sqrt(2.0) - 1)

    /** Centred rolling median with an odd window; edges use the available (shrunk) window. */
    fun rollingMedian(values: DoubleArray, window: Int): DoubleArray {
        val w = if (window % 2 == 0) window + 1 else window
        val half = w / 2
        val out = DoubleArray(values.size)
        val buf = DoubleArray(w)
        for (i in values.indices) {
            val lo = maxOf(0, i - half)
            val hi = minOf(values.lastIndex, i + half)
            val n = hi - lo + 1
            for (k in 0 until n) buf[k] = values[lo + k]
            java.util.Arrays.sort(buf, 0, n)
            out[i] = if (n % 2 == 1) buf[n / 2] else (buf[n / 2 - 1] + buf[n / 2]) / 2
        }
        return out
    }

    /**
     * Zero-phase low-pass: a first-order IIR run forwards then backwards.
     * The two passes give amplitude response 1 / (1 + (ωτ)²); choosing ωτ = √(√2 − 1) ≈ 0.6436 at
     * period T makes T the half-power ("cutoff") period.
     */
    fun forwardBackwardLowPass(values: DoubleArray, cutoffPeriodSec: Double, dtSec: Double = 1.0): DoubleArray {
        if (values.isEmpty()) return values
        val tau = HALF_POWER_WT * cutoffPeriodSec / (2 * Math.PI)
        val alpha = dtSec / (tau + dtSec)
        val f = DoubleArray(values.size)
        f[0] = values[0]
        for (i in 1 until values.size) f[i] = f[i - 1] + alpha * (values[i] - f[i - 1])
        val b = DoubleArray(values.size)
        b[values.lastIndex] = f[values.lastIndex]
        for (i in values.lastIndex - 1 downTo 0) b[i] = b[i + 1] + alpha * (f[i] - b[i + 1])
        return b
    }

    /** Spike-robust zero-phase low-pass: rolling median (removes multipath spikes) then [forwardBackwardLowPass]. */
    fun robustLowPass(values: DoubleArray, cutoffPeriodSec: Double, medianWindow: Int = 15): DoubleArray =
        forwardBackwardLowPass(rollingMedian(values, medianWindow), cutoffPeriodSec)

    /** Linear-interpolated percentile (q in 0..1) of an unsorted list. NaN when empty. */
    fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sortedArray()
        val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.lastIndex)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun percentile(values: List<Double>, q: Double): Double = percentile(values.toDoubleArray(), q)

    fun median(values: DoubleArray): Double = percentile(values, 0.5)

    fun rms(values: DoubleArray): Double = if (values.isEmpty()) Double.NaN else sqrt(values.sumOf { it * it } / values.size)

    /** Weiszfeld geometric median of 2-D points. Robust to up to 50 % outliers. */
    fun geometricMedian(points: List<DoubleArray>, iterations: Int = 100, tol: Double = 1e-4): DoubleArray {
        require(points.isNotEmpty())
        var x = points.sumOf { it[0] } / points.size
        var y = points.sumOf { it[1] } / points.size
        repeat(iterations) {
            var wx = 0.0; var wy = 0.0; var wsum = 0.0
            for (p in points) {
                val d = hypot(p[0] - x, p[1] - y).coerceAtLeast(1e-6)
                val w = 1.0 / d
                wx += p[0] * w; wy += p[1] * w; wsum += w
            }
            val nx = wx / wsum
            val ny = wy / wsum
            val step = hypot(nx - x, ny - y)
            x = nx; y = ny
            if (step < tol) return doubleArrayOf(x, y)
        }
        return doubleArrayOf(x, y)
    }

    /** Relative change (b - a) / |a|, 0 when both are ~0. */
    fun relChange(before: Double, after: Double): Double =
        if (abs(before) < 1e-9) (if (abs(after) < 1e-9) 0.0 else Double.POSITIVE_INFINITY) else (after - before) / abs(before)
}
