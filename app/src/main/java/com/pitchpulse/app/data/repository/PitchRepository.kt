package com.pitchpulse.app.data.repository

import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.local.MatchDao
import com.pitchpulse.app.data.local.PitchDao
import com.pitchpulse.app.data.local.toEntity
import com.pitchpulse.app.data.local.toModel
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.domain.algorithm.GeoMath
import com.pitchpulse.app.domain.algorithm.PitchEstimator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PitchRepository(
    private val pitchDao: PitchDao,
    private val matchDao: MatchDao,
    private val trackStore: TrackFileStore,
    private val thumbnails: ThumbnailRepository,
) {
    data class Resolution(
        val pitch: PitchConfig,
        val autoCreated: Boolean,
        /** Distance from the track centroid to the pitch centre, meters. */
        val distanceMeters: Double,
    )

    fun observePitches(): Flow<List<PitchConfig>> = pitchDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeMatchCounts(): Flow<Map<String, Int>> =
        matchDao.observeCountsByPitch().map { list -> list.associate { it.pitchId to it.count } }

    suspend fun getPitch(id: String): PitchConfig? = pitchDao.getById(id)?.toModel()

    suspend fun getAll(): List<PitchConfig> = pitchDao.getAll().map { it.toModel() }

    suspend fun save(pitch: PitchConfig) {
        val now = System.currentTimeMillis()
        val existing = pitchDao.getById(pitch.id)
        pitchDao.upsert(pitch.toEntity(createdAt = existing?.createdAt ?: now, updatedAt = now))
        if (existing != null) {
            thumbnails.invalidate(matchDao.getByPitch(pitch.id).map { it.id })
        }
    }

    /** Deletes a pitch and re-assigns its matches to the nearest remaining (or an auto-estimated) pitch. */
    suspend fun delete(id: String) {
        val affected = matchDao.getByPitch(id)
        pitchDao.delete(id)
        for (m in affected) {
            val points = trackStore.read(m.id)
            val resolution = resolveForTrack(points, preferredPitchId = null)
            matchDao.updatePitch(m.id, resolution.pitch.id)
        }
        thumbnails.invalidate(affected.map { it.id })
    }

    /**
     * Picks the pitch for a track: the preferred pitch if it exists, else the saved pitch nearest to
     * the track centroid (within [MATCH_RADIUS_METERS] or the pitch's own diagonal), else a new
     * pitch estimated from the track's shape.
     */
    suspend fun resolveForTrack(points: List<TrackPoint>, preferredPitchId: String?): Resolution {
        val centroid = PitchEstimator.centroid(points)
        if (preferredPitchId != null) {
            getPitch(preferredPitchId)?.let { p ->
                val d = centroid?.let { GeoMath.distanceMeters(it[0], it[1], p.centerLat, p.centerLng) } ?: 0.0
                return Resolution(p, autoCreated = false, distanceMeters = d)
            }
        }
        val all = getAll()
        if (centroid != null) {
            val nearest = all.minByOrNull { GeoMath.distanceMeters(centroid[0], centroid[1], it.centerLat, it.centerLng) }
            if (nearest != null) {
                val d = GeoMath.distanceMeters(centroid[0], centroid[1], nearest.centerLat, nearest.centerLng)
                val diagonal = Math.hypot(nearest.lengthMeters.toDouble(), nearest.widthMeters.toDouble())
                if (d <= maxOf(MATCH_RADIUS_METERS, diagonal)) {
                    return Resolution(nearest, autoCreated = false, distanceMeters = d)
                }
            }
            val date = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(points.minOf { it.t }))
            val estimated = PitchEstimator.estimate(points, UUID.randomUUID().toString(), "自动识别球场 $date")
            if (estimated != null) {
                save(estimated)
                return Resolution(estimated, autoCreated = true, distanceMeters = 0.0)
            }
            if (nearest != null) {
                val d = GeoMath.distanceMeters(centroid[0], centroid[1], nearest.centerLat, nearest.centerLng)
                return Resolution(nearest, autoCreated = false, distanceMeters = d)
            }
            // No pitches at all and too few points to estimate: default-size pitch on the centroid.
            val (l, w) = PitchConfig.defaultSizeFor(7)
            val fallback = PitchConfig(UUID.randomUUID().toString(), "自动创建球场 $date", 7, centroid[0], centroid[1], l, w, 0f)
            save(fallback)
            return Resolution(fallback, autoCreated = true, distanceMeters = 0.0)
        }
        throw IllegalStateException("轨迹中没有有效坐标点")
    }

    companion object {
        const val MATCH_RADIUS_METERS = 500.0
    }
}
