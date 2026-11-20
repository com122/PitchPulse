package com.pitchpulse.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
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
