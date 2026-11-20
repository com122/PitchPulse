package com.pitchpulse.app.ui.matches

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.model.MatchRecord
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.PlayerPosition
import com.pitchpulse.app.data.repository.ImportRepository
import com.pitchpulse.app.data.repository.ImportResult
import com.pitchpulse.app.data.repository.MatchRepository
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.repository.ThumbnailRepository
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.util.Formatters
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SeasonOverview(
    val matchCount: Int = 0,
    val totalKm: Float = 0f,
    val avgHr: Int = 0,
    val topSpeedKmh: Float = 0f,
)

data class MatchesUiState(
    val loading: Boolean = true,
    val matches: List<MatchRecord> = emptyList(),
    val pitches: List<PitchConfig> = emptyList(),
    val overview: SeasonOverview = SeasonOverview(),
    val busyMessage: String? = null,
)

sealed interface MatchesEvent {
    data class Message(val text: String) : MatchesEvent
    data class OpenMatch(val matchId: String) : MatchesEvent
}

class MatchesViewModel(
    private val matchRepository: MatchRepository,
    private val pitchRepository: PitchRepository,
    private val importRepository: ImportRepository,
    private val thumbnails: ThumbnailRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val busy = MutableStateFlow<String?>(null)
    private val _events = Channel<MatchesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val thumbnailVersion: StateFlow<Int> = thumbnails.version

    val state: StateFlow<MatchesUiState> = combine(
        matchRepository.observeMatches(),
        pitchRepository.observePitches(),
        busy,
    ) { matches, pitches, busyMsg ->
        MatchesUiState(
            loading = false,
            matches = matches,
            pitches = pitches,
            overview = overviewOf(matches),
            busyMessage = busyMsg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchesUiState())

    private fun overviewOf(matches: List<MatchRecord>): SeasonOverview {
        val withHr = matches.filter { it.stats.avgHr > 0 }
        return SeasonOverview(
            matchCount = matches.size,
            totalKm = matches.sumOf { it.stats.totalDistanceMeters.toDouble() }.toFloat() / 1000f,
            avgHr = if (withHr.isEmpty()) 0 else withHr.sumOf { it.stats.avgHr } / withHr.size,
            topSpeedKmh = matches.maxOfOrNull { it.stats.maxSpeedKmh } ?: 0f,
        )
    }

    suspend fun thumbnail(match: MatchRecord): Bitmap? {
        val pitch = match.pitchId?.let { pitchRepository.getPitch(it) }
        return runCatching { thumbnails.get(match.id, pitch, settingsRepository.current()) }.getOrNull()
    }

    fun simulate(pitchId: String, position: PlayerPosition) = runImport("正在生成 90 分钟仿真比赛…") {
        importRepository.simulate(pitchId, position)
    }

    fun import(uri: Uri) = runImport("正在解析并分析轨迹文件…") {
        importRepository.importUri(uri)
    }

    private fun runImport(message: String, block: suspend () -> ImportResult) {
        if (busy.value != null) return
        viewModelScope.launch {
            busy.value = message
            try {
                val r = block()
                val extra = buildString {
                    if (r.pitchAutoCreated) append("，已根据轨迹自动创建球场「${r.pitch.name}」")
                    else append("，匹配球场「${r.pitch.name}」")
                    if (r.skippedLines > 0) append("，跳过 ${r.skippedLines} 行无效数据")
                }
                _events.send(MatchesEvent.Message("入库成功：${Formatters.km(r.stats.totalDistanceMeters)} km$extra"))
                _events.send(MatchesEvent.OpenMatch(r.matchId))
            } catch (e: Exception) {
                _events.send(MatchesEvent.Message("导入失败：${e.message ?: e.javaClass.simpleName}"))
            } finally {
                busy.value = null
            }
        }
    }
}
