package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** One position of a track version, in the pitch ENU frame. */
data class EvalPoint(val ta: Long, val e: Double, val n: Double, val phase: Int, val ve: Double = Double.NaN, val vn: Double = Double.NaN)

enum class EvaluationMode(val label: String) {
    PROTOCOL("精度测试协议（有地面真值）"),
    MATCH("比赛（无真值，使用代理指标）"),
}

/** Metrics of one track version. Null = not measurable for this session. */
data class AccuracyMetrics(
    val sidelineMedian: Double? = null,
    val sidelineP90: Double? = null,
    val sidelineCount: Int = 0,
    val halfwayMedian: Double? = null,
    val halfwayP90: Double? = null,
    val halfwayCount: Int = 0,
    val zoneMisclass: Double? = null,
    val zoneDwells: Int = 0,
    /** Static dispersion about each static window's own mean (precision), m. */
    val staticCep50: Double? = null,
    val staticCep95: Double? = null,
    val staticSeconds: Double = 0.0,
    /** Static error against the known point (accuracy), m. */
    val staticAbsCep50: Double? = null,
    val staticAbsCep95: Double? = null,
    /** In-play fraction of time more than 2 m outside the pitch lines. */
    val oobFraction: Double? = null,
)

/**
 * Evaluates track versions on a fixed set of labels so every version is compared on the same samples.
 *
 * Accuracy-test protocol (route between the "我出发了" / "我回来了" taps):
 * - sideline: samples moving (> 0.8 m/s) parallel (±30°) to a touchline/goal line within 12 m of it,
 *   ≥ 5 m from the corners → perpendicular distance to that line (median, P90);
 * - halfway: same for the halfway line (walked from touchline to touchline);
 * - zone misclassification: dwells (< 0.5 m/s for ≥ 5 s) within 15 m of the halfway line while
 *   shuttling across it; true sides alternate, so the error is the share of dwell samples on the
 *   wrong half under the better of the two alternating patterns;
 * - static windows before leaving and after returning: CEP50/CEP95 about the window mean and,
 *   with a known point, against the known point.
 * Match mode: static windows = stationary stretches, plus the out-of-pitch fraction.
 *
 * Labels come from a labelling track (normally the smoothed track without bias corrections); with a
 * 12 m band the selection does not depend on errors of a few meters.
 */
class AccuracyEvaluator(private val frame: PitchFrame) {

    data class StaticWindow(val start: Long, val end: Long, val truthE: Double?, val truthN: Double?)

    data class Protocol(val routeStart: Long, val routeEnd: Long)

    class Labels(
        val mode: EvaluationMode,
        /** ta → line id (0..3 boundary, 4 halfway). */
        val lines: Map<Long, Int>,
        val dwells: List<LongRange>,
        val statics: List<StaticWindow>,
    )

    fun label(
        labelTrack: List<EvalPoint>,
        protocol: Protocol?,
        knownEn: DoubleArray?,
        stationary: List<LongRange>,
    ): Labels {
        if (protocol == null) {
            return Labels(EvaluationMode.MATCH, emptyMap(), emptyList(), stationary.map { StaticWindow(it.first, it.last, null, null) })
        }
        val route = labelTrack.filter { it.ta in protocol.routeStart..protocol.routeEnd }
        val vel = velocities(route)
        val hl = frame.halfLength
        val hw = frame.halfWidth
        val lines = HashMap<Long, Int>()
        for ((i, p) in route.withIndex()) {
            val (ve, vn) = vel[i]
            val sp = hypot(ve, vn)
            if (sp < 0.8) continue
            val uv = frame.toUv(p.e, p.n)
            val duv = frame.toUv(ve, vn)
            val headingFromU = Math.toDegrees(atan2(abs(duv[1]), abs(duv[0]))) // 0 = along u, 90 = along v
            val candidates = ArrayList<Pair<Int, Double>>()
            if (headingFromU <= 30 && abs(uv[0]) <= hl - 5) {
                candidates += 0 to abs(uv[1] + hw)
                candidates += 1 to abs(uv[1] - hw)
            }
            if (headingFromU >= 60 && abs(uv[1]) <= hw - 5) {
                candidates += 2 to abs(uv[0] + hl)
                candidates += 3 to abs(uv[0] - hl)
                candidates += 4 to abs(uv[0])
            }
            val best = candidates.filter { it.second <= 12.0 }.minByOrNull { it.second } ?: continue
            lines[p.ta] = best.first
        }

        // Dwells near the halfway line (shuttle).
        val dwells = ArrayList<LongRange>()
        var s = -1
        for (i in route.indices) {
            val slow = hypot(vel[i].first, vel[i].second) < 0.5
            if (slow && s < 0) s = i
            if ((!slow || i == route.lastIndex) && s >= 0) {
                val e = if (slow) i else i - 1
                if (route[e].ta - route[s].ta >= 5_000) {
                    val seg = route.subList(s, e + 1)
                    val ce = seg.sumOf { it.e } / seg.size
                    val cn = seg.sumOf { it.n } / seg.size
                    val uv = frame.toUv(ce, cn)
                    if (abs(uv[0]) <= 15 && abs(uv[1]) <= hw - 6) dwells += route[s].ta..route[e].ta
                }
                s = -1
            }
        }

        val first = labelTrack.firstOrNull()?.ta ?: protocol.routeStart
        val last = labelTrack.lastOrNull()?.ta ?: protocol.routeEnd
        val statics = listOf(
            StaticWindow(first, protocol.routeStart - 10_000, knownEn?.get(0), knownEn?.get(1)),
            StaticWindow(protocol.routeEnd + 15_000, last, knownEn?.get(0), knownEn?.get(1)),
        ).filter { it.end - it.start >= 30_000 }
        return Labels(EvaluationMode.PROTOCOL, lines, dwells, statics)
    }

    fun metrics(version: List<EvalPoint>, labels: Labels): AccuracyMetrics {
        val byTa = version.associateBy { it.ta }
        val hl = frame.halfLength
        val hw = frame.halfWidth

        val side = ArrayList<Double>(); val half = ArrayList<Double>()
        for ((ta, line) in labels.lines) {
            val p = byTa[ta] ?: continue
            val uv = frame.toUv(p.e, p.n)
            val d = when (line) {
                0 -> abs(uv[1] + hw)
                1 -> abs(uv[1] - hw)
                2 -> abs(uv[0] + hl)
                3 -> abs(uv[0] - hl)
                else -> abs(uv[0])
            }
            if (line == 4) half += d else side += d
        }

        var zone: Double? = null
        if (labels.dwells.size >= 4) {
            var wrongA = 0; var wrongB = 0; var total = 0
            labels.dwells.forEachIndexed { k, range ->
                val trueA = if (k % 2 == 0) 1 else -1
                for (p in version) {
                    if (p.ta !in range) continue
                    val sideSign = if (frame.toUv(p.e, p.n)[0] >= 0) 1 else -1
                    total++
                    if (sideSign != trueA) wrongA++ else wrongB++
                }
            }
            if (total > 0) zone = minOf(wrongA, wrongB).toDouble() / total
        }

        val disp = ArrayList<Double>(); val absErr = ArrayList<Double>()
        var staticMs = 0L
        for (w in labels.statics) {
            val pts = version.filter { it.ta in w.start..w.end }
            if (pts.size < 20) continue
            staticMs += w.end - w.start
            val ce = pts.sumOf { it.e } / pts.size
            val cn = pts.sumOf { it.n } / pts.size
            for (p in pts) {
                disp += hypot(p.e - ce, p.n - cn)
                if (w.truthE != null && w.truthN != null) absErr += hypot(p.e - w.truthE, p.n - w.truthN)
            }
        }

        val inPlay = version.filter { it.phase != TrackPoint.PHASE_PAUSED }
        val oob = if (inPlay.isEmpty()) null else inPlay.count {
            val uv = frame.toUv(it.e, it.n)
            abs(uv[0]) > hl + 2 || abs(uv[1]) > hw + 2
        }.toDouble() / inPlay.size

        fun q(list: List<Double>, p: Double) = if (list.size < 10) null else SignalFilters.percentile(list, p)
        return AccuracyMetrics(
            sidelineMedian = q(side, 0.5),
            sidelineP90 = q(side, 0.9),
            sidelineCount = side.size,
            halfwayMedian = q(half, 0.5),
            halfwayP90 = q(half, 0.9),
            halfwayCount = half.size,
            zoneMisclass = zone,
            zoneDwells = labels.dwells.size,
            staticCep50 = q(disp, 0.5),
            staticCep95 = q(disp, 0.95),
            staticSeconds = staticMs / 1000.0,
            staticAbsCep50 = q(absErr, 0.5),
            staticAbsCep95 = q(absErr, 0.95),
            oobFraction = oob,
        )
    }

    /** Velocity per point: given state velocity, else centred difference over ±2 samples. */
    private fun velocities(track: List<EvalPoint>): List<Pair<Double, Double>> = track.indices.map { i ->
        val p = track[i]
        if (!p.ve.isNaN() && !p.vn.isNaN()) p.ve to p.vn else {
            val a = track[maxOf(0, i - 2)]
            val b = track[minOf(track.lastIndex, i + 2)]
            val dt = (b.ta - a.ta) / 1000.0
            if (dt <= 0) 0.0 to 0.0 else (b.e - a.e) / dt to (b.n - a.n) / dt
        }
    }

    companion object {
        /** Relative tolerance before a layer counts as "worse". */
        const val MAX_WORSENING = 0.05

        /**
         * True if [with] is worse than [without] by more than 5 % on any primary metric of [mode]
         * (plus a small absolute floor so noise-level changes don't flip decisions).
         * Returns the human-readable reason, or null.
         */
        fun worsening(mode: EvaluationMode, with: AccuracyMetrics, without: AccuracyMetrics): String? {
            fun check(name: String, a: Double?, b: Double?, floor: Double, unit: String): String? {
                if (a == null || b == null) return null
                return if (a > b * (1 + MAX_WORSENING) + floor) "$name ${fmt(b, unit)} → ${fmt(a, unit)}（变差 > 5%）" else null
            }
            val list = when (mode) {
                EvaluationMode.PROTOCOL -> listOf(
                    check("边线垂距 P90", with.sidelineP90, without.sidelineP90, 0.05, "m"),
                    check("静止 CEP95（相对已知点）", with.staticAbsCep95, without.staticAbsCep95, 0.05, "m"),
                    check("静止 CEP95（离散度）", with.staticCep95, without.staticCep95, 0.05, "m"),
                )
                EvaluationMode.MATCH -> listOf(
                    check("静止 CEP95（离散度）", with.staticCep95, without.staticCep95, 0.05, "m"),
                    check("界外比例", with.oobFraction, without.oobFraction, 0.005, "%"),
                )
            }
            return list.firstOrNull { it != null }
        }

        private fun fmt(v: Double, unit: String) = if (unit == "%") "%.1f%%".format(v * 100) else "%.2f m".format(v)
    }
}
