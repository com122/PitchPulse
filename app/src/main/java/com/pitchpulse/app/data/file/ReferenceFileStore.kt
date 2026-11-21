package com.pitchpulse.app.data.file

import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File

/**
 * Reference-station files under `filesDir/refs/`:
 * - `{id}.jsonl`: one [ReferenceFix] per line;
 * - `{id}.meta.json`: [ReferenceSessionMeta];
 * - `{id}.gnss.txt`: optional raw GNSS log in Google GnssLogger text format.
 */
class ReferenceFileStore(filesDir: File) {
    val dir = File(filesDir, "refs").apply { mkdirs() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun fixesFile(id: String) = File(dir, "$id.jsonl")
    fun metaFile(id: String) = File(dir, "$id.meta.json")
    fun rawFile(id: String) = File(dir, "$id.gnss.txt")

    fun encodeFix(fix: ReferenceFix): String = json.encodeToString(ReferenceFix.serializer(), fix)

    /** Appending writer for live recording. */
    fun openFixWriter(id: String): BufferedWriter = java.io.FileWriter(fixesFile(id), true).buffered()

    /** Atomic meta write (tmp + rename) so a crash never leaves a half-written file. Blocking. */
    fun writeMetaBlocking(meta: ReferenceSessionMeta) {
        val tmp = File(dir, "${meta.id}.meta.json.tmp")
        tmp.writeText(json.encodeToString(ReferenceSessionMeta.serializer(), meta))
        if (!tmp.renameTo(metaFile(meta.id))) {
            metaFile(meta.id).writeText(tmp.readText()); tmp.delete()
        }
    }

    suspend fun writeMeta(meta: ReferenceSessionMeta) = withContext(Dispatchers.IO) { writeMetaBlocking(meta) }

    suspend fun readMeta(id: String): ReferenceSessionMeta? = withContext(Dispatchers.IO) {
        val f = metaFile(id)
        if (!f.exists()) null else runCatching { json.decodeFromString(ReferenceSessionMeta.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun listMetas(): List<ReferenceSessionMeta> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(ReferenceSessionMeta.serializer(), f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    /** Lenient read: malformed lines (e.g. a truncated last line after a crash) are skipped. */
    suspend fun readFixes(id: String): List<ReferenceFix> = withContext(Dispatchers.IO) {
        val f = fixesFile(id)
        if (!f.exists()) return@withContext emptyList()
        val out = ArrayList<ReferenceFix>()
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val s = line.trim()
                if (s.isEmpty()) continue
                runCatching { json.decodeFromString(ReferenceFix.serializer(), s) }.getOrNull()?.let { out += it }
            }
        }
        out
    }

    suspend fun writeFixes(id: String, fixes: List<ReferenceFix>) = withContext(Dispatchers.IO) {
        fixesFile(id).bufferedWriter().use { w -> fixes.forEach { w.write(encodeFix(it)); w.newLine() } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fixesFile(id).delete(); metaFile(id).delete(); rawFile(id).delete()
    }
}
