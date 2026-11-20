package com.pitchpulse.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.model.UserSettings
import com.pitchpulse.app.data.repository.MatchRepository
import com.pitchpulse.app.data.repository.WatchConnectionState
import com.pitchpulse.app.data.repository.WatchSyncRepository
import com.pitchpulse.app.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val matchRepository: MatchRepository,
    watchSync: WatchSyncRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val watchState: StateFlow<WatchConnectionState> = watchSync.connectionState

    private val _recompute = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (done, total) while recomputing, null otherwise. */
    val recompute: StateFlow<Pair<Int, Int>?> = _recompute.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun update(transform: (UserSettings) -> UserSettings) {
        val current = settings.value ?: return
        val next = transform(current).let {
            it.copy(restingHr = it.restingHr.coerceAtMost(it.maxHr - 20))
        }
        viewModelScope.launch { settingsRepository.update(next) }
    }

    fun reset() {
        viewModelScope.launch { settingsRepository.update(UserSettings()) }
    }

    fun recomputeAll() {
        if (_recompute.value != null) return
        viewModelScope.launch {
            _recompute.value = 0 to 0
            try {
                val s = settingsRepository.current()
                val n = matchRepository.recomputeAll(s) { done, total -> _recompute.value = done to total }
                _messages.send("已按当前参数重算 $n 场比赛")
            } catch (e: Exception) {
                _messages.send("重算失败：${e.message}")
            } finally {
                _recompute.value = null
            }
        }
    }
}
