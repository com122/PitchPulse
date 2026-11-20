package com.pitchpulse.app.data.model

import kotlinx.serialization.Serializable

/**
 * Pitch template. All coordinates are WGS-84.
 *
 * @property pitchType 5 / 7 / 8 / 11-a-side.
 * @property bearingDegrees clockwise angle (0..360) between true north and the pitch's long axis
 *   (the goal-to-goal direction). Normalised x = 0 is the goal line "behind" the bearing,
 *   x = 1 is the goal line the bearing points at.
 */
@Serializable
data class PitchConfig(
    val id: String,
    val name: String,
    val pitchType: Int,
    val centerLat: Double,
    val centerLng: Double,
    val lengthMeters: Float,
    val widthMeters: Float,
    val bearingDegrees: Float,
) {
    companion object {
        val SUPPORTED_TYPES = listOf(5, 7, 8, 11)

        /** Typical amateur pitch sizes (length x width, meters) per format. */
        fun defaultSizeFor(pitchType: Int): Pair<Float, Float> = when (pitchType) {
            5 -> 40f to 20f
            7 -> 60f to 40f
            8 -> 70f to 50f
            else -> 105f to 68f
        }

        /** Picks the format whose default size is closest to the given dimensions. */
        fun closestType(lengthMeters: Float, widthMeters: Float): Int =
            SUPPORTED_TYPES.minBy { type ->
                val (l, w) = defaultSizeFor(type)
                (l - lengthMeters) * (l - lengthMeters) + (w - widthMeters) * (w - widthMeters)
            }
    }
}
