package com.pitchpulse.app.data.file

import com.pitchpulse.app.data.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Reads and writes per-match JSONL tracks under `filesDir/tracks/{matchId}.jsonl`. */
class TrackFileStore(filesDir: File) {

    data class ParseResult(val points: List<TrackPoint>, val skippedLines: Int)

    private val dir = File(filesDir, "tracks").apply { mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        // Every line must carry all 7 contract fields, even when equal to the Kotlin default.
        encodeDefaults = true
    }

    fun fileFor(matchId: String) = File(dir, "$matchId.jsonl")

    suspend fun write(file: File, points: List<TrackPoint>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            for (p in points) {
                w.write(json.encodeToString(TrackPoint.serializer(), p))
                w.newLine()
            }
        }
    }

    suspend fun write(matchId: String, points: List<TrackPoint>) = write(fileFor(matchId), points)

    suspend fun read(matchId: String): List<TrackPoint> = parse(fileFor(matchId)).points

    /** Lenient parse: blank or malformed lines are skipped and counted. */
    suspend fun parse(file: File): ParseResult = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ParseResult(emptyList(), 0)
        val points = ArrayList<TrackPoint>()
        var skipped = 0
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val p = runCatching { json.decodeFromString(TrackPoint.serializer(), trimmed) }.getOrNull()
                if (p == null || !isPlausible(p)) skipped++ else points += p
            }
        }
        ParseResult(points, skipped)
    }

    fun delete(matchId: String) {
        fileFor(matchId).delete()
    }

    private fun isPlausible(p: TrackPoint) =
        p.t > 0 && p.lat in -90.0..90.0 && p.lng in -180.0..180.0 && !(p.lat == 0.0 && p.lng == 0.0)
}
