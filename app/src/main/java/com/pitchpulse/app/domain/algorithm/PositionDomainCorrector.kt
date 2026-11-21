package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.RefPlacement
import kotlin.math.hypot

/**
 * Tier A: position-domain correction from the phone reference station.
 *
 *   P'_w(t) = P_w(t) − K_b · b − diag(K_E, K_N) · (ê_ref(t) − b)
 *
 * - ê_ref: low-passed reference error, b = mean of ê_ref over the match window.
 * - K (time-varying gain, per axis, in [0, 1]) is estimated by least squares on watch stationary
 *   periods, where the watch's own slow error is observable as lowpass(P_w) − block mean.
 *   It is gated by total stationary time ≥ 120 s, pooled R² ≥ 0.2, and a leave-one-block-out
 *   cross-validation that must explain variance out of sample and must not worsen the stationary
 *   CEP95 by more than 5 %. Otherwise K = 0.
 * - K_b (bias gain) is only non-zero with a known-point placement: with an unknown placement the
 *   absolute bias is not observable. It is validated later by the evaluator (sideline / out-of-pitch
 *   metrics).
 * - When both devices report satellites, epochs with < 4 common satellites get half weight.
 */
class PositionDomainCorrector(private val config: Config = Config()) {

    data class Config(
        val lowPassPeriodSec: Double = 60.0,
        val minStationarySec: Double = 120.0,
        val minR2: Double = 0.2,
        val targetBlockMs: Long = 180_000L,
        val minBlockMs: Long = 60_000L,
        val minBlockSamples: Int = 30,
        val biasGain: Double = 1.0,
        val minCommonSatellites: Int = 4,
        val lowCommonWeight: Double = 0.5,
        val maxCepWorsening: Double = 0.05,
    )

    data class Estimate(
        val kE: Double,
        val kN: Double,
        /** Unclamped least-squares gains (diagnostic). */
        val rawKE: Double,
        val rawKN: Double,
        val kB: Double,
        val biasE: Double,
        val biasN: Double,
        val r2: Double,
        val cvR2: Double,
        val stationarySec: Double,
        val blocks: Int,
        /** Leave-one-block-out stationary CEP95 without / with the time-varying correction (m). */
        val cvCep95Off: Double,
        val cvCep95On: Double,
        val coverage: Double,
        val lowCommonSatFraction: Double?,
        val timeVaryingEnabled: Boolean,
        val reasons: List<String>,
    )

    data class Applied(
        val samples: List<EnuSample>,
        /** |correction| distribution over corrected samples, m. */
        val magnitudeMedian: Double,
        val magnitudeP90: Double,
        val magnitudeMax: Double,
        val correctedFraction: Double,
    )

    private class Block(val idx: IntArray, val yE: DoubleArray, val yN: DoubleArray, val xE: DoubleArray, val xN: DoubleArray, val w: DoubleArray, val pE: DoubleArray, val pN: DoubleArray)

    fun estimate(samples: List<EnuSample>, station: ReferenceStation): Estimate {
        val reasons = ArrayList<String>()
        val start = samples.first().ta
        val end = samples.last().ta
        val coverage = station.coverage(start, end)
        val ehat = samples.map { station.lowPassedError(it.ta) }

        // Bias over the match window.
        val valid = ehat.filterNotNull()
        val biasE = if (valid.isEmpty()) 0.0 else valid.sumOf { it[0] } / valid.size
        val biasN = if (valid.isEmpty()) 0.0 else valid.sumOf { it[1] } / valid.size
        val kB = if (station.placement == RefPlacement.KNOWN_POINT && valid.isNotEmpty()) config.biasGain else 0.0
        if (station.placement == RefPlacement.UNKNOWN_POINT) reasons += "未知点放置：绝对偏差不可观测，K_b = 0，只修正随时间变化的部分"

        // Common-satellite weights (only when both sides report satellites).
        var lowCommon = 0; var withSats = 0
        val weights = DoubleArray(samples.size) { i ->
            val ws = samples[i].point.svs?.toSet()
            val rs = station.satellitesAt(samples[i].ta)
            if (ws.isNullOrEmpty() || rs.isNullOrEmpty()) 1.0 else {
                withSats++
                if (ws.intersect(rs).size < config.minCommonSatellites) { lowCommon++; config.lowCommonWeight } else 1.0
            }
        }
        val lowCommonFraction = if (withSats == 0) null else lowCommon.toDouble() / withSats

        // Stationary blocks.
        val blocks = ArrayList<Block>()
        var stationaryMs = 0L
        for (run in StationaryDetector.find(samples)) {
            val runIdx = run.toList().filter { station.rawError(samples[it].ta) != null }
            if (runIdx.size < config.minBlockSamples) continue
            // Watch and reference errors through the identical filter over the identical window.
            val lE = SignalFilters.robustLowPass(runIdx.map { samples[it].e }.toDoubleArray(), config.lowPassPeriodSec)
            val lN = SignalFilters.robustLowPass(runIdx.map { samples[it].n }.toDoubleArray(), config.lowPassPeriodSec)
            val rE = SignalFilters.robustLowPass(runIdx.map { station.rawError(samples[it].ta)!![0] }.toDoubleArray(), config.lowPassPeriodSec)
            val rN = SignalFilters.robustLowPass(runIdx.map { station.rawError(samples[it].ta)!![1] }.toDoubleArray(), config.lowPassPeriodSec)
            val runMs = samples[run.last].ta - samples[run.first].ta
            stationaryMs += runMs
            val nBlocks = maxOf(1, minOf((runMs / config.targetBlockMs).toInt().coerceAtLeast(1), (runMs / config.minBlockMs).toInt()))
            val effectiveBlocks = if (runMs >= 2 * config.minBlockMs && nBlocks == 1) 2 else nBlocks
            val per = runIdx.size / effectiveBlocks
            for (b in 0 until effectiveBlocks) {
                val from = b * per
                val to = if (b == effectiveBlocks - 1) runIdx.size else (b + 1) * per
                val local = (from until to).toList()
                if (local.size < config.minBlockSamples) continue
                val ye = local.map { lE[it] }.toDoubleArray()
                val yn = local.map { lN[it] }.toDoubleArray()
                val xe = local.map { rE[it] }.toDoubleArray()
                val xn = local.map { rN[it] }.toDoubleArray()
                val pe = local.map { samples[runIdx[it]].e }.toDoubleArray()
                val pn = local.map { samples[runIdx[it]].n }.toDoubleArray()
                demean(ye); demean(yn); demean(xe); demean(xn)
                blocks += Block(
                    idx = local.map { runIdx[it] }.toIntArray(),
                    yE = ye, yN = yn, xE = xe, xN = xn,
                    w = local.map { weights[runIdx[it]] }.toDoubleArray(),
                    pE = pe, pN = pn,
                )
            }
        }
        val stationarySec = stationaryMs / 1000.0

        val (rawKE, rawKN) = fit(blocks)
        val kE = rawKE.coerceIn(0.0, 1.0)
        val kN = rawKN.coerceIn(0.0, 1.0)
        val r2 = r2(blocks, kE, kN)

        // Leave-one-block-out cross-validation.
        var cvRes = 0.0; var cvTot = 0.0
        val offDist = ArrayList<Double>(); val onDist = ArrayList<Double>()
        if (blocks.size >= 2) {
            for (j in blocks.indices) {
                val train = blocks.filterIndexed { i, _ -> i != j }
                val (te, tn) = fit(train)
                val ke = te.coerceIn(0.0, 1.0)
                val kn = tn.coerceIn(0.0, 1.0)
                val gate = r2(train, ke, kn) >= config.minR2
                val ue = if (gate) ke else 0.0
                val un = if (gate) kn else 0.0
                val b = blocks[j]
                for (i in b.yE.indices) {
                    val re = b.yE[i] - ue * b.xE[i]
                    val rn = b.yN[i] - un * b.xN[i]
                    cvRes += b.w[i] * (re * re + rn * rn)
                    cvTot += b.w[i] * (b.yE[i] * b.yE[i] + b.yN[i] * b.yN[i])
                }
                // Stationary dispersion of the raw positions, off vs on (each about its own block mean).
                val ce = b.pE.average(); val cn = b.pN.average()
                val qe = DoubleArray(b.pE.size) { b.pE[it] - ue * b.xE[it] }
                val qn = DoubleArray(b.pN.size) { b.pN[it] - un * b.xN[it] }
                val qce = qe.average(); val qcn = qn.average()
                for (i in b.pE.indices) {
                    offDist += hypot(b.pE[i] - ce, b.pN[i] - cn)
                    onDist += hypot(qe[i] - qce, qn[i] - qcn)
                }
            }
        }
        val cvR2 = if (cvTot > 0) 1 - cvRes / cvTot else Double.NaN
        val cepOff = SignalFilters.percentile(offDist, 0.95)
        val cepOn = SignalFilters.percentile(onDist, 0.95)

        var enabled = true
        when {
            coverage < 0.5 -> { enabled = false; reasons += "参考站只覆盖了 ${(coverage * 100).toInt()}% 的比赛时间（< 50%）" }
            stationarySec < config.minStationarySec -> { enabled = false; reasons += "手表静止样本只有 ${stationarySec.toInt()} s（< ${config.minStationarySec.toInt()} s），无法估计 K" }
            blocks.size < 2 -> { enabled = false; reasons += "静止分块不足 2 个，无法交叉验证" }
            r2 < config.minR2 -> { enabled = false; reasons += "回归 R² = ${"%.2f".format(r2)}（< ${config.minR2}）：手机与手表的慢变误差不相关" }
            !(cvR2 > 0) -> { enabled = false; reasons += "交叉验证 R² = ${"%.2f".format(cvR2)}：样本外没有解释力" }
            cepOn > cepOff * (1 + config.maxCepWorsening) -> { enabled = false; reasons += "交叉验证静止 CEP95 由 ${"%.2f".format(cepOff)} m 变为 ${"%.2f".format(cepOn)} m（变差 > 5%）" }
            kE == 0.0 && kN == 0.0 -> { enabled = false; reasons += "估计的 K 为 0" }
        }
        if (enabled) reasons += "时变改正启用：K_E=${"%.2f".format(kE)}，K_N=${"%.2f".format(kN)}，R²=${"%.2f".format(r2)}，交叉验证 R²=${"%.2f".format(cvR2)}"

        return Estimate(
            kE = if (enabled) kE else 0.0,
            kN = if (enabled) kN else 0.0,
            rawKE = rawKE,
            rawKN = rawKN,
            kB = kB,
            biasE = biasE,
            biasN = biasN,
            r2 = r2,
            cvR2 = cvR2,
            stationarySec = stationarySec,
            blocks = blocks.size,
            cvCep95Off = cepOff,
            cvCep95On = cepOn,
            coverage = coverage,
            lowCommonSatFraction = lowCommonFraction,
            timeVaryingEnabled = enabled,
            reasons = reasons,
        )
    }

    fun apply(samples: List<EnuSample>, station: ReferenceStation, est: Estimate, useTimeVarying: Boolean, useBias: Boolean): Applied {
        val mags = ArrayList<Double>()
        var corrected = 0
        val out = samples.map { s ->
            val ehat = station.lowPassedError(s.ta)
            var de = 0.0; var dn = 0.0
            if (useBias) { de += est.kB * est.biasE; dn += est.kB * est.biasN }
            if (useTimeVarying && ehat != null) {
                de += est.kE * (ehat[0] - est.biasE)
                dn += est.kN * (ehat[1] - est.biasN)
            }
            if (de != 0.0 || dn != 0.0) { corrected++; mags += hypot(de, dn) }
            s.moved(-de, -dn)
        }
        return Applied(
            samples = out,
            magnitudeMedian = if (mags.isEmpty()) 0.0 else SignalFilters.percentile(mags, 0.5),
            magnitudeP90 = if (mags.isEmpty()) 0.0 else SignalFilters.percentile(mags, 0.9),
            magnitudeMax = mags.maxOrNull() ?: 0.0,
            correctedFraction = if (samples.isEmpty()) 0.0 else corrected.toDouble() / samples.size,
        )
    }

    private fun demean(a: DoubleArray) {
        if (a.isEmpty()) return
        val m = a.average()
        for (i in a.indices) a[i] -= m
    }

    private fun fit(blocks: List<Block>): Pair<Double, Double> {
        var sxyE = 0.0; var sxxE = 0.0; var sxyN = 0.0; var sxxN = 0.0
        for (b in blocks) for (i in b.yE.indices) {
            sxyE += b.w[i] * b.xE[i] * b.yE[i]; sxxE += b.w[i] * b.xE[i] * b.xE[i]
            sxyN += b.w[i] * b.xN[i] * b.yN[i]; sxxN += b.w[i] * b.xN[i] * b.xN[i]
        }
        // Tiny reference variation (e.g. no common error): no information, gain 0.
        val ke = if (sxxE > 1e-6) sxyE / sxxE else 0.0
        val kn = if (sxxN > 1e-6) sxyN / sxxN else 0.0
        return ke to kn
    }

    private fun r2(blocks: List<Block>, kE: Double, kN: Double): Double {
        var res = 0.0; var tot = 0.0
        for (b in blocks) for (i in b.yE.indices) {
            val re = b.yE[i] - kE * b.xE[i]
            val rn = b.yN[i] - kN * b.xN[i]
            res += b.w[i] * (re * re + rn * rn)
            tot += b.w[i] * (b.yE[i] * b.yE[i] + b.yN[i] * b.yN[i])
        }
        return if (tot <= 0) 0.0 else 1 - res / tot
    }
}
