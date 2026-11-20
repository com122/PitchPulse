package com.pitchpulse.app.data.model

import kotlinx.serialization.Serializable

/**
 * One GPS/HR sample. Serialised as one JSON object per line in `filesDir/tracks/{matchId}.jsonl`.
 * This is the wire contract shared with the future Galaxy Watch 4 (Wear OS) recorder.
 *
 * Example line:
 * `{"t":1732070400000,"lat":22.543101,"lng":113.942011,"acc":3.8,"spd":2.41,"hr":152,"p":1}`
 */
@Serializable
data class TrackPoint(
    /** Epoch milliseconds. */
    val t: Long,
    /** WGS-84 latitude. */
    val lat: Double,
    /** WGS-84 longitude. */
    val lng: Double,
    /** Horizontal accuracy in meters. */
    val acc: Float = 5f,
    /** Instantaneous speed in m/s (0 when the source did not provide one). */
    val spd: Float = 0f,
    /** Heart rate in bpm (0 when unknown). */
    val hr: Int = 0,
    /** Phase: 1 = first half, 2 = second half, 0 = half-time / paused. */
    val p: Int = 1,
) {
    companion object {
        const val PHASE_PAUSED = 0
        const val PHASE_FIRST_HALF = 1
        const val PHASE_SECOND_HALF = 2
    }
}
