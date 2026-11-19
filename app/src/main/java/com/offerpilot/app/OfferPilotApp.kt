package com.offerpilot.app

import android.app.Application
import com.offerpilot.app.settings.SettingsRepository

class OfferPilotApp : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
    }
}
