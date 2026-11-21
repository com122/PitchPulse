package com.pitchpulse.app.ui.reference

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceSessionMeta
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.repository.ReferenceRepository
import com.pitchpulse.app.service.RecorderState
import com.pitchpulse.app.service.ReferenceStationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReferenceSetup(
    val pitchId: String? = null,
    val placement: RefPlacement = RefPlacement.KNOWN_POINT,
    val knownPoint: KnownPoint = KnownPoint.HALFWAY_TOUCHLINE_A,
    val protocol: Boolean = false,
)

class ReferenceStationViewModel(
    private val pitchRepository: PitchRepository,
    private val referenceRepository: ReferenceRepository,
) : ViewModel() {

    val pitches: StateFlow<List<PitchConfig>> = pitchRepository.observePitches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions: StateFlow<List<ReferenceSessionMeta>> = referenceRepository.sessions
    val recorder: StateFlow<RecorderState> = ReferenceStationController.state

    private val _setup = MutableStateFlow(ReferenceSetup())
    val setup: StateFlow<ReferenceSetup> = _setup.asStateFlow()

    init {
        viewModelScope.launch { referenceRepository.refresh() }
        viewModelScope.launch {
            pitches.collect { list ->
                if (_setup.value.pitchId == null || list.none { it.id == _setup.value.pitchId }) {
                    _setup.update { it.copy(pitchId = list.firstOrNull()?.id) }
                }
            }
        }
        // Refresh the session list whenever a recording stops.
        viewModelScope.launch {
            var wasRecording = recorder.value.recording
            recorder.collect { s ->
                if (wasRecording && !s.recording) referenceRepository.refresh()
                wasRecording = s.recording
            }
        }
    }

    fun setPitch(id: String) = _setup.update { it.copy(pitchId = id) }
    fun setPlacement(p: RefPlacement) = _setup.update { it.copy(placement = p, protocol = if (p == RefPlacement.UNKNOWN_POINT) false else it.protocol) }
    fun setKnownPoint(k: KnownPoint) = _setup.update { it.copy(knownPoint = k) }
    fun setProtocol(on: Boolean) = _setup.update { it.copy(protocol = on, placement = if (on) RefPlacement.KNOWN_POINT else it.placement) }

    fun start(context: Context) {
        val s = _setup.value
        val pitch = s.pitchId ?: return
        ReferenceStationController.clearError()
        ReferenceStationController.start(context, pitch, s.placement, if (s.placement == RefPlacement.KNOWN_POINT) s.knownPoint else null, s.protocol)
    }

    fun stop(context: Context) = ReferenceStationController.stop(context)
    fun markStart(context: Context) = ReferenceStationController.markRouteStart(context)
    fun markEnd(context: Context) = ReferenceStationController.markRouteEnd(context)

    fun delete(id: String) {
        viewModelScope.launch { referenceRepository.delete(id) }
    }
}
