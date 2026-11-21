package com.pitchpulse.app.sim

import com.pitchpulse.app.data.model.EnhancementSettings
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.AccuracyEnhancer
import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.algorithm.EvalPoint
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.algorithm.PitchFrame
import com.pitchpulse.app.domain.simulation.AccuracyTestSimulator
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.domain.simulation.MatchSimulator
import com.pitchpulse.app.domain.simulation.TruthSample
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

/** Simulated session: truth, watch track and (optional) phone reference station. */
class Scenario(
    val name: String,
    val pitch: PitchConfig,
    val truth: List<TruthSample>,
    val watch: List<TrackPoint>,
    val reference: AccuracyEnhancer.Reference?,
    val model: GnssErrorSimulator.Model,
) {
    val frame = PitchFrame(pitch)
    private val truthByT = truth.associateBy { it.t }

    /** Horizontal RMSE of in-play [track] vs truth (matched by GNSS time). */
    fun rmse(track: List<EvalPoint>): Double {
        val errs = track.filter { it.phase != TrackPoint.PHASE_PAUSED }.mapNotNull { p ->
            val tr = truthByT[p.ta] ?: return@mapNotNull null
            val en = frame.enu(tr.lat, tr.lng)
            hypot(p.e - en[0], p.n - en[1])
        }
        return sqrt(errs.sumOf { it * it } / errs.size)
    }

    fun run(settings: EnhancementSettings = EnhancementSettings(), layers: Set<EnhancementLayer>? = null): AccuracyEnhancer.Output =
        AccuracyEnhancer(settings, MatchStatsCalculator()).run(watch, pitch, reference, layers)

    /** Raw watch positions (acc ≤ 15) as eval points, no filtering at all. */
    fun rawTrack(): List<EvalPoint> = watch.filter { it.acc <= 15f }.map {
        val en = frame.enu(it.lat, it.lng)
        EvalPoint(it.tu ?: it.t, en[0], en[1], it.p)
    }

    companion object {
        val PITCH = PitchConfig("p", "测试 7 人制", 7, 22.5431, 113.9420, 60f, 40f, 28f)
        const val KICKOFF = 1_732_070_400_000L

        fun match(
            name: String,
            seed: Int,
            model: GnssErrorSimulator.Model,
            placement: RefPlacement? = RefPlacement.KNOWN_POINT,
            knownPoint: KnownPoint = KnownPoint.HALFWAY_TOUCHLINE_A,
            phoneOffsetMeters: DoubleArray = doubleArrayOf(0.0, 0.0),
            position: PlayerPosition = PlayerPosition.MIDFIELDER,
            refStartOffsetMs: Long = -10 * 60_000L,
        ): Scenario {
            val random = Random(seed)
            val truth = MatchSimulator(random).generateTruth(PITCH, position, UserSettings(), KICKOFF)
            val sim = GnssErrorSimulator(model, random)
            val frame = PitchFrame(PITCH)
            val watch = sim.watch(truth, frame)
            val ref = placement?.let {
                reference(sim, frame, it, knownPoint, phoneOffsetMeters, truth.first().t + refStartOffsetMs, truth.last().t + 5 * 60_000L, false, null, null)
            }
            return Scenario(name, PITCH, truth, watch, ref, model)
        }

        fun protocol(
            name: String,
            seed: Int,
            model: GnssErrorSimulator.Model,
            placement: RefPlacement = RefPlacement.KNOWN_POINT,
            phoneOffsetMeters: DoubleArray = doubleArrayOf(0.0, 0.0),
        ): Scenario {
            val random = Random(seed)
            val r = AccuracyTestSimulator().generate(PITCH, KICKOFF)
            val sim = GnssErrorSimulator(model, random)
            val frame = PitchFrame(PITCH)
            val watch = sim.watch(r.truth, frame)
            val ref = reference(sim, frame, placement, r.knownPoint, phoneOffsetMeters, r.truth.first().t - 60_000, r.truth.last().t + 60_000, true, r.routeStart, r.routeEnd)
            return Scenario(name, PITCH, r.truth, watch, ref, model)
        }

        private fun reference(
            sim: GnssErrorSimulator, frame: PitchFrame, placement: RefPlacement, kp: KnownPoint,
            offset: DoubleArray, start: Long, end: Long, protocol: Boolean, routeStart: Long?, routeEnd: Long?,
        ): AccuracyEnhancer.Reference {
            val known = frame.normalizedToEn(kp.nx, kp.ny)
            val actual = frame.latLng(known[0] + offset[0], known[1] + offset[1])
            val fixes: List<ReferenceFix> = sim.phone(actual[0], actual[1], start, end, frame)
            val knownLl = frame.latLng(known[0], known[1])
            val meta = ReferenceSessionMeta(
                id = "sim", pitchId = PITCH.id, pitchName = PITCH.name, placement = placement,
                knownPoint = if (placement == RefPlacement.KNOWN_POINT) kp else null,
                knownLat = if (placement == RefPlacement.KNOWN_POINT) knownLl[0] else null,
                knownLng = if (placement == RefPlacement.KNOWN_POINT) knownLl[1] else null,
                startedAt = start, stoppedAt = end, firstFixAt = fixes.first().t, lastFixAt = fixes.last().t,
                fixCount = fixes.size, protocol = protocol, protocolRouteStart = routeStart, protocolRouteEnd = routeEnd,
                simulated = true,
            )
            return AccuracyEnhancer.Reference(meta, fixes)
        }
    }
}
