package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.EnhancementSettings
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.data.model.TrackPoint
import kotlin.math.hypot

enum class EnhancementLayer(val label: String) {
    A_BIAS("A · 绝对偏差改正"),
    A_TIME("A · 时变误差改正"),
    C("C · KF + RTS 平滑"),
    D("D · 球场约束平移"),
}

data class LayerDecision(
    val layer: EnhancementLayer,
    val requested: Boolean,
    val applicable: Boolean,
    val kept: Boolean,
    val reason: String,
    val with: AccuracyMetrics? = null,
    val without: AccuracyMetrics? = null,
)

data class ReferenceSummary(
    val sessionId: String,
    val placement: RefPlacement,
    val knownPoint: KnownPoint?,
    val coverage: Double,
    val distanceToPitchCenter: Double,
    val usedFixes: Int,
    val rejectedFixes: Int,
    /** Known point: the phone's mean absolute error; unknown: ~0. */
    val phoneBias: Double,
    val phoneWander95: Double,
    val knownPointDrift: Double?,
    val simulated: Boolean,
)

data class SmootherSummary(
    val rejected: Int,
    val resets: Int,
    val segments: Int,
    /** Data-driven multiplier on R (1 = trust acc). */
    val rScale: Double,
    val meanNis: Double,
    val speedSource: String,
)

data class AccuracyReport(
    val mode: EvaluationMode,
    val alignment: TimeAlignment.Result,
    val reference: ReferenceSummary?,
    val tierA: PositionDomainCorrector.Estimate?,
    val tierAApplied: PositionDomainCorrector.Applied?,
    val tierBStatus: String,
    val tierC: SmootherSummary?,
    val tierD: PitchConstraintAligner.Result?,
    val layers: List<LayerDecision>,
    val raw: AccuracyMetrics,
    val final: AccuracyMetrics,
    val notes: List<String>,
) {
    val activeLayers: List<EnhancementLayer> get() = layers.filter { it.kept }.map { it.layer }
}

/**
 * Runs the accuracy-enhancement layers on one watch track:
 * time alignment → Tier A (reference station) → Tier C (KF + RTS) → Tier D (pitch constraint),
 * then evaluates every enabled layer by leave-one-out and switches off any layer that makes a
 * primary metric more than 5 % worse. Raw data is never modified.
 */
class AccuracyEnhancer(
    private val settings: EnhancementSettings,
    private val stats: MatchStatsCalculator,
) {
    data class Reference(
        val meta: ReferenceSessionMeta,
        val fixes: List<ReferenceFix>,
        /** Parsed raw GNSS log (only loaded when Tier B is enabled). */
        val raw: com.pitchpulse.app.domain.gnss.RawLogSummary? = null,
    )

    data class Output(
        /** Enhanced in-play points for stats / heatmap (p != 0), same contract as the raw pipeline. */
        val clean: List<CleanPoint>,
        /** Every enhanced position incl. half-time, for display and evaluation. */
        val track: List<EvalPoint>,
        val report: AccuracyReport,
    )

    private class Version(
        val eval: List<EvalPoint>,
        val clean: List<CleanPoint>,
        val smoother: TrajectorySmoother.Result?,
        val shift: PitchConstraintAligner.Result?,
        val rScale: Double = 1.0,
        val meanNis: Double = Double.NaN,
    )

    /**
     * @param forceLayers when set, exactly these layers are applied (no automatic switching); used by
     *   tests and the simulation report to measure each layer in isolation.
     */
    fun run(
        points: List<TrackPoint>,
        pitch: PitchConfig,
        reference: Reference?,
        forceLayers: Set<EnhancementLayer>? = null,
    ): Output {
        val frame = PitchFrame(pitch)
        val notes = ArrayList<String>()
        val alignment = TimeAlignment.analyze(points)

        val samples = points.withIndex()
            .filter { it.value.acc <= stats.maxAccuracyMeters }
            .sortedBy { it.value.t }
            .map { (i, p) ->
                val en = frame.enu(p.lat, p.lng)
                EnuSample(i, p, alignment.alignedTime(p), en[0], en[1], p.acc.toDouble())
            }
            .distinctBy { it.ta }
        require(samples.size >= 30) { "有效轨迹点不足" }

        // Reference station.
        var station: ReferenceStation? = null
        var refSummary: ReferenceSummary? = null
        if (reference != null) {
            val kp = reference.meta.knownPoint
            val knownEn = if (reference.meta.placement == RefPlacement.KNOWN_POINT && kp != null) frame.normalizedToEn(kp.nx, kp.ny) else null
            val st = ReferenceStation.build(reference.meta, reference.fixes, frame, knownEn, settings.lowPassPeriodSec.toDouble())
            if (st == null) {
                notes += "参考站有效定位点不足 ${ReferenceStation.MIN_FIXES} 个（精度 ≤ 10 m），未使用"
            } else {
                val coverage = st.coverage(samples.first().ta, samples.last().ta)
                refSummary = ReferenceSummary(
                    sessionId = reference.meta.id,
                    placement = st.placement,
                    knownPoint = if (st.placement == RefPlacement.KNOWN_POINT) kp else null,
                    coverage = coverage,
                    distanceToPitchCenter = st.distanceToPitchCenter,
                    usedFixes = st.usedFixCount,
                    rejectedFixes = st.rejectedFixCount,
                    phoneBias = hypot(st.rawMeanE, st.rawMeanN),
                    phoneWander95 = st.rawWander95,
                    knownPointDrift = st.knownPointDrift,
                    simulated = reference.meta.simulated,
                )
                val maxDist = hypot(frame.halfLength, frame.halfWidth) + 150
                when {
                    st.distanceToPitchCenter > maxDist ->
                        notes += "参考站距球场中心 ${st.distanceToPitchCenter.toInt()} m，不在该球场，未使用"
                    coverage < 0.05 ->
                        notes += "参考站与比赛时间几乎不重叠（${(coverage * 100).toInt()}%），未使用"
                    else -> {
                        station = st
                        if (coverage < 0.5) notes += "参考站只覆盖了 ${(coverage * 100).toInt()}% 的比赛时间（< 50%），改正可能不完整"
                    }
                }
                st.knownPointDrift?.takeIf { it > 1.0 }?.let {
                    notes += "球场标定在录制后改动过，已知点按当前标定计算（与录制时相差 ${"%.1f".format(it)} m）"
                }
            }
        }

        // Tier A estimate.
        val corrector = PositionDomainCorrector(PositionDomainCorrector.Config(lowPassPeriodSec = settings.lowPassPeriodSec.toDouble()))
        val estimate = if (station != null && settings.tierA) corrector.estimate(samples, station) else null

        val smoother = TrajectorySmoother(
            TrajectorySmoother.Config(
                accelSigma = settings.accelSigma.toDouble(),
                accScale = settings.accScale.toDouble(),
                gateChi2 = settings.gateChi2.toDouble(),
                useDeviceSpeed = settings.useDeviceSpeed,
                adaptiveR = settings.adaptiveNoise,
            ),
        )
        val aligner = PitchConstraintAligner()

        fun build(layers: Set<EnhancementLayer>): Version {
            var s = samples
            if (station != null && estimate != null && (EnhancementLayer.A_BIAS in layers || EnhancementLayer.A_TIME in layers)) {
                s = corrector.apply(samples, station, estimate, EnhancementLayer.A_TIME in layers, EnhancementLayer.A_BIAS in layers).samples
            }
            var smoothed: TrajectorySmoother.Result? = null
            var eval: List<EvalPoint>
            var clean: List<CleanPoint>
            var rScale = 1.0
            var nis = Double.NaN
            if (EnhancementLayer.C in layers) {
                val r = smoother.smooth(s)
                smoothed = r
                rScale = smoother.lastRScale
                nis = smoother.lastMeanNis
                eval = r.states.map { EvalPoint(it.sample.ta, it.e, it.n, it.sample.phase, it.ve, it.vn) }
                clean = cleanFromSmoothed(r.states, frame)
            } else {
                // Legacy path: implied-speed jump filter of MatchStatsCalculator on the (corrected) fixes.
                val corrected = s.map { toTrackPoint(it, frame) }
                clean = stats.clean(corrected)
                val taOf = HashMap<Long, Long>(s.size).apply { s.forEach { put(it.point.t, it.ta) } }
                val inPlay = clean.map { c ->
                    val en = frame.enu(c.point.lat, c.point.lng)
                    EvalPoint(taOf[c.point.t] ?: c.point.t, en[0], en[1], c.point.p)
                }
                val paused = s.filter { it.phase == TrackPoint.PHASE_PAUSED }.map { EvalPoint(it.ta, it.e, it.n, it.phase) }
                eval = (inPlay + paused).sortedBy { it.ta }
            }
            var shift: PitchConstraintAligner.Result? = null
            if (EnhancementLayer.D in layers) {
                val uv = eval.filter { it.phase != TrackPoint.PHASE_PAUSED }.map { frame.toUv(it.e, it.n) }
                val r = aligner.estimate(uv, frame)
                shift = r
                if (r.adopted) {
                    eval = eval.map { it.copy(e = it.e + r.de, n = it.n + r.dn) }
                    clean = clean.map { c ->
                        val en = frame.enu(c.point.lat, c.point.lng)
                        val ll = frame.latLng(en[0] + r.de, en[1] + r.dn)
                        c.copy(point = c.point.copy(lat = ll[0], lng = ll[1]))
                    }
                }
            }
            return Version(eval, clean, smoothed, shift, rScale, nis)
        }

        // Which layers are candidates.
        val knownRef = station?.placement == RefPlacement.KNOWN_POINT
        val decisions = LinkedHashMap<EnhancementLayer, LayerDecision>()
        fun decide(layer: EnhancementLayer, requested: Boolean, applicable: Boolean, reason: String) {
            decisions[layer] = LayerDecision(layer, requested, applicable, requested && applicable, reason)
        }
        // Plausibility: the long-term mean error of a static phone should not exceed its own
        // reported 68 % accuracy. A larger mean almost always means the phone is not on the point
        // the user selected (or the pitch calibration is off), and shifting the watch by it would
        // add, not remove, error. Catches gross misplacement (≳ 3 m); smaller ones need the
        // accuracy-test protocol.
        val biasMagnitude = estimate?.let { hypot(it.biasE, it.biasN) } ?: 0.0
        val biasLimit = station?.let { maxOf(MIN_BIAS_LIMIT, it.medianAcc) } ?: MIN_BIAS_LIMIT
        val biasPlausible = biasMagnitude <= biasLimit
        decide(
            EnhancementLayer.A_BIAS, settings.tierA,
            estimate != null && estimate.kB > 0 && biasPlausible,
            when {
                !settings.tierA -> "已在设置中关闭"
                station == null -> "没有可用的参考站数据"
                !knownRef -> "参考站放在未知点，绝对偏差不可观测"
                !biasPlausible -> "参考站平均偏差 ${"%.1f".format(biasMagnitude)} m 超过手机自报精度 ${"%.1f".format(biasLimit)} m：" +
                    "手机可能没放在「${station.meta.knownPoint?.label}」上，或球场标定有偏移，不做绝对改正"
                else -> "已知点偏差 b = (${"%.2f".format(estimate!!.biasE)}, ${"%.2f".format(estimate.biasN)}) m，K_b = ${"%.1f".format(estimate.kB)}"
            },
        )
        decide(
            EnhancementLayer.A_TIME, settings.tierA,
            estimate?.timeVaryingEnabled == true,
            when {
                !settings.tierA -> "已在设置中关闭"
                estimate == null -> "没有可用的参考站数据"
                else -> estimate.reasons.lastOrNull() ?: ""
            },
        )
        decide(EnhancementLayer.C, settings.tierC, true, if (settings.tierC) "前向卡尔曼 + RTS 后向平滑" else "已在设置中关闭")
        decide(
            EnhancementLayer.D, settings.tierD, !(knownRef && settings.tierA),
            when {
                !settings.tierD -> "已在设置中关闭"
                knownRef && settings.tierA -> "已知点参考站可直接改正绝对偏差，球场约束默认关闭"
                else -> "在 ±8 m 内搜索使轨迹落在球场内的最小平移"
            },
        )

        // Labels from the smoothed track without bias layers (or the legacy track if C is off).
        val labelVersion = build(if (settings.tierC) setOf(EnhancementLayer.C) else emptySet())
        val protocol = reference?.meta?.takeIf { it.protocol && it.protocolRouteStart != null && it.protocolRouteEnd != null }
            ?.let { AccuracyEvaluator.Protocol(it.protocolRouteStart!!, it.protocolRouteEnd!!) }
            ?.takeIf { p -> samples.first().ta < p.routeStart && samples.last().ta > p.routeEnd }
        val evaluator = AccuracyEvaluator(frame)
        val knownEn = if (knownRef) doubleArrayOf(station!!.truthE, station.truthN) else null
        val stationary = StationaryDetector.find(samples).map { samples[it.first].ta..samples[it.last].ta }
        val labels = evaluator.label(labelVersion.eval, protocol, knownEn, stationary)

        // Leave-one-out evaluation.
        var active = forceLayers ?: decisions.values.filter { it.kept }.map { it.layer }.toSet()
        if (forceLayers != null) {
            for (layer in EnhancementLayer.entries) {
                decisions[layer] = decisions.getValue(layer).copy(kept = layer in forceLayers, reason = "测试：强制${if (layer in forceLayers) "开启" else "关闭"}")
            }
        } else if (active.isNotEmpty()) {
            val full = evaluator.metrics(build(active).eval, labels)
            val disabled = HashSet<EnhancementLayer>()
            for (layer in active) {
                val without = evaluator.metrics(build(active - layer).eval, labels)
                val worse = AccuracyEvaluator.worsening(labels.mode, full, without)
                val d = decisions.getValue(layer)
                decisions[layer] = if (worse != null) {
                    disabled += layer
                    d.copy(kept = false, reason = "自动关闭：$worse", with = full, without = without)
                } else d.copy(with = full, without = without)
            }
            active = active - disabled
        }

        val finalVersion = build(active)
        val rawVersion = build(emptySet())
        val rawMetrics = evaluator.metrics(rawVersion.eval, labels)
        val finalMetrics = evaluator.metrics(finalVersion.eval, labels)

        if (forceLayers == null) finalVersion.shift?.let { d ->
            decisions[EnhancementLayer.D] = decisions.getValue(EnhancementLayer.D).copy(reason = d.reason)
        }

        val applied = if (station != null && estimate != null) {
            corrector.apply(samples, station, estimate, EnhancementLayer.A_TIME in active, EnhancementLayer.A_BIAS in active)
        } else null

        val tierB = tierBStatus(reference, points)
        val report = AccuracyReport(
            mode = labels.mode,
            alignment = alignment,
            reference = refSummary,
            tierA = estimate,
            tierAApplied = applied,
            tierBStatus = tierB,
            tierC = finalVersion.smoother?.let {
                SmootherSummary(
                    rejected = it.rejected, resets = it.resets, segments = it.segments,
                    rScale = finalVersion.rScale, meanNis = finalVersion.meanNis,
                    speedSource = if (points.count { p -> p.spd > 0f } >= points.size / 2) "手表多普勒速度（3 点平均）" else "平滑状态速度（无设备速度）",
                )
            },
            tierD = finalVersion.shift,
            layers = decisions.values.toList(),
            raw = rawMetrics,
            final = finalMetrics,
            notes = notes + alignment.messages,
        )
        return Output(finalVersion.clean, finalVersion.eval, report)
    }

    companion object {
        /** Lower bound of the bias plausibility limit, m. */
        const val MIN_BIAS_LIMIT = 3.0
    }

    /** The watch contract carries positions only; raw pseudoranges would need a separate rover log. */
    @Suppress("UNUSED_PARAMETER")
    private fun tierBStatus(reference: Reference?, points: List<TrackPoint>): String {
        if (settings.tierB && reference != null && !reference.meta.hasMeasurements) return "参考站手机不支持或未记录 GNSS 原始观测"
        return com.pitchpulse.app.domain.gnss.DgnssAvailability.check(settings.tierB, reference?.raw, roverHasRaw = false).reason
    }

    /**
     * Builds stats-ready clean points (p != 0) from smoothed states, with the anti-jitter rule.
     *
     * Speed source: when the watch reports Doppler speed, the 3-sample mean of that speed is used
     * (as in the raw pipeline) — it is a direct measurement and clearly more accurate than any
     * position-derived estimate, so sprint counts stay unbiased. Without device speed (e.g. GPX)
     * the smoothed state velocity is used.
     */
    private fun cleanFromSmoothed(states: List<TrajectorySmoother.Smoothed>, frame: PitchFrame): List<CleanPoint> {
        val out = ArrayList<CleanPoint>(states.size)
        val hasDevice = states.count { it.sample.spd > 0.0 } >= states.size / 2
        fun deviceOk(k: Int) = k in states.indices && states[k].sample.spd in 0.0..stats.maxPlausibleSpeedMs.toDouble()
        var prev: TrajectorySmoother.Smoothed? = null
        for ((k, st) in states.withIndex()) {
            if (st.sample.phase == TrackPoint.PHASE_PAUSED) { prev = null; continue }
            val speed = if (hasDevice && deviceOk(k)) {
                var sum = st.sample.spd; var cnt = 1
                if (!st.segmentStart && deviceOk(k - 1)) { sum += states[k - 1].sample.spd; cnt++ }
                if (k + 1 < states.size && !states[k + 1].segmentStart && deviceOk(k + 1)) { sum += states[k + 1].sample.spd; cnt++ }
                sum / cnt
            } else st.speed
            val p = prev
            val start = st.segmentStart || p == null || p.sample.phase != st.sample.phase
            val dt = if (start) 0L else st.sample.t - p!!.sample.t
            val step = if (start) 0.0 else hypot(st.e - p!!.e, st.n - p.n)
            val ll = frame.latLng(st.e, st.n)
            out += CleanPoint(
                point = st.sample.point.copy(lat = ll[0], lng = ll[1]),
                speedMs = speed.toFloat(),
                rawStepMeters = step.toFloat(),
                countedStepMeters = if (start) 0f else stats.countedStep(step, dt).toFloat(),
                dtMs = dt,
                segmentStart = start,
            )
            prev = st
        }
        return out
    }

    private fun toTrackPoint(s: EnuSample, frame: PitchFrame): TrackPoint {
        val ll = frame.latLng(s.e, s.n)
        return s.point.copy(lat = ll[0], lng = ll[1])
    }
}
