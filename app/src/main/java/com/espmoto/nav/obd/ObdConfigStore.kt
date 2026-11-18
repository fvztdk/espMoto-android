package com.espmoto.nav.obd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ObdConfigStore {
    private const val PREFS_NAME = "espmoto_obd_prefs"
    private const val KEY_PROFILE_JSON = "bike_profile_json"

    private val _profile = MutableStateFlow(ObdPresets.create(BikePresetId.EURO4_5_GENERIC))
    val profile: StateFlow<BikeProfile> = _profile

    private var loaded = false

    fun load(context: Context): BikeProfile {
        if (loaded) return _profile.value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILE_JSON, null)
        val restored = raw?.let { BikeProfile.fromJsonString(it) }
        if (restored != null) {
            _profile.value = restored
        }
        loaded = true
        return _profile.value
    }

    fun save(context: Context, next: BikeProfile) {
        _profile.value = next
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PROFILE_JSON, next.toStorageJson()).apply()
    }
}
