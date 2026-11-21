package com.pitchpulse.app

import com.pitchpulse.app.domain.gnss.DgnssAvailability
import com.pitchpulse.app.domain.gnss.GnssRawLogParser
import com.pitchpulse.app.domain.gnss.Pseudorange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Tier B groundwork: GnssLogger parsing and the Android pseudorange formula. */
class GnssRawLogTest {

    // GPS time ≈ week 2340, 3 days into the week.
    private val gpsNanos = 2340L * Pseudorange.WEEK_NANOS + 3L * 86_400L * 1_000_000_000L
    private val timeNanos = 123_456_789_000L
    private val fullBias = timeNanos - gpsNanos  // so that TimeNanos − FullBias = GPS time
    private val travelNanos = 75_000_000L        // 75 ms ≈ 22 484 km

    private fun rawLine(svTime: Long, state: Int = Pseudorange.STATE_CODE_LOCK or Pseudorange.STATE_TOW_DECODED, constellation: Int = 1, bias: Double = 0.25) =
        "Raw,1000,$timeNanos,18,0,$fullBias,$bias,10,0,0,0,7,0.0,$state,$svTime,20,42.5,100.1,0.1,0,0,0,1575420030,,,,0,,$constellation,"

    @Test
    fun `pseudorange follows the Android formula`() {
        val svTime = Math.floorMod(gpsNanos, Pseudorange.WEEK_NANOS) - travelNanos
        val m = GnssRawLogParser.parseRaw(rawLine(svTime, bias = 0.0))!!
        assertEquals(travelNanos * 1e-9 * Pseudorange.SPEED_OF_LIGHT, Pseudorange.gpsMeters(m)!!, 1e-6)
        // A positive BiasNanos means the receiver clock is ahead: subtract it.
        val biased = GnssRawLogParser.parseRaw(rawLine(svTime, bias = 1000.0))!!
        assertEquals((travelNanos - 1000) * 1e-9 * Pseudorange.SPEED_OF_LIGHT, Pseudorange.gpsMeters(biased)!!, 1e-6)
    }

    @Test
    fun `unusable measurements are rejected`() {
        val svTime = Math.floorMod(gpsNanos, Pseudorange.WEEK_NANOS) - travelNanos
        assertNull("no TOW", Pseudorange.gpsMeters(GnssRawLogParser.parseRaw(rawLine(svTime, state = Pseudorange.STATE_CODE_LOCK))!!))
        assertNull("not GPS", Pseudorange.gpsMeters(GnssRawLogParser.parseRaw(rawLine(svTime, constellation = 6))!!))
        assertNull("implausible travel time", Pseudorange.gpsMeters(GnssRawLogParser.parseRaw(rawLine(svTime - 50_000_000L))!!))
    }

    @Test
    fun `parser groups epochs and counts nav messages`() {
        val svTime = Math.floorMod(gpsNanos, Pseudorange.WEEK_NANOS) - travelNanos
        val log = buildString {
            appendLine("# Raw,ElapsedRealtimeMillis,TimeNanos,...")
            repeat(5) { appendLine(rawLine(svTime + it * 1000)) }
            appendLine("Fix,gps,22.5,113.9,10,0,3.5,1732070400000")
            appendLine("Nav,7,257,1,1,1,1,2,3")
            appendLine("Raw,broken")
        }
        val s = GnssRawLogParser.parse(log.reader().buffered())
        assertEquals(1, s.epochs.size)
        assertEquals(5, s.measurementCount)
        assertEquals(1, s.navMessages)
        assertEquals(1, s.malformedLines)
        assertEquals(1, s.gpsPseudorangeEpochs())
        val status = DgnssAvailability.check(enabled = true, reference = s, roverHasRaw = false)
        assertFalse(status.available)
    }
}
