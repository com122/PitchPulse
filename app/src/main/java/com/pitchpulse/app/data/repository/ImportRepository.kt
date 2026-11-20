package com.pitchpulse.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pitchpulse.app.data.file.GpxParser
import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.model.MatchSource
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.domain.algorithm.HalfTimeDetector
import com.pitchpulse.app.domain.simulation.MatchSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** File-picker imports (JSONL / GPX) and simulated matches; all funnel into WatchSyncRepository. */
class ImportRepository(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
    private val trackStore: TrackFileStore,
    private val watchSync: WatchSyncRepository,
    private val pitchRepository: PitchRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun importUri(uri: Uri): ImportResult {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "import"
        val temp = File(cacheDir, "import-${UUID.randomUUID()}.tmp")
        try {
            withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                input.use { src -> temp.outputStream().use { src.copyTo(it) } }
            }
            val isGpx = name.endsWith(".gpx", ignoreCase = true) || looksLikeXml(temp)
            return if (isGpx) {
                val points = withContext(Dispatchers.IO) { temp.inputStream().use { GpxParser.parse(it) } }
                val phased = HalfTimeDetector.assignPhases(points)
                val jsonl = File(cacheDir, "gpx-${UUID.randomUUID()}.jsonl")
                try {
                    trackStore.write(jsonl, phased)
                    watchSync.importTrackJsonl(jsonl, ImportOptions(source = MatchSource.GPX))
                } finally {
                    jsonl.delete()
                }
            } else {
                watchSync.importTrackJsonl(temp, ImportOptions(source = MatchSource.JSONL))
            }
        } finally {
            temp.delete()
        }
    }

    /** Generates a 90-minute match on [pitchId] and imports it. Kick-off is placed so it ended just now. */
    suspend fun simulate(pitchId: String, position: PlayerPosition): ImportResult {
        val pitch = pitchRepository.getPitch(pitchId) ?: error("球场不存在")
        val settings = settingsRepository.current()
        val random = Random(System.nanoTime())
        val kickoff = System.currentTimeMillis() - (110 + random.nextInt(0, 30)) * 60_000L
        val points = withContext(Dispatchers.Default) {
            MatchSimulator(random).generate(pitch, position, settings, kickoff)
        }
        val file = File(cacheDir, "sim-${UUID.randomUUID()}.jsonl")
        try {
            trackStore.write(file, points)
            return watchSync.importTrackJsonl(
                file,
                ImportOptions(preferredPitchId = pitchId, source = MatchSource.SIMULATION, position = position),
            )
        } finally {
            file.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun looksLikeXml(file: File): Boolean {
        val head = file.inputStream().use { s ->
            val buf = ByteArray(256)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("<?xml") || head.startsWith("<gpx")
    }
}
