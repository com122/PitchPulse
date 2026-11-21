package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.hypot

/** A watch fix in the pitch ENU frame, with its GNSS-aligned time. */
data class EnuSample(
    /** Index into the source TrackPoint list. */
    val index: Int,
    val point: TrackPoint,
    /** GNSS-UTC aligned time (ms), used to look up the reference station. */
    val ta: Long,
    val e: Double,
    val n: Double,
    val acc: Double,
) {
    val t: Long get() = point.t
    val phase: Int get() = point.p
    val spd: Double get() = point.spd.toDouble()

    fun moved(de: Double, dn: Double) = copy(e = e + de, n = n + dn)
}

/** Finds stretches where the watch is standing still (half-time, pre-match, accuracy test). */
object StationaryDetector {
    const val MAX_DEVICE_SPEED = 0.3
    const val MAX_DERIVED_SPEED = 0.4
    const val MIN_DURATION_MS = 60_000L
    const val MAX_GAP_MS = 3_000L

    /** Index ranges (inclusive) into [samples] (sorted by time). */
    fun find(samples: List<EnuSample>, minDurationMs: Long = MIN_DURATION_MS): List<IntRange> {
        if (samples.size < 2) return emptyList()
        val speed = speeds(samples)
        val limit = threshold(samples)
        val slow = BooleanArray(samples.size) { speed[it] < limit }
        val runs = ArrayList<IntRange>()
        var s = -1
        for (i in samples.indices) {
            val gapBreak = i > 0 && samples[i].ta - samples[i - 1].ta > MAX_GAP_MS
            if (s >= 0 && (!slow[i] || gapBreak)) {
                if (samples[i - 1].ta - samples[s].ta >= minDurationMs) runs += s..(i - 1)
                s = -1
            }
            if (slow[i] && s < 0) s = i
        }
        if (s >= 0 && samples.last().ta - samples[s].ta >= minDurationMs) runs += s..samples.lastIndex
        return runs
    }

    private fun usesDeviceSpeed(samples: List<EnuSample>) = samples.count { it.spd > 0.0 } >= samples.size / 2

    private fun threshold(samples: List<EnuSample>) = if (usesDeviceSpeed(samples)) MAX_DEVICE_SPEED else MAX_DERIVED_SPEED

    /**
     * Per-sample speed for stationarity: device (Doppler) speed when available, otherwise the
     * displacement of 20 s-low-passed positions over a ±5 s baseline. Then a 5-sample median.
     */
    fun speeds(samples: List<EnuSample>): DoubleArray {
        val raw = if (usesDeviceSpeed(samples)) {
            DoubleArray(samples.size) { samples[it].spd }
        } else {
            val le = SignalFilters.forwardBackwardLowPass(samples.map { it.e }.toDoubleArray(), 20.0)
            val ln = SignalFilters.forwardBackwardLowPass(samples.map { it.n }.toDoubleArray(), 20.0)
            DoubleArray(samples.size) { i ->
                val a = maxOf(0, i - 5)
                val b = minOf(samples.lastIndex, i + 5)
                val dt = (samples[b].ta - samples[a].ta) / 1000.0
                if (dt <= 0) 0.0 else hypot(le[b] - le[a], ln[b] - ln[a]) / dt
            }
        }
        return SignalFilters.rollingMedian(raw, 5)
    }
}
