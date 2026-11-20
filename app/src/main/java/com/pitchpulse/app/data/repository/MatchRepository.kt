package com.pitchpulse.app.data.repository

import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.local.MatchDao
import com.pitchpulse.app.data.local.MatchEntity
import com.pitchpulse.app.data.local.PitchDao
import com.pitchpulse.app.data.local.toModel
import com.pitchpulse.app.data.local.toRecord
import com.pitchpulse.app.data.local.withStats
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class MatchRepository(
    private val matchDao: MatchDao,
    private val pitchDao: PitchDao,
    private val trackStore: TrackFileStore,
    private val thumbnails: ThumbnailRepository,
) {
    fun observeMatches(): Flow<List<MatchRecord>> =
        combine(matchDao.observeAll(), pitchDao.observeAll()) { matches, pitches ->
            val byId = pitches.associate { it.id to it.toModel() }
            matches.map { it.toRecord(it.pitchId?.let(byId::get)) }
        }

    fun observeMatch(id: String): Flow<MatchRecord?> =
        combine(matchDao.observeById(id), pitchDao.observeAll()) { m, pitches ->
            m?.toRecord(pitches.firstOrNull { it.id == m.pitchId }?.toModel())
        }

    suspend fun getEntity(id: String): MatchEntity? = matchDao.getById(id)

    suspend fun loadTrack(id: String): List<TrackPoint> = trackStore.read(id)

    suspend fun insert(entity: MatchEntity) = matchDao.upsert(entity)

    suspend fun delete(id: String) {
        matchDao.delete(id)
        withContext(Dispatchers.IO) { trackStore.delete(id) }
        thumbnails.invalidate(listOf(id))
    }

    /** Re-runs the stats engine on every stored track with the current settings. Returns the match count. */
    suspend fun recomputeAll(settings: UserSettings, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val all = matchDao.getAll()
        val calculator = MatchStatsCalculator(MatchStatsCalculator.Config.from(settings))
        all.forEachIndexed { i, m ->
            val points = trackStore.read(m.id)
            if (points.isNotEmpty()) {
                val stats = withContext(Dispatchers.Default) { calculator.analyze(points).stats }
                matchDao.upsert(m.withStats(stats))
            }
            onProgress(i + 1, all.size)
        }
        thumbnails.invalidate(all.map { it.id })
        return all.size
    }
}
