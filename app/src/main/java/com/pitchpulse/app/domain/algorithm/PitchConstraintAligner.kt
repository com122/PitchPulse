package com.pitchpulse.app.domain.algorithm

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Tier D: small constant shift (|d| ≤ 8 m) that keeps the in-play track inside the pitch.
 *
 * Objective: Σ outside(p + d)² where outside() is the distance beyond the lines expanded by 2 m,
 * plus a tiny |d|² tie-breaker so that among equally good shifts the smallest is chosen. The search
 * is a 0.25 m grid in the pitch frame (u along the length, v across).
 *
 * The shift is only adopted when it clearly helps: the out-of-pitch time fraction must drop by
 * ≥ 30 % relative and ≥ 1 percentage point absolute. Touchline density peaks are used as a
 * consistency check only (a shift may not push a touchline peak outside the line): players running
 * the flank are usually 1–3 m inside the line, so snapping the peak onto the line would bias it.
 */
class PitchConstraintAligner(private val config: Config = Config()) {

    data class Config(
        val margin: Double = 2.0,
        val maxShift: Double = 8.0,
        val step: Double = 0.25,
        val minRelativeGain: Double = 0.30,
        val minAbsoluteGain: Double = 0.01,
    )

    data class Result(
        val du: Double,
        val dv: Double,
        val de: Double,
        val dn: Double,
        val oobBefore: Double,
        val oobAfter: Double,
        val adopted: Boolean,
        val reason: String,
    ) {
        val magnitude: Double get() = hypot(du, dv)
    }

    /** @param uv in-play positions in the pitch frame (u along length, v across). */
    fun estimate(uv: List<DoubleArray>, frame: PitchFrame): Result {
        if (uv.size < 100) return Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, "球场内样本太少")
        val hl = frame.halfLength + config.margin
        val hw = frame.halfWidth + config.margin

        fun outside(u: Double, v: Double): Double {
            val ou = max(0.0, abs(u) - hl)
            val ov = max(0.0, abs(v) - hw)
            return hypot(ou, ov)
        }

        fun oob(du: Double, dv: Double) = uv.count { outside(it[0] + du, it[1] + dv) > 0 }.toDouble() / uv.size

        // Only points near the boundary can change the objective: pre-select them for speed.
        val near = uv.filter { abs(it[0]) > hl - config.maxShift - 1 || abs(it[1]) > hw - config.maxShift - 1 }
        var best = Double.MAX_VALUE
        var bu = 0.0; var bv = 0.0
        val steps = (config.maxShift / config.step).toInt()
        for (i in -steps..steps) for (j in -steps..steps) {
            val du = i * config.step
            val dv = j * config.step
            if (hypot(du, dv) > config.maxShift) continue
            var cost = 0.0
            for (p in near) {
                val o = outside(p[0] + du, p[1] + dv)
                cost += o * o
            }
            cost = cost / uv.size + 1e-4 * (du * du + dv * dv)
            if (cost < best - 1e-12) { best = cost; bu = du; bv = dv }
        }

        val before = oob(0.0, 0.0)
        val after = oob(bu, bv)
        val en = frame.toEn(bu, bv)
        val gain = before - after
        val clear = gain >= config.minAbsoluteGain && (before <= 0 || gain / before >= config.minRelativeGain)
        if (!clear) {
            return Result(bu, bv, en[0], en[1], before, before, false,
                "界外比例 ${pct(before)} → ${pct(after)}，改善不明显，不平移")
        }
        if (pushesPeakOut(uv, frame, bv)) {
            return Result(bu, bv, en[0], en[1], before, before, false, "平移会把边线附近的跑动密度峰推到线外，放弃")
        }
        return Result(bu, bv, en[0], en[1], before, after, true,
            "平移 ${"%.1f".format(hypot(bu, bv))} m 后界外比例 ${pct(before)} → ${pct(after)}")
    }

    /** True if a clear touchline density peak (|v| within 6 m of the line) would end up outside the line. */
    private fun pushesPeakOut(uv: List<DoubleArray>, frame: PitchFrame, dv: Double): Boolean {
        for (sign in intArrayOf(-1, 1)) {
            val band = uv.map { it[1] * sign }.filter { it > frame.halfWidth - 6 && it < frame.halfWidth + 4 }
            if (band.size < uv.size * 0.05) continue
            // Histogram with 0.5 m bins; peak must stand out (≥ 2× mean bin count).
            val bins = IntArray(20)
            for (v in band) {
                val b = ((v - (frame.halfWidth - 6)) / 0.5).toInt()
                if (b in bins.indices) bins[b]++
            }
            val peak = bins.indices.maxBy { bins[it] }
            if (bins[peak] < 2 * bins.average()) continue
            val peakV = frame.halfWidth - 6 + (peak + 0.5) * 0.5
            if (peakV + dv * sign > frame.halfWidth) return true
        }
        return false
    }

    private fun pct(f: Double) = "%.1f%%".format(f * 100)
}
