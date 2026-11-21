package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Puts watch samples on the GNSS-UTC time base used by the phone reference station.
 *
 * The phone logs Location.getTime(), which for GPS_PROVIDER is GNSS-derived UTC. The watch contract
 * carries `t` (device clock) and, optionally, `tu` (GNSS UTC of the fix):
 * - tu on ≥ 90 % of points: align on tu. The device-clock offset median(t − tu) is measured and
 *   reported; |offset| > 0.5 s raises a warning (already corrected because tu is used).
 * - no tu: align on t. The device clock offset is not observable from positions alone (the shared
 *   error is too slow to locate a few seconds of lag), so the alignment is reported as unverified.
 */
object TimeAlignment {
    const val MAX_ALIGNMENT_ERROR_MS = 500L
    const val MIN_TU_COVERAGE = 0.9

    enum class Status(val label: String) {
        ALIGNED("已按 GNSS 时间对齐"),
        CORRECTED("检测到手表时钟偏移，已按 GNSS 时间纠正"),
        UNVERIFIED("手表未提供 GNSS 时间，无法验证对齐"),
    }

    data class Result(
        val status: Status,
        /** Device-clock offset median(t − tu), ms; null without tu. */
        val clockOffsetMs: Long?,
        /** Spread (P90 − P10) of t − tu, ms. */
        val clockJitterMs: Long?,
        val tuCoverage: Double,
        /** True when the watch clock and GNSS time differ by more than 0.5 s or drift. */
        val warning: Boolean,
        val messages: List<String>,
    ) {
        /** Aligned GNSS-UTC time of [p]. */
        fun alignedTime(p: TrackPoint): Long = when {
            p.tu != null && status != Status.UNVERIFIED -> p.tu
            clockOffsetMs != null -> p.t - clockOffsetMs
            else -> p.t
        }
    }

    fun analyze(points: List<TrackPoint>): Result {
        if (points.isEmpty()) return Result(Status.UNVERIFIED, null, null, 0.0, true, listOf("没有轨迹点"))
        val withTu = points.filter { it.tu != null }
        val coverage = withTu.size.toDouble() / points.size
        if (coverage < MIN_TU_COVERAGE) {
            return Result(
                status = Status.UNVERIFIED,
                clockOffsetMs = null,
                clockJitterMs = null,
                tuCoverage = coverage,
                warning = true,
                messages = listOf(
                    "手表轨迹只有 ${(coverage * 100).toInt()}% 的点带 GNSS 时间 tu，按手表系统时钟 t 与参考站对齐；" +
                        "对齐误差无法从数据中验证（要求 < 0.5 s）。",
                ),
            )
        }
        val diffs = withTu.map { (it.t - it.tu!!).toDouble() }.toDoubleArray()
        val offset = SignalFilters.median(diffs).roundToLong()
        val jitter = (SignalFilters.percentile(diffs, 0.9) - SignalFilters.percentile(diffs, 0.1)).roundToLong()
        val messages = ArrayList<String>()
        var warning = false
        val status = if (abs(offset) > MAX_ALIGNMENT_ERROR_MS) {
            warning = true
            messages += "手表系统时钟比 GNSS 时间${if (offset > 0) "快" else "慢"} ${"%.1f".format(abs(offset) / 1000.0)} s，" +
                "超过 0.5 s 对齐要求；已改用 GNSS 时间 tu 对齐。"
            Status.CORRECTED
        } else {
            Status.ALIGNED
        }
        if (jitter > MAX_ALIGNMENT_ERROR_MS) {
            warning = true
            messages += "手表时钟与 GNSS 时间的差值波动 ${"%.1f".format(jitter / 1000.0)} s（时钟跳变或漂移），已逐点按 tu 对齐。"
        }
        return Result(status, offset, jitter, coverage, warning, messages)
    }
}
