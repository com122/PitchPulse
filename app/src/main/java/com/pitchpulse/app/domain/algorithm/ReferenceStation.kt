package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.data.model.RefPlacement
import kotlin.math.hypot

/**
 * Phone reference station: its "truth" position X_ref and its error series e_ref(t) = P_ref(t) − X_ref,
 * all in the pitch ENU frame (origin = pitch centre).
 *
 * - Known-point placement: X_ref is the calibrated pitch point, so e_ref includes the absolute bias.
 * - Unknown placement: X_ref is the geometric median of the fixes, so only the time-varying part of
 *   the error is observable (its mean is ~0 by construction).
 *
 * The series is put on a 1 s grid (linear interpolation across gaps ≤ 5 s; longer gaps stay invalid)
 * and low-passed with a spike-robust zero-phase filter: periods shorter than the cutoff (default 60 s)
 * are treated as phone-only noise and removed.
 */
class ReferenceStation private constructor(
    val meta: ReferenceSessionMeta,
    val placement: RefPlacement,
    val truthE: Double,
    val truthN: Double,
    private val t0: Long,
    private val errE: DoubleArray,
    private val errN: DoubleArray,
    private val rawE: DoubleArray,
    private val rawN: DoubleArray,
    private val valid: BooleanArray,
    private val fixTimes: LongArray,
    private val fixSats: List<Set<String>>,
    val usedFixCount: Int,
    val rejectedFixCount: Int,
    /** Distance from the (median) phone position to the pitch centre, m. */
    val distanceToPitchCenter: Double,
    /** Known-point mode: |frozen meta lat/lng − current pitch point|, m (pitch re-calibrated since). */
    val knownPointDrift: Double?,
    /** 95th percentile of |e_ref − mean| before low-pass: how much the phone itself wandered. */
    val rawWander95: Double,
    /** Mean of the raw error (known point: the phone's absolute bias). */
    val rawMeanE: Double,
    val rawMeanN: Double,
    /** Median accuracy (acc) the phone reported for the used fixes, m. */
    val medianAcc: Double,
) {
    val firstT: Long get() = t0
    val lastT: Long get() = t0 + (errE.size - 1) * GRID_MS
    val hasSatellites: Boolean get() = fixSats.any { it.isNotEmpty() }

    /** Fraction of 1 s grid instants in [start, end] with a valid error estimate. */
    fun coverage(start: Long, end: Long): Double {
        if (end <= start) return 0.0
        var total = 0; var ok = 0
        var t = start
        while (t <= end) {
            total++
            if (lowPassedError(t) != null) ok++
            t += GRID_MS
        }
        return if (total == 0) 0.0 else ok.toDouble() / total
    }

    /** Low-passed e_ref at GNSS-UTC [t] (ms), or null outside coverage / inside a gap. */
    fun lowPassedError(t: Long): DoubleArray? = interp(errE, errN, t)

    /**
     * Unfiltered e_ref on the 1 s grid at [t]. Used when estimating K so the reference error can go
     * through exactly the same filter (and edge effects) as the watch error.
     */
    fun rawError(t: Long): DoubleArray? = interp(rawE, rawN, t)

    private fun interp(a: DoubleArray, b: DoubleArray, t: Long): DoubleArray? {
        if (a.isEmpty() || t < t0 || t > lastT) return null
        val pos = (t - t0).toDouble() / GRID_MS
        val i = pos.toInt().coerceAtMost(a.size - 1)
        val j = (i + 1).coerceAtMost(a.size - 1)
        if (!valid[i] || !valid[j]) return null
        val f = pos - i
        return doubleArrayOf(a[i] + (a[j] - a[i]) * f, b[i] + (b[j] - b[i]) * f)
    }

    /** Satellites used by the phone at [t] (nearest fix within 1.5 s), null if unknown. */
    fun satellitesAt(t: Long): Set<String>? {
        if (fixTimes.isEmpty()) return null
        var idx = java.util.Arrays.binarySearch(fixTimes, t)
        if (idx < 0) {
            val ins = -idx - 1
            idx = when {
                ins <= 0 -> 0
                ins >= fixTimes.size -> fixTimes.size - 1
                t - fixTimes[ins - 1] <= fixTimes[ins] - t -> ins - 1
                else -> ins
            }
        }
        if (kotlin.math.abs(fixTimes[idx] - t) > 1500) return null
        return fixSats[idx].ifEmpty { null }
    }

    companion object {
        const val GRID_MS = 1000L
        const val MAX_INTERP_GAP_MS = 5000L
        const val MAX_FIX_ACC = 10.0
        const val MIN_FIXES = 60

        /**
         * @param knownEn ENU of the known point from the *current* pitch calibration (known mode).
         * @return null when there are too few usable fixes.
         */
        fun build(
            meta: ReferenceSessionMeta,
            fixes: List<ReferenceFix>,
            frame: PitchFrame,
            knownEn: DoubleArray?,
            lowPassPeriodSec: Double,
        ): ReferenceStation? {
            val sorted = fixes.sortedBy { it.t }.distinctBy { it.t }
            val usable = sorted.filter { it.acc in 0f..MAX_FIX_ACC.toFloat() }
            if (usable.size < MIN_FIXES) return null

            val enu = usable.map { frame.enu(it.lat, it.lng) }
            val median = SignalFilters.geometricMedian(enu)
            val placement = if (meta.placement == RefPlacement.KNOWN_POINT && knownEn != null) RefPlacement.KNOWN_POINT
            else RefPlacement.UNKNOWN_POINT
            val truth = if (placement == RefPlacement.KNOWN_POINT) knownEn!! else median

            val drift = if (placement == RefPlacement.KNOWN_POINT && meta.knownLat != null && meta.knownLng != null) {
                val frozen = frame.enu(meta.knownLat, meta.knownLng)
                hypot(frozen[0] - truth[0], frozen[1] - truth[1])
            } else null

            // 1 s grid with interpolation across short gaps.
            val t0 = usable.first().t
            val n = ((usable.last().t - t0) / GRID_MS + 1).toInt()
            val gE = DoubleArray(n); val gN = DoubleArray(n); val ok = BooleanArray(n)
            var k = 0
            for (g in 0 until n) {
                val t = t0 + g * GRID_MS
                while (k + 1 < usable.size && usable[k + 1].t <= t) k++
                val a = usable[k]
                if (a.t == t) {
                    gE[g] = enu[k][0] - truth[0]; gN[g] = enu[k][1] - truth[1]; ok[g] = true
                } else if (k + 1 < usable.size && usable[k + 1].t - a.t <= MAX_INTERP_GAP_MS) {
                    val b = usable[k + 1]
                    val f = (t - a.t).toDouble() / (b.t - a.t)
                    gE[g] = enu[k][0] + (enu[k + 1][0] - enu[k][0]) * f - truth[0]
                    gN[g] = enu[k][1] + (enu[k + 1][1] - enu[k][1]) * f - truth[1]
                    ok[g] = true
                }
            }

            val rawE = gE.filterIndexed { i, _ -> ok[i] }
            val rawN = gN.filterIndexed { i, _ -> ok[i] }
            val meanE = rawE.average()
            val meanN = rawN.average()
            val wander = SignalFilters.percentile(
                rawE.indices.map { hypot(rawE[it] - meanE, rawN[it] - meanN) }, 0.95,
            )

            // Zero-phase robust low-pass per contiguous valid run.
            val lE = DoubleArray(n); val lN = DoubleArray(n)
            var s = 0
            while (s < n) {
                if (!ok[s]) { s++; continue }
                var e = s
                while (e + 1 < n && ok[e + 1]) e++
                val segE = gE.copyOfRange(s, e + 1)
                val segN = gN.copyOfRange(s, e + 1)
                val fe = SignalFilters.robustLowPass(segE, lowPassPeriodSec)
                val fn = SignalFilters.robustLowPass(segN, lowPassPeriodSec)
                fe.copyInto(lE, s); fn.copyInto(lN, s)
                s = e + 1
            }

            return ReferenceStation(
                meta = meta,
                placement = placement,
                truthE = truth[0],
                truthN = truth[1],
                t0 = t0,
                errE = lE,
                errN = lN,
                rawE = gE,
                rawN = gN,
                valid = ok,
                fixTimes = sorted.map { it.t }.toLongArray(),
                fixSats = sorted.map { it.usedSatelliteIds() },
                usedFixCount = usable.size,
                rejectedFixCount = sorted.size - usable.size,
                distanceToPitchCenter = hypot(median[0], median[1]),
                knownPointDrift = drift,
                rawWander95 = wander,
                rawMeanE = meanE,
                rawMeanN = meanN,
                medianAcc = SignalFilters.median(usable.map { it.acc.toDouble() }.toDoubleArray()),
            )
        }
    }
}
