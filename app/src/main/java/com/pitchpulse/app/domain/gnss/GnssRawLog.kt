package com.pitchpulse.app.domain.gnss

import java.io.BufferedReader
import kotlin.math.roundToLong

/**
 * Tier B groundwork (experimental, behind the `tierB` feature flag): reading raw GNSS logs written in
 * the Google GnssLogger text format and forming pseudoranges with the standard Android formula
 * (see Google gps-measurement-tools, ProcessGnssMeas):
 *
 *   t_Rx = TimeNanos + TimeOffsetNanos − (FullBiasNanos + BiasNanos)        (GNSS time, ns)
 *   ρ    = c · ((t_Rx mod week) − ReceivedSvTimeNanos)                       (GPS / Galileo / BeiDou*)
 *
 * Only measurements with code lock and a known time of week are used.
 * *BeiDou time of week is offset by 14 s from GPS; GLONASS uses time of day. Only GPS is formed here.
 */
data class RawMeasurement(
    val elapsedRealtimeMillis: Long,
    val timeNanos: Long,
    val fullBiasNanos: Long?,
    val biasNanos: Double,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val svid: Int,
    val constellationType: Int,
) {
    val satelliteId: String get() = com.pitchpulse.app.data.model.ReferenceFix.satelliteId(constellationType, svid)
}

data class RawEpoch(val timeNanos: Long, val measurements: List<RawMeasurement>)

data class RawLogSummary(
    val epochs: List<RawEpoch>,
    val navMessages: Int,
    val navByType: Map<Int, Int>,
    val malformedLines: Int,
) {
    val measurementCount: Int get() = epochs.sumOf { it.measurements.size }
    fun gpsPseudorangeEpochs(): Int = epochs.count { e -> e.measurements.count { Pseudorange.gpsMeters(it) != null } >= 4 }
}

object GnssRawLogParser {
    // Column indices of the GnssLogger "Raw" record.
    private const val ELAPSED = 1
    private const val TIME_NANOS = 2
    private const val FULL_BIAS = 5
    private const val BIAS = 6
    private const val SVID = 11
    private const val TIME_OFFSET = 12
    private const val STATE = 13
    private const val RX_SV_TIME = 14
    private const val RX_SV_TIME_UNC = 15
    private const val CN0 = 16
    private const val CONSTELLATION = 28

    fun parse(reader: BufferedReader): RawLogSummary {
        val byEpoch = LinkedHashMap<Long, MutableList<RawMeasurement>>()
        var nav = 0
        val navTypes = HashMap<Int, Int>()
        var bad = 0
        reader.lineSequence().forEach { line ->
            when {
                line.startsWith("Raw,") -> {
                    val m = parseRaw(line)
                    if (m == null) bad++ else byEpoch.getOrPut(m.timeNanos) { ArrayList() } += m
                }
                line.startsWith("Nav,") -> {
                    nav++
                    line.split(',').getOrNull(2)?.toIntOrNull()?.let { navTypes[it] = (navTypes[it] ?: 0) + 1 }
                }
            }
        }
        return RawLogSummary(byEpoch.map { (t, list) -> RawEpoch(t, list) }, nav, navTypes, bad)
    }

    fun parseRaw(line: String): RawMeasurement? {
        val f = line.split(',')
        if (f.size <= CONSTELLATION) return null
        return runCatching {
            RawMeasurement(
                elapsedRealtimeMillis = f[ELAPSED].toLong(),
                timeNanos = f[TIME_NANOS].toLong(),
                fullBiasNanos = f[FULL_BIAS].toLongOrNull(),
                biasNanos = f[BIAS].toDoubleOrNull() ?: 0.0,
                timeOffsetNanos = f[TIME_OFFSET].toDouble(),
                state = f[STATE].toInt(),
                receivedSvTimeNanos = f[RX_SV_TIME].toLong(),
                receivedSvTimeUncertaintyNanos = f[RX_SV_TIME_UNC].toLong(),
                cn0DbHz = f[CN0].toDouble(),
                svid = f[SVID].toInt(),
                constellationType = f[CONSTELLATION].toInt(),
            )
        }.getOrNull()
    }
}

object Pseudorange {
    const val SPEED_OF_LIGHT = 299_792_458.0
    const val WEEK_NANOS = 604_800L * 1_000_000_000L
    const val CONSTELLATION_GPS = 1

    // android.location.GnssMeasurement state bits.
    const val STATE_CODE_LOCK = 1
    const val STATE_TOW_DECODED = 8
    const val STATE_TOW_KNOWN = 1 shl 14

    /** Plausible GPS signal travel time: ~67–87 ms; accept 60–100 ms. */
    private const val MIN_TRAVEL_NANOS = 60_000_000L
    private const val MAX_TRAVEL_NANOS = 100_000_000L

    /** GPS L1 pseudorange in meters, or null if the measurement is not usable. */
    fun gpsMeters(m: RawMeasurement): Double? {
        if (m.constellationType != CONSTELLATION_GPS) return null
        val full = m.fullBiasNanos ?: return null
        if (m.state and STATE_CODE_LOCK == 0) return null
        if (m.state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) == 0) return null
        // Integer part exactly in Long, sub-ns parts in double.
        val rxIntNanos = m.timeNanos - full
        val rxFrac = m.timeOffsetNanos - m.biasNanos
        val rxWeekInt = Math.floorMod(rxIntNanos, WEEK_NANOS)
        var travel = (rxWeekInt - m.receivedSvTimeNanos).toDouble() + rxFrac
        if (travel < -WEEK_NANOS / 2.0) travel += WEEK_NANOS
        if (travel > WEEK_NANOS / 2.0) travel -= WEEK_NANOS
        if (travel < MIN_TRAVEL_NANOS || travel > MAX_TRAVEL_NANOS) return null
        return travel * 1e-9 * SPEED_OF_LIGHT
    }

    /** GPS time of the receiver clock (ns since the GPS epoch) for [m]. */
    fun receiverGpsNanos(m: RawMeasurement): Long? = m.fullBiasNanos?.let {
        m.timeNanos - it + (m.timeOffsetNanos - m.biasNanos).roundToLong()
    }
}

/** Whether the pseudorange-domain DGNSS layer can run at all, and why not. */
object DgnssAvailability {
    data class Status(val available: Boolean, val reason: String)

    /**
     * Both sides need raw pseudoranges of ≥ 4 common satellites in the same epochs, plus broadcast
     * ephemeris (from navigation messages) to compute satellite positions.
     */
    fun check(enabled: Boolean, reference: RawLogSummary?, roverHasRaw: Boolean): Status = when {
        !enabled -> Status(false, "未启用（实验功能，需在设置中打开）")
        reference == null -> Status(false, "参考站没有原始观测日志（手机不支持或未记录）")
        reference.gpsPseudorangeEpochs() == 0 -> Status(false, "参考站原始日志里没有可用的 GPS 伪距历元（需要码锁定 + 周内时）")
        reference.navMessages == 0 -> Status(false, "参考站没有记录导航电文，无法计算卫星位置")
        !roverHasRaw -> Status(false, "手表轨迹不含伪距原始观测（Wear OS Health Services 只提供位置结果），伪距差分不可用")
        else -> Status(false, "两端原始观测齐备，但伪距差分解算器尚未实现；可用 RTKLIB 的 DGPS 模式离线处理这两份日志")
    }
}
