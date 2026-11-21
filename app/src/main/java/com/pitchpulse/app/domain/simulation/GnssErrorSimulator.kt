package com.pitchpulse.app.domain.simulation

import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.domain.algorithm.PitchFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two-receiver GNSS error model for the reference-station tests.
 *
 * Error of each device = shared error c(t) + its own error:
 * - c(t) = constant bias + sum of slow sinusoids (periods 1–10 min, total RMS ~1–3 m per axis),
 *   identical for watch and phone (atmosphere, orbit/clock, geometry);
 * - own error = white noise + optional AR(1) slow wander + multipath bursts (watch) / spikes (phone),
 *   plus occasional low-accuracy fixes on the watch.
 *
 * Watch time stamps: tu = GNSS time, t = tu + clockOffset (device clock).
 */
class GnssErrorSimulator(val model: Model, private val random: Random) {

    data class Component(val periodSec: Double, val ampE: Double, val ampN: Double, val phaseE: Double, val phaseN: Double)

    data class Model(
        val biasE: Double = 0.0,
        val biasN: Double = 0.0,
        val components: List<Component> = emptyList(),
        val watchWhite: Double = 2.0,
        val watchSlowSigma: Double = 0.0,
        val watchSlowTauSec: Double = 120.0,
        /** Multipath bursts per hour (1–4 s, 8–30 m). */
        val watchMultipathPerHour: Double = 30.0,
        val watchPoorFixRate: Double = 0.003,
        val phoneWhite: Double = 1.2,
        val phoneSlowSigma: Double = 0.0,
        val phoneSlowTauSec: Double = 300.0,
        val phoneSpikePerHour: Double = 6.0,
        val watchClockOffsetMs: Long = 0,
        val watchHasTu: Boolean = true,
    ) {
        val commonRms: Double get() = sqrt(components.sumOf { (it.ampE * it.ampE + it.ampN * it.ampN) / 2 })

        companion object {
            /** Shared bias 1–3 m and shared slow error with per-axis RMS in [rmsRange]. */
            fun shared(random: Random, rmsRange: ClosedFloatingPointRange<Double> = 1.0..3.0, biasRange: ClosedFloatingPointRange<Double> = 1.0..3.0): Model {
                val bias = random.nextDouble(biasRange.start, biasRange.endInclusive)
                val dir = random.nextDouble(0.0, 2 * PI)
                val n = 3
                val rmsE = random.nextDouble(rmsRange.start, rmsRange.endInclusive)
                val rmsN = random.nextDouble(rmsRange.start, rmsRange.endInclusive)
                // Each sinusoid has RMS amp/√2; n of them with equal amplitude give RMS = amp·√(n/2).
                val ampE = rmsE / sqrt(n / 2.0)
                val ampN = rmsN / sqrt(n / 2.0)
                val comps = List(n) {
                    Component(
                        periodSec = random.nextDouble(60.0, 600.0),
                        ampE = ampE, ampN = ampN,
                        phaseE = random.nextDouble(0.0, 2 * PI), phaseN = random.nextDouble(0.0, 2 * PI),
                    )
                }
                return Model(biasE = bias * sin(dir), biasN = bias * cos(dir), components = comps)
            }
        }
    }

    fun common(tMs: Long): DoubleArray {
        val ts = tMs / 1000.0
        var e = model.biasE
        var n = model.biasN
        for (c in model.components) {
            val w = 2 * PI / c.periodSec
            e += c.ampE * sin(w * ts + c.phaseE)
            n += c.ampN * sin(w * ts + c.phaseN)
        }
        return doubleArrayOf(e, n)
    }

    /** Watch track from the truth. Returns points in contract format. */
    fun watch(truth: List<TruthSample>, frame: PitchFrame): List<TrackPoint> {
        var slowE = gaussian() * model.watchSlowSigma
        var slowN = gaussian() * model.watchSlowSigma
        val rho = exp(-1.0 / model.watchSlowTauSec)
        val k = sqrt(1 - rho * rho) * model.watchSlowSigma
        var burstLeft = 0
        var burstE = 0.0; var burstN = 0.0
        val baseAcc = sqrt(model.watchWhite * model.watchWhite + model.commonRms * model.commonRms +
            model.watchSlowSigma * model.watchSlowSigma + model.biasE * model.biasE + model.biasN * model.biasN)
        return truth.map { s ->
            slowE = rho * slowE + k * gaussian()
            slowN = rho * slowN + k * gaussian()
            val c = common(s.t)
            var de = c[0] + slowE + gaussian() * model.watchWhite
            var dn = c[1] + slowN + gaussian() * model.watchWhite
            var acc = baseAcc * (0.9 + 0.2 * random.nextDouble())
            if (burstLeft == 0 && random.nextDouble() < model.watchMultipathPerHour / 3600.0) {
                burstLeft = random.nextInt(1, 5)
                val ang = random.nextDouble(0.0, 2 * PI)
                val mag = random.nextDouble(8.0, 30.0)
                burstE = mag * sin(ang); burstN = mag * cos(ang)
            }
            if (burstLeft > 0) { de += burstE; dn += burstN; burstLeft-- }
            if (random.nextDouble() < model.watchPoorFixRate) {
                acc = random.nextDouble(16.0, 30.0)
                de += gaussian() * 12; dn += gaussian() * 12
            }
            val en = frame.enu(s.lat, s.lng)
            val ll = frame.latLng(en[0] + de, en[1] + dn)
            TrackPoint(
                t = s.t + model.watchClockOffsetMs,
                lat = ll[0], lng = ll[1],
                acc = acc.toFloat(),
                spd = max(0.0, s.speed + gaussian() * 0.12).toFloat(),
                hr = s.hr,
                p = s.phase,
                tu = if (model.watchHasTu) s.t else null,
            )
        }
    }

    /** Static phone at [lat]/[lng] logging 1 Hz fixes over [startMs, endMs]. */
    fun phone(lat: Double, lng: Double, startMs: Long, endMs: Long, frame: PitchFrame): List<ReferenceFix> {
        val home = frame.enu(lat, lng)
        var slowE = gaussian() * model.phoneSlowSigma
        var slowN = gaussian() * model.phoneSlowSigma
        val rho = exp(-1.0 / model.phoneSlowTauSec)
        val k = sqrt(1 - rho * rho) * model.phoneSlowSigma
        val baseAcc = sqrt(model.phoneWhite * model.phoneWhite + model.commonRms * model.commonRms +
            model.biasE * model.biasE + model.biasN * model.biasN).coerceAtLeast(1.5)
        val sats = listOf(intArrayOf(1, 2), intArrayOf(1, 5), intArrayOf(1, 12), intArrayOf(1, 15), intArrayOf(1, 24),
            intArrayOf(1, 29), intArrayOf(6, 3), intArrayOf(6, 11), intArrayOf(6, 24), intArrayOf(5, 19), intArrayOf(5, 21), intArrayOf(3, 7))
        val out = ArrayList<ReferenceFix>()
        var t = startMs
        while (t <= endMs) {
            slowE = rho * slowE + k * gaussian()
            slowN = rho * slowN + k * gaussian()
            val c = common(t)
            var de = c[0] + slowE + gaussian() * model.phoneWhite
            var dn = c[1] + slowN + gaussian() * model.phoneWhite
            if (random.nextDouble() < model.phoneSpikePerHour / 3600.0) {
                val ang = random.nextDouble(0.0, 2 * PI)
                val mag = random.nextDouble(5.0, 15.0)
                de += mag * sin(ang); dn += mag * cos(ang)
            }
            val ll = frame.latLng(home[0] + de, home[1] + dn)
            out += ReferenceFix(
                t = t,
                er = t - startMs,
                lat = ll[0], lng = ll[1],
                acc = (baseAcc * (0.9 + 0.2 * random.nextDouble())).toFloat(),
                nu = sats.size, nv = sats.size + 8,
                cn = 36f + random.nextFloat() * 4f,
                ts = t + 300,
                sv = sats.map { listOf(it[0], it[1], 380 + random.nextInt(-40, 40), 1) },
            )
            t += 1000
        }
        return out
    }

    /** RMS of horizontal error of [track] against [truth] at equal GNSS times (ta → (e, n)). */
    companion object {
        fun rmse(errors: List<Double>): Double = sqrt(errors.sumOf { it * it } / errors.size)
        fun horizontal(e: Double, n: Double) = hypot(e, n)
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }
}
