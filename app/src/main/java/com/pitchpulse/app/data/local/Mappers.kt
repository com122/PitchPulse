package com.pitchpulse.app.data.local

import com.pitchpulse.app.data.model.HrZoneDurations
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.MatchSource
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.SegmentDistance
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun PitchEntity.toModel() = PitchConfig(
    id = id,
    name = name,
    pitchType = pitchType,
    centerLat = centerLat,
    centerLng = centerLng,
    lengthMeters = lengthMeters,
    widthMeters = widthMeters,
    bearingDegrees = bearingDegrees,
)

fun PitchConfig.toEntity(createdAt: Long, updatedAt: Long) = PitchEntity(
    id = id,
    name = name,
    pitchType = pitchType,
    centerLat = centerLat,
    centerLng = centerLng,
    lengthMeters = lengthMeters,
    widthMeters = widthMeters,
    bearingDegrees = bearingDegrees,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MatchEntity.toStats() = MatchStats(
    totalDistanceMeters = totalDistanceMeters,
    effectiveDurationMs = effectiveDurationMs,
    avgHr = avgHr,
    maxHr = maxHr,
    maxSpeedKmh = maxSpeedKmh,
    sprintCount = sprintCount,
    sprintDistanceMeters = sprintDistanceMeters,
    highIntensityDistanceMeters = highIntensityDistanceMeters,
    firstHalfDistanceMeters = firstHalfDistanceMeters,
    secondHalfDistanceMeters = secondHalfDistanceMeters,
    hrZones = runCatching { json.decodeFromString(HrZoneDurations.serializer(), hrZonesJson) }
        .getOrDefault(HrZoneDurations()),
    segments = runCatching { json.decodeFromString(ListSerializer(SegmentDistance.serializer()), segmentsJson) }
        .getOrDefault(emptyList()),
    rawPointCount = rawPointCount,
    validPointCount = validPointCount,
)

fun MatchEntity.toRecord(pitch: PitchConfig?) = MatchRecord(
    id = id,
    pitchId = pitchId,
    pitchName = pitch?.name ?: "未知球场",
    pitchType = pitch?.pitchType ?: 0,
    startTime = startTime,
    endTime = endTime,
    source = runCatching { MatchSource.valueOf(source) }.getOrDefault(MatchSource.JSONL),
    position = position?.let { runCatching { PlayerPosition.valueOf(it) }.getOrNull() },
    stats = toStats(),
    createdAt = createdAt,
)

fun MatchEntity.withStats(stats: MatchStats) = copy(
    totalDistanceMeters = stats.totalDistanceMeters,
    effectiveDurationMs = stats.effectiveDurationMs,
    avgHr = stats.avgHr,
    maxHr = stats.maxHr,
    maxSpeedKmh = stats.maxSpeedKmh,
    sprintCount = stats.sprintCount,
    sprintDistanceMeters = stats.sprintDistanceMeters,
    highIntensityDistanceMeters = stats.highIntensityDistanceMeters,
    firstHalfDistanceMeters = stats.firstHalfDistanceMeters,
    secondHalfDistanceMeters = stats.secondHalfDistanceMeters,
    hrZonesJson = json.encodeToString(HrZoneDurations.serializer(), stats.hrZones),
    segmentsJson = json.encodeToString(ListSerializer(SegmentDistance.serializer()), stats.segments),
    rawPointCount = stats.rawPointCount,
    validPointCount = stats.validPointCount,
)

fun newMatchEntity(
    id: String,
    pitchId: String,
    startTime: Long,
    endTime: Long,
    source: MatchSource,
    position: PlayerPosition?,
    trackFileName: String,
    stats: MatchStats,
) = MatchEntity(
    id = id,
    pitchId = pitchId,
    startTime = startTime,
    endTime = endTime,
    source = source.name,
    position = position?.name,
    trackFileName = trackFileName,
    totalDistanceMeters = 0f,
    effectiveDurationMs = 0,
    avgHr = 0,
    maxHr = 0,
    maxSpeedKmh = 0f,
    sprintCount = 0,
    sprintDistanceMeters = 0f,
    highIntensityDistanceMeters = 0f,
    firstHalfDistanceMeters = 0f,
    secondHalfDistanceMeters = 0f,
    hrZonesJson = "{}",
    segmentsJson = "[]",
    rawPointCount = 0,
    validPointCount = 0,
    createdAt = System.currentTimeMillis(),
).withStats(stats)
