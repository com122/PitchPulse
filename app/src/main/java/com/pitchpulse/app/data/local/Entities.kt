package com.pitchpulse.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pitches")
data class PitchEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pitchType: Int,
    val centerLat: Double,
    val centerLng: Double,
    val lengthMeters: Float,
    val widthMeters: Float,
    val bearingDegrees: Float,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Match summary metadata. Raw samples live in `filesDir/tracks/{id}.jsonl`, not in SQLite.
 * pitchId is intentionally not a foreign key: when a pitch is deleted, matches are re-assigned
 * to the nearest remaining pitch by the repository.
 */
@Entity(tableName = "matches", indices = [Index("pitchId"), Index("startTime")])
data class MatchEntity(
    @PrimaryKey val id: String,
    val pitchId: String?,
    val startTime: Long,
    val endTime: Long,
    val source: String,
    val position: String?,
    val trackFileName: String,
    val totalDistanceMeters: Float,
    val effectiveDurationMs: Long,
    val avgHr: Int,
    val maxHr: Int,
    val maxSpeedKmh: Float,
    val sprintCount: Int,
    val sprintDistanceMeters: Float,
    val highIntensityDistanceMeters: Float,
    val firstHalfDistanceMeters: Float,
    val secondHalfDistanceMeters: Float,
    /** JSON of HrZoneDurations. */
    val hrZonesJson: String,
    /** JSON of List<SegmentDistance>. */
    val segmentsJson: String,
    val rawPointCount: Int,
    val validPointCount: Int,
    val createdAt: Long,
)

data class PitchMatchCount(
    @ColumnInfo(name = "pitchId") val pitchId: String,
    @ColumnInfo(name = "count") val count: Int,
)
