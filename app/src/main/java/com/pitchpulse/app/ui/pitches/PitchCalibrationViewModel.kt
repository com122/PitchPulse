package com.pitchpulse.app.ui.pitches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchpulse.app.data.local.AppDatabase
import com.pitchpulse.app.data.model.PitchConfig
import com.pitchpulse.app.data.repository.PitchRepository
import com.pitchpulse.app.data.settings.SettingsRepository
import com.pitchpulse.app.domain.algorithm.CoordTransform
import com.pitchpulse.app.domain.algorithm.GeoMath
import com.pitchpulse.app.domain.algorithm.PitchCornerSolver
import com.pitchpulse.app.domain.algorithm.PitchProjector
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class CalibrationMode(val label: String) { MAP("地图四点标定"), MANUAL("手动参数模式") }

enum class MapLayer(val id: String, val label: String, val gcj02: Boolean) {
    ESRI("esri", "Esri 卫星", false),
    OSM("osm", "OSM 街道", false),
    AMAP("amap", "高德卫星", true);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: ESRI
    }
}

data class CalibrationUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val id: String = "",
    val name: String = "",
    val pitchType: Int = 7,
    val centerLat: Double = 22.5431,
    val centerLng: Double = 113.9420,
    val lengthMeters: Float = 60f,
    val widthMeters: Float = 40f,
    val bearingDegrees: Float = 0f,
    /** True once the draft rectangle is meaningful (existing pitch or 4 corners solved). */
    val hasGeometry: Boolean = false,
    val mode: CalibrationMode = CalibrationMode.MAP,
    val layer: MapLayer = MapLayer.ESRI,
    /** Tapped corner flags in WGS-84 (lat, lng). */
    val corners: List<DoubleArray> = emptyList(),
    val networkAvailable: Boolean = true,
    val tilesFailing: Boolean = false,
    /** Incremented to ask the map to fit the rectangle. */
    val fitRequest: Int = 0,
    val latText: String = "",
    val lngText: String = "",
    val saving: Boolean = false,
)

class PitchCalibrationViewModel(
    private val pitchId: String?,
    private val pitchRepository: PitchRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    /** "saved" closes the screen; anything else is a message. */
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val layer = MapLayer.of(settingsRepository.mapLayer.first())
            val existing = pitchId?.let { pitchRepository.getPitch(it) }
            val s = if (existing != null) {
                CalibrationUiState(
                    loaded = true, isNew = false, id = existing.id, name = existing.name,
                    pitchType = existing.pitchType, centerLat = existing.centerLat, centerLng = existing.centerLng,
                    lengthMeters = existing.lengthMeters, widthMeters = existing.widthMeters,
                    bearingDegrees = existing.bearingDegrees, hasGeometry = true, layer = layer,
                    fitRequest = 1,
                )
            } else {
                // Start the map where the user's most recent pitch is.
                val ref = pitchRepository.getAll().firstOrNull()
                val (l, w) = PitchConfig.defaultSizeFor(7)
                CalibrationUiState(
                    loaded = true, isNew = true, id = UUID.randomUUID().toString(), name = "",
                    pitchType = 7, centerLat = ref?.centerLat ?: 22.5431, centerLng = ref?.centerLng ?: 113.9420,
                    lengthMeters = l, widthMeters = w, bearingDegrees = 0f, hasGeometry = false, layer = layer,
                )
            }
            _state.value = s.withCoordText()
        }
    }

    private fun CalibrationUiState.withCoordText() = copy(
        latText = String.format(Locale.US, "%.6f", centerLat),
        lngText = String.format(Locale.US, "%.6f", centerLng),
    )

    // ---- Mode / map plumbing ----

    fun setMode(mode: CalibrationMode) = _state.update { it.copy(mode = mode) }

    fun setNetworkAvailable(available: Boolean) = _state.update {
        // Without network the map can't show imagery: fall back to manual mode.
        if (!available && it.mode == CalibrationMode.MAP) it.copy(networkAvailable = false, mode = CalibrationMode.MANUAL)
        else it.copy(networkAvailable = available)
    }

    fun setLayer(layer: MapLayer) {
        _state.update { it.copy(layer = layer, tilesFailing = false) }
        viewModelScope.launch { settingsRepository.setMapLayer(layer.id) }
    }

    fun onTileStatus(ok: Boolean) = _state.update { it.copy(tilesFailing = !ok) }

    /**
     * A tap on the map. Coordinates are in the datum of [layerId]'s imagery: AMap tiles are GCJ-02,
     * so the point is converted back to WGS-84 before being stored.
     */
    fun onMapClick(lat: Double, lng: Double, layerId: String) {
        val wgs = if (MapLayer.of(layerId).gcj02) CoordTransform.gcj02ToWgs84(lat, lng)
        else CoordTransform.LatLng(lat, lng)
        _state.update { s ->
            if (s.mode != CalibrationMode.MAP) return@update s
            val corners = (if (s.corners.size >= 4) emptyList() else s.corners) + listOf(doubleArrayOf(wgs.lat, wgs.lng))
            if (corners.size == 4) solved(s.copy(corners = corners)) else s.copy(corners = corners)
        }
    }

    private fun solved(s: CalibrationUiState): CalibrationUiState {
        val sol = PitchCornerSolver.solve(s.corners)
        return s.copy(
            centerLat = sol.centerLat,
            centerLng = sol.centerLng,
            lengthMeters = sol.lengthMeters,
            widthMeters = sol.widthMeters,
            bearingDegrees = sol.bearingDegrees,
            pitchType = PitchConfig.closestType(sol.lengthMeters, sol.widthMeters),
            hasGeometry = true,
            fitRequest = s.fitRequest + 1,
        ).withCoordText()
    }

    fun undoCorner() = _state.update { it.copy(corners = it.corners.dropLast(1)) }

    fun clearCorners() = _state.update { it.copy(corners = emptyList()) }

    /** JSON for `PP.setOverlay`, converted into the display datum of the active layer. */
    fun overlayJson(s: CalibrationUiState): String {
        fun disp(lat: Double, lng: Double): JSONArray {
            val p = if (s.layer.gcj02) CoordTransform.wgs84ToGcj02(lat, lng) else CoordTransform.LatLng(lat, lng)
            return JSONArray().put(p.lat).put(p.lng)
        }
        val o = JSONObject()
        o.put("corners", JSONArray().apply { s.corners.forEach { put(disp(it[0], it[1])) } })
        if (s.hasGeometry) {
            val rect = PitchProjector(draft(s)).cornersLatLng()
            o.put("rect", JSONArray().apply { rect.forEach { put(disp(it[0], it[1])) } })
            o.put("center", disp(s.centerLat, s.centerLng))
        }
        return o.toString()
    }

    /** Map centre in the display datum, as (lat, lng). */
    fun displayCenter(s: CalibrationUiState): DoubleArray {
        val p = if (s.layer.gcj02) CoordTransform.wgs84ToGcj02(s.centerLat, s.centerLng) else CoordTransform.LatLng(s.centerLat, s.centerLng)
        return doubleArrayOf(p.lat, p.lng)
    }

    // ---- Parameter editing (both modes) ----

    fun setName(name: String) = _state.update { it.copy(name = name.take(40)) }

    fun setPitchType(type: Int, applyDefaultSize: Boolean) = _state.update {
        if (applyDefaultSize) {
            val (l, w) = PitchConfig.defaultSizeFor(type)
            it.copy(pitchType = type, lengthMeters = l, widthMeters = w)
        } else it.copy(pitchType = type)
    }

    fun setLength(v: Float) = _state.update { it.copy(lengthMeters = v, hasGeometry = it.hasGeometry || it.mode == CalibrationMode.MANUAL) }
    fun setWidth(v: Float) = _state.update { it.copy(widthMeters = v, hasGeometry = it.hasGeometry || it.mode == CalibrationMode.MANUAL) }
    fun setBearing(v: Float) = _state.update {
        it.copy(bearingDegrees = GeoMath.normalizeDegrees(v.toDouble()).toFloat(), hasGeometry = it.hasGeometry || it.mode == CalibrationMode.MANUAL)
    }

    fun setLatText(t: String) = _state.update { s ->
        val v = t.toDoubleOrNull()
        if (v != null && v in -90.0..90.0) s.copy(latText = t, centerLat = v, hasGeometry = true) else s.copy(latText = t)
    }

    fun setLngText(t: String) = _state.update { s ->
        val v = t.toDoubleOrNull()
        if (v != null && v in -180.0..180.0) s.copy(lngText = t, centerLng = v, hasGeometry = true) else s.copy(lngText = t)
    }

    private fun draft(s: CalibrationUiState) = PitchConfig(
        id = s.id,
        name = s.name.ifBlank { defaultName(s) },
        pitchType = s.pitchType,
        centerLat = s.centerLat,
        centerLng = s.centerLng,
        lengthMeters = s.lengthMeters,
        widthMeters = s.widthMeters,
        bearingDegrees = s.bearingDegrees,
    )

    private fun defaultName(s: CalibrationUiState) =
        if (s.id == AppDatabase.DEMO_PITCH_ID) "标准 7 人制演示球场" else "${s.pitchType} 人制球场"

    fun save() {
        val s = _state.value
        val error = when {
            s.latText.toDoubleOrNull()?.let { it in -90.0..90.0 } != true -> "纬度格式不正确"
            s.lngText.toDoubleOrNull()?.let { it in -180.0..180.0 } != true -> "经度格式不正确"
            s.mode == CalibrationMode.MAP && !s.hasGeometry -> "请先在地图上依次点击 4 个角旗位置"
            s.lengthMeters < 15f || s.widthMeters < 10f -> "球场尺寸过小"
            else -> null
        }
        if (error != null) {
            viewModelScope.launch { _events.send(error) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                pitchRepository.save(draft(s))
                _events.send(SAVED)
            } catch (e: Exception) {
                _events.send("保存失败：${e.message}")
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        const val SAVED = "saved"
    }
}
