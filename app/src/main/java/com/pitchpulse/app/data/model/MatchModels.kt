package com.pitchpulse.app.data.model

import kotlinx.serialization.Serializable

enum class PlayerPosition(val label: String) {
    WINGER("边锋"),
    MIDFIELDER("中场"),
    DEFENDER("后卫"),
    FORWARD("前锋"),
}

enum class MatchSource(val label: String) {
    SIMULATION("仿真"),
    JSONL("JSONL 导入"),
    GPX("GPX 导入"),
    WATCH("Watch 同步"),
}

/** Distance covered in one 5-minute block of match time. */
@Serializable
data class SegmentDistance(
    val index: Int,
    val phase: Int,
    val startMinute: Int,
    val distanceMeters: Float,
)

/** Time spent in each heart-rate zone, Z1..Z5 based on % of max HR. */
@Serializable
data class HrZoneDurations(
    val belowZ1Ms: Long = 0,
    val zonesMs: List<Long> = List(5) { 0L },
) {
    val totalInZonesMs: Long get() = zonesMs.sum()
    val totalMs: Long get() = totalInZonesMs + belowZ1Ms

    /** Share of each zone (0..1) relative to all HR-recorded time. */
    fun share(zoneIndex: Int): Float =
        if (totalMs == 0L) 0f else zonesMs[zoneIndex].toFloat() / totalMs
}

/** Stored match summary (mapped from Room's MatchEntity). */
data class MatchRecord(
    val id: String,
    val pitchId: String?,
    val pitchName: String,
    val pitchType: Int,
    val startTime: Long,
    val endTime: Long,
    val source: MatchSource,
    val position: PlayerPosition?,
    val stats: MatchStats,
    val createdAt: Long,
)

/** Output of MatchStatsCalculator; also what is persisted per match. */
@Serializable
data class MatchStats(
    val totalDistanceMeters: Float = 0f,
    val effectiveDurationMs: Long = 0,
    val avgHr: Int = 0,
    val maxHr: Int = 0,
    val maxSpeedKmh: Float = 0f,
    val sprintCount: Int = 0,
    val sprintDistanceMeters: Float = 0f,
    val highIntensityDistanceMeters: Float = 0f,
    val firstHalfDistanceMeters: Float = 0f,
    val secondHalfDistanceMeters: Float = 0f,
    val hrZones: HrZoneDurations = HrZoneDurations(),
    val segments: List<SegmentDistance> = emptyList(),
    val rawPointCount: Int = 0,
    val validPointCount: Int = 0,
) {
    val totalDistanceKm: Float get() = totalDistanceMeters / 1000f
}

/** User fitness parameters persisted with DataStore. */
data class UserSettings(
    val maxHr: Int = DEFAULT_MAX_HR,
    val restingHr: Int = DEFAULT_RESTING_HR,
    val sprintSpeedMs: Float = DEFAULT_SPRINT_SPEED,
    val sprintMinDurationSec: Float = DEFAULT_SPRINT_MIN_DURATION,
    /** Gaussian sigma of the heatmap kernel, in grid cells (grid is 120 x 80). */
    val heatmapSigma: Float = DEFAULT_HEATMAP_SIGMA,
) {
    companion object {
        const val DEFAULT_MAX_HR = 190
        const val DEFAULT_RESTING_HR = 60
        const val DEFAULT_SPRINT_SPEED = 5.5f
        const val DEFAULT_SPRINT_MIN_DURATION = 2f
        const val DEFAULT_HEATMAP_SIGMA = 2.5f
    }
}
