package com.pitchpulse.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.pitchpulse.app.data.model.EnhancementSettings
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pitchpulse.app.data.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pitchpulse_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val MAX_HR = intPreferencesKey("max_hr")
        val RESTING_HR = intPreferencesKey("resting_hr")
        val SPRINT_SPEED = floatPreferencesKey("sprint_speed")
        val SPRINT_MIN_DURATION = floatPreferencesKey("sprint_min_duration")
        val HEATMAP_SIGMA = floatPreferencesKey("heatmap_sigma")
        val MAP_LAYER = stringPreferencesKey("map_layer")
        val E_TIER_A = booleanPreferencesKey("enh_tier_a")
        val E_TIER_B = booleanPreferencesKey("enh_tier_b")
        val E_TIER_C = booleanPreferencesKey("enh_tier_c")
        val E_TIER_D = booleanPreferencesKey("enh_tier_d")
        val E_LOWPASS = floatPreferencesKey("enh_lowpass_period")
        val E_ACCEL = floatPreferencesKey("enh_accel_sigma")
        val E_ACC_SCALE = floatPreferencesKey("enh_acc_scale")
        val E_ADAPTIVE = booleanPreferencesKey("enh_adaptive_noise")
        val E_DEVICE_SPEED = booleanPreferencesKey("enh_device_speed")
        val E_DEFAULT_ENHANCED = booleanPreferencesKey("enh_default_enhanced")
    }

    val enhancement: Flow<EnhancementSettings> = context.dataStore.data.map { p ->
        val d = EnhancementSettings()
        EnhancementSettings(
            tierA = p[Keys.E_TIER_A] ?: d.tierA,
            tierB = p[Keys.E_TIER_B] ?: d.tierB,
            tierC = p[Keys.E_TIER_C] ?: d.tierC,
            tierD = p[Keys.E_TIER_D] ?: d.tierD,
            lowPassPeriodSec = p[Keys.E_LOWPASS] ?: d.lowPassPeriodSec,
            accelSigma = p[Keys.E_ACCEL] ?: d.accelSigma,
            accScale = p[Keys.E_ACC_SCALE] ?: d.accScale,
            adaptiveNoise = p[Keys.E_ADAPTIVE] ?: d.adaptiveNoise,
            useDeviceSpeed = p[Keys.E_DEVICE_SPEED] ?: d.useDeviceSpeed,
            defaultEnhanced = p[Keys.E_DEFAULT_ENHANCED] ?: d.defaultEnhanced,
        )
    }

    suspend fun updateEnhancement(s: EnhancementSettings) {
        context.dataStore.edit { p ->
            p[Keys.E_TIER_A] = s.tierA
            p[Keys.E_TIER_B] = s.tierB
            p[Keys.E_TIER_C] = s.tierC
            p[Keys.E_TIER_D] = s.tierD
            p[Keys.E_LOWPASS] = s.lowPassPeriodSec
            p[Keys.E_ACCEL] = s.accelSigma
            p[Keys.E_ACC_SCALE] = s.accScale
            p[Keys.E_ADAPTIVE] = s.adaptiveNoise
            p[Keys.E_DEVICE_SPEED] = s.useDeviceSpeed
            p[Keys.E_DEFAULT_ENHANCED] = s.defaultEnhanced
        }
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            maxHr = p[Keys.MAX_HR] ?: UserSettings.DEFAULT_MAX_HR,
            restingHr = p[Keys.RESTING_HR] ?: UserSettings.DEFAULT_RESTING_HR,
            sprintSpeedMs = p[Keys.SPRINT_SPEED] ?: UserSettings.DEFAULT_SPRINT_SPEED,
            sprintMinDurationSec = p[Keys.SPRINT_MIN_DURATION] ?: UserSettings.DEFAULT_SPRINT_MIN_DURATION,
            heatmapSigma = p[Keys.HEATMAP_SIGMA] ?: UserSettings.DEFAULT_HEATMAP_SIGMA,
        )
    }

    val mapLayer: Flow<String> = context.dataStore.data.map { it[Keys.MAP_LAYER] ?: "esri" }

    suspend fun current(): UserSettings = settings.first()

    suspend fun update(settings: UserSettings) {
        context.dataStore.edit { p ->
            p[Keys.MAX_HR] = settings.maxHr
            p[Keys.RESTING_HR] = settings.restingHr
            p[Keys.SPRINT_SPEED] = settings.sprintSpeedMs
            p[Keys.SPRINT_MIN_DURATION] = settings.sprintMinDurationSec
            p[Keys.HEATMAP_SIGMA] = settings.heatmapSigma
        }
    }

    suspend fun setMapLayer(layer: String) {
        context.dataStore.edit { it[Keys.MAP_LAYER] = layer }
    }
}
