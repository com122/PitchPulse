package com.pitchpulse.app.ui.detail

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.export.ReportExporter
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.MatchStats
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.TrackPoint
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.data.repository.MatchRepository
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.settings.SettingsRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var points: List<TrackPoint>? = null
    private var analyses: Map<MatchSegment, MatchAnalysis> = emptyMap()
    private var analysisKey: Any? = null
    private var renderJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                matchRepository.observeMatch(matchId),
                pitchRepository.observePitches(),
                settingsRepository.settings,
            ) { m, pitches, s -> Triple(m, pitches.firstOrNull { it.id == m?.pitchId }, s) }
                .collect { (match, pitch, settings) ->
                    if (match == null) {
                        if (!_state.value.loading || _state.value.match != null) return@collect
                        _state.update { it.copy(loading = false, error = "比赛不存在或已被删除") }
                        return@collect
                    }
                    _state.update { it.copy(match = match, pitch = pitch, settings = settings) }
                    try {
                        ensureAnalyses(settings)
                        rerender()
                    } catch (e: Exception) {
                        _state.update { it.copy(loading = false, error = "轨迹读取失败：${e.message}") }
                    }
                }
        }
    }

    private suspend fun ensureAnalyses(settings: UserSettings) {
        val pts = points ?: matchRepository.loadTrack(matchId).also { points = it }
        if (pts.isEmpty()) throw IllegalStateException("轨迹文件为空")
        val key = MatchStatsCalculator.Config.from(settings)
        if (key == analysisKey) return
        analyses = withContext(Dispatchers.Default) {
            val calc = MatchStatsCalculator(key)
            MatchSegment.entries.associateWith { calc.analyze(pts, it.phase) }
        }
        analysisKey = key
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

    /** Re-projects the selected segment and regenerates the heatmap on Dispatchers.Default. */
    private fun rerender() {
        val s = _state.value
        val pitch = s.pitch ?: run {
            _state.update { it.copy(loading = false, error = "该比赛关联的球场不存在，请在「球场」页重新标定") }
            return
        }
        val analysis = analyses[s.segment] ?: return
        val full = analyses[MatchSegment.FULL] ?: return
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
                val bmp = ReportExporter.render(
                    ReportExporter.Input(
                        match = match,
                        pitch = pitch,
                        stats = s.stats.copy(segments = s.fullStats.segments),
                        segmentLabel = s.segment.label,
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
