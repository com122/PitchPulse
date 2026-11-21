package com.pitchpulse.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pitchpulse.app.data.file.GpxParser
import com.pitchpulse.app.data.file.TrackFileStore
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.MatchSource
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.domain.algorithm.HalfTimeDetector
import com.pitchpulse.app.domain.algorithm.PitchFrame
import com.pitchpulse.app.domain.simulation.AccuracyTestSimulator
import com.pitchpulse.app.domain.simulation.GnssErrorSimulator
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
    private val referenceRepository: ReferenceRepository,
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

    enum class SimulationKind(val label: String) {
        PLAIN("仅手表（原噪声模型）"),
        WITH_REFERENCE("手表 + 手机参考站（已知点）"),
        ACCURACY_TEST("精度测试协议（含参考站）"),
    }

    /**
     * Generates a simulated session on [pitchId] and imports it. Kick-off is placed so it ended just now.
     * With a reference station, the phone sits on the halfway line / touchline intersection and both
     * devices share a slow GNSS error (bias + 1–10 min components), so Tier A can be exercised.
     */
    suspend fun simulate(pitchId: String, position: PlayerPosition, kind: SimulationKind = SimulationKind.PLAIN): ImportResult {
        val pitch = pitchRepository.getPitch(pitchId) ?: error("球场不存在")
        val settings = settingsRepository.current()
        val random = Random(System.nanoTime())
        val kickoff = System.currentTimeMillis() - (110 + random.nextInt(0, 30)) * 60_000L
        val points = withContext(Dispatchers.Default) {
            when (kind) {
                SimulationKind.PLAIN -> MatchSimulator(random).generate(pitch, position, settings, kickoff)
                SimulationKind.WITH_REFERENCE, SimulationKind.ACCURACY_TEST -> {
                    val frame = PitchFrame(pitch)
                    val sim = GnssErrorSimulator(GnssErrorSimulator.Model.shared(random), random)
                    val kp = KnownPoint.HALFWAY_TOUCHLINE_A
                    val (truth, routeStart, routeEnd) = if (kind == SimulationKind.ACCURACY_TEST) {
                        val r = AccuracyTestSimulator().generate(pitch, System.currentTimeMillis() - 30 * 60_000L, kp)
                        Triple(r.truth, r.routeStart, r.routeEnd)
                    } else Triple(MatchSimulator(random).generateTruth(pitch, position, settings, kickoff), null, null)
                    val watch = sim.watch(truth, frame)
                    val known = frame.normalizedToEn(kp.nx, kp.ny)
                    val ll = frame.latLng(known[0], known[1])
                    val fixes = sim.phone(ll[0], ll[1], truth.first().t - 5 * 60_000L, truth.last().t + 2 * 60_000L, frame)
                    val id = "sim-" + UUID.randomUUID().toString().take(8)
                    referenceRepository.save(
                        ReferenceSessionMeta(
                            id = id, pitchId = pitch.id, pitchName = pitch.name,
                            placement = RefPlacement.KNOWN_POINT, knownPoint = kp, knownLat = ll[0], knownLng = ll[1],
                            startedAt = fixes.first().t, stoppedAt = fixes.last().t,
                            firstFixAt = fixes.first().t, lastFixAt = fixes.last().t, fixCount = fixes.size,
                            device = "仿真参考站", protocol = routeStart != null,
                            protocolRouteStart = routeStart, protocolRouteEnd = routeEnd, simulated = true,
                        ),
                        fixes,
                    )
                    watch
                }
            }
        }
        val file = File(cacheDir, "sim-${UUID.randomUUID()}.jsonl")
        try {
            trackStore.write(file, points)
            return watchSync.importTrackJsonl(
                file,
                ImportOptions(
                    preferredPitchId = pitchId, source = MatchSource.SIMULATION,
                    position = if (kind == SimulationKind.ACCURACY_TEST) null else position,
                ),
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
