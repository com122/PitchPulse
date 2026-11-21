package com.pitchpulse.app

import com.pitchpulse.app.data.model.CleanPointProbe
import com.pitchpulse.app.data.model.EnhancementSettings
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.CleanPoint
import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.domain.simulation.MatchSimulator
import com.pitchpulse.app.sim.Scenario
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** Section 5 (c): RTS smoothing lowers RMSE and does not inflate sprint counts. */
class TrajectorySmootherTest {

    private val calc = MatchStatsCalculator()

    /** Sprint count of the error-free trajectory, speed through the same 3-sample mean as the pipelines. */
    private fun truthSprints(sc: Scenario): Int = CleanPointProbe.truthSprints(sc, calc, smooth3 = true)

    private fun sprints(sc: Scenario, clean: List<CleanPoint>) = calc.analyzeFromClean(sc.watch, clean).stats.sprintCount

    @Test
    fun `c - smoothing lowers RMSE vs the legacy filter`() {
        for (seed in 1..4) {
            val sc = Scenario.match("c$seed", seed, GnssErrorSimulator.Model(watchWhite = 2.5), placement = null,
                position = PlayerPosition.entries[seed % 4])
            val legacy = sc.run(layers = emptySet())
            val smoothed = sc.run(layers = setOf(EnhancementLayer.C))
            val r0 = sc.rmse(legacy.track)
            val r1 = sc.rmse(smoothed.track)
            println("c seed=$seed RMSE legacy=%.2f smoothed=%.2f".format(r0, r1))
            assertTrue("RMSE $r0 -> $r1 should drop", r1 < r0 * 0.9)
        }
    }

    @Test
    fun `c - sprint counts are not inflated by noise (with and without Doppler speed)`() {
        for (seed in 1..4) {
            val sc = Scenario.match("c-spr$seed", seed, GnssErrorSimulator.Model(watchWhite = 2.5), placement = null,
                position = PlayerPosition.entries[seed % 4])
            val truth = truthSprints(sc)
            val withSpd = sprints(sc, sc.run(layers = setOf(EnhancementLayer.C)).clean)
            // GPX-like input: no device speed, sprints must come from positions only.
            val noSpd = Scenario(sc.name, sc.pitch, sc.truth, sc.watch.map { it.copy(spd = 0f) }, null, sc.model)
            val rawNoSpd = sprints(noSpd, calc.clean(noSpd.watch))
            val smoothNoSpd = sprints(noSpd, noSpd.run(EnhancementSettings(useDeviceSpeed = false), setOf(EnhancementLayer.C)).clean)
            val rawWithSpd = sprints(sc, calc.clean(sc.watch))
            println("c-spr seed=$seed truth=$truth raw(spd)=$rawWithSpd smoothed(spd)=$withSpd legacy(noSpd)=$rawNoSpd smoothed(noSpd)=$smoothNoSpd")
            assertTrue("smoothed with spd $withSpd must not exceed truth $truth by > 15%", withSpd <= truth * 1.15 + 1)
            // Position-only speed at 1 Hz is noisy: allow a modest overcount (documented limitation),
            // but it must stay an order of magnitude below the unsmoothed count.
            assertTrue("smoothed without spd $smoothNoSpd must stay within 30% + 3 of truth $truth", smoothNoSpd <= truth * 1.3 + 3)
            assertTrue("noise-inflated legacy count $rawNoSpd should be far above smoothed $smoothNoSpd", rawNoSpd > smoothNoSpd * 3)
        }
    }

    @Test
    fun `c - legacy wrist noise model, smoothing still helps`() {
        for (seed in 1..3) {
            val random = Random(seed)
            val sim = MatchSimulator(random)
            val truth = sim.generateTruth(Scenario.PITCH, PlayerPosition.WINGER, UserSettings(), Scenario.KICKOFF)
            val watch = sim.applyLegacyNoise(truth).map { it.copy(tu = it.t) }
            val sc = Scenario("legacy$seed", Scenario.PITCH, truth, watch, null, GnssErrorSimulator.Model())
            val r0 = sc.rmse(sc.run(layers = emptySet()).track)
            val r1 = sc.rmse(sc.run(layers = setOf(EnhancementLayer.C)).track)
            val dist0 = calc.analyze(watch).stats.totalDistanceMeters
            val dist1 = calc.analyzeFromClean(watch, sc.run(layers = setOf(EnhancementLayer.C)).clean).stats.totalDistanceMeters
            val distTruth = truth.zipWithNext().filter { (a, b) -> a.phase == b.phase && a.phase != 0 }
                .sumOf { (a, b) -> calc.countedStep(hypot(b.x - a.x, b.y - a.y), 1000) }
            println("legacy seed=$seed RMSE %.2f -> %.2f, distance truth=%.0f legacy=%.0f smoothed=%.0f".format(r0, r1, distTruth, dist0, dist1))
            // This noise is an AR(1) process with a 200 s time constant: a smoother cannot remove
            // slow correlated error, so position RMSE may only stay level. Distance must improve.
            assertTrue("RMSE must not get worse by > 2%: $r0 -> $r1", r1 <= r0 * 1.02)
            assertTrue("smoothed distance error should not exceed legacy", abs(dist1 - distTruth) <= abs(dist0 - distTruth))
            assertTrue("smoothed distance within 5% of truth", abs(dist1 - distTruth) <= distTruth * 0.05)
        }
    }
}
