package com.pitchpulse.app.domain.algorithm

/**
 * Line-marking dimensions in meters for each format. 11-a-side follows the IFAB Laws of the Game;
 * small-sided formats use common amateur conventions. Values are clamped to fit the actual pitch.
 */
data class PitchMarkings(
    val length: Float,
    val width: Float,
    val penaltyAreaDepth: Float,
    val penaltyAreaWidth: Float,
    /** 0 when the format has no separate goal area. */
    val goalAreaDepth: Float,
    val goalAreaWidth: Float,
    val penaltySpotDistance: Float,
    val centerCircleRadius: Float,
    /** Radius of the "D" arc around the penalty spot; 0 to skip. */
    val penaltyArcRadius: Float,
    val goalWidth: Float,
    val cornerArcRadius: Float,
) {
    companion object {
        fun forPitch(pitchType: Int, length: Float, width: Float): PitchMarkings {
            val base = when (pitchType) {
                5 -> floatArrayOf(6f, 15f, 0f, 0f, 6f, 3f, 0f, 3f, 0.5f)
                7 -> floatArrayOf(12f, 26f, 4f, 12f, 9f, 6f, 6f, 5f, 0.8f)
                8 -> floatArrayOf(13.5f, 30f, 4.5f, 14f, 10f, 7f, 7f, 6f, 0.8f)
                else -> floatArrayOf(16.5f, 40.32f, 5.5f, 18.32f, 11f, 9.15f, 9.15f, 7.32f, 1f)
            }
            val paDepth = base[0].coerceAtMost(length * 0.3f)
            val paWidth = base[1].coerceAtMost(width * 0.8f)
            return PitchMarkings(
                length = length,
                width = width,
                penaltyAreaDepth = paDepth,
                penaltyAreaWidth = paWidth,
                goalAreaDepth = base[2].coerceAtMost(paDepth * 0.5f),
                goalAreaWidth = base[3].coerceAtMost(paWidth * 0.7f),
                penaltySpotDistance = base[4].coerceAtMost(paDepth * 0.8f),
                centerCircleRadius = base[5].coerceAtMost(width * 0.3f),
                penaltyArcRadius = base[6],
                goalWidth = base[7].coerceAtMost(paWidth * 0.5f),
                cornerArcRadius = base[8],
            )
        }
    }
}
