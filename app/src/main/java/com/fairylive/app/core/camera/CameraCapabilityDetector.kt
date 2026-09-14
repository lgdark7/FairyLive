package com.fairylive.app.core.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.fairylive.app.core.camera.model.CameraDeviceInfo
import com.fairylive.app.core.camera.model.CameraFacing
import com.fairylive.app.core.camera.model.CameraSize

/**
 * Probes and enumerates camera hardware capabilities on the device via Camera2 API.
 * Identifies front/back sensors, orientations, and supported SurfaceTexture preview sizes.
 */
class CameraCapabilityDetector(private val context: Context) {

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /**
     * Enumerates all physical cameras available to the application.
     */
    fun discoverCameras(): List<CameraDeviceInfo> {
        val devices = mutableListOf<CameraDeviceInfo>()
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facingInt = chars.get(CameraCharacteristics.LENS_FACING)
                val facing = when (facingInt) {
                    CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
                    CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
                    else -> CameraFacing.UNKNOWN
                }
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map {
                    CameraSize(it.width, it.height)
                } ?: emptyList()
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
                val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED

                devices.add(
                    CameraDeviceInfo(
                        cameraId = id,
                        facing = facing,
                        sensorOrientation = sensorOrientation,
                        supportedSizes = supportedSizes,
                        supportedFpsRanges = fpsRanges,
                        hardwareLevel = hardwareLevel
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering cameras: ${e.message}", e)
        }
        return devices
    }

    fun getFrontCamera(): CameraDeviceInfo? = discoverCameras().firstOrNull { it.facing == CameraFacing.FRONT }

    fun getBackCamera(): CameraDeviceInfo? = discoverCameras().firstOrNull { it.facing == CameraFacing.BACK }

    fun getCameraById(id: String): CameraDeviceInfo? = discoverCameras().firstOrNull { it.cameraId == id }

    fun getOptimalSize(device: CameraDeviceInfo, targetWidth: Int = 640, targetHeight: Int = 480): CameraSize =
        Companion.getOptimalSize(device, targetWidth, targetHeight)

    companion object {
        private const val TAG = "CameraCapabilityDetector"

        /**
         * Resolves the optimal preview resolution for the Facecam PIP layer.
         *
         * Low-end optimization: Avoids multi-megapixel resolutions that oversaturate
         * memory and GPU fill-rate. Chooses a size nearest to target bounds (typically 640x480 or 1280x720).
         */
        fun getOptimalSize(device: CameraDeviceInfo, targetWidth: Int = 640, targetHeight: Int = 480): CameraSize {
            if (device.supportedSizes.isEmpty()) {
                return CameraSize(targetWidth, targetHeight)
            }

            // Exact match preference
            device.supportedSizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }?.let {
                return it
            }

            // Standard low/balanced streaming preview candidates
            val preferredSizes = listOf(
                CameraSize(640, 480),
                CameraSize(640, 360),
                CameraSize(1280, 720),
                CameraSize(800, 600),
                CameraSize(320, 240)
            )

            for (candidate in preferredSizes) {
                if (device.supportedSizes.contains(candidate)) {
                    return candidate
                }
            }

            // Fallback: Pick supported size with minimum area >= target or closest total area
            val targetArea = targetWidth * targetHeight
            val sorted = device.supportedSizes.sortedBy { kotlin.math.abs(it.area - targetArea) }
            return sorted.first()
        }
    }
}
