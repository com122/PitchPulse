package com.pitchpulse.app

import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.domain.algorithm.EnhancementLayer
import com.pitchpulse.app.domain.algorithm.EvaluationMode
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
import com.pitchpulse.app.sim.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Section 4.6: protocol metrics and automatic layer switching. */
class AccuracyEnhancerTest {

    @Test
    fun `protocol run with known point improves sideline and static metrics`() {
        val sc = Scenario.protocol("proto", 11, GnssErrorSimulator.Model.shared(Random(11)))
        val out = sc.run()
        val r = out.report
        println("protocol layers: " + r.layers.joinToString { "${it.layer}=${it.kept} (${it.reason})" })
        println("raw   : ${r.raw}")
        println("final : ${r.final}")
        assertEquals(EvaluationMode.PROTOCOL, r.mode)
        // 7-a-side lap ≈ 200 m ≈ 70 s of jogging; corners and turns are excluded from the labels.
        assertTrue(r.raw.sidelineCount > 40)
        assertTrue(r.raw.halfwayCount > 15)
        assertTrue(r.raw.zoneDwells >= 4)
        assertNotNull(r.final.sidelineP90)
        assertTrue("sideline P90 ${r.raw.sidelineP90} -> ${r.final.sidelineP90}", r.final.sidelineP90!! < r.raw.sidelineP90!! * 0.8)
        assertTrue("abs CEP95 ${r.raw.staticAbsCep95} -> ${r.final.staticAbsCep95}", r.final.staticAbsCep95!! < r.raw.staticAbsCep95!! * 0.8)
        assertTrue(EnhancementLayer.A_BIAS in r.activeLayers)
        assertTrue(EnhancementLayer.C in r.activeLayers)
    }

    @Test
    fun `misplaced phone - bias layer is switched off automatically`() {
        // Phone actually 6 m from the declared known point, shared bias only 0.5 m.
        val model = GnssErrorSimulator.Model.shared(Random(12), rmsRange = 0.5..0.8, biasRange = 0.4..0.6)
        val sc = Scenario.protocol("misplaced", 12, model, phoneOffsetMeters = doubleArrayOf(4.2, 4.2))
        val r = sc.run().report
        val bias = r.layers.first { it.layer == EnhancementLayer.A_BIAS }
        println("misplaced: ${bias.reason}")
        assertFalse("bias layer must be switched off", bias.kept)
        assertTrue(bias.reason.startsWith("自动关闭"))
        assertTrue("final must not be worse than raw", r.final.sidelineP90!! <= r.raw.sidelineP90!! * 1.05)
    }

    @Test
    fun `unknown placement never applies an absolute shift from the reference`() {
        val sc = Scenario.match("unknown", 13, GnssErrorSimulator.Model.shared(Random(13)), placement = RefPlacement.UNKNOWN_POINT)
        val r = sc.run().report
        assertEquals(0.0, r.tierA!!.kB, 0.0)
        assertFalse(EnhancementLayer.A_BIAS in r.activeLayers)
    }

    @Test
    fun `tier D recovers a large offset without a reference and stays off when unnecessary`() {
        // 6 m shared bias pointing across the pitch, no reference station: winger hugs the touchline.
        val model = GnssErrorSimulator.Model(biasE = 0.0, biasN = 0.0)
        val base = Scenario.match("d-none", 14, model, placement = null, position = com.pitchpulse.app.data.model.PlayerPosition.WINGER)
        val noShift = base.run(layers = setOf(EnhancementLayer.C, EnhancementLayer.D)).report.tierD!!
        println("tier D, unbiased: ${noShift.reason}")
        assertFalse("no shift needed on an unbiased track", noShift.adopted)

        val frame = base.frame
        val across = frame.toEn(0.0, 6.0)
        val biased = GnssErrorSimulator.Model(biasE = across[0], biasN = across[1])
        val sc = Scenario.match("d-bias", 14, biased, placement = null, position = com.pitchpulse.app.data.model.PlayerPosition.WINGER)
        val c = sc.rmse(sc.run(layers = setOf(EnhancementLayer.C)).track)
        val out = sc.run(layers = setOf(EnhancementLayer.C, EnhancementLayer.D))
        val cd = sc.rmse(out.track)
        println("tier D, 6 m bias: ${out.report.tierD!!.reason}; RMSE C=${"%.2f".format(c)} C+D=${"%.2f".format(cd)}")
        assertTrue(out.report.tierD!!.adopted)
        assertTrue(cd < c)
    }
}
