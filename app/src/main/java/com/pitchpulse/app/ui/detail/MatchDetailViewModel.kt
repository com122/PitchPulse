package com.pitchpulse.app.ui.detail

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.export.ReportExporter
import com.pitchpulse.app.data.model.EnhancementSettings
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.data.repository.MatchRepository
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.repository.ReferenceRepository
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.domain.algorithm.AccuracyEnhancer
import com.pitchpulse.app.domain.algorithm.AccuracyReport
import com.pitchpulse.app.domain.algorithm.HeatmapGenerator
import com.pitchpulse.app.domain.algorithm.MatchAnalysis
import com.pitchpulse.app.domain.algorithm.MatchStatsCalculator
import com.pitchpulse.app.domain.algorithm.PitchProjector
import com.pitchpulse.app.domain.algorithm.ProjectedPoint
import com.pitchpulse.app.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MatchSegment(val label: String, val phase: Int?) {
    FULL("全场", null), FIRST("上半场", TrackPoint.PHASE_FIRST_HALF), SECOND("下半场", TrackPoint.PHASE_SECOND_HALF)
}

enum class PitchLayer(val label: String) { HEATMAP("热区图"), TRACK("跑动轨迹线"), SPRINTS("高速冲刺点") }

enum class TrackMode(val label: String) { RAW("原始"), ENHANCED("增强") }

data class MatchDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val match: MatchRecord? = null,
    val pitch: PitchConfig? = null,
    val settings: UserSettings = UserSettings(),
    val segment: MatchSegment = MatchSegment.FULL,
    val swapSecondHalf: Boolean = true,
    val layer: PitchLayer = PitchLayer.HEATMAP,
    val rendering: Boolean = false,
    val heatmap: Bitmap? = null,
    val projected: List<ProjectedPoint> = emptyList(),
    val sprintPaths: List<List<ProjectedPoint>> = emptyList(),
    /** Stats of the selected segment. */
    val stats: MatchStats = MatchStats(),
    /** Full-match stats (for the 5-minute chart). */
    val fullStats: MatchStats = MatchStats(),
    val exporting: Boolean = false,
    val mode: TrackMode = TrackMode.ENHANCED,
    /** Accuracy-enhancement result; null while computing or when it failed. */
    val report: AccuracyReport? = null,
    val enhancementError: String? = null,
    /** Raw full-match stats, to show the difference in the report card. */
    val rawFullStats: MatchStats = MatchStats(),
    val enhancedFullStats: MatchStats? = null,
    /** Reference-station coverage of this match (0..1), null when none overlaps. */
    val referenceOverlap: Double? = null,
)

sealed interface DetailEvent {
    data class Toast(val text: String) : DetailEvent
    data object Closed : DetailEvent
}

class MatchDetailViewModel(
    private val matchId: String,
    private val appContext: Context,
    private val matchRepository: MatchRepository,
    private val pitchRepository: PitchRepository,
    private val settingsRepository: SettingsRepository,
    private val referenceRepository: ReferenceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var points: List<TrackPoint>? = null
    private var rawAnalyses: Map<MatchSegment, MatchAnalysis> = emptyMap()
    private var enhancedAnalyses: Map<MatchSegment, MatchAnalysis>? = null
    private var rawKey: Any? = null
    private var enhancedKey: Any? = null
    private var renderJob: Job? = null
    private var modeChosen = false

    private data class Inputs(
        val match: MatchRecord?,
        val pitch: PitchConfig?,
        val settings: UserSettings,
        val enhancement: EnhancementSettings,
        val sessionsVersion: Int,
    )

    init {
        viewModelScope.launch { referenceRepository.refresh() }
        viewModelScope.launch {
            combine(
                matchRepository.observeMatch(matchId),
                pitchRepository.observePitches(),
                settingsRepository.settings,
                settingsRepository.enhancement,
                referenceRepository.sessions,
            ) { m, pitches, s, e, sessions ->
                Inputs(m, pitches.firstOrNull { it.id == m?.pitchId }, s, e, sessions.hashCode())
            }.collect { inputs ->
                val match = inputs.match
                if (match == null) {
                    if (!_state.value.loading || _state.value.match != null) return@collect
                    _state.update { it.copy(loading = false, error = "比赛不存在或已被删除") }
                    return@collect
                }
                if (!modeChosen) {
                    modeChosen = true
                    _state.update { it.copy(mode = if (inputs.enhancement.defaultEnhanced) TrackMode.ENHANCED else TrackMode.RAW) }
                }
                _state.update { it.copy(match = match, pitch = inputs.pitch, settings = inputs.settings) }
                try {
                    ensureRaw(inputs.settings)
                    rerender()
                    if (inputs.pitch != null) ensureEnhanced(match, inputs.pitch, inputs.settings, inputs.enhancement, inputs.sessionsVersion)
                    rerender()
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = "轨迹读取失败：${e.message}") }
                }
            }
        }
    }

    private suspend fun ensureRaw(settings: UserSettings) {
        val pts = points ?: matchRepository.loadTrack(matchId).also { points = it }
        if (pts.isEmpty()) throw IllegalStateException("轨迹文件为空")
        val key = MatchStatsCalculator.Config.from(settings)
        if (key == rawKey) return
        rawAnalyses = withContext(Dispatchers.Default) {
            val calc = MatchStatsCalculator(key)
            MatchSegment.entries.associateWith { calc.analyze(pts, it.phase) }
        }
        rawKey = key
        _state.update { it.copy(rawFullStats = rawAnalyses.getValue(MatchSegment.FULL).stats) }
    }

    /** Runs the accuracy layers on Dispatchers.Default; raw data is untouched. */
    private suspend fun ensureEnhanced(match: MatchRecord, pitch: PitchConfig, settings: UserSettings, enh: EnhancementSettings, sessionsVersion: Int) {
        val pts = points ?: return
        val key = listOf(MatchStatsCalculator.Config.from(settings), enh, pitch, sessionsVersion)
        if (key == enhancedKey) return
        enhancedKey = key
        try {
            val candidate = referenceRepository.bestFor(match.startTime, match.endTime, pitch.id)
            val reference = candidate?.let { referenceRepository.load(it.meta.id, includeRaw = enh.tierB) }
            val calc = MatchStatsCalculator(MatchStatsCalculator.Config.from(settings))
            val out = withContext(Dispatchers.Default) { AccuracyEnhancer(enh, calc).run(pts, pitch, reference) }
            enhancedAnalyses = withContext(Dispatchers.Default) {
                MatchSegment.entries.associateWith { calc.analyzeFromClean(pts, out.clean, it.phase) }
            }
            _state.update {
                it.copy(
                    report = out.report,
                    enhancementError = null,
                    enhancedFullStats = enhancedAnalyses!!.getValue(MatchSegment.FULL).stats,
                    referenceOverlap = candidate?.overlap,
                )
            }
        } catch (e: Exception) {
            enhancedAnalyses = null
            _state.update { it.copy(report = null, enhancementError = "精度增强失败：${e.message ?: e.javaClass.simpleName}", enhancedFullStats = null) }
        }
    }

    fun selectSegment(segment: MatchSegment) {
        _state.update { it.copy(segment = segment) }
        rerender()
    }

    fun setSwap(enabled: Boolean) {
        _state.update { it.copy(swapSecondHalf = enabled) }
        rerender()
    }

    fun selectLayer(layer: PitchLayer) {
        _state.update { it.copy(layer = layer) }
    }

    fun selectMode(mode: TrackMode) {
        _state.update { it.copy(mode = mode) }
        rerender()
    }

    /** Re-projects the selected segment and regenerates the heatmap on Dispatchers.Default. */
    private fun rerender() {
        val s = _state.value
        val pitch = s.pitch ?: run {
            _state.update { it.copy(loading = false, error = "该比赛关联的球场不存在，请在「球场」页重新标定") }
            return
        }
        // Enhanced view falls back to raw until the enhancement is ready (or if it failed).
        val source = if (s.mode == TrackMode.ENHANCED) enhancedAnalyses ?: rawAnalyses else rawAnalyses
        val analysis = source[s.segment] ?: return
        val full = source[MatchSegment.FULL] ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _state.update { it.copy(rendering = true) }
            val (projected, sprintPaths) = withContext(Dispatchers.Default) {
                val proj = PitchProjector(pitch).project(analysis.clean, s.swapSecondHalf)
                val byIndex = proj.associateBy { it.cleanIndex }
                val sprints = analysis.sprints.map { run -> (run.startIndex..run.endIndex).mapNotNull { byIndex[it] } }
                    .filter { it.isNotEmpty() }
                proj to sprints
            }
            val bmp = HeatmapGenerator.generate(projected, s.settings.heatmapSigma)
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    rendering = false,
                    heatmap = bmp,
                    projected = projected,
                    sprintPaths = sprintPaths,
                    stats = analysis.stats,
                    fullStats = full.stats,
                )
            }
        }
    }

    fun exportReport() {
        val s = _state.value
        val match = s.match ?: return
        val pitch = s.pitch ?: return
        if (s.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            try {
                val modeLabel = if (s.mode == TrackMode.ENHANCED && enhancedAnalyses != null) "增强轨迹" else "原始轨迹"
                val bmp = ReportExporter.render(
                    ReportExporter.Input(
                        match = match,
                        pitch = pitch,
                        stats = s.stats.copy(segments = s.fullStats.segments),
                        segmentLabel = "${s.segment.label} · $modeLabel",
                        swapEnabled = s.swapSecondHalf,
                        heatmap = s.heatmap,
                        sprintThresholdKmh = s.settings.sprintSpeedMs * 3.6f,
                    ),
                )
                ReportExporter.saveToGallery(appContext, bmp, "PitchPulse_${Formatters.fileStamp(match.startTime)}")
                _events.send(DetailEvent.Toast("战报已保存到相册 Pictures/PitchPulse"))
            } catch (e: Exception) {
                _events.send(DetailEvent.Toast("导出失败：${e.message ?: e.javaClass.simpleName}"))
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun deleteMatch() {
        viewModelScope.launch {
            matchRepository.delete(matchId)
            _events.send(DetailEvent.Closed)
        }
    }
}
