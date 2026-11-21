package com.pitchpulse.app

import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.sim.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Section 5 (a) and (b): Tier A must help with shared error and must not hurt without it. */
class PositionDomainCorrectorTest {

    private val cOnly = setOf(EnhancementLayer.C)
    private val aAndC = setOf(EnhancementLayer.A_BIAS, EnhancementLayer.A_TIME, EnhancementLayer.C)

    /**
     * Tier A alone removes the shared error but not the watch's own white noise, so its RMSE gain
     * depends on the shared/independent ratio. Here: shared bias 1–3 m + slow 1–3 m RMS vs 2 m white.
     */
    @Test
    fun `a - shared error, Tier A alone cuts RMSE by at least 20 percent and removes most of the shared part`() {
        for (seed in 1..3) {
            val model = GnssErrorSimulator.Model.shared(Random(100 + seed)).copy(watchMultipathPerHour = 0.0, watchPoorFixRate = 0.0)
            val sc = Scenario.match("a$seed", seed, model)
            val raw = sc.rmse(sc.run(layers = emptySet()).track)
            val out = sc.run(layers = setOf(EnhancementLayer.A_BIAS, EnhancementLayer.A_TIME))
            val corrected = sc.rmse(out.track)
            val est = out.report.tierA!!
            // Independent-error floor: the watch's own white noise, σ per axis → horizontal RMS σ·√2.
            val floor = model.watchWhite * kotlin.math.sqrt(2.0)
            val sharedBefore = kotlin.math.sqrt((raw * raw - floor * floor).coerceAtLeast(0.0))
            val sharedAfter = kotlin.math.sqrt((corrected * corrected - floor * floor).coerceAtLeast(0.0))
            println("a seed=$seed raw=%.2f A=%.2f shared part %.2f -> %.2f K=(%.2f, %.2f) R2=%.2f cvR2=%.2f".format(
                raw, corrected, sharedBefore, sharedAfter, est.kE, est.kN, est.r2, est.cvR2))
            assertTrue("Tier A time-varying gate should open", est.timeVaryingEnabled)
            assertTrue("RMSE $raw -> $corrected should drop >= 20%", corrected <= raw * 0.80)
            assertTrue("shared error $sharedBefore -> $sharedAfter should drop >= 60%", sharedAfter <= sharedBefore * 0.40)
        }
    }

    @Test
    fun `a - shared error with multipath, A plus C beats C alone by at least 30 percent`() {
        for (seed in 1..3) {
            val sc = Scenario.match("a+mp$seed", seed, GnssErrorSimulator.Model.shared(Random(200 + seed)))
            val c = sc.rmse(sc.run(layers = cOnly).track)
            val ac = sc.rmse(sc.run(layers = aAndC).track)
            println("a+mp seed=$seed C=%.2f A+C=%.2f".format(c, ac))
            assertTrue("RMSE $c -> $ac should drop >= 30%", ac <= c * 0.70)
        }
    }

    @Test
    fun `b - no shared error, K is near zero and nothing gets worse`() {
        for (seed in 1..3) {
            val model = GnssErrorSimulator.Model()
            val sc = Scenario.match("b$seed", seed, model)
            val c = sc.rmse(sc.run(layers = cOnly).track)
            val out = sc.run(layers = aAndC)
            val ac = sc.rmse(out.track)
            val est = out.report.tierA!!
            println("b seed=$seed C=%.3f A+C=%.3f rawK=(%.2f, %.2f) K=(%.2f, %.2f) R2=%.2f bias=(%.2f, %.2f)".format(
                c, ac, est.rawKE, est.rawKN, est.kE, est.kN, est.r2, est.biasE, est.biasN))
            assertEquals(0.0, est.kE, 0.15)
            assertEquals(0.0, est.kN, 0.15)
            assertTrue("RMSE must not get worse: $c -> $ac", ac <= c * 1.02)
        }
    }

    @Test
    fun `b - only independent slow errors, gate keeps K at zero`() {
        for (seed in 1..3) {
            val model = GnssErrorSimulator.Model(watchSlowSigma = 2.0, watchSlowTauSec = 120.0, phoneSlowSigma = 1.5, phoneSlowTauSec = 200.0)
            val sc = Scenario.match("b-indep$seed", seed, model)
            val est = sc.run(layers = aAndC).report.tierA!!
            println("b-indep seed=$seed rawK=(%.2f, %.2f) R2=%.2f cvR2=%.2f enabled=${est.timeVaryingEnabled} ${est.reasons.last()}".format(
                est.rawKE, est.rawKN, est.r2, est.cvR2))
            assertFalse("Uncorrelated slow errors must not enable the time-varying correction", est.timeVaryingEnabled)
        }
    }
}
