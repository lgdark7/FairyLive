package com.fairylive.app.core.optimization

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.fairylive.app.core.optimization.model.ThermalThrottlingLevel
import java.util.concurrent.CopyOnWriteArrayList

class ThermalMitigationManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "ThermalMitigationMgr"
    }

    interface ThermalListener {
        fun onThermalLevelChanged(level: ThermalThrottlingLevel, rawStatus: Int)
    }

    private val listeners = CopyOnWriteArrayList<ThermalListener>()
    private var powerManagerListener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null

    @Volatile
    var currentLevel: ThermalThrottlingLevel = ThermalThrottlingLevel.NOMINAL
        private set

    @Volatile
    var currentRawStatus: Int = 0
        private set

    fun addListener(listener: ThermalListener) {
        listeners.add(listener)
        listener.onThermalLevelChanged(currentLevel, currentRawStatus)
    }

    fun removeListener(listener: ThermalListener) {
        listeners.remove(listener)
    }

    fun start() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                powerManagerListener = PowerManager.OnThermalStatusChangedListener { status ->
                    handleThermalStatus(status)
                }
                try {
                    pm.addThermalStatusListener(powerManagerListener!!)
                    val initialStatus = pm.currentThermalStatus
                    handleThermalStatus(initialStatus)
                    Log.i(TAG, "Registered PowerManager thermal status listener. Initial status: $initialStatus")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register PowerManager thermal listener, falling back to battery: ${e.message}")
                }
            }
        }

        // Fallback: battery temperature monitoring
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val tempC = tempTenths / 10.0
                val mappedStatus = when {
                    tempC >= 48.0 -> 4 // CRITICAL
                    tempC >= 43.0 -> 3 // SEVERE
                    tempC >= 38.0 -> 2 // MODERATE
                    tempC >= 35.0 -> 1 // LIGHT
                    else -> 0          // NONE
                }
                handleThermalStatus(mappedStatus)
            }
        }
        try {
            context.registerReceiver(batteryReceiver, filter)
            Log.i(TAG, "Registered battery temperature thermal monitor fallback")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register battery receiver: ${e.message}")
        }
    }

    fun stop() {
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManagerListener != null) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                pm?.removeThermalStatusListener(powerManagerListener!!)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing thermal listener: ${e.message}")
            }
            powerManagerListener = null
        }
        if (batteryReceiver != null) {
            try {
                ctx.unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
            }
            batteryReceiver = null
        }
    }

    /**
     * For testing / deterministic validation: injects an explicit thermal status code.
     */
    fun setThermalStatusForTesting(status: Int) {
        handleThermalStatus(status)
    }

    private fun handleThermalStatus(status: Int) {
        currentRawStatus = status
        val newLevel = when (status) {
            0, 1 -> ThermalThrottlingLevel.NOMINAL   // NONE, LIGHT
            2 -> ThermalThrottlingLevel.MODERATE       // MODERATE
            3 -> ThermalThrottlingLevel.SEVERE         // SEVERE
            else -> ThermalThrottlingLevel.CRITICAL    // CRITICAL, EMERGENCY, SHUTDOWN
        }

        if (newLevel != currentLevel) {
            Log.w(TAG, "Thermal level shifted: $currentLevel -> $newLevel (raw: $status)")
            currentLevel = newLevel
            for (listener in listeners) {
                try {
                    listener.onThermalLevelChanged(newLevel, status)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying thermal listener: ${e.message}")
                }
            }
        }
    }
}
