package com.espmoto.nav.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists hidden Developer Mode unlock state and offline GPS simulation toggle.
 */
object DeveloperPrefs {
    private const val PREFS_NAME = "espmoto_dev_prefs"
    private const val KEY_DEV_MODE_UNLOCKED = "dev_mode_unlocked"
    private const val KEY_GPS_SIM_ENABLED = "gps_sim_enabled"

    private val lock = Any()

    private val _devModeUnlocked = MutableStateFlow(false)
    val devModeUnlocked: StateFlow<Boolean> = _devModeUnlocked

    private val _gpsSimulationEnabled = MutableStateFlow(false)
    val gpsSimulationEnabled: StateFlow<Boolean> = _gpsSimulationEnabled

    @Volatile
    private var loaded = false

    fun load(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (loaded) return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _devModeUnlocked.value = prefs.getBoolean(KEY_DEV_MODE_UNLOCKED, false)
            _gpsSimulationEnabled.value = prefs.getBoolean(KEY_GPS_SIM_ENABLED, false)
            loaded = true
        }
    }

    fun unlockDeveloperMode(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEV_MODE_UNLOCKED, true)
                .apply()
            _devModeUnlocked.value = true
            loaded = true
        }
    }

    fun setGpsSimulationEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GPS_SIM_ENABLED, enabled)
                .apply()
            _gpsSimulationEnabled.value = enabled
            loaded = true
        }
    }

    fun disableDeveloperMode(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEV_MODE_UNLOCKED, false)
                .putBoolean(KEY_GPS_SIM_ENABLED, false)
                .apply()
            _devModeUnlocked.value = false
            _gpsSimulationEnabled.value = false
            loaded = true
        }
    }
}
