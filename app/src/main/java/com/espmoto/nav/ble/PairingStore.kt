package com.espmoto.nav.ble

import android.content.Context
import android.net.Uri
import java.util.Locale

data class PairedDevice(
    val mac: String,
    val token: String,
    val name: String = BleProtocol.DEVICE_NAME
)

object PairingStore {
    private const val PREFS_NAME = "espmoto_pairing"
    private const val KEY_MAC = "paired_mac"
    private const val KEY_TOKEN = "paired_token"
    private const val KEY_NAME = "paired_name"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect_enabled"

    fun load(context: Context): PairedDevice? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_MAC, null)?.trim().orEmpty()
        val token = prefs.getString(KEY_TOKEN, null)?.trim().orEmpty()
        val name = prefs.getString(KEY_NAME, BleProtocol.DEVICE_NAME)?.trim().orEmpty()
            .ifEmpty { BleProtocol.DEVICE_NAME }
        if (mac.isEmpty() || token.isEmpty()) return null
        return PairedDevice(mac = mac, token = token, name = name)
    }

    fun save(context: Context, device: PairedDevice) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAC, device.mac.uppercase(Locale.US))
            .putString(KEY_TOKEN, device.token.uppercase(Locale.US))
            .putString(KEY_NAME, device.name)
            .apply()
    }

    fun loadAutoReconnect(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true)
    }

    fun saveAutoReconnect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT, enabled)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MAC)
            .remove(KEY_TOKEN)
            .remove(KEY_NAME)
            .apply()
    }

    fun parsePairingUri(raw: String): PairedDevice? {
        val trimmed = raw.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (!"espmoto".equals(uri.scheme, ignoreCase = true) ||
            !"pair".equals(uri.host, ignoreCase = true)
        ) {
            return null
        }
        val mac = uri.getQueryParameter("mac")?.trim()?.uppercase(Locale.US).orEmpty()
        val token = uri.getQueryParameter("token")?.trim()?.uppercase(Locale.US).orEmpty()
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifEmpty { BleProtocol.DEVICE_NAME }
        if (mac.isEmpty() || token.length < 8) {
            return null
        }
        return PairedDevice(mac = mac, token = token, name = name)
    }
}
