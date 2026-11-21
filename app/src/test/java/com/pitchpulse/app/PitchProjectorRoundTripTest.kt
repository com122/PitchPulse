package com.pitchpulse.app

import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.domain.algorithm.PitchCornerSolver
import com.pitchpulse.app.domain.algorithm.PitchFrame
import com.pitchpulse.app.domain.algorithm.PitchProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/** Section 5 (e): 4-corner calibration → projector → back, error < 1e-4 (normalised units). */
class PitchProjectorRoundTripTest {

    @Test
    fun `e - four corner round trip`() {
        val random = Random(5)
        repeat(50) {
            val pitch = PitchConfig(
                "p", "p", 7,
                random.nextDouble(-60.0, 60.0), random.nextDouble(-179.0, 179.0),
                random.nextDouble(35.0, 110.0).toFloat(), random.nextDouble(20.0, 70.0).toFloat(),
                random.nextDouble(0.0, 360.0).toFloat(),
            )
            if (pitch.widthMeters >= pitch.lengthMeters) return@repeat
            val corners = PitchProjector(pitch).cornersLatLng().shuffled(random)
            val sol = PitchCornerSolver.solve(corners)
            val solved = PitchConfig("s", "s", 7, sol.centerLat, sol.centerLng, sol.lengthMeters, sol.widthMeters, sol.bearingDegrees)
            val proj = PitchProjector(solved)
            val unit = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
            for (c in corners) {
                val n = proj.normalize(c[0], c[1])
                val err = unit.minOf { (ux, uy) -> hypot(n[0] - ux, n[1] - uy) }
                assertTrue("corner error $err", err < 1e-4)
            }
            // normalize ∘ denormalize identity.
            for (k in 0 until 20) {
                val nx = random.nextDouble(-0.05, 1.05)
                val ny = random.nextDouble(-0.05, 1.05)
                val ll = proj.denormalize(nx, ny)
                val back = proj.normalize(ll[0], ll[1])
                assertEquals(nx, back[0].toDouble(), 1e-4)
                assertEquals(ny, back[1].toDouble(), 1e-4)
            }
        }
    }

    @Test
    fun `e - PitchFrame matches PitchProjector`() {
        val pitch = PitchConfig("p", "p", 7, 22.5431, 113.9420, 60f, 40f, 28f)
        val proj = PitchProjector(pitch)
        val frame = PitchFrame(pitch)
        for ((nx, ny) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.3 to 0.8, 0.5 to 0.5)) {
            val en = frame.normalizedToEn(nx, ny)
            val ll = frame.latLng(en[0], en[1])
            val n = proj.normalize(ll[0], ll[1])
            assertEquals(nx, n[0].toDouble(), 1e-5)
            assertEquals(ny, n[1].toDouble(), 1e-5)
            val uv = frame.toUv(en[0], en[1])
            val back = frame.toEn(uv[0], uv[1])
            assertEquals(en[0], back[0], 1e-9)
            assertEquals(en[1], back[1], 1e-9)
        }
    }
}
