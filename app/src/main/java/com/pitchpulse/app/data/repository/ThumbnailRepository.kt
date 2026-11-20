package com.pitchpulse.app.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.domain.algorithm.HeatmapGenerator
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.algorithm.PitchProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mini heatmaps for the match list: memory LRU → PNG in `filesDir/thumbs` → generated on demand.
 * Thumbnails use half-time side-swap correction so both halves read in the same direction.
 */
class ThumbnailRepository(
    filesDir: File,
    private val trackStore: TrackFileStore,
) {
    private val dir = File(filesDir, "thumbs").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(64)

    private val _version = MutableStateFlow(0)
    /** Bumped whenever thumbnails are invalidated so the UI can reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun fileFor(matchId: String) = File(dir, "$matchId.png")

    suspend fun get(matchId: String, pitch: PitchConfig?, settings: UserSettings): Bitmap? {
        cache.get(matchId)?.let { return it }
        val fromDisk = withContext(Dispatchers.IO) {
            val f = fileFor(matchId)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        }
        if (fromDisk != null) {
            cache.put(matchId, fromDisk)
            return fromDisk
        }
        if (pitch == null) return null
        val points = trackStore.read(matchId)
        if (points.isEmpty()) return null
        return generate(matchId, pitch, points, settings)
    }

    suspend fun generate(matchId: String, pitch: PitchConfig, points: List<TrackPoint>, settings: UserSettings): Bitmap {
        val bmp = withContext(Dispatchers.Default) {
            val clean = MatchStatsCalculator(MatchStatsCalculator.Config.from(settings)).clean(points)
            val projected = PitchProjector(pitch).project(clean, swapSecondHalf = true)
            HeatmapGenerator.generate(projected, settings.heatmapSigma)
        }
        withContext(Dispatchers.IO) {
            fileFor(matchId).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        cache.put(matchId, bmp)
        return bmp
    }

    fun invalidate(matchIds: Collection<String>) {
        for (id in matchIds) {
            cache.remove(id)
            fileFor(id).delete()
        }
        _version.update { it + 1 }
    }
}
