package com.pitchpulse.app.data.repository

import com.pitchpulse.app.data.file.ReferenceFileStore
import com.pitchpulse.app.data.model.ReferenceFix
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.domain.algorithm.AccuracyEnhancer
import com.pitchpulse.app.domain.gnss.GnssRawLogParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/** Phone reference-station sessions and their association with matches (by time overlap). */
class ReferenceRepository(private val store: ReferenceFileStore) {

    data class Candidate(val meta: ReferenceSessionMeta, val overlap: Double)

    private val _sessions = MutableStateFlow<List<ReferenceSessionMeta>>(emptyList())
    val sessions: StateFlow<List<ReferenceSessionMeta>> = _sessions.asStateFlow()

    suspend fun refresh() {
        _sessions.value = store.listMetas()
    }

    suspend fun delete(id: String) {
        store.delete(id)
        refresh()
    }

    suspend fun save(meta: ReferenceSessionMeta, fixes: List<ReferenceFix>) {
        store.writeFixes(meta.id, fixes)
        store.writeMeta(meta)
        refresh()
    }

    /** @param includeRaw also parse the raw GNSS log (large; only needed for Tier B). */
    suspend fun load(id: String, includeRaw: Boolean = false): AccuracyEnhancer.Reference? {
        val meta = store.readMeta(id) ?: return null
        val fixes = store.readFixes(id)
        if (fixes.isEmpty()) return null
        val raw = if (includeRaw && store.rawFile(id).exists()) withContext(Dispatchers.IO) {
            runCatching { store.rawFile(id).bufferedReader().use { GnssRawLogParser.parse(it) } }.getOrNull()
        } else null
        return AccuracyEnhancer.Reference(meta, fixes, raw)
    }

    /**
     * Session with the largest time overlap with [start]..[end] (fraction of the match covered).
     * Sessions of the same pitch win ties. Returns null when nothing overlaps.
     */
    suspend fun bestFor(start: Long, end: Long, pitchId: String?): Candidate? {
        if (_sessions.value.isEmpty()) refresh()
        val span = (end - start).coerceAtLeast(1)
        return _sessions.value.mapNotNull { m ->
            val a = m.firstFixAt ?: return@mapNotNull null
            val b = m.lastFixAt ?: return@mapNotNull null
            val ov = (min(end, b) - max(start, a)).coerceAtLeast(0).toDouble() / span
            if (ov <= 0) null else Candidate(m, ov)
        }.maxWithOrNull(compareBy<Candidate> { it.overlap }.thenBy { if (it.meta.pitchId == pitchId) 1 else 0 })
    }

    companion object {
        /** Below this coverage the UI warns that the reference only covers part of the match. */
        const val MIN_GOOD_OVERLAP = 0.5
    }
}
