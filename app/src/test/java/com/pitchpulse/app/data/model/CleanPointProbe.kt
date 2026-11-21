package com.pitchpulse.app.data.model

import com.pitchpulse.app.domain.algorithm.CleanPoint
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.simulation.TruthSample
import com.pitchpulse.app.sim.Scenario
import kotlin.math.hypot

/** Truth-based sprint counting shared by tests. */
object CleanPointProbe {
    /**
     * Sprint count of the error-free trajectory with the production detector. With [smooth3] the
     * truth speed goes through the same 3-sample mean as the pipelines, so the comparison isolates
     * the effect of GNSS noise.
     */
    fun truthSprints(sc: Scenario, calc: MatchStatsCalculator, smooth3: Boolean): Int {
        val inPlay = sc.truth.filter { it.phase != TrackPoint.PHASE_PAUSED }
        var prev: TruthSample? = null
        val clean = inPlay.mapIndexed { i, s ->
            val p = prev
            val start = p == null || p.phase != s.phase
            val step = if (start) 0.0 else hypot(s.x - p!!.x, s.y - p.y)
            prev = s
            val speed = if (!smooth3) s.speed else {
                val a = inPlay.getOrNull(i - 1)?.takeIf { !start && it.phase == s.phase }
                val b = inPlay.getOrNull(i + 1)?.takeIf { it.phase == s.phase }
                listOfNotNull(a?.speed, s.speed, b?.speed).average()
            }
            CleanPoint(
                point = TrackPoint(s.t, s.lat, s.lng, 3f, s.speed.toFloat(), s.hr, s.phase),
                speedMs = speed.toFloat(),
                rawStepMeters = step.toFloat(),
                countedStepMeters = calc.countedStep(step, 1000).toFloat(),
                dtMs = if (start) 0 else 1000,
                segmentStart = start,
            )
        }
        return calc.sprints(clean).size
    }
}
