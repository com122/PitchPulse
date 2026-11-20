package com.pitchpulse.app.data.repository

import com.pitchpulse.app.data.model.MatchSource
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface WatchConnectionState {
    /** No Wear OS data layer integration yet. */
    data object AwaitingWatch : WatchConnectionState
    data class Connected(val deviceName: String, val lastSyncAt: Long?) : WatchConnectionState
}

data class ImportOptions(
    /** Use this pitch instead of auto-matching (e.g. simulated matches). */
    val preferredPitchId: String? = null,
    val source: MatchSource = MatchSource.WATCH,
    val position: PlayerPosition? = null,
)

data class ImportResult(
    val matchId: String,
    val pitch: PitchConfig,
    val pitchAutoCreated: Boolean,
    val pitchDistanceMeters: Double,
    val stats: MatchStats,
    val skippedLines: Int,
)

/**
 * Entry point for tracks recorded on the Galaxy Watch 4 (Wear OS). The watch will transfer one
 * `{matchId}.jsonl` file per match (TrackPoint per line); everything downstream is handled here:
 * parsing, nearest-pitch matching, stats and Room persistence.
 */
interface WatchSyncRepository {
    val connectionState: StateFlow<WatchConnectionState>

    suspend fun importTrackJsonl(file: File, options: ImportOptions = ImportOptions()): ImportResult
}
