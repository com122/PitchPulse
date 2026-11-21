package com.pitchpulse.app.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** How the phone (reference station) was placed. */
enum class RefPlacement(val label: String) {
    /** On a calibrated pitch point: removes both the time-varying and the absolute (bias) error. */
    KNOWN_POINT("已知点"),
    /** Anywhere static: only the time-varying part of the error can be removed. */
    UNKNOWN_POINT("未知点"),
}

/** Pitch points a phone can be placed on, in normalised pitch coordinates (see PitchProjector). */
enum class KnownPoint(val nx: Double, val ny: Double, val label: String) {
    CENTER(0.5, 0.5, "中圈开球点"),
    /** Side A = ny 0: the left touchline when facing the bearing direction (the nx = 1 goal). */
    HALFWAY_TOUCHLINE_A(0.5, 0.0, "中线 × A 侧边线"),
    HALFWAY_TOUCHLINE_B(0.5, 1.0, "中线 × B 侧边线"),
    CORNER_1(0.0, 0.0, "角旗 1"),
    CORNER_2(1.0, 0.0, "角旗 2"),
    CORNER_3(1.0, 1.0, "角旗 3"),
    CORNER_4(0.0, 1.0, "角旗 4"),
}

/**
 * One phone GNSS fix, one JSON object per line in `filesDir/refs/{sessionId}.jsonl`.
 * Times: [t] is Location.getTime() which for GPS_PROVIDER is GNSS-derived UTC.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReferenceFix(
    /** GNSS-derived UTC fix time, ms. */
    val t: Long,
    /** Location.getElapsedRealtimeNanos() / 1e6, ms (monotonic, for gap detection). */
    val er: Long = 0,
    val lat: Double,
    val lng: Double,
    /** Horizontal accuracy (68 %), meters. */
    val acc: Float,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val alt: Double? = null,
    /** Satellites used in fix / visible, from the latest GnssStatus. */
    val nu: Int = 0,
    val nv: Int = 0,
    /** Mean C/N0 of the satellites used in the fix, dB-Hz. */
    val cn: Float = 0f,
    /** System wall clock at callback time, ms (diagnostic only; includes fix latency). */
    val ts: Long = 0,
    /** Latest GnssStatus satellites: [constellationType, svid, round(C/N0 × 10), usedInFix 0/1]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sv: List<List<Int>>? = null,
) {
    /** Ids like "G05" of the satellites used in this fix (constellation letter + svid). */
    fun usedSatelliteIds(): Set<String> =
        sv?.filter { it.size >= 4 && it[3] == 1 }?.map { satelliteId(it[0], it[1]) }?.toSet() ?: emptySet()

    companion object {
        /** RINEX-style id from android.location.GnssStatus constellation constants. */
        fun satelliteId(constellation: Int, svid: Int): String {
            val letter = when (constellation) {
                1 -> 'G'; 2 -> 'S'; 3 -> 'R'; 4 -> 'J'; 5 -> 'C'; 6 -> 'E'; 7 -> 'I'
                else -> 'X'
            }
            return "$letter${svid.toString().padStart(2, '0')}"
        }
    }
}

/** Metadata of a reference-station session: `filesDir/refs/{id}.meta.json`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReferenceSessionMeta(
    val id: String,
    val pitchId: String? = null,
    val pitchName: String = "",
    val placement: RefPlacement = RefPlacement.UNKNOWN_POINT,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val knownPoint: KnownPoint? = null,
    /** WGS-84 of the known point at recording time (frozen, so later pitch edits are visible). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val knownLat: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val knownLng: Double? = null,
    /** Wall-clock start/stop of the recording. */
    val startedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val stoppedAt: Long? = null,
    /** GNSS-UTC time of the first/last fix. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val firstFixAt: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastFixAt: Long? = null,
    val fixCount: Int = 0,
    val device: String = "",
    /** GnssCapabilities / callback status at recording time. */
    val hasMeasurements: Boolean = false,
    val hasNavMessages: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val rawLogFile: String? = null,
    /** Accuracy-test protocol: GNSS-UTC times of "我出发了" / "我回来了". */
    val protocol: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val protocolRouteStart: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val protocolRouteEnd: Long? = null,
    val simulated: Boolean = false,
)

/** User-tunable accuracy-enhancement switches (DataStore). */
data class EnhancementSettings(
    /** Tier A: position-domain correction from the phone reference station. */
    val tierA: Boolean = true,
    /** Tier B: pseudorange DGNSS (experimental, needs raw measurements on both devices). */
    val tierB: Boolean = false,
    /** Tier C: Kalman filter + RTS smoother. */
    val tierC: Boolean = true,
    /** Tier D: pitch-constraint shift (only without a known-point reference). */
    val tierD: Boolean = true,
    val lowPassPeriodSec: Float = 60f,
    /** Kalman process noise σ_a (m/s²); 2.0 gave the smallest distance error in the simulator sweep. */
    val accelSigma: Float = 2f,
    /** Scale the measurement noise from the data (innovation consistency) instead of trusting acc. */
    val adaptiveNoise: Boolean = true,
    /** Multiplier for the watch's reported accuracy (raise if it is optimistic). */
    val accScale: Float = 1f,
    val gateChi2: Float = 9.21f,
    /** Fuse the watch's Doppler speed (spd) into the smoother. */
    val useDeviceSpeed: Boolean = true,
    /** Show the enhanced track by default on the review screen. */
    val defaultEnhanced: Boolean = true,
)
