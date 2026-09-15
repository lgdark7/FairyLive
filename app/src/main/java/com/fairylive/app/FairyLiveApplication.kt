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

    lateinit var sceneRepository: com.fairylive.app.core.scene.SceneRepository
        private set

    lateinit var sceneManager: com.fairylive.app.core.scene.BroadcastSceneManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val detector = DeviceCapabilityDetector(this)
        deviceCapability = detector.detectCapabilities()

        settingsRepository = StreamSettingsRepository(this)

        sceneRepository = com.fairylive.app.core.scene.SceneRepository(this)
        val savedScenes = sceneRepository.loadScenes()
        val initialScenes = if (savedScenes.isNotEmpty()) savedScenes else com.fairylive.app.core.scene.BroadcastSceneManager.defaultScenes()
        sceneManager = com.fairylive.app.core.scene.BroadcastSceneManager(initialScenes)
        val activeId = sceneRepository.loadActiveSceneId()
        if (activeId != null && sceneManager.getScene(activeId) != null) {
            sceneManager.switchScene(activeId)
        }
    }

    companion object {
        lateinit var instance: FairyLiveApplication
            private set
    }
}
