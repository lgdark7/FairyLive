package com.fairylive.app

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fairylive.app.core.compositor.OverlayCompositor
import com.fairylive.app.core.compositor.resource.OverlayResourceManager
import com.fairylive.app.core.optimization.DeviceProfileDetector
import com.fairylive.app.core.optimization.MemoryPressureGuard
import com.fairylive.app.core.optimization.ThermalMitigationManager
import com.fairylive.app.core.optimization.model.DeviceTier
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowEndOptimizationInstrumentedTest {

    @Test
    fun testOnDeviceDeviceProfileDetection() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profile = DeviceProfileDetector.detect(context)
        assertNotNull("Device profile must not be null", profile)
        assertTrue("Available cores must be >= 1", profile.availableCores >= 1)
        assertTrue("Total RAM must be > 0", profile.totalRamMb > 0)
        assertTrue("Tier must be defined", profile.tier in listOf(DeviceTier.LOW_END, DeviceTier.MID_RANGE, DeviceTier.FLAGSHIP))
    }

    @Test
    fun testOnDeviceThermalMitigationThrottling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val compositor = OverlayCompositor(context)
        val thermalManager = ThermalMitigationManager(context)

        thermalManager.addListener(object : ThermalMitigationManager.ThermalListener {
            override fun onThermalLevelChanged(level: ThermalThrottlingLevel, rawStatus: Int) {
                compositor.fpsThrottler.updateThermalLevel(level)
            }
        })

        // Nominal
        thermalManager.setThermalStatusForTesting(0)
        assertEquals(30, compositor.fpsThrottler.currentTargetFps)

        // Moderate (24 FPS)
        thermalManager.setThermalStatusForTesting(2)
        assertEquals(24, compositor.fpsThrottler.currentTargetFps)

        // Severe (20 FPS)
        thermalManager.setThermalStatusForTesting(3)
        assertEquals(20, compositor.fpsThrottler.currentTargetFps)

        // Critical (15 FPS)
        thermalManager.setThermalStatusForTesting(4)
        assertEquals(15, compositor.fpsThrottler.currentTargetFps)

        // Recovery to Nominal (30 FPS)
        thermalManager.setThermalStatusForTesting(0)
        assertEquals(30, compositor.fpsThrottler.currentTargetFps)
    }

    @Test
    fun testOnDeviceMemoryTrimPrunesOverlayCaches() {
        val resMgr = OverlayResourceManager(maxCachedBitmaps = 10, maxBitmapBytes = 10_000_000L)
        for (i in 1..6) {
            val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            resMgr.getOrPutBitmap("cache_key_$i") { bmp }
        }
        assertEquals(6, resMgr.cachedCount())

        // Test moderate trim
        resMgr.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("Moderate trim must evict 50% of cached bitmaps", 3, resMgr.cachedCount())

        // Test critical trim
        resMgr.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals("Critical trim must purge all cached bitmaps", 0, resMgr.cachedCount())
        assertEquals("Total cached bytes must be 0", 0L, resMgr.totalBitmapBytes())
    }
}
