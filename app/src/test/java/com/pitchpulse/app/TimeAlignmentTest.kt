package com.pitchpulse.app

import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.algorithm.TimeAlignment
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.sim.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/** Section 5 (d): a 2 s clock offset is detected, reported and corrected via tu. */
class TimeAlignmentTest {

    private val model = GnssErrorSimulator.Model.shared(Random(7))

    @Test
    fun `d - 2 s watch clock offset is detected and raises a warning`() {
        val sc = Scenario.match("d", 3, model.copy(watchClockOffsetMs = 2_000))
        val r = TimeAlignment.analyze(sc.watch)
        println("d: status=${r.status} offset=${r.clockOffsetMs} ms, ${r.messages}")
        assertEquals(TimeAlignment.Status.CORRECTED, r.status)
        assertTrue(r.warning)
        assertEquals(2_000L, r.clockOffsetMs!!)
        assertTrue(r.messages.any { it.contains("2.0 s") })
    }

    @Test
    fun `d - correction is identical with and without the offset because tu is used`() {
        val layers = setOf(EnhancementLayer.A_BIAS, EnhancementLayer.A_TIME, EnhancementLayer.C)
        val a = Scenario.match("d0", 3, model).run(layers = layers).track
        val b = Scenario.match("d2", 3, model.copy(watchClockOffsetMs = 2_000)).run(layers = layers).track
        assertEquals(a.size, b.size)
        val maxDiff = a.indices.maxOf { hypot(a[it].e - b[it].e, a[it].n - b[it].n) }
        assertTrue("max position difference $maxDiff", maxDiff < 1e-6)
    }

    @Test
    fun `d - no tu means alignment is unverified and flagged`() {
        val sc = Scenario.match("d-notu", 3, model.copy(watchHasTu = false, watchClockOffsetMs = 2_000))
        val r = TimeAlignment.analyze(sc.watch)
        assertEquals(TimeAlignment.Status.UNVERIFIED, r.status)
        assertTrue(r.warning)
        val report = sc.run().report
        assertTrue(report.notes.any { it.contains("无法从数据中验证") })
    }

    @Test
    fun `d - aligned clocks give no warning`() {
        val r = TimeAlignment.analyze(Scenario.match("d-ok", 3, model.copy(watchClockOffsetMs = 120)).watch)
        assertEquals(TimeAlignment.Status.ALIGNED, r.status)
        assertTrue(!r.warning)
    }
}
