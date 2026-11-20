package com.pitchpulse.app

import android.app.Application
import com.pitchpulse.app.di.AppContainer

class PitchPulseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
