package com.pitchpulse.app.domain.algorithm

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tier C: offline fixed-interval smoothing (Kalman forward filter + Rauch–Tung–Striebel backward pass).
 *
 * State per segment: [e, n, ve, vn] (pitch ENU, m and m/s). Constant-velocity model driven by white
 * acceleration noise with spectral density q = σ_a² (m²/s³), i.e. per 1 s step the velocity
 * random walk has σ ≈ σ_a (default 2 m/s², chosen by a simulator sweep of distance error; without
 * device speed 0.75 × σ_a, the stiffest value that kept distance error within ~8 % — position-only
 * velocity at 1 Hz is noisy, so sprint counts from GPX-like input are low-confidence).
 *
 * Measurements:
 * - position, prior σ = max(acc · accScale, 2.5 m), then scaled by a data-driven factor
 *   (see [Config.adaptiveR]). Wrist GNSS chips output already-filtered, strongly time-correlated
 *   positions, so treating `acc` as white noise over-smooths: in simulation that under-counted
 *   distance by 10–27 %. The scale is picked from a grid so that the mean normalised innovation
 *   squared is closest to 2 (its expected value for a consistent 2-D filter);
 * - optionally the device's Doppler speed: above 1 m/s as |v| (EKF scalar update, σ = 0.3 m/s);
 *   below 1 m/s as a linear "velocity ≈ 0" update with per-axis variance spd²/2 + σ², which stops
 *   position noise from creating motion while the player stands or walks.
 *
 * Outliers: a position whose Mahalanobis distance d² exceeds the χ²(2) gate (99 % → 9.21) is not
 * used. After [Config.maxConsecutiveRejects] consecutive rejections the filter restarts at the current
 * fix (a new segment). Segments also break at phase changes and gaps > 10 s; the RTS pass never
 * smooths across a break.
 */
class TrajectorySmoother(private val config: Config = Config()) {

    data class Config(
        val accelSigma: Double = 2.0,
        /** Without device speed, position noise must be suppressed by a stiffer motion model. */
        val noSpeedAccelFactor: Double = 0.75,
        val accScale: Double = 1.0,
        val minPosSigma: Double = 2.5,
        val gateChi2: Double = 9.21,
        val maxConsecutiveRejects: Int = 5,
        val maxGapMs: Long = 10_000,
        val useDeviceSpeed: Boolean = true,
        val speedSigma: Double = 0.3,
        /** Doppler magnitude update needs predicted and measured speed above this (direction defined). */
        val minSpeedForFusion: Double = 1.0,
        /** Below this Doppler speed the player is treated as (nearly) standing: velocity ≈ 0 update. */
        val zeroVelocityBelow: Double = 1.0,
        val maxSpeed: Double = 11.0,
        val initVelSigma: Double = 4.0,
        /**
         * Calibrate the position noise from the data: the reported `acc` mixes white noise (which
         * smoothing removes) with slow correlated error (which it cannot). The scale of R is chosen
         * so the mean normalised innovation squared is ≈ 2 (= χ²(2) mean), i.e. R matches the noise
         * the filter actually sees.
         */
        val adaptiveR: Boolean = true,
    )

    /** Result also reports the chosen R scale and the resulting mean NIS. */
    var lastRScale: Double = 1.0
        private set
    var lastMeanNis: Double = Double.NaN
        private set

    private val rScales = doubleArrayOf(0.02, 0.035, 0.06, 0.1, 0.15, 0.22, 0.33, 0.5, 0.7, 1.0, 1.4, 2.0)
    private var rScale = 1.0
    private var nisSum = 0.0
    private var nisCount = 0

    data class Smoothed(
        val sample: EnuSample,
        val e: Double,
        val n: Double,
        val ve: Double,
        val vn: Double,
        /** sqrt(P_ee + P_nn) after smoothing, m. */
        val posSigma: Double,
        /** True if this fix's position was rejected by the χ² gate. */
        val rejected: Boolean,
        /** True if a new segment starts here (first fix, gap, phase change or filter reset). */
        val segmentStart: Boolean,
    ) {
        val speed: Double get() = hypot(ve, vn)
    }

    data class Result(val states: List<Smoothed>, val rejected: Int, val resets: Int, val segments: Int)

    /** True when the track carries device speed (≥ 50 % of samples > 0), so spd = 0 means "standing". */
    private var hasDeviceSpeed = false

    fun smooth(samples: List<EnuSample>): Result {
        if (samples.isEmpty()) return Result(emptyList(), 0, 0, 0)
        hasDeviceSpeed = samples.count { it.spd > 0.0 } >= samples.size / 2
        rScale = 1.0
        if (config.adaptiveR && samples.size >= 120) {
            var best = 1.0
            var bestErr = Double.MAX_VALUE
            for (s in rScales) {
                rScale = s
                nisSum = 0.0; nisCount = 0
                pass(samples)
                if (nisCount < 60) continue
                val err = kotlin.math.abs(ln(nisSum / nisCount / 2.0))
                if (err < bestErr) { bestErr = err; best = s }
            }
            rScale = best
        }
        nisSum = 0.0; nisCount = 0
        val result = pass(samples)
        lastRScale = rScale
        lastMeanNis = if (nisCount == 0) Double.NaN else nisSum / nisCount
        return result
    }

    private fun pass(samples: List<EnuSample>): Result {
        val out = ArrayList<Smoothed>(samples.size)
        var rejectedTotal = 0
        var resets = 0
        var segments = 0

        var s = 0
        while (s < samples.size) {
            // Natural segment: same phase, no gap > maxGap.
            var e = s
            while (e + 1 < samples.size && samples[e + 1].phase == samples[s].phase &&
                samples[e + 1].ta - samples[e].ta in 1..config.maxGapMs) e++
            var from = s
            while (from <= e) {
                val r = runSegment(samples, from, e)
                segments++
                rejectedTotal += r.rejected
                out += r.states
                if (r.resetAt != null) { resets++; from = r.resetAt } else from = e + 1
            }
            s = e + 1
        }
        return Result(out, rejectedTotal, resets, segments)
    }

    private class SegmentRun(val states: List<Smoothed>, val rejected: Int, val resetAt: Int?)

    /** Filters [from]..[to]; stops early (returning resetAt) when the gate rejects too many fixes in a row. */
    private fun runSegment(samples: List<EnuSample>, from: Int, to: Int): SegmentRun {
        val n = to - from + 1
        val xf = Array(n) { DoubleArray(4) }      // filtered state
        val pf = Array(n) { DoubleArray(16) }     // filtered covariance
        val xp = Array(n) { DoubleArray(4) }      // predicted state (k | k−1)
        val pp = Array(n) { DoubleArray(16) }     // predicted covariance
        val fm = Array(n) { DoubleArray(16) }     // transition k−1 → k
        val rejectedFlags = BooleanArray(n)
        val sigmaA = if (config.useDeviceSpeed && hasDeviceSpeed) config.accelSigma else config.accelSigma * config.noSpeedAccelFactor
        val q = sigmaA * sigmaA

        val first = samples[from]
        val r0 = sigma(first)
        var x = doubleArrayOf(first.e, first.n, 0.0, 0.0)
        var p = Mat4.diag(r0 * r0, r0 * r0, config.initVelSigma * config.initVelSigma, config.initVelSigma * config.initVelSigma)
        if (config.useDeviceSpeed && hasDeviceSpeed && first.spd > config.minSpeedForFusion) {
            // Speed known, direction unknown: inflate both velocity variances to the speed scale.
            val v2 = first.spd * first.spd
            p[10] = max(p[10], v2); p[15] = max(p[15], v2)
        }
        xf[0] = x.copyOf(); pf[0] = p.copyOf(); xp[0] = x.copyOf(); pp[0] = p.copyOf(); fm[0] = Mat4.identity()

        var consecutive = 0
        var rejected = 0
        var last = 0
        for (k in 1 until n) {
            val smp = samples[from + k]
            val dt = (smp.ta - samples[from + k - 1].ta) / 1000.0
            val f = Mat4.identity().also { it[2] = dt; it[7] = dt }
            val qm = processNoise(dt, q)
            x = Mat4.mulVec(f, x)
            p = Mat4.add(Mat4.mul(Mat4.mul(f, p), Mat4.transpose(f)), qm)
            fm[k] = f; xp[k] = x.copyOf(); pp[k] = p.copyOf()

            // Position update with χ² gate.
            val r = sigma(smp)
            val ve = smp.e - x[0]
            val vn = smp.n - x[1]
            val s00 = p[0] + r * r
            val s01 = p[1]
            val s11 = p[5] + r * r
            val det = s00 * s11 - s01 * s01
            val d2 = (s11 * ve * ve - 2 * s01 * ve * vn + s00 * vn * vn) / det
            if (d2 > config.gateChi2) {
                rejectedFlags[k] = true
                rejected++
                consecutive++
                if (consecutive >= config.maxConsecutiveRejects) {
                    // Restart at the first of the rejected run so no data is dropped.
                    val restart = from + k - consecutive + 1
                    val kept = restart - from
                    return SegmentRun(rts(samples, from, kept, xf, pf, xp, pp, fm, rejectedFlags), rejected - consecutive, restart)
                }
            } else {
                consecutive = 0
                nisSum += d2; nisCount++
                val i00 = s11 / det; val i01 = -s01 / det; val i11 = s00 / det
                // K = P Hᵀ S⁻¹, H selects the position.
                val kg = DoubleArray(8)
                for (row in 0 until 4) {
                    val ph0 = p[row * 4]; val ph1 = p[row * 4 + 1]
                    kg[row * 2] = ph0 * i00 + ph1 * i01
                    kg[row * 2 + 1] = ph0 * i01 + ph1 * i11
                }
                for (row in 0 until 4) x[row] += kg[row * 2] * ve + kg[row * 2 + 1] * vn
                // Joseph-free form is fine here: P = (I − K H) P, symmetrised.
                val np = DoubleArray(16)
                for (row in 0 until 4) for (col in 0 until 4) {
                    np[row * 4 + col] = p[row * 4 + col] - kg[row * 2] * p[col] - kg[row * 2 + 1] * p[4 + col]
                }
                p = Mat4.symmetrize(np)
            }

            // Low Doppler speed: the velocity vector is small in an unknown direction → linear update
            // z = (0, 0) with per-axis variance spd²/2 + σ². Keeps standing/walking from being
            // driven by position noise.
            if (config.useDeviceSpeed && hasDeviceSpeed && smp.spd < config.zeroVelocityBelow) {
                val rv = smp.spd * smp.spd / 2 + config.speedSigma * config.speedSigma
                val s22 = p[10] + rv; val s23 = p[11]; val s33 = p[15] + rv
                val dv = s22 * s33 - s23 * s23
                val i22 = s33 / dv; val i23 = -s23 / dv; val i33 = s22 / dv
                val nu2 = -x[2]; val nu3 = -x[3]
                val kg = DoubleArray(8)
                for (row in 0 until 4) {
                    val a = p[row * 4 + 2]; val b = p[row * 4 + 3]
                    kg[row * 2] = a * i22 + b * i23
                    kg[row * 2 + 1] = a * i23 + b * i33
                }
                for (row in 0 until 4) x[row] += kg[row * 2] * nu2 + kg[row * 2 + 1] * nu3
                val np = DoubleArray(16)
                for (row in 0 until 4) for (col in 0 until 4) {
                    np[row * 4 + col] = p[row * 4 + col] - kg[row * 2] * p[8 + col] - kg[row * 2 + 1] * p[12 + col]
                }
                p = Mat4.symmetrize(np)
            }

            // Doppler speed magnitude update (scalar EKF), skipped for implausible values.
            if (config.useDeviceSpeed && hasDeviceSpeed && smp.spd >= config.zeroVelocityBelow && smp.spd >= config.minSpeedForFusion && smp.spd <= config.maxSpeed) {
                val sp = hypot(x[2], x[3])
                if (sp > config.minSpeedForFusion) {
                    val h2 = x[2] / sp; val h3 = x[3] / sp
                    val ph = DoubleArray(4) { row -> p[row * 4 + 2] * h2 + p[row * 4 + 3] * h3 }
                    val sv = h2 * ph[2] + h3 * ph[3] + config.speedSigma * config.speedSigma
                    val innov = smp.spd - sp
                    if (innov * innov / sv <= 6.63) { // χ²(1) 99 %
                        for (row in 0 until 4) x[row] += ph[row] / sv * innov
                        val np = DoubleArray(16)
                        for (row in 0 until 4) for (col in 0 until 4) np[row * 4 + col] = p[row * 4 + col] - ph[row] * ph[col] / sv
                        p = Mat4.symmetrize(np)
                    }
                }
            }
            xf[k] = x.copyOf(); pf[k] = p.copyOf()
            last = k
        }
        return SegmentRun(rts(samples, from, last + 1, xf, pf, xp, pp, fm, rejectedFlags), rejected, null)
    }

    /** RTS backward pass over the first [count] epochs of a segment. */
    private fun rts(
        samples: List<EnuSample>, from: Int, count: Int,
        xf: Array<DoubleArray>, pf: Array<DoubleArray>, xp: Array<DoubleArray>, pp: Array<DoubleArray>,
        fm: Array<DoubleArray>, rejected: BooleanArray,
    ): List<Smoothed> {
        val xs = Array(count) { xf[it].copyOf() }
        val ps = Array(count) { pf[it].copyOf() }
        for (k in count - 2 downTo 0) {
            val c = Mat4.mul(Mat4.mul(pf[k], Mat4.transpose(fm[k + 1])), Mat4.inverse(pp[k + 1]))
            val dx = DoubleArray(4) { xs[k + 1][it] - xp[k + 1][it] }
            val corr = Mat4.mulVec(c, dx)
            for (i in 0 until 4) xs[k][i] = xf[k][i] + corr[i]
            val dp = Mat4.sub(ps[k + 1], pp[k + 1])
            ps[k] = Mat4.symmetrize(Mat4.add(pf[k], Mat4.mul(Mat4.mul(c, dp), Mat4.transpose(c))))
        }
        return List(count) { k ->
            Smoothed(
                sample = samples[from + k],
                e = xs[k][0], n = xs[k][1], ve = xs[k][2], vn = xs[k][3],
                posSigma = sqrt(max(0.0, ps[k][0] + ps[k][5])),
                rejected = rejected[k],
                segmentStart = k == 0,
            )
        }
    }

    private fun sigma(s: EnuSample) = max(s.acc * config.accScale, config.minPosSigma) * sqrt(rScale)

    private fun processNoise(dt: Double, q: Double): DoubleArray {
        val a = q * dt * dt * dt / 3
        val b = q * dt * dt / 2
        val c = q * dt
        return doubleArrayOf(
            a, 0.0, b, 0.0,
            0.0, a, 0.0, b,
            b, 0.0, c, 0.0,
            0.0, b, 0.0, c,
        )
    }
}

/** Minimal row-major 4×4 matrix helpers. */
internal object Mat4 {
    fun identity() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }
    fun diag(a: Double, b: Double, c: Double, d: Double) = DoubleArray(16).also { it[0] = a; it[5] = b; it[10] = c; it[15] = d }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[i * 4 + k] * b[k * 4 + j]
            r[i * 4 + j] = s
        }
        return r
    }

    fun mulVec(a: DoubleArray, v: DoubleArray) = DoubleArray(4) { i ->
        a[i * 4] * v[0] + a[i * 4 + 1] * v[1] + a[i * 4 + 2] * v[2] + a[i * 4 + 3] * v[3]
    }

    fun transpose(a: DoubleArray) = DoubleArray(16) { a[(it % 4) * 4 + it / 4] }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { a[it] - b[it] }
    fun symmetrize(a: DoubleArray) = DoubleArray(16) { (a[it] + a[(it % 4) * 4 + it / 4]) / 2 }

    /** Gauss–Jordan inverse with partial pivoting. */
    fun inverse(a: DoubleArray): DoubleArray {
        val m = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) a[r * 4 + c] else if (c - 4 == r) 1.0 else 0.0 } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            require(kotlin.math.abs(d) > 1e-15) { "singular covariance" }
            for (c in 0 until 8) m[col][c] /= d
            for (r in 0 until 4) if (r != col) {
                val f = m[r][col]
                if (f != 0.0) for (c in 0 until 8) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(16) { m[it / 4][4 + it % 4] }
    }
}
