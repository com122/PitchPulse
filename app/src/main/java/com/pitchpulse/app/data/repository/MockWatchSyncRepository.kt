package com.pitchpulse.app.data.repository

import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.local.newMatchEntity
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Phone-only implementation. The connection is always "awaiting Watch 4"; files come from the
 * simulator or the system file picker, but go through exactly the pipeline the watch will use.
 */
class MockWatchSyncRepository(
    private val trackStore: TrackFileStore,
    private val pitchRepository: PitchRepository,
    private val matchRepository: MatchRepository,
    private val thumbnails: ThumbnailRepository,
    private val settingsRepository: SettingsRepository,
) : WatchSyncRepository {

    private val _state = MutableStateFlow<WatchConnectionState>(WatchConnectionState.AwaitingWatch)
    override val connectionState: StateFlow<WatchConnectionState> = _state.asStateFlow()

    override suspend fun importTrackJsonl(file: File, options: ImportOptions): ImportResult {
        val parsed = trackStore.parse(file)
        val points = parsed.points.sortedBy { it.t }
        require(points.size >= MIN_POINTS) {
            "有效轨迹点不足（${points.size} 个，至少需要 $MIN_POINTS 个；跳过 ${parsed.skippedLines} 行无法解析的数据）"
        }

        val resolution = pitchRepository.resolveForTrack(points, options.preferredPitchId)
        val settings = settingsRepository.current()
        val stats = withContext(Dispatchers.Default) {
            MatchStatsCalculator(MatchStatsCalculator.Config.from(settings)).analyze(points).stats
        }

        val matchId = UUID.randomUUID().toString()
        val target = trackStore.fileFor(matchId)
        trackStore.write(target, points)

        matchRepository.insert(
            newMatchEntity(
                id = matchId,
                pitchId = resolution.pitch.id,
                startTime = points.first().t,
                endTime = points.last().t,
                source = options.source,
                position = options.position,
                trackFileName = target.name,
                stats = stats,
            ),
        )
        runCatching { thumbnails.generate(matchId, resolution.pitch, points, settings) }

        return ImportResult(
            matchId = matchId,
            pitch = resolution.pitch,
            pitchAutoCreated = resolution.autoCreated,
            pitchDistanceMeters = resolution.distanceMeters,
            stats = stats,
            skippedLines = parsed.skippedLines,
        )
    }

    companion object {
        const val MIN_POINTS = 30
    }
}
