package com.fairylive.app

import android.app.Application
import com.fairylive.app.core.capability.DeviceCapabilityDetector
import com.fairylive.app.core.capability.model.DeviceCapability
import com.fairylive.app.core.settings.StreamSettingsRepository

/**
 * Base Application class for Fairy Live.
 * Pre-warms hardware capability detection and manages app-level singletons.
 */
class FairyLiveApplication : Application() {

    lateinit var deviceCapability: DeviceCapability
        private set

    lateinit var settingsRepository: StreamSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val detector = DeviceCapabilityDetector(this)
        deviceCapability = detector.detectCapabilities()

        settingsRepository = StreamSettingsRepository(this)
    }

    companion object {
        lateinit var instance: FairyLiveApplication
            private set
    }
}
