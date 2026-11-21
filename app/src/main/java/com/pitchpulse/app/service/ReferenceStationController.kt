package com.pitchpulse.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pitchpulse.app.data.model.KnownPoint
import com.pitchpulse.app.data.model.RefPlacement
import com.pitchpulse.app.data.model.ReferenceFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state of the reference-station recorder, shared between the service and the UI. */
data class RecorderState(
    val recording: Boolean = false,
    val sessionId: String? = null,
    val pitchName: String = "",
    val placement: RefPlacement = RefPlacement.KNOWN_POINT,
    val knownPoint: KnownPoint? = null,
    val startedAt: Long = 0,
    val fixCount: Int = 0,
    val lastFix: ReferenceFix? = null,
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val meanCn0: Float = 0f,
    /** null = not determined yet. */
    val measurementsSupported: Boolean? = null,
    val navMessagesSupported: Boolean? = null,
    val rawEpochs: Int = 0,
    val navMessages: Int = 0,
    val protocol: Boolean = false,
    val routeStart: Long? = null,
    val routeEnd: Long? = null,
    val error: String? = null,
)

object ReferenceStationController {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** Atomic (CAS) update: called from the GNSS thread and the main thread. */
    internal fun update(f: (RecorderState) -> RecorderState) {
        _state.update(f)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun start(
        context: Context,
        pitchId: String,
        placement: RefPlacement,
        knownPoint: KnownPoint?,
        protocol: Boolean,
    ) {
        val intent = Intent(context, ReferenceStationService::class.java).apply {
            action = ReferenceStationService.ACTION_START
            putExtra(ReferenceStationService.EXTRA_PITCH_ID, pitchId)
            putExtra(ReferenceStationService.EXTRA_PLACEMENT, placement.name)
            putExtra(ReferenceStationService.EXTRA_KNOWN_POINT, knownPoint?.name)
            putExtra(ReferenceStationService.EXTRA_PROTOCOL, protocol)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) = send(context, ReferenceStationService.ACTION_STOP)

    /** Accuracy-test markers: "我出发了" / "我回来了". */
    fun markRouteStart(context: Context) = send(context, ReferenceStationService.ACTION_MARK_START)

    fun markRouteEnd(context: Context) = send(context, ReferenceStationService.ACTION_MARK_END)

    private fun send(context: Context, action: String) {
        if (!_state.value.recording) return
        context.startService(Intent(context, ReferenceStationService::class.java).setAction(action))
    }
}
