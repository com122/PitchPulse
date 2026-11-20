package com.pitchpulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pitchpulse.app.ui.navigation.PitchPulseNavHost
import com.pitchpulse.app.ui.theme.PitchPulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PitchPulseApp).container
        setContent {
            PitchPulseTheme {
                PitchPulseNavHost(container)
            }
        }
    }
}
