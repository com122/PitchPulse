package com.pitchpulse.app.ui.pitches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.repository.PitchRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PitchItem(val pitch: PitchConfig, val matchCount: Int)

class PitchesViewModel(private val pitchRepository: PitchRepository) : ViewModel() {

    val pitches: StateFlow<List<PitchItem>?> = combine(
        pitchRepository.observePitches(),
        pitchRepository.observeMatchCounts(),
    ) { pitches, counts -> pitches.map { PitchItem(it, counts[it.id] ?: 0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun delete(item: PitchItem) {
        viewModelScope.launch {
            try {
                pitchRepository.delete(item.pitch.id)
                _messages.send(
                    if (item.matchCount > 0) "已删除「${item.pitch.name}」，${item.matchCount} 场比赛已重新匹配球场"
                    else "已删除「${item.pitch.name}」",
                )
            } catch (e: Exception) {
                _messages.send("删除失败：${e.message}")
            }
        }
    }
}
