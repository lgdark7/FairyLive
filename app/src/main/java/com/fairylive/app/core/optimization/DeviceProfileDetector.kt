package com.fairylive.app.core.optimization

import android.app.ActivityManager
import android.content.Context
import com.fairylive.app.core.optimization.model.DeviceProfile
import com.fairylive.app.core.optimization.model.DeviceTier

object DeviceProfileDetector {

    fun detect(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val isLowRam = am?.isLowRamDevice ?: false
        val cores = Runtime.getRuntime().availableProcessors()

        return classify(totalRamMb, isLowRam, cores)
    }

    fun classify(totalRamMb: Long, isLowRam: Boolean, cores: Int): DeviceProfile {
        val tier = when {
            isLowRam || totalRamMb < 3072 || cores <= 4 -> DeviceTier.LOW_END
            totalRamMb < 6144 || cores <= 6 -> DeviceTier.MID_RANGE
            else -> DeviceTier.FLAGSHIP
        }

        return when (tier) {
            DeviceTier.LOW_END -> DeviceProfile(
                tier = tier,
                totalRamMb = totalRamMb,
                availableCores = cores,
                isLowRamDevice = isLowRam,
                recommendedFps = 24,
                recommendedBitrateKbps = 900,
                enableHighQualityOverlays = false
            )
            DeviceTier.MID_RANGE -> DeviceProfile(
                tier = tier,
                totalRamMb = totalRamMb,
                availableCores = cores,
                isLowRamDevice = isLowRam,
                recommendedFps = 30,
                recommendedBitrateKbps = 1200,
                enableHighQualityOverlays = true
            )
            DeviceTier.FLAGSHIP -> DeviceProfile(
                tier = tier,
                totalRamMb = totalRamMb,
                availableCores = cores,
                isLowRamDevice = isLowRam,
                recommendedFps = 30,
                recommendedBitrateKbps = 1500,
                enableHighQualityOverlays = true
            )
        }
    }
}
