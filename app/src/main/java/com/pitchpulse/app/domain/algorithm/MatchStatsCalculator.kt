package com.pitchpulse.app.domain.algorithm

import com.pitchpulse.app.data.model.HrZoneDurations
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.SegmentDistance
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import kotlin.math.ceil
import kotlin.math.max

/** A track point that survived filtering, with derived kinematics. */
data class CleanPoint(
    val point: TrackPoint,
    /** Smoothed speed at this point, m/s. */
    val speedMs: Float,
    /** Geometric distance from the previous clean point; 0 at a segment start. */
    val rawStepMeters: Float,
    /** Distance counted towards mileage (0 when the step is below the anti-jitter threshold). */
    val countedStepMeters: Float,
    /** Time since the previous clean point; 0 at a segment start. */
    val dtMs: Long,
    /** True when this point starts a new continuous segment (first point, phase change, data gap). */
    val segmentStart: Boolean,
)

/** One sprint: consecutive steps at >= sprint speed lasting >= the minimum duration. */
data class SprintRun(
    /** Index (into the clean list) of the point where the sprint starts. */
    val startIndex: Int,
    /** Index (inclusive) of the last point of the sprint. */
    val endIndex: Int,
    val distanceMeters: Float,
    val durationMs: Long,
    val maxSpeedMs: Float,
)

data class MatchAnalysis(
    val clean: List<CleanPoint>,
    val stats: MatchStats,
    val sprints: List<SprintRun>,
)

/**
 * Football-specific distance / fitness engine.
 *
 * Pipeline:
 * 1. Drop points with `acc > 15 m` or phase `p == 0` (half-time / paused).
 * 2. Drop GPS jumps: a point whose implied speed from the last accepted point exceeds 11 m/s.
 *    If 5 consecutive points are rejected the anchor itself is assumed bad and the track re-anchors.
 * 3. Anti-jitter: a step whose displacement rate is below 0.5 m per second is not counted as mileage.
 * 4. Derive per-point speed (device speed when plausible, else displacement speed), smooth it with
 *    a 3-sample centred mean, then compute the statistics.
 */
class MatchStatsCalculator(private val config: Config = Config()) {

    data class Config(
        val maxHr: Int = UserSettings.DEFAULT_MAX_HR,
        val sprintSpeedMs: Float = UserSettings.DEFAULT_SPRINT_SPEED,
        val sprintMinDurationMs: Long = (UserSettings.DEFAULT_SPRINT_MIN_DURATION * 1000).toLong(),
        val maxAccuracyMeters: Float = 15f,
        val maxPlausibleSpeedMs: Float = 11f,
        val minDisplacementPerSecond: Float = 0.5f,
        val maxGapMs: Long = 10_000,
        val maxConsecutiveRejects: Int = 5,
        /** "High intensity running" band, >= 14.4 km/h. */
        val highIntensitySpeedMs: Float = 4.0f,
        val segmentMinutes: Int = 5,
    ) {
        companion object {
            fun from(settings: UserSettings) = Config(
                maxHr = settings.maxHr,
                sprintSpeedMs = settings.sprintSpeedMs,
                sprintMinDurationMs = (settings.sprintMinDurationSec * 1000).toLong(),
            )
        }
    }

    /** Runs filtering only. Points are returned sorted by time. */
    fun clean(points: List<TrackPoint>): List<CleanPoint> {
        val candidates = points
            .asSequence()
            .filter { it.acc <= config.maxAccuracyMeters && it.p != TrackPoint.PHASE_PAUSED }
            .sortedBy { it.t }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        // Pass 1: jump rejection, segment breaks, distances.
        data class Accepted(val p: TrackPoint, val step: Double, val dt: Long, val start: Boolean)

        val accepted = ArrayList<Accepted>(candidates.size)
        var anchor: TrackPoint? = null
        var rejects = 0
        for (p in candidates) {
            val a = anchor
            if (a == null) {
                accepted += Accepted(p, 0.0, 0, true); anchor = p; continue
            }
            val dt = p.t - a.t
            if (dt <= 0) continue
            if (p.p != a.p || dt > config.maxGapMs) {
                accepted += Accepted(p, 0.0, 0, true); anchor = p; rejects = 0; continue
            }
            val d = GeoMath.distanceMeters(a.lat, a.lng, p.lat, p.lng)
            val v = d / (dt / 1000.0)
            if (v > config.maxPlausibleSpeedMs) {
                rejects++
                if (rejects >= config.maxConsecutiveRejects) {
                    accepted += Accepted(p, 0.0, 0, true); anchor = p; rejects = 0
                }
                continue
            }
            rejects = 0
            accepted += Accepted(p, d, dt, false)
            anchor = p
        }

        // Pass 2: raw per-point speed.
        val rawSpeed = FloatArray(accepted.size)
        for (i in accepted.indices) {
            val a = accepted[i]
            val device = a.p.spd
            val derived = when {
                !a.start && a.dt > 0 -> (a.step / (a.dt / 1000.0)).toFloat()
                i + 1 < accepted.size && !accepted[i + 1].start && accepted[i + 1].dt > 0 ->
                    (accepted[i + 1].step / (accepted[i + 1].dt / 1000.0)).toFloat()
                else -> 0f
            }
            rawSpeed[i] = if (device > 0f && device <= config.maxPlausibleSpeedMs) device else derived
        }

        // Pass 3: 3-sample centred smoothing within segments, anti-jitter.
        val out = ArrayList<CleanPoint>(accepted.size)
        for (i in accepted.indices) {
            val a = accepted[i]
            var sum = rawSpeed[i]
            var n = 1
            if (!a.start && i > 0) { sum += rawSpeed[i - 1]; n++ }
            if (i + 1 < accepted.size && !accepted[i + 1].start) { sum += rawSpeed[i + 1]; n++ }
            val counted = if (a.start || a.dt <= 0) 0.0 else {
                val perSecond = a.step / (a.dt / 1000.0)
                if (perSecond < config.minDisplacementPerSecond) 0.0 else a.step
            }
            out += CleanPoint(
                point = a.p,
                speedMs = sum / n,
                rawStepMeters = a.step.toFloat(),
                countedStepMeters = counted.toFloat(),
                dtMs = a.dt,
                segmentStart = a.start,
            )
        }
        return out
    }

    /**
     * Full analysis. When [phase] is 1 or 2 only that half is analysed; null means the whole match.
     */
    fun analyze(points: List<TrackPoint>, phase: Int? = null): MatchAnalysis =
        analyzeFromClean(points, clean(points), phase)

    /**
     * Statistics from already-cleaned points (e.g. the Kalman-smoothed track). [raw] is only used
     * for the 5-minute block boundaries; [allClean] must be sorted by time and exclude p == 0.
     */
    fun analyzeFromClean(raw: List<TrackPoint>, allClean: List<CleanPoint>, phase: Int? = null): MatchAnalysis {
        val clean = if (phase == null) allClean else allClean.filter { it.point.p == phase }
            .mapIndexed { i, c -> if (i == 0 && !c.segmentStart) c.copy(segmentStart = true, rawStepMeters = 0f, countedStepMeters = 0f, dtMs = 0) else c }
        return analyzeClean(raw, clean)
    }

    /** Distance counted for a step under the anti-jitter rule. */
    fun countedStep(stepMeters: Double, dtMs: Long): Double =
        if (dtMs <= 0 || stepMeters / (dtMs / 1000.0) < config.minDisplacementPerSecond) 0.0 else stepMeters

    val sprintSpeedMs: Float get() = config.sprintSpeedMs
    val maxPlausibleSpeedMs: Float get() = config.maxPlausibleSpeedMs
    val sprintMinDurationMs: Long get() = config.sprintMinDurationMs
    val maxAccuracyMeters: Float get() = config.maxAccuracyMeters

    /** Sprint runs over clean points (same rule as [analyze]). */
    fun sprints(clean: List<CleanPoint>): List<SprintRun> = detectSprints(clean)

    private fun analyzeClean(raw: List<TrackPoint>, clean: List<CleanPoint>): MatchAnalysis {
        var distance = 0.0
        var duration = 0L
        var hiDistance = 0.0
        var firstHalf = 0.0
        var secondHalf = 0.0
        var maxSpeed = 0f
        var hrSum = 0L
        var hrCount = 0
        var hrMax = 0
        var belowZ1 = 0L
        val zones = LongArray(5)

        for (c in clean) {
            val hr = c.point.hr
            val hrValid = hr in 30..250
            if (hrValid) {
                hrSum += hr; hrCount++; hrMax = max(hrMax, hr)
            }
            if (c.speedMs > maxSpeed) maxSpeed = c.speedMs
            if (c.segmentStart) continue
            distance += c.countedStepMeters
            duration += c.dtMs
            if (c.speedMs >= config.highIntensitySpeedMs) hiDistance += c.countedStepMeters
            when (c.point.p) {
                TrackPoint.PHASE_FIRST_HALF -> firstHalf += c.countedStepMeters
                TrackPoint.PHASE_SECOND_HALF -> secondHalf += c.countedStepMeters
            }
            if (hrValid) {
                val z = zoneIndex(hr)
                if (z < 0) belowZ1 += c.dtMs else zones[z] += c.dtMs
            }
        }

        val sprints = detectSprints(clean)

        val stats = MatchStats(
            totalDistanceMeters = distance.toFloat(),
            effectiveDurationMs = duration,
            avgHr = if (hrCount == 0) 0 else (hrSum / hrCount).toInt(),
            maxHr = hrMax,
            maxSpeedKmh = maxSpeed * 3.6f,
            sprintCount = sprints.size,
            sprintDistanceMeters = sprints.sumOf { it.distanceMeters.toDouble() }.toFloat(),
            highIntensityDistanceMeters = hiDistance.toFloat(),
            firstHalfDistanceMeters = firstHalf.toFloat(),
            secondHalfDistanceMeters = secondHalf.toFloat(),
            hrZones = HrZoneDurations(belowZ1, zones.toList()),
            segments = segmentDistances(raw, clean),
            rawPointCount = raw.size,
            validPointCount = clean.size,
        )
        return MatchAnalysis(clean, stats, sprints)
    }

    /** -1 below 50 % of max HR, otherwise 0..4 for Z1..Z5. */
    fun zoneIndex(hr: Int): Int {
        val pct = hr.toFloat() / config.maxHr
        return when {
            pct < 0.5f -> -1
            pct < 0.6f -> 0
            pct < 0.7f -> 1
            pct < 0.8f -> 2
            pct < 0.9f -> 3
            else -> 4
        }
    }

    private fun detectSprints(clean: List<CleanPoint>): List<SprintRun> {
        val result = ArrayList<SprintRun>()
        var runStart = -1
        var runDist = 0.0
        var runDur = 0L
        var runMax = 0f

        fun close(endIndex: Int) {
            if (runStart >= 0 && runDur >= config.sprintMinDurationMs) {
                result += SprintRun(runStart, endIndex, runDist.toFloat(), runDur, runMax)
            }
            runStart = -1; runDist = 0.0; runDur = 0L; runMax = 0f
        }

        for (i in clean.indices) {
            val c = clean[i]
            if (c.segmentStart) { close(i - 1); continue }
            if (c.speedMs >= config.sprintSpeedMs) {
                if (runStart < 0) runStart = i - 1
                runDist += c.rawStepMeters
                runDur += c.dtMs
                if (c.speedMs > runMax) runMax = c.speedMs
            } else {
                close(i - 1)
            }
        }
        close(clean.lastIndex)
        return result
    }

    private fun segmentDistances(raw: List<TrackPoint>, clean: List<CleanPoint>): List<SegmentDistance> {
        val blockMs = config.segmentMinutes * 60_000L
        val phases = listOf(TrackPoint.PHASE_FIRST_HALF, TrackPoint.PHASE_SECOND_HALF)
        val starts = HashMap<Int, Long>()
        val ends = HashMap<Int, Long>()
        for (p in raw) {
            if (p.p !in phases) continue
            starts[p.p] = minOf(starts[p.p] ?: Long.MAX_VALUE, p.t)
            ends[p.p] = maxOf(ends[p.p] ?: Long.MIN_VALUE, p.t)
        }
        val phasesInClean = clean.map { it.point.p }.toSet()
        val result = ArrayList<SegmentDistance>()
        var indexOffset = 0
        var minuteOffset = 0
        for (phase in phases) {
            val start = starts[phase] ?: continue
            val end = ends[phase] ?: continue
            val blocks = max(1, ceil((end - start + 1).toDouble() / blockMs).toInt())
            if (phase in phasesInClean) {
                val dist = DoubleArray(blocks)
                for (c in clean) {
                    if (c.point.p != phase || c.segmentStart) continue
                    val b = ((c.point.t - start) / blockMs).toInt().coerceIn(0, blocks - 1)
                    dist[b] = dist[b] + c.countedStepMeters.toDouble()
                }
                for (b in 0 until blocks) {
                    result += SegmentDistance(
                        index = indexOffset + b,
                        phase = phase,
                        startMinute = minuteOffset + b * config.segmentMinutes,
                        distanceMeters = dist[b].toFloat(),
                    )
                }
            }
            indexOffset += blocks
            minuteOffset += blocks * config.segmentMinutes
        }
        return result
    }
}
