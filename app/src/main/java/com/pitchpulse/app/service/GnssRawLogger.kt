package com.pitchpulse.app.service

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.Location
import android.os.Build
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Raw GNSS log in the Google GnssLogger text format (Raw / Fix / Nav records), so the data can be
 * post-processed with standard tools (gps-measurement-tools, RINEX converters, RTKLIB).
 *
 * Deprecated platform fields (CarrierCycles, CarrierPhase, CarrierPhaseUncertainty, AgcDb) are
 * written as empty columns; AccumulatedDeltaRange carries the carrier information instead.
 */
class GnssRawLogger(file: File, header: String) {
    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, true))
    private val lock = Any()
    var measurementEpochs = 0
        private set
    var navMessages = 0
        private set

    init {
        synchronized(lock) {
            writer.write("#\n# Header Description:\n#\n# Version: PitchPulse $header\n#\n")
            writer.write("# Raw,ElapsedRealtimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos," +
                "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
                "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
                "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
                "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters," +
                "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase," +
                "CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb\n#\n")
            writer.write("# Fix,Provider,Latitude,Longitude,Altitude,Speed,Accuracy,(UTC)TimeInMs\n#\n")
            writer.write("# Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)\n#\n")
        }
    }

    fun onMeasurements(event: GnssMeasurementsEvent) {
        val clock = event.clock
        val sb = StringBuilder()
        for (m in event.measurements) sb.append(raw(clock, m)).append('\n')
        synchronized(lock) { writer.write(sb.toString()); measurementEpochs++ }
    }

    fun onNavigationMessage(msg: GnssNavigationMessage) {
        val sb = StringBuilder("Nav,").append(msg.svid).append(',').append(msg.type).append(',')
            .append(msg.status).append(',').append(msg.messageId).append(',').append(msg.submessageId)
        for (b in msg.data) sb.append(',').append(b)
        synchronized(lock) { writer.write(sb.append('\n').toString()); navMessages++ }
    }

    fun onLocation(loc: Location) {
        val line = "Fix,${loc.provider},${loc.latitude},${loc.longitude},${loc.altitude},${loc.speed},${loc.accuracy},${loc.time}\n"
        synchronized(lock) { writer.write(line) }
    }

    fun flush() = synchronized(lock) { writer.flush() }

    fun close() = synchronized(lock) { runCatching { writer.flush(); writer.close() } }

    private fun raw(c: GnssClock, m: GnssMeasurement): String {
        fun d(has: Boolean, v: () -> Any) = if (has) v().toString() else ""
        val snr = d(m.hasSnrInDb()) { m.snrInDb }
        val freq = d(m.hasCarrierFrequencyHz()) { m.carrierFrequencyHz }
        return listOf(
            "Raw",
            SystemClock.elapsedRealtime().toString(),
            c.timeNanos.toString(),
            d(c.hasLeapSecond()) { c.leapSecond },
            d(c.hasTimeUncertaintyNanos()) { c.timeUncertaintyNanos },
            d(c.hasFullBiasNanos()) { c.fullBiasNanos },
            d(c.hasBiasNanos()) { c.biasNanos },
            d(c.hasBiasUncertaintyNanos()) { c.biasUncertaintyNanos },
            d(c.hasDriftNanosPerSecond()) { c.driftNanosPerSecond },
            d(c.hasDriftUncertaintyNanosPerSecond()) { c.driftUncertaintyNanosPerSecond },
            c.hardwareClockDiscontinuityCount.toString(),
            m.svid.toString(),
            m.timeOffsetNanos.toString(),
            m.state.toString(),
            m.receivedSvTimeNanos.toString(),
            m.receivedSvTimeUncertaintyNanos.toString(),
            m.cn0DbHz.toString(),
            m.pseudorangeRateMetersPerSecond.toString(),
            m.pseudorangeRateUncertaintyMetersPerSecond.toString(),
            m.accumulatedDeltaRangeState.toString(),
            m.accumulatedDeltaRangeMeters.toString(),
            m.accumulatedDeltaRangeUncertaintyMeters.toString(),
            freq,
            "", "", "",
            m.multipathIndicator.toString(),
            snr,
            m.constellationType.toString(),
            "",
        ).joinToString(",")
    }

    companion object {
        fun header(): String = "Platform: ${Build.VERSION.RELEASE} Manufacturer: ${Build.MANUFACTURER} Model: ${Build.MODEL}"
    }
}
